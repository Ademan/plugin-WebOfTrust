/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.keys.FreenetURI;
import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.TransferThread;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;
import freenet.support.io.NativeThread;

/**
 * Inserts OwnIdentities to Freenet when they need it.
 * 
 * @author xor (xor@freenetproject.org), Julien Cornuwel (batosai@freenetproject.org)
 */
public final class IdentityInserter extends TransferThread {
	
	private static final int STARTUP_DELAY = 1 * 60 * 1000; /* FIXME: Tweak before release */
	private static final int THREAD_PERIOD = 6 * 60 * 1000; /* FIXME: Tweak before release */
	
	/**
	 * The minimal time for which an identity must not have changed before we insert it.
	 */
	private static final int MIN_DELAY_BEFORE_INSERT = 5 * 60 * 1000; /* FIXME: Tweak before release */
	
	/**
	 * The maximal delay for which an identity insert can be delayed (relative to the last insert) due to continuous changes.
	 */
	private static final int MAX_DELAY_BEFORE_INSERT = 10 * 60 * 1000; /* FIXME: Tweak before release */
	
	
	private WoT mWoT;

	/** Random number generator */
	private Random mRandom;
	
	/**
	 * Creates an IdentityInserter.
	 * 
	 * @param db A reference to the database
	 * @param client A reference to an {@link HighLevelSimpleClient} to perform inserts
	 * @param tbf Needed to create buckets from Identities before insert
	 */
	public IdentityInserter(WoT myWoT) {
		super(myWoT.getPluginRespirator().getNode(), myWoT.getPluginRespirator().getHLSimpleClient(), "WoT Identity Inserter");
		mWoT = myWoT;
		mRandom = mWoT.getPluginRespirator().getNode().fastWeakRandom;
		
		start();
	}
	
	@Override
	protected Collection<ClientGetter> createFetchStorage() {
		return null;
	}

	@Override
	protected Collection<BaseClientPutter> createInsertStorage() {
		return new ArrayList<BaseClientPutter>(10); /* 10 identities should be much */
	}

	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}

	@Override
	protected long getStartupDelay() {
		return STARTUP_DELAY/2 + mRandom.nextInt(STARTUP_DELAY);
	}
	
	@Override
	protected long getSleepTime() {
		return THREAD_PERIOD/2 + mRandom.nextInt(THREAD_PERIOD);
	}

	@Override
	protected void iterate() {
		abortInserts();
		
		synchronized(mWoT) {
			for(OwnIdentity identity : mWoT.getAllOwnIdentities()) {
				if(identity.needsInsert()) {
					try {
						long minDelayedInsertTime = identity.getLastChangeDate().getTime() + MIN_DELAY_BEFORE_INSERT;
						long maxDelayedInsertTime = identity.getLastInsertDate().getTime() + MAX_DELAY_BEFORE_INSERT; 
						
						if(CurrentTimeUTC.getInMillis() > Math.min(minDelayedInsertTime, maxDelayedInsertTime)) {
							Logger.debug(this, "Starting insert of " + identity.getNickname() + " (" + identity.getInsertURI() + ")");
							insert(identity);
						} else {
							long lastChangeBefore = (CurrentTimeUTC.getInMillis() - identity.getLastChangeDate().getTime()) / (60*1000);
							long lastInsertBefore = (CurrentTimeUTC.getInMillis() - identity.getLastInsertDate().getTime()) / (60*1000); 
							
							Logger.debug(this, "Delaying insert of " + identity.getNickname() + " (" + identity.getInsertURI() + "), " +
									"last change: " + lastChangeBefore + "min ago, last insert: " + lastInsertBefore + "min ago");
						}
					} catch (Exception e) {
						Logger.error(this, "Identity insert failed: " + e.getMessage(), e);
					}
				}
			}
		}
	}

	/**
	 * Inserts an OwnIdentity.
	 * @throws IOException 
	 */
	private void insert(OwnIdentity identity) throws IOException {
		Bucket tempB = mTBF.makeBucket(64 * 1024); /* FIXME: Tweak */  
		OutputStream os = null;

		try {
			os = tempB.getOutputStream();
			mWoT.getXMLTransformer().exportOwnIdentity(identity, os);
			os.close(); os = null;
			tempB.setReadOnly();
		
			long edition = identity.getEdition();
			if(identity.getLastInsertDate().after(new Date(0)))
				++edition;
			
			/* FIXME: Toad: Are these parameters correct? */
			InsertBlock ib = new InsertBlock(tempB, null, identity.getInsertURI().setSuggestedEdition(edition));
			InsertContext ictx = mClient.getInsertContext(true);
			
			/* FIXME: are these parameters correct? */
			ClientPutter pu = mClient.insert(ib, false, null, false, ictx, this);
			// FIXME: Set to a reasonable value before release, PluginManager default is interactive priority
			// pu.setPriorityClass(RequestStarter.UPDATE_PRIORITY_CLASS);
			addInsert(pu);
			tempB = null;
			
			Logger.debug(this, "Started insert of identity '" + identity.getNickname() + "'");
		}
		catch(Exception e) {
			Logger.error(this, "Error during insert of identity '" + identity.getNickname() + "'", e);
		}
		finally {
			if(tempB != null)
				tempB.free();
			Closer.close(os);
		}
	}
	
	public void onSuccess(BaseClientPutter state, ObjectContainer container)
	{
		try {
			synchronized(mWoT) {
				OwnIdentity identity = mWoT.getOwnIdentityByURI(state.getURI());
				identity.setEdition(state.getURI().getEdition());
				identity.updateLastInsertDate();
				mWoT.storeAndCommit(identity);
				Logger.debug(this, "Successful insert of identity '" + identity.getNickname() + "'");
			}
		}
		catch(Exception e)
		{
			Logger.error(this, "Error", e);
		}
		finally {
			removeInsert(state);
		}
	}

	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) 
	{
		try {
			Logger.error(this, "Error during insert of identity ", e);
			/* We do not increase the edition of the identity if there is a collision because the fetcher will fetch the new edition
			 * and the Inserter will insert it with that edition in the next run. */
		}
		catch(Exception ex) {
			Logger.error(this, "Error", e);
		}
		finally {
			removeInsert(state);
		}
	}
	
	/* Not needed functions from the ClientCallback interface */
	
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onFetchable(BaseClientPutter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onMajorProgress(ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		// TODO Auto-generated method stub
		
	}

}

