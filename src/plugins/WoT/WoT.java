/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

import plugins.WoT.exceptions.DuplicateContextException;
import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NoSuchContextException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionClient;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionPuzzleStore;
import plugins.WoT.introduction.IntroductionServer;
import plugins.WoT.ui.fcp.FCPInterface;
import plugins.WoT.ui.web.WebInterface;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.config.Configuration;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;
import com.db4o.reflect.jdk.JdkReflector;

import freenet.keys.FreenetURI;
import freenet.l10n.L10n.LANGUAGE;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginHTTP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.FredPluginWithClassLoader;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * A web of trust plugin based on Freenet.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public class WoT implements FredPlugin, FredPluginHTTP, FredPluginThreadless, FredPluginFCP, FredPluginVersioned, FredPluginRealVersioned,
	FredPluginL10n, FredPluginWithClassLoader {
	
	/* Constants */
	
	public static final String DATABASE_FILENAME =  "WebOfTrust-testing.db4o";
	public static final int DATABASE_FORMAT_VERSION = -98;
	
	/** The relative path of the plugin on Freenet's web interface */
	public static final String SELF_URI = "/plugins/plugins.WoT.WoT";
	
	/**
	 * The "name" of this web of trust. It is included in the document name of identity URIs. For an example, see the SEED_IDENTITIES
	 * constant below. The purpose of this costant is to allow anyone to create his own custom web of trust which is completely disconnected
	 * from the "official" web of trust of the Freenet project.
	 */
	public static final String WOT_NAME = "WoT-testing";
	
	/**
	 * The official seed identities of the WoT plugin: If a newbie wants to download the whole offficial web of trust, he needs at least one
	 * trust list from an identity which is well-connected to the web of trust. To prevent newbies from having to add this identity manually,
	 * the Freenet development team provides a list of seed identities - each of them is one of the developers.
	 */
	private static final String[] SEED_IDENTITIES = new String[] { 
		"USK@fWK9InP~vG6HnTDm3wiJgvh6ULJQaU5XYTkXXNuKTTk,GnZgrilXSYjD~xrD6l4~5x~Nspz3aFe2eYXWRvaNRHU,AQACAAE/WoT/0" // xor
		/* FIXME: Add the developers. But first we need to debug :) */
	};
	

	/* References from the node */
	
	/** The ClassLoader which was used to load the plugin JAR, needed by db4o to work correctly */
	private ClassLoader mClassLoader;
	
	/** The node's interface to connect the plugin with the node, needed for retrieval of all other interfaces */
	private PluginRespirator mPR;	
	
	
	/* References from the plugin itself */
	
	/* Database & configuration of the plugin */
	private ExtObjectContainer mDB;
	private Config mConfig;
	private IntroductionPuzzleStore mPuzzleStore;
	
	/** Used for exporting identities, identity introductions and introduction puzzles to XML and importing them from XML. */
	private XMLTransformer mXMLTransformer;
	private RequestClient mRequestClient;

	/* Worker objects which actually run the plugin */
	
	/**
	 * Periodically wakes up and inserts any OwnIdentity which needs to be inserted.
	 */
	private IdentityInserter mInserter;
	
	/**
	 * Fetches identities when it is told to do so by the plugin:
	 * - At startup, all known identities are fetched
	 * - When a new identity is received from a trust list it is fetched
	 * - When a new identity is received by the IntrouductionServer it is fetched
	 * - When an identity is manually added it is also fetched.
	 * - ...
	 */
	private IdentityFetcher mFetcher;
	
	/**
	 * Uploads captchas belonging to our own identities which others can solve to get on the trust list of them. Checks whether someone
	 * uploaded solutions for them periodically and adds the new identities if a solution is received. 
	 */
	private IntroductionServer mIntroductionServer;
	
	/**
	 * Downloads captchas which the user can solve to announce his identities on other people's trust lists, provides the interface for
	 * the UI to obtain the captchas and enter solutions. Uploads the solutions if the UI enters them.
	 */
	private IntroductionClient mIntroductionClient;
	
	
	/* User interfaces */
	
	private WebInterface mWebInterface;
	private FCPInterface mFCPInterface;

	public void runPlugin(PluginRespirator myPR) {
		try {
			Logger.debug(this, "Start");
			
			/* Catpcha generation needs headless mode on linux */
			System.setProperty("java.awt.headless", "true"); 
	
			mPR = myPR;
			mDB = initDB(DATABASE_FILENAME); /* FIXME: Change before release */
			
			mConfig = Config.loadOrCreate(this);
			if(mConfig.getInt(Config.DATABASE_FORMAT_VERSION) > WoT.DATABASE_FORMAT_VERSION)
				throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
			
			upgradeDB();
			deleteDuplicateObjects();
			deleteOrphanObjects();
			
			mXMLTransformer = new XMLTransformer(this);
			mPuzzleStore = new IntroductionPuzzleStore(this);
			
			mRequestClient = new RequestClient() {
	
				public boolean persistent() {
					return false;
				}
	
				public void removeFrom(ObjectContainer container) {
					throw new UnsupportedOperationException();
				}
				
			};
	
			createSeedIdentities();
			
			mInserter = new IdentityInserter(this);
			mFetcher = new IdentityFetcher(this);		
			
			mIntroductionServer = new IntroductionServer(this, mFetcher);
			mIntroductionClient = new IntroductionClient(this);

			mWebInterface = new WebInterface(this, SELF_URI);
			mFCPInterface = new FCPInterface(this);
			
			Logger.debug(this, "Starting fetches of all identities...");
			synchronized(this) {
				for(Identity identity : getAllIdentities())
					mFetcher.fetch(identity, true);
			}
			Logger.debug(this, "WoT startup completed.");
		}
		catch(Exception e) {
			Logger.error(this, "Error during startup", e);
			/* We call it so the database is properly closed */
			terminate();
		}
	}
	
	public WoT() {
		
	}
	
	/**
	 * For use by the unit tests to be able to run WoT without a node.
	 * @param databaseFilename The filename of the database.
	 */
	public WoT(String databaseFilename) {
		mDB = initDB(databaseFilename);
		mConfig = Config.loadOrCreate(this);
		
		if(mConfig.getInt(Config.DATABASE_FORMAT_VERSION) > WoT.DATABASE_FORMAT_VERSION)
			throw new RuntimeException("The WoT plugin's database format is newer than the WoT plugin which is being used.");
	}

	/**
	 * Initializes the plugin's db4o database.
	 * 
	 * @return A db4o <code>ObjectContainer</code>. 
	 */
	private ExtObjectContainer initDB(String filename) {
		Configuration cfg = Db4o.newConfiguration();
		cfg.reflectWith(new JdkReflector(mClassLoader));
		cfg.activationDepth(5); /* FIXME: Change to 1 and add explicit activation everywhere */
		cfg.exceptionsOnNotStorable(true);
		
		for(String field : Identity.getIndexedFields()) cfg.objectClass(Identity.class).objectField(field).indexed(true);
		for(String field : OwnIdentity.getIndexedFields()) cfg.objectClass(OwnIdentity.class).objectField(field).indexed(true);
		for(String field : Context.getIndexedFields()) cfg.objectClass(Context.class).objectField(field).indexed(true);
		for(String field : ContextOffer.getIndexedFields()) cfg.objectClass(ContextOffer.class).objectField(field).indexed(true);
		for(String field : Trust.getIndexedFields()) cfg.objectClass(Trust.class).objectField(field).indexed(true);
		for(String field : Score.getIndexedFields()) cfg.objectClass(Score.class).objectField(field).indexed(true);
		
		cfg.objectClass(IntroductionPuzzle.PuzzleType.class).persistStaticFieldValues(); /* Needed to be able to store enums */
		for(String field : IntroductionPuzzle.getIndexedFields()) cfg.objectClass(IntroductionPuzzle.class).objectField(field).indexed(true);
		
		return Db4o.openFile(cfg, filename).ext();
	}
	
	private synchronized void upgradeDB() {
		int oldVersion = mConfig.getInt(Config.DATABASE_FORMAT_VERSION);
		
		if(oldVersion == WoT.DATABASE_FORMAT_VERSION)
			return;
			
		try {
		if(oldVersion == -100) {
			Logger.normal(this, "Found old database (-100), adding last fetched date to all identities ...");
			for(Identity identity : getAllIdentities()) {
				identity.mLastFetchedDate = new Date(0);
				storeWithoutCommit(identity);
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, WoT.DATABASE_FORMAT_VERSION);
			mConfig.storeAndCommit();
		}
		else if(oldVersion == -99) {
			Context introductionContext = getOrCreateContextWithoutCommit(IntroductionPuzzle.INTRODUCTION_CONTEXT);
			Context freetalkContext = getOrCreateContextWithoutCommit("Freetalk");
			
			Logger.normal(this, "Found old database (-99), adding Introduction/Freetalk ContextOffers to all identities ...");
			for(Identity identity: getAllIdentities()) {
				addContextOfferWithoutCommit(identity, introductionContext);
				addContextOfferWithoutCommit(identity, freetalkContext);
			}
			
			mConfig.set(Config.DATABASE_FORMAT_VERSION, WoT.DATABASE_FORMAT_VERSION);
			mConfig.storeAndCommit();
		}
		else
			throw new RuntimeException("Your database is too outdated to be upgraded automatically, please create a new one by deleting " 
				+ DATABASE_FILENAME + ". Contact the developers if you really need your old data.");
		}
		catch(RuntimeException e) {
			mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}

	/**
	 * Debug function for deleting duplicate identities etc. which might have been created due to bugs :)
	 */
	@SuppressWarnings("unchecked")
	private synchronized void deleteDuplicateObjects() {
		synchronized(mDB) {
			try {
				ObjectSet<Identity> identities = getAllIdentities();
				HashSet<String> deleted = new HashSet<String>();
				
				for(Identity identity : identities) {
					Query q = mDB.query();
					q.constrain(Identity.class);
					q.descend("mID").constrain(identity.getID());
					q.constrain(identity).identity().not();
					ObjectSet<Identity> duplicates = q.execute();
					for(Identity duplicate : duplicates) {
						if(deleted.contains(duplicate.getID()) == false) {
							Logger.error(duplicate, "Deleting duplicate identity " + duplicate.getRequestURI());
							for(Trust trust : getReceivedTrusts(duplicate))
								mDB.delete(trust);
							for(Trust trust : getGivenTrusts(duplicate))
								mDB.delete(trust);
							for(Score score : getScores(duplicate))
								mDB.delete(score);
							mDB.delete(duplicate);
						}
					}
					deleted.add(identity.getID());
					mDB.commit(); Logger.debug(this, "COMMITED.");
				}
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				Logger.error(this, "Error while deleting duplicate identities", e);
			}
			
			try {
				for(OwnIdentity treeOwner : getAllOwnIdentities()) {
					HashSet<String> givenTo = new HashSet<String>();
					
					for(Trust trust : getGivenTrusts(treeOwner)) {
						if(givenTo.contains(trust.getTrustee().getID()) == false)
							givenTo.add(trust.getTrustee().getID());
						else {
							Identity trustee = trust.getTrustee();
							Logger.error(this, "Deleting duplicate given trust from " + treeOwner.getNickname() + " to " +
									trustee.getNickname());
							mDB.delete(trust);
							
							try {
								updateScoreWithoutCommit(treeOwner, trustee);
							}
							catch(Exception e) { /* Maybe another duplicate prevents it from working ... */
								Logger.error(this, "Updating score of " + trustee.getNickname() + " failed.", e);
							}
						}
					}
					mDB.commit(); Logger.debug(this, "COMMITED.");
				}
				
				
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				Logger.error(this, "Error while deleting duplicate trusts", e);
			}

		}
		
		/* FIXME: Also delete duplicate trust, score, context etc. */
	}
	
	/**
	 * Debug function for deleting trusts, scores or context offers of which one of the involved partners is missing.
	 */
	@SuppressWarnings("unchecked")
	private synchronized void deleteOrphanObjects() {
		synchronized(mDB) {
			try {
				Query q = mDB.query();
				q.constrain(Trust.class);
				q.descend("mTruster").constrain(null).identity().or(q.descend("mTrustee").constrain(null).identity());
				ObjectSet<Trust> orphanTrusts = q.execute();
				for(Trust trust : orphanTrusts) {
					Logger.error(trust, "Deleting orphan trust, truster = " + trust.getTruster() + ", trustee = " + trust.getTrustee());
					mDB.delete(trust);
				}
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan trusts failed.", e);
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			}
			
			try {
				Query q = mDB.query();
				q.constrain(Score.class);
				q.descend("mTreeOwner").constrain(null).identity().or(q.descend("mTarget").constrain(null).identity());
				ObjectSet<Score> orphanScores = q.execute();
				for(Score score : orphanScores) {
					Logger.error(score, "Deleting orphan score, treeOwner = " + score.getTreeOwner() + ", target = " + score.getTarget());
					mDB.delete(score);
				}
				
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan trusts failed.", e);
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			}
			
			try {
				Query q = mDB.query();
				q.constrain(ContextOffer.class);
				q.descend("mProvider").constrain(null).identity().or(q.descend("mContext").constrain(null)).identity();
				ObjectSet<ContextOffer> orphanOffers = q.execute();
				for(ContextOffer offer : orphanOffers) {
					Logger.error(offer, "Deleting orphan context offer, provider = " + offer.getProvider() + ", context = " + offer.getContext());
				}
				
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(Exception e) {
				Logger.error(this, "Deleting orphan context offers failed.", e);
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			}
		}
	}
	
	/**
	 * Locks the WoT and creates the seed identities (if they do not exist yet) from the seed identity URIs listed in the {@link SEED_IDENTITIES} constant.
	 * If a seed identity in the list is an own identity, then the own identity is configured to provide identity introduction and the amount of introduction
	 * puzzles it publishes per day is increased to {@link IntroductionServer.SEED_IDENTITY_PUZZLE_COUNT}.
	 */
	private synchronized void createSeedIdentities() {
		for(String seedURI : SEED_IDENTITIES) {
			Identity seed;
			
			try { 
				seed = getIdentityByURI(seedURI);
				if(seed instanceof OwnIdentity) {
					OwnIdentity ownSeed = (OwnIdentity)seed;
					// FIXME: Does the cast make that necessary? I'm adding it to make sure that we do not lose information when storing
					mDB.activate(ownSeed, 5);
					addContextOfferWithoutCommit(ownSeed, getOrCreateContextWithoutCommit(IntroductionPuzzle.INTRODUCTION_CONTEXT));
					ownSeed.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY,
							Integer.toString(IntroductionServer.SEED_IDENTITY_PUZZLE_COUNT));
					storeAndCommit(ownSeed);
				}
				else {
					try {
						seed.setEdition(new FreenetURI(seedURI).getEdition());
						storeAndCommit(seed);
					} catch(InvalidParameterException e) {
						/* We already have the latest edition stored */
					}
				}
			}
			catch (UnknownIdentityException uie) {
				try {
					seed = new Identity(seedURI, null, true);
					storeAndCommit(seed);
				} catch (RuntimeException e) {
					Logger.error(this, "Seed identity creation error", e);
					mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				}
			}
			catch (RuntimeException e) {
				Logger.error(this, "Seed identity creation error", e);
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			}
		}
	}
	

	public void terminate() {
		Logger.debug(this, "WoT plugin terminating ...");
		
		/* We use single try/catch blocks so that failure of termination of one service does not prevent termination of the others */
		try {
			if(mIntroductionClient != null)
				mIntroductionClient.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}

		try {
			if(mIntroductionServer != null)
				mIntroductionServer.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			if(mInserter != null)
				mInserter.terminate();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		try {
			if(mFetcher != null)
				mFetcher.stop();
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}
		
		
		try {
			if(mDB != null) {
				/* FIXME: Is it possible to ask db4o whether a transaction is pending? If the plugin's synchronization works correctly,
				 * NONE should be pending here and we should log an error if there are any pending transactions at this point. */
				synchronized(mDB.lock()) {
					mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
					mDB.close();
				}
			}
		}
		catch(Exception e) {
			Logger.error(this, "Error during termination.", e);
		}

		Logger.debug(this, "WoT plugin terminated.");
	}

	/**
	 * Inherited event handler from FredPluginHTTP, handled in <code>class WebInterface</code>.
	 */
	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {	
		return mWebInterface.handleHTTPGet(request);
	}

	/**
	 * Inherited event handler from FredPluginHTTP, handled in <code>class WebInterface</code>.
	 */
	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		return mWebInterface.handleHTTPPost(request);
	}
	
	/**
	 * Inherited event handler from FredPluginHTTP, handled in <code>class WebInterface</code>.
	 */
	public String handleHTTPPut(HTTPRequest request) throws PluginHTTPException {
		return mWebInterface.handleHTTPPut(request);
	}

	/**
	 * Inherited event handler from FredPluginFCP, handled in <code>class FCPInterface</code>.
	 */
	public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) {
		mFCPInterface.handle(replysender, params, data, accesstype);
	}

	/**
	 * Loads an own or normal identity from the database, querying on its ID.
	 * 
	 * @param id The ID of the identity to load
	 * @return The identity matching the supplied ID.
	 * @throws DuplicateIdentityException if there are more than one identity with this id in the database
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 */
	@SuppressWarnings("unchecked")
	public synchronized Identity getIdentityByID(String id) throws UnknownIdentityException {
		Query query = mDB.query();
		query.constrain(Identity.class);
		query.descend("mID").constrain(id);
		ObjectSet<Identity> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownIdentityException(id);
			default:
				throw new DuplicateIdentityException(id, result.size());
		}	
	}
	
	/**
	 * Gets an OwnIdentity by its ID.
	 * 
	 * @param id The unique identifier to query an OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if there is now OwnIdentity with that id
	 */
	@SuppressWarnings("unchecked")
	public synchronized OwnIdentity getOwnIdentityByID(String id) throws UnknownIdentityException {
		Query query = mDB.query();
		query.constrain(OwnIdentity.class);
		query.descend("mID").constrain(id);
		ObjectSet<OwnIdentity> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new UnknownIdentityException(id);
			default:
				throw new DuplicateIdentityException(id, result.size());
		}
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (a valid {@link FreenetURI})
	 * 
	 * @param uri The requestURI of the identity
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 */
	public Identity getIdentityByURI(FreenetURI uri) throws UnknownIdentityException {
		return getIdentityByID(Identity.getIDFromURI(uri));
	}

	/**
	 * Loads an identity from the database, querying on its requestURI (as String)
	 * 
	 * @param uri The requestURI of the identity which will be converted to {@link FreenetURI} 
	 * @return The identity matching the supplied requestURI
	 * @throws UnknownIdentityException if there is no identity with this id in the database
	 * @throws MalformedURLException if the requestURI isn't a valid FreenetURI
	 */
	public Identity getIdentityByURI(String uri) throws UnknownIdentityException, MalformedURLException {
		return getIdentityByURI(new FreenetURI(uri));
	}

	/**
	 * Gets an OwnIdentity by its requestURI (a {@link FreenetURI}).
	 * The OwnIdentity's unique identifier is extracted from the supplied requestURI.
	 * 
	 * @param uri The requestURI of the desired OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if the OwnIdentity isn't in the database
	 */
	public OwnIdentity getOwnIdentityByURI(FreenetURI uri) throws UnknownIdentityException {
		return getOwnIdentityByID(OwnIdentity.getIDFromURI(uri));
	}

	/**
	 * Gets an OwnIdentity by its requestURI (as String).
	 * The given String is converted to {@link FreenetURI} in order to extract a unique id.
	 * 
	 * @param db A reference to the database
	 * @param uri The requestURI (as String) of the desired OwnIdentity
	 * @return The requested OwnIdentity
	 * @throws UnknownIdentityException if the OwnIdentity isn't in the database
	 * @throws MalformedURLException if the supplied requestURI is not a valid FreenetURI
	 */
	public OwnIdentity getOwnIdentityByURI(String uri) throws UnknownIdentityException, MalformedURLException {
		return getOwnIdentityByURI(new FreenetURI(uri));
	}
	
	/**
	 * Returns all identities that are in the database
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database 
	 */
	public synchronized ObjectSet<Identity> getAllIdentities() {
		return mDB.queryByExample(Identity.class);
	}
	
	/**
	 * Returns all non-own identities that are in the database.
	 * 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Identity> getAllNonOwnIdentities() {
		Query q = mDB.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		return q.execute();
	}
	
	/**
	 * Returns all non-own identities that are in the database, sorted descending by their date of modification, i.e. recently
	 * modified identities will be at the beginning of the list.
	 * 
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * Used by the IntroductionClient for fetching puzzles from recently modified identities.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Identity> getAllNonOwnIdentitiesSortedByModification () {
		Query q = mDB.query();
		q.constrain(Identity.class);
		q.constrain(OwnIdentity.class).not();
		/* FIXME: As soon as identities announce that they were online every day, uncomment the following line */
		/* q.descend("mLastChangedDate").constrain(new Date(CurrentTimeUTC.getInMillis() - 1 * 24 * 60 * 60 * 1000)).greater(); */
		q.descend("mLastFetchedDate").orderDescending();
		
		return q.execute();
	}
	
	/**
	 * Returns all own identities that are in the database
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all identities present in the database.
	 */
	public synchronized ObjectSet<OwnIdentity> getAllOwnIdentities() {
		return mDB.queryByExample(OwnIdentity.class);
	}
	
	/**
	 * Returns all contexts that are in the database.
	 * 
	 * An allowed context must not necessarily exist in the database yet if it is not offered by any identity, for a list of allowed Contexts
	 * please use {@link Config.getString(Config.ALLOWED_CONTEXT_NAMES)}
	 */
	public synchronized ObjectSet<Context> getAllContexts() {
		return mDB.queryByExample(Context.class);
	}
	
	/**
	 * Gets all contexts which the given identity provides.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<ContextOffer> getOfferedContexts(Identity identity)  {
		Query q = mDB.query();
		q.constrain(ContextOffer.class);
		q.descend("mProvider").constrain(identity).identity();
		return q.execute();
	}
	
	/**
	 * Gets the context which the given name. If it does not exist it is created and stored in the database, without commiting the transaction. 
	 */
	protected synchronized Context getOrCreateContextWithoutCommit(String name) {
		try {
			return getContext(name);
		}
		catch(NoSuchContextException e) {
			Logger.debug(this, "Context '" + name + "' did not exist, trying to create it...");
			
			// We create it before checking whether the name is okay because it will throw if the name contains invalid characters, we should notify about that first.
			Context context = new Context(name); 
			
			String[] allowedContexts = mConfig.getString(Config.ALLOWED_CONTEXT_NAMES).split("[,]");
			if(Arrays.binarySearch(allowedContexts, name) < 0)
				throw new IllegalArgumentException("The context name '" + name + "' is not in the configured list of allowed contexts.");
			
			storeWithoutCommit(context);
			return context;
		}
	}

	/**
	 * Get the context with the given name.
	 * @throws NoSuchContextException If no context with that name exists yet.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized Context getContext(String name) throws NoSuchContextException {
		Query q = mDB.query();
		q.constrain(Context.class);
		q.descend("mName").constrain(name);
		ObjectSet<Context> result = q.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new NoSuchContextException(name);
			default:
				throw new DuplicateContextException(name, result.size());
		}
	
	}

	/**
	 * Locks the WoT, locks the identity, locks the database and stores the identity.
	 */
	public synchronized void storeAndCommit(Identity identity) {
		synchronized(identity) {
		synchronized(mDB.lock()) {
			try {
				storeWithoutCommit(identity);
				mDB.commit(); Logger.debug(identity, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
		}
	}

	
	/**
	 * Stores the given identity in the database without committing the transaction.
	 * 
	 * You must synchronize on the WoT, on the identity and then on the database when using this function, it does not provide any synchronization.
	 * 
	 * @param identity The identity to store.
	 */
	private void storeWithoutCommit(Identity identity) {
		if(mDB.ext().isStored(identity) && !mDB.ext().isActive(identity))
			throw new RuntimeException("Trying to store an inactive Identity object!");

		/* FIXME: We also need to check whether the member objects are active here!!! */

		try {
			if(identity instanceof OwnIdentity) {
				OwnIdentity ownId = (OwnIdentity)identity;
				mDB.store(ownId.mInsertURI);
				// mDB.store(ownId.mCreationDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
				// mDB.store(ownId.mLastInsertDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			}
			// mDB.store(mID); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			mDB.store(identity.mRequestURI);
			// mDB.store(mFirstFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mLastFetchedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mLastChangedDate); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mNickname); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			// mDB.store(mDoesPublishTrustList); /* Not stored because db4o considers it as a primitive and automatically stores it. */
			mDB.store(identity.mProperties);
			mDB.store(identity);
		}
		catch(RuntimeException e) {
			mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
			throw e;
		}
	}
	
	/**
	 * Stores the given context in the database without commiting the transaction.
	 * 
	 * Does not prevent the creation of duplicate contexts, please use getOrCreateContext() instead of this function when you want to create a context.
	 * 
	 * @param context The context to store.
	 */
	private void storeWithoutCommit(Context context) {	
		if(mDB.ext().isStored(context) && !mDB.ext().isActive(context))
			throw new RuntimeException("Trying to store an inactive Context object!");

		mDB.store(context);
	}
	
	
	/**
	 * Locks:
	 * 1. The WoT.
	 * 2. The given {@link ContextOffer}
	 * 3. The provider of that offer
	 * 4. The database.
	 * 
	 * Then stores the given context offer. Does not prevent the creation of duplicate offers, please use {@link addContextOffer} for adding contexts to identities.
	 * @param offer
	 */
	private synchronized void storeWithoutCommit(ContextOffer offer) {
		if(mDB.ext().isStored(offer) && !mDB.ext().isActive(offer))
			throw new RuntimeException("Trying to store an inactive ContextOffer object!");

		mDB.store(offer);
	}

	/**
	 * Adds a context to the given identity.
	 * A context is a string, the identities contexts are a set of strings - no context will be added more than once.
	 * 
	 * @param context Name of the context.
	 * @throws InvalidParameterException If the context name is empty
	 */
	public synchronized void addContextOfferWithoutCommit(Identity provider, Context context) throws InvalidParameterException {
		if(!hasContext(provider, context))
			/* FIXME: Limit the amount of contexts */
			mContexts.add(newContext);
			updated();
		}
	}
	
	/**
	 * Checks whether this identity offers the given context.
	 * 
	 * @param context The context we want to know if this Identity has it or not
	 * @return Whether this Identity has that context or not
	 */
	public synchronized boolean hasContext(String context) {
		return mContexts.contains(context.trim());
	}

	/**
	 * Clears the list of contexts and sets it to the new list of contexts which was passed to the function.
	 * Duplicate contexts are ignored. For invalid contexts an error is logged, all valid ones will be added.
	 * 
	 * IMPORTANT: This always marks the identity as updated so it should not be used on OwnIdentities because it would result in
	 * a re-insert even if nothing was changed.
	 */
	protected synchronized void setContexts(List<String> newContexts) {
		mContexts.clear();
		
		for(String context : newContexts) {
			try {
				addContext(context);
			}
			catch(InvalidParameterException e) {
				Logger.error(this, "setContexts(): addContext() failed.", e);
			}
		}
		
		mContexts.trimToSize();
	}

	/**
	 * Removes a context from this Identity, does nothing if it does not exist.
	 * If this Identity is no longer used by a client application, the user can tell it and others won't try to fetch it anymore.
	 * 
	 * @param db A reference to the database.
	 * @param context Name of the context.
	 */
	public synchronized void removeContext(String context) throws InvalidParameterException {
		context = context.trim();
		
		if(mContexts.contains(context)) {
			mContexts.remove(context);
			updated();
		}
	}

	
	
	/**
	 * Gets the score of this identity in a trust tree.
	 * Each {@link OwnIdentity} has its own trust tree.
	 * 
	 * @param treeOwner The owner of the trust tree
	 * @param db A reference to the database
	 * @return The {@link Score} of this Identity in the required trust tree
	 * @throws NotInTrustTreeException if this identity is not in the required trust tree 
	 */
	@SuppressWarnings("unchecked")
	public synchronized Score getScore(OwnIdentity treeOwner, Identity target) throws NotInTrustTreeException {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTreeOwner").constrain(treeOwner).identity();
		query.descend("mTarget").constrain(target).identity();
		ObjectSet<Score> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
	
			case 0:
				throw new NotInTrustTreeException(treeOwner, target);
	
			default:
				throw new DuplicateScoreException(treeOwner, target, result.size());
		}
	}
	
	/* 
	 * FIXME:
	 * I suggest before releasing we should write a getRealScore() function which recalculates the score from all Trust objects which are
	 * stored in the database. We could then assert(getScore() == getRealScore()) for verifying that the database is consistent and watch
	 * for some time whether it stays consistent, just to make sure that there are no flaws in the code.
	 */
	
	/**
	 * Gets a list of all this Identity's Scores.
	 * You have to synchronize on this WoT around the call to this function and the processing of the returned list! 
	 * 
	 * @param db A reference to the database
	 * @return An {@link ObjectSet} containing all {@link Score} this Identity has.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Score> getScores(Identity identity) {
		Query query = mDB.query();
		query.constrain(Score.class);
		query.descend("mTarget").constrain(identity).identity();
		return query.execute();
	}
	
	/**
	 * Gets the best score this Identity has in existing trust trees, 0 if it is not in the trust tree.
	 * 
	 * @return the best score this Identity has
	 */
	public synchronized int getBestScore(Identity identity) {
		int bestScore = 0;
		ObjectSet<Score> scores = getScores(identity);
		/* TODO: Use a db4o native query for this loop. Maybe use an index, indexes should be sorted so maximum search will be O(1)... 
		 * but I guess indexes cannot be done for the (target, value) pair so we might just cache the best score ourselves...
		 * OTOH the number of own identies will be small so the maximum search will probably be fast... */
		while(scores.hasNext()) {
			Score score = scores.next();
			bestScore = Math.max(score.getScore(), bestScore);
		}
		return bestScore;
	}
	
	/**
	 * Get all scores in the database.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	public synchronized ObjectSet<Score> getAllScores() {
		return mDB.queryByExample(Score.class);
	}
	
	/**
	 * Gets Identities matching a specified score criteria.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @param owner requestURI of the owner of the trust tree, null if you want the trusted identities of all owners.
	 * @param select Score criteria, can be '+', '0' or '-'
	 * @return an {@link ObjectSet} containing Identities that match the criteria
	 * @throws InvalidParameterException if the criteria is not recognised
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Score> getIdentitiesByScore(OwnIdentity treeOwner, int select) {		
		Query query = mDB.query();
		query.constrain(Score.class);
		if(treeOwner != null)
			query.descend("mTreeOwner").constrain(treeOwner).identity();
		query.descend("mTarget").constrain(OwnIdentity.class).not();
	
		/* We include 0 in the list of identities with positive score because solving captchas gives no points to score */
		
		if(select > 0)
			query.descend("mValue").constrain(0).smaller().not();
		else if(select < 0 )
			query.descend("mValue").constrain(0).smaller();
		else 
			query.descend("mValue").constrain(0);

		return query.execute();
	}
	
	/**
	 * Gets {@link Trust} from a specified truster to a specified trustee.
	 * 
	 * @param truster The identity that gives trust to this Identity
	 * @param trustee The identity which receives the trust
	 * @return The trust given to the trustee by the specified truster
	 * @throws NotTrustedException if the truster doesn't trust the trustee
	 */
	@SuppressWarnings("unchecked")
	public synchronized Trust getTrust(Identity truster, Identity trustee) throws NotTrustedException, DuplicateTrustException {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		query.descend("mTrustee").constrain(trustee).identity();
		ObjectSet<Trust> result = query.execute();
		
		switch(result.size()) {
			case 1:
				return result.next();
			case 0:
				throw new NotTrustedException(truster, trustee);
			default:
				throw new DuplicateTrustException(truster, trustee, result.size());
		}
	}

	/**
	 * Gets all trusts given by the given truster.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has given.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getGivenTrusts(Identity truster) {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTruster").constrain(truster).identity();
		return query.execute();
	}
	
	/**
	 * Gets all trusts given by the given truster in a trust list older than the given edition number.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 */
	@SuppressWarnings("unchecked")
	protected synchronized ObjectSet<Trust> getGivenTrustsOlderThan(Identity truster, long edition) {
		Query q = mDB.query();
		q.constrain(Trust.class);
		q.descend("mTruster").constrain(truster).identity();
		q.descend("mTrusterTrustListEdition").constrain(edition).smaller();
		return q.execute();
	}

	/**
	 * Gets all trusts received by the given trustee.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getReceivedTrusts(Identity trustee) {
		Query query = mDB.query();
		query.constrain(Trust.class);
		query.descend("mTrustee").constrain(trustee).identity();
		return query.execute();
	}
	
	/**
	 * Gets all trusts.
	 * You have to synchronize on this WoT when calling the function and processing the returned list!
	 * 
	 * @return An {@link ObjectSet} containing all {@link Trust} the passed Identity has received.
	 */
	@SuppressWarnings("unchecked")
	public synchronized ObjectSet<Trust> getAllTrusts() {
		Query query = mDB.query();
		query.constrain(Trust.class);
		return query.execute();
	}
	
	/**
	 * Gives some {@link Trust} to another Identity.
	 * It creates or updates an existing Trust object and make the trustee compute its {@link Score}.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param truster The Identity that gives the trust
	 * @param trustee The Identity that receives the trust
	 * @param newValue Numeric value of the trust
	 * @param newComment A comment to explain the given value
	 * @throws InvalidParameterException if a given parameter isn't valid, {@see Trust} for details on accepted values.
	 */
	protected synchronized void setTrustWithoutCommit(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		Trust trust;
		try { // Check if we are updating an existing trust value
			trust = getTrust(truster, trustee);
			trust.trusterEditionUpdated();
			trust.setComment(newComment);
			mDB.store(trust);

			if(trust.getValue() != newValue) {
				trust.setValue(newValue);
				mDB.store(trust);
				Logger.debug(this, "Updated trust value ("+ trust +"), now updating Score.");
				updateScoreWithoutCommit(trustee);
			}
		} catch (NotTrustedException e) {
			trust = new Trust(truster, trustee, newValue, newComment);
			mDB.store(trust);
			Logger.debug(this, "New trust value ("+ trust +"), now updating Score.");
			updateScoreWithoutCommit(trustee);
		} 

		truster.updated();
	}
	
	/**
	 * Only for being used by WoT internally and by unit tests! 
	 */
	public synchronized void setTrust(Identity truster, Identity trustee, byte newValue, String newComment)
		throws InvalidParameterException {
		
		/* FIXME: Throw if we are no unit test and the truster is no own identity */
		assert(truster instanceof OwnIdentity); /* Unit tests may ignore this. */
		
		synchronized(mDB.lock()) {
			try {
				setTrustWithoutCommit(truster, trustee, newValue, newComment);
				storeAndCommit(truster);
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
	}
	
	/**
	 * Deletes a trust object
	 * @param truster
	 * @param trustee
	 */
	protected synchronized void removeTrustWithoutCommit(OwnIdentity truster, Identity trustee) {
		synchronized(mDB.lock()) {
			try {
				try {
					Trust trust = getTrust(truster, trustee);
					mDB.delete(trust);
					updateScoreWithoutCommit(trustee);
				} catch (NotTrustedException e) {
					Logger.error(this, "Cannot remove trust - there is none - from " + truster.getNickname() + " to "
						+ trustee.getNickname());
				} 
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
	}
	
	/**
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 */
	protected synchronized void removeTrustWithoutCommit(Trust trust) {
		mDB.delete(trust);
		updateScoreWithoutCommit(trust.getTrustee());
	}
	
	/**
	 * Initializes this OwnIdentity's trust tree without commiting the transaction.
	 * Meaning : It creates a Score object for this OwnIdentity in its own trust tree, 
	 * so it gets a rank and a capacity and can give trust to other Identities.
	 *  
	 * @param db A reference to the database 
	 * @throws DuplicateScoreException if there already is more than one Score for this identity (should never happen)
	 */
	private synchronized void initTrustTreeWithoutCommit(OwnIdentity identity) throws DuplicateScoreException {
		synchronized(mDB.lock()) {
			try {
				getScore(identity, identity);
				Logger.error(this, "initTrusTree called even though there is already one for " + identity);
				return;
			} catch (NotInTrustTreeException e) {
				mDB.store(new Score(identity, identity, 100, 0, 100));
			}
		}
	}
	
	/**
	 * Updates this Identity's {@link Score} in every trust tree.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 */
	private synchronized void updateScoreWithoutCommit(Identity trustee) {
		ObjectSet<OwnIdentity> treeOwners = getAllOwnIdentities();
		if(treeOwners.size() == 0)
			Logger.debug(this, "Can't update " + trustee.getNickname() + "'s score: there is no own identity yet");

		while(treeOwners.hasNext())
			updateScoreWithoutCommit(treeOwners.next(), trustee);
	}
	
	/**
	 * Updates this Identity's {@link Score} in one trust tree.
	 * Makes this Identity's trustees update their score if its capacity has changed.
	 * 
	 * This function does neither lock the database nor commit the transaction. You have to surround it with
	 * synchronized(mDB.lock()) {
	 *     try { ... setTrustWithoutCommit(...); storeAndCommit(truster); }
	 *     catch(RuntimeException e) { mDB.rollback(); throw e; }
	 * }
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 */
	private synchronized void updateScoreWithoutCommit(OwnIdentity treeOwner, Identity target) {
		if(target == treeOwner)
			return;
		
		boolean changedCapacity = false;
		
		Logger.debug(target, "Updating " + target.getNickname() + "'s score in " + treeOwner.getNickname() + "'s trust tree...");
		
		Score score;
		int value = computeScoreValue(treeOwner, target);
		int rank = computeRank(treeOwner, target);
		
		
		
		if(rank == -1) { // -1 value means the identity is not in the trust tree
			try { // If he had a score, we delete it
				score = getScore(treeOwner, target);
				mDB.delete(score); // He had a score, we delete it
				changedCapacity = true;
				Logger.debug(target, target.getNickname() + " is not in " + treeOwner.getNickname() + "'s trust tree anymore");
			} catch (NotInTrustTreeException e) { } 
		}
		else { // The identity is in the trust tree
			
			/* We must detect if an identity had a null or negative score which has changed to positive:
			 * If we download the trust list of someone who has no or negative score we do not create the identities in his trust list.
			 * If the identity now gets a positive score we must re-download his current trust list. */
			boolean scoreWasNegative = false;
			
			try { // Get existing score or create one if needed
				score = getScore(treeOwner, target);
				scoreWasNegative = score.getScore() < 0;
			} catch (NotInTrustTreeException e) {
				score = new Score(treeOwner, target, 0, -1, 0);
				scoreWasNegative = true;
			}
			
			score.setValue(value);
			score.setRank(rank + 1);
			
			if(scoreWasNegative && score.getScore() >= 0) {
				target.decreaseEdition();
				storeWithoutCommit(target);
				Logger.debug(this, "Score changed from negative/null to positive, refetching " + target.getRequestURI());
				if(mFetcher != null) /* For JUnit */
					mFetcher.fetch(target);
			}
			
			int oldCapacity = score.getCapacity();
			
			boolean hasNegativeTrust = false;
			// Does the treeOwner personally distrust this identity ?
			try {
				if(getTrust(treeOwner, target).getValue() < 0) {
					hasNegativeTrust = true;
					Logger.debug(target, target.getNickname() + " received negative trust from " + treeOwner.getNickname() + 
							" and therefore has no capacity in his trust tree.");
				}
			} catch (NotTrustedException e) {}
			
			if(hasNegativeTrust)
				score.setCapacity(0);
			else
				score.setCapacity((score.getRank() >= Score.capacities.length) ? 1 : Score.capacities[score.getRank()]);
			
			if(score.getCapacity() != oldCapacity)
				changedCapacity = true;
			
			mDB.store(score);
			Logger.debug(target, "New score: " + score.toString());
		}
		
		if(changedCapacity) { // We have to update trustees' score
			ObjectSet<Trust> givenTrusts = getGivenTrusts(target);
			Logger.debug(target, target.getNickname() + "'s capacity has changed in " + treeOwner.getNickname() +
					"'s trust tree, updating his (" + givenTrusts.size() + ") trustees");
			
			for(Trust givenTrust : givenTrusts)
				updateScoreWithoutCommit(treeOwner, givenTrust.getTrustee());
		}
		
	}
	
	/**
	 * Computes the target's Score value according to the trusts it has received and the capacity of its trusters in the specified
	 * trust tree.
	 * 
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Score if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeScoreValue(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		int value = 0;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(target);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				value += trust.getValue() * (getScore(treeOwner, trust.getTruster())).getCapacity() / 100;
			} catch (NotInTrustTreeException e) {}
		}
		return value;
	}
	
	/**
	 * Computes the target's rank in the trust tree.
	 * It gets its best ranked truster's rank, plus one. Or -1 if none of its trusters are in the trust tree. 
	 *  
	 * @param db A reference to the database
	 * @param treeOwner The OwnIdentity that owns the trust tree
	 * @return The new Rank if this Identity
	 * @throws DuplicateScoreException if there already exist more than one {@link Score} objects for the trustee (should never happen)
	 */
	private synchronized int computeRank(OwnIdentity treeOwner, Identity target) throws DuplicateScoreException {
		int rank = -1;
		
		ObjectSet<Trust> receivedTrusts = getReceivedTrusts(target);
		while(receivedTrusts.hasNext()) {
			Trust trust = receivedTrusts.next();
			try {
				Score score = getScore(treeOwner, trust.getTruster());
				
				if(score.getCapacity() != 0) // If the truster has no capacity, he can't give his rank
					if(rank == -1 || score.getRank() < rank) // If the truster's rank is better than ours or if we have not  
						rank = score.getRank();
			} catch (NotInTrustTreeException e) {}
		}
		return rank;
	}
	
	/* Client interface functions */
	
	public synchronized Identity addIdentity(String requestURI) throws MalformedURLException, InvalidParameterException {
		Identity identity;
		
		try {
			identity = getIdentityByURI(requestURI);
			Logger.debug(this, "Tried to manually add an identity we already know, ignored.");
			throw new InvalidParameterException("We already have this identity");
		}
		catch(UnknownIdentityException e) {
			identity = new Identity(new FreenetURI(requestURI), null, false);
			storeAndCommit(identity);
			Logger.debug(this, "Trying to fetch manually added identity (" + identity.getRequestURI() + ")");
			mFetcher.fetch(identity);
		}
		
		return identity;
	}
	
	public synchronized void deleteIdentity(Identity identity) {
		synchronized(mDB.lock()) {
			try {
				Logger.debug(this, "Deleting identity " + identity + " ...");
				
				Logger.debug(this, "Deleting scores...");
				for(Score score : getScores(identity))
					mDB.delete(score);
				
				Logger.debug(this, "Deleting received trusts...");
				for(Trust trust : getReceivedTrusts(identity))
					mDB.delete(trust);
				
				Logger.debug(this, "Deleting given trusts...");
				for(Trust givenTrust : getGivenTrusts(identity)) {
					mDB.delete(givenTrust);
					updateScoreWithoutCommit(givenTrust.getTrustee());
				}
				
				Logger.debug(this, "Deleting the identity...");
				mDB.delete(identity);
				mDB.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
		}
	}
	
	public synchronized void deleteIdentity(String id) throws UnknownIdentityException {
		synchronized(mDB.lock()) {
			Identity identity = getIdentityByID(id);
			deleteIdentity(identity);
		}
	}
	
	public OwnIdentity createOwnIdentity(String nickName, boolean publishTrustList, String context)
		throws MalformedURLException, InvalidParameterException {
		
		FreenetURI[] keypair = getPluginRespirator().getHLSimpleClient().generateKeyPair("WoT");
		return createOwnIdentity(keypair[0].toString(), keypair[1].toString(), nickName, publishTrustList, context);
	}

	public synchronized OwnIdentity createOwnIdentity(String insertURI, String requestURI, String nickName,
			boolean publishTrustList, String context) throws MalformedURLException, InvalidParameterException {
		
		synchronized(mDB.lock()) {
			OwnIdentity identity;
			
			try {
				identity = getOwnIdentityByURI(requestURI);
				Logger.debug(this, "Tried to create an own identity with an already existing request URI.");
				throw new InvalidParameterException("The URI you specified is already used by the own identity " +
						identity.getNickname() + ".");
			}
			catch(UnknownIdentityException uie) {
				identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), nickName, publishTrustList);
				identity.addContext(context);
				identity.addContext(IntroductionPuzzle.INTRODUCTION_CONTEXT); /* FIXME: make configureable */
				identity.setProperty(IntroductionServer.PUZZLE_COUNT_PROPERTY, Integer.toString(IntroductionServer.DEFAULT_PUZZLE_COUNT));
				
				try {
					mDB.store(identity);
					initTrustTree(identity);
					
					for(String seedURI : SEED_IDENTITIES) {
						try {
							setTrustWithoutCommit(identity, getIdentityByURI(seedURI), (byte)100, "I trust the Freenet developers.");
						} catch(UnknownIdentityException e) {
							Logger.error(this, "SHOULD NOT HAPPEN: Seed identity not known.", e);
						}
					}
					
					mDB.commit(); Logger.debug(this, "COMMITED.");
					
					Logger.debug(this, "Successfully created a new OwnIdentity (" + identity.getNickname() + ")");
					return identity;
				}
				catch(RuntimeException e) {
					mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
					throw e;
				}
			}
		}
	}

	public synchronized void restoreIdentity(String requestURI, String insertURI) throws MalformedURLException, InvalidParameterException {
		OwnIdentity identity;
		synchronized(mDB.lock()) {
			try {
				try {
					Identity old = getIdentityByURI(requestURI);
					
					if(old instanceof OwnIdentity)
						throw new InvalidParameterException("There is already an own identity with the given URI pair.");
					
					// We already have fetched this identity as a stranger's one. We need to update the database.
					identity = new OwnIdentity(insertURI, requestURI, old.getNickname(), old.doesPublishTrustList());
					/* We re-fetch the current edition to make sure all trustees are imported */
					identity.setEdition(old.getEdition() - 1);
				
					for(ContextOffer offer : getOfferedContexts(old))
						addContextOfferWithoutCommit(identity, offer.getContext());
					
					identity.setProperties(old.getProperties());
	
					// Update all received trusts
					for(Trust oldReceivedTrust : getReceivedTrusts(old)) {
						Trust newReceivedTrust = new Trust(oldReceivedTrust.getTruster(), identity,
								oldReceivedTrust.getValue(), oldReceivedTrust.getComment());
						
						mDB.delete(oldReceivedTrust); /* FIXME: Is this allowed by db4o in a for-each loop? */
						mDB.store(newReceivedTrust);
					}
		
					// Update all received scores
					for(Score oldScore : getScores(old)) {
						Score newScore = new Score(oldScore.getTreeOwner(), identity, oldScore.getScore(),
								oldScore.getRank(), oldScore.getCapacity());
						
						mDB.delete(oldScore);
						mDB.store(newScore);
					}
		
					mDB.store(identity);
					initTrustTreeWithoutCommit(identity);
					
					// Update all given trusts
					for(Trust givenTrust : getGivenTrusts(old)) {
						setTrustWithoutCommit(identity, givenTrust.getTrustee(), givenTrust.getValue(), givenTrust.getComment());
						/* FIXME: The old code would just delete the old trust value here instead of doing the following... 
						 * Is the following line correct? */
						removeTrustWithoutCommit(givenTrust);
					}
		
					// Remove the old identity
					mDB.delete(old);
					storeAndCommit(identity);
					
					Logger.debug(this, "Successfully restored an already known identity from Freenet (" + identity.getNickname() + ")");
					
				} catch (UnknownIdentityException e) {
					identity = new OwnIdentity(new FreenetURI(insertURI), new FreenetURI(requestURI), null, false);
					
					// Store the new identity
					mDB.store(identity);
					initTrustTreeWithoutCommit(identity);
					storeAndCommit(identity);
					
					Logger.debug(this, "Successfully restored not-yet-known identity from Freenet (" + identity.getRequestURI() + ")");
				}
			}
			catch(RuntimeException e) {
				mDB.rollback(); Logger.debug(this, "ROLLED BACK!");
				throw e;
			}
			
			mFetcher.fetch(identity);
		}
		
	}

	public synchronized void setTrust(String ownTrusterID, String trusteeID, byte value, String comment)
		throws UnknownIdentityException, NumberFormatException, InvalidParameterException {
		
		OwnIdentity truster = getOwnIdentityByID(ownTrusterID);
		Identity trustee = getIdentityByID(trusteeID);
		
		setTrust(truster, trustee, value, comment);
	}
	
	public synchronized void removeTrust(String ownTrusterID, String trusteeID) throws UnknownIdentityException {
		OwnIdentity truster = getOwnIdentityByID(ownTrusterID);
		Identity trustee = getIdentityByID(trusteeID);

		removeTrust(truster, trustee);
	}
	
	

	

	
	public synchronized void addContext(String ownIdentityID, String newContext) throws UnknownIdentityException, InvalidParameterException {
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.addContext(newContext);
		storeAndCommit(identity);
		
		Logger.debug(this, "Added context '" + newContext + "' to identity '" + identity.getNickname() + "'");
	}

	public synchronized void removeContext(String ownIdentityID, String context) throws UnknownIdentityException, InvalidParameterException {
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.removeContext(context);
		storeAndCommit(identity);
		
		Logger.debug(this, "Removed context '" + context + "' from identity '" + identity.getNickname() + "'");
	}
	
	public synchronized String getProperty(String identityID, String property) throws InvalidParameterException, UnknownIdentityException {
		return getIdentityByID(identityID).getProperty(property);
	}

	public synchronized void setProperty(String ownIdentityID, String property, String value)
		throws UnknownIdentityException, InvalidParameterException {
		
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.setProperty(property, value);
		storeAndCommit(identity);
		
		Logger.debug(this, "Added property '" + property + "=" + value + "' to identity '" + identity.getNickname() + "'");
	}
	
	public void removeProperty(String ownIdentityID, String property) throws UnknownIdentityException, InvalidParameterException {
		Identity identity = getOwnIdentityByID(ownIdentityID);
		identity.removeProperty(property);
		storeAndCommit(identity);
		
		Logger.debug(this, "Removed property '" + property + "' from identity '" + identity.getNickname() + "'");
	}

	public String getVersion() {
		return Version.getMarketingVersion();
	}
	
	public long getRealVersion() {
		return Version.getRealVersion();
	}

	public String getString(String key) {
		return key;
	}

	public void setClassLoader(ClassLoader myClassLoader) {
		mClassLoader = myClassLoader;
	}
	
	public void setLanguage(LANGUAGE newLanguage) {
	}
	
	public PluginRespirator getPluginRespirator() {
		return mPR;
	}
	
	public ExtObjectContainer getDB() {
		return mDB;
	}
	
	public Config getConfig() {
		return mConfig;
	}
	
	public IdentityFetcher getIdentityFetcher() {
		return mFetcher;
	}

	public XMLTransformer getXMLTransformer() {
		return mXMLTransformer;
	}
	
	public IntroductionPuzzleStore getIntroductionPuzzleStore() {
		return mPuzzleStore;
	}

	public IntroductionClient getIntroductionClient() {
		return mIntroductionClient;
	}

	public RequestClient getRequestClient() {
		return mRequestClient;
	}

}
