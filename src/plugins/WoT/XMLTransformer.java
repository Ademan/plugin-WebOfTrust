/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionPuzzle;

import com.db4o.ext.ExtObjectContainer;

import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;

/**
 * This class handles all XML creation and parsing of the WoT plugin, that is import and export of identities, identity introductions 
 * and introduction puzzles. The code for handling the XML related to identity introduction is not in a separate class in the WoT.Introduction
 * package so that we do not need to create multiple instances of the XML parsers / pass the parsers to the other class. 
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class XMLTransformer {

	private static final int XML_FORMAT_VERSION = 1;
	
	private final WoT mWoT;
	
	private final ExtObjectContainer mDB;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for parsing the identity XML when decoding identities*/
	private final DocumentBuilder mDocumentBuilder;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Created by mDocumentBuilder, used for building the identity XML DOM when encoding identities */
	private final DOMImplementation mDOM;
	
	/* TODO: Check with a profiler how much memory this takes, do not cache it if it is too much */
	/** Used for storing the XML DOM of encoded identities as physical XML text */
	private final Transformer mSerializer;
	
	/**
	 * Initializes the XML creator & parser and caches those objects in the new IdentityXML object so that they do not have to be initialized
	 * each time an identity is exported/imported.
	 */
	public XMLTransformer(WoT myWoT)
		throws ParserConfigurationException, TransformerConfigurationException, TransformerFactoryConfigurationError {
		
		mWoT = myWoT;
		mDB = mWoT.getDB();
		
		DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
		xmlFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		// DOM parser uses .setAttribute() to pass to underlying Xerces
		xmlFactory.setAttribute("http://apache.org/xml/features/disallow-doctype-decl", true);
		mDocumentBuilder = xmlFactory.newDocumentBuilder(); 
		mDOM = mDocumentBuilder.getDOMImplementation();
		
		mSerializer = TransformerFactory.newInstance().newTransformer();
		mSerializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		mSerializer.setOutputProperty(OutputKeys.INDENT, "yes"); /* FIXME: Set to no before release. */
		mSerializer.setOutputProperty(OutputKeys.STANDALONE, "no");
	}
	
	public synchronized void exportOwnIdentity(OwnIdentity identity, OutputStream os) throws TransformerException {
		Document xmlDoc = mDOM.createDocument(null, WoT.WOT_NAME, null);
		Element rootElement = xmlDoc.getDocumentElement();
		
		/* Create the identity Element */
		
		Element identityElement = xmlDoc.createElement("Identity");
		identityElement.setAttribute("Version", Integer.toString(XML_FORMAT_VERSION)); /* Version of the XML format */
		
		synchronized(mWoT) {
		synchronized(identity) {
			identityElement.setAttribute("Name", identity.getNickname());
			identityElement.setAttribute("PublishesTrustList", Boolean.toString(identity.doesPublishTrustList()));
			
			/* Create the context Elements */
			
			for(String context : identity.getContexts()) {
				Element contextElement = xmlDoc.createElement("Context");
				contextElement.setAttribute("Name", context);
				identityElement.appendChild(contextElement);
			}
			
			/* Create the property Elements */
			
			for(Entry<String, String> property : identity.getProperties().entrySet()) {
				Element propertyElement = xmlDoc.createElement("Property");
				propertyElement.setAttribute("Name", property.getKey());
				propertyElement.setAttribute("Value", property.getValue());
				identityElement.appendChild(propertyElement);
			}
			
			/* Create the trust list Element and its trust Elements */

			Element trustListElement = xmlDoc.createElement("TrustList");
			
			for(Trust trust : mWoT.getGivenTrusts(identity)) {
				Element trustElement = xmlDoc.createElement("Trust");
				trustElement.setAttribute("Identity", trust.getTrustee().getRequestURI().toString());
				trustElement.setAttribute("Value", Byte.toString(trust.getValue()));
				trustElement.setAttribute("Comment", trust.getComment());
				trustListElement.appendChild(trustElement);
			}
			identityElement.appendChild(trustListElement);
		}
		}
		
		rootElement.appendChild(identityElement);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		mSerializer.transform(domSource, resultStream);
	}
	
	/**
	 * Imports a identity XML file into the given web of trust. This includes:
	 * - The identity itself and its attributes
	 * - The trust list of the identity, if it has published one in the XML.
	 * 
	 * If the identity does not exist yet, it is created. If it does, the existing one is updated.
	 * 
	 * @param myWoT The web of trust where to store the identity and trust list at.
	 * @param xmlInputStream The input stream containing the XML.
	 * @throws Exception 
	 * @throws Exception
	 */
	public synchronized void importIdentity(FreenetURI identityURI, InputStream xmlInputStream) throws Exception  { 
		Document xml = mDocumentBuilder.parse(xmlInputStream);
		Element identityElement = (Element)xml.getElementsByTagName("Identity").item(0);
		
		if(Integer.parseInt(identityElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new Exception("Version " + identityElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);
		
		String identityName = identityElement.getAttribute("Name");
		boolean identityPublishesTrustList = Boolean.parseBoolean(identityElement.getAttribute("PublishesTrustList"));
		
		ArrayList<String> identityContexts = new ArrayList<String>(4);
		NodeList contextList = identityElement.getElementsByTagName("Context");
		for(int i = 0; i < contextList.getLength(); ++i) {
			Element contextElement = (Element)contextList.item(i);
			identityContexts.add(contextElement.getAttribute("Name"));
		}
		
		HashMap<String, String> identityProperties = new HashMap<String, String>(8);
		NodeList propertyList = identityElement.getElementsByTagName("Property");
		for(int i = 0; i < propertyList.getLength(); ++i) {
			Element propertyElement = (Element)propertyList.item(i);
			identityProperties.put(propertyElement.getAttribute("Name"), propertyElement.getAttribute("Value"));
		}
		
		/* We tried to parse as much as we can without synchronization before we lock everything :) */
		
		synchronized(mWoT) {
			Identity identity;
			boolean isNewIdentity = false;
			
			try {
				identity = mWoT.getIdentityByURI(identityURI);
			}
			catch(UnknownIdentityException e) {
				identity = new Identity(identityURI, identityName, identityPublishesTrustList);
				isNewIdentity = true;
			}
			
			synchronized(identity) {
				identity.setEdition(identityURI.getEdition());

				try {
					identity.setNickname(identityName);
				}
				catch(Exception e) {
					/* Nickname changes are not allowed, ignore them... */
					Logger.error(identityURI, "setNickname() failed.", e);
				}

				try { /* Failure of context importing should not make an identity disappear, therefore we catch exceptions. */
					identity.setContexts(identityContexts);
				}
				catch(Exception e) {
					Logger.error(identityURI, "setContexts() failed.", e);
				}

				try { /* Failure of property importing should not make an identity disappear, therefore we catch exceptions. */
					identity.setProperties(identityProperties);
				}
				catch(Exception e) {
					Logger.error(identityURI, "setProperties() failed", e);
				}
				
				/* We store the identity even if it's trust list import fails - identities should not disappear then. */
				mWoT.storeAndCommit(identity);
				
				if(identityPublishesTrustList) {
					/* This try block is for rolling back in catch() if an exception is thrown during trust list import.
					 * Our policy is: We either import the whole trust list or nothing. We should not bias the trust system by allowing
					 * the import of partial trust lists. Especially we should not ignore failing deletions of old trust objects. */
					try {
						boolean trusteeCreationAllowed = mWoT.getBestScore(identity) > 0;

						Element trustListElement = (Element)identityElement.getElementsByTagName("TrustList").item(0);
						NodeList trustList = trustListElement.getElementsByTagName("Trust");
						for(int i = 0; i < trustList.getLength(); ++i) {
							Element trustElement = (Element)trustList.item(i);

							String trusteeURI = trustElement.getAttribute("Identity");
							byte trustValue = Byte.parseByte(trustElement.getAttribute("Value"));
							String trustComment = trustElement.getAttribute("Comment");

							Identity trustee = null;
							try {
								trustee = mWoT.getIdentityByURI(trusteeURI);
							}
							catch(UnknownIdentityException e) {
								if(trusteeCreationAllowed) { /* We only create trustees if the truster has a positive score */
									trustee = new Identity(trusteeURI, null, false);
									mDB.store(trustee);
									mWoT.getIdentityFetcher().fetch(trustee);
								}
							}

							if(trustee != null)
								mWoT.setTrustWithoutCommit(identity, trustee, trustValue, trustComment);
						}

						if(!isNewIdentity) { /* Delete trust objects of trustees which were removed from the trust list */
							for(Trust trust : mWoT.getGivenTrustsOlderThan(identity, identityURI.getEdition())) {
								mWoT.removeTrustWithoutCommit(trust);
							}
						}

						mWoT.storeAndCommit(identity);
					}
					
					catch(Exception e) {
						mDB.rollback();
						Logger.error(identityURI, "Importing trust list failed.", e);
					}
				}
			}
		}
	}

	public synchronized void exportIntroduction(OwnIdentity identity, OutputStream os) throws TransformerException {
		Document xmlDoc = mDOM.createDocument(null, WoT.WOT_NAME, null);
		Element rootElement = xmlDoc.getDocumentElement();

		Element introElement = xmlDoc.createElement("IdentityIntroduction");
		introElement.setAttribute("Version", Integer.toString(XML_FORMAT_VERSION)); /* Version of the XML format */

		Element identityElement = xmlDoc.createElement("Identity");
		identityElement.setAttribute("URI", identity.getRequestURI().toString());
		introElement.appendChild(identityElement);
	
		rootElement.appendChild(introElement);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		mSerializer.transform(domSource, resultStream);
	}

	/**
	 * Creates an identity from an identity introduction, stores it in the database and returns the new identity.
	 * If the identity already exists, the existing identity is returned.
	 * 
	 * @throws InvalidParameterException 
	 * @throws IOException 
	 * @throws SAXException 
	 */
	public synchronized Identity importIntroduction(InputStream xmlInputStream) throws InvalidParameterException, SAXException, IOException {
		Document xml = mDocumentBuilder.parse(xmlInputStream);
		Element identityElement = (Element)xml.getElementsByTagName("Identity").item(0);
		
		if(Integer.parseInt(identityElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new InvalidParameterException("Version " + identityElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);
		
		FreenetURI identityURI = new FreenetURI(identityElement.getAttribute("URI"));
		
		Identity identity;
		
		synchronized(mWoT) {
			try {
				identity = mWoT.getIdentityByURI(identityURI);
				Logger.minor(this, "Imported introduction for an already existing identity: " + identity);
			}
			catch (UnknownIdentityException e) {
				identity = new Identity(identityURI, null, false);
				mWoT.storeAndCommit(identity);
				mWoT.getIdentityFetcher().fetch(identity);
			}
		}

		return identity;
	}

	public synchronized void exportIntroductionPuzzle(IntroductionPuzzle puzzle, OutputStream os)
		throws TransformerException, ParserConfigurationException {
		
		Document xmlDoc = mDOM.createDocument(null, WoT.WOT_NAME, null);
		Element rootElement = xmlDoc.getDocumentElement();

		Element puzzleElement = xmlDoc.createElement("IntroductionPuzzle");
		puzzleElement.setAttribute("Version", Integer.toString(XML_FORMAT_VERSION)); /* Version of the XML format */
		
		/* TODO: This lock is actually not neccessary because all values which are taken from the puzzle are final. Decide whether anything
		 * else which is bad can happen if we do not synchronize. For example how does db4o handle deletion of objects if they are still
		 * referenced somewhere? */
		synchronized(puzzle) { 
			puzzleElement.setAttribute("ID", puzzle.getID());
			puzzleElement.setAttribute("Type", puzzle.getType().toString());
			puzzleElement.setAttribute("MimeType", puzzle.getMimeType());
			puzzleElement.setAttribute("ValidUntilTime", Long.toString(puzzle.getValidUntilTime()));
			
			Element dataElement = xmlDoc.createElement("Data");
			dataElement.setAttribute("Value", Base64.encodeStandard(puzzle.getData()));
			puzzleElement.appendChild(dataElement);	
		}
		
		rootElement.appendChild(puzzleElement);

		DOMSource domSource = new DOMSource(xmlDoc);
		StreamResult resultStream = new StreamResult(os);
		mSerializer.transform(domSource, resultStream);
	}

	public synchronized IntroductionPuzzle importIntroductionPuzzle(FreenetURI puzzleURI, InputStream xmlInputStream)
		throws SAXException, IOException, InvalidParameterException, UnknownIdentityException, IllegalBase64Exception, ParseException {
		
		Document xml = mDocumentBuilder.parse(xmlInputStream);
		Element puzzleElement = (Element)xml.getElementsByTagName("IntroductionPuzzle").item(0);
		
		if(Integer.parseInt(puzzleElement.getAttribute("Version")) > XML_FORMAT_VERSION)
			throw new InvalidParameterException("Version " + puzzleElement.getAttribute("Version") + " > " + XML_FORMAT_VERSION);	
		
		Identity puzzleInserter = mWoT.getIdentityByURI(puzzleURI);
		String puzzleID = puzzleElement.getAttribute("ID");
		IntroductionPuzzle.PuzzleType puzzleType = IntroductionPuzzle.PuzzleType.valueOf(puzzleElement.getAttribute("Type"));
		String puzzleMimeType = puzzleElement.getAttribute("MimeType");
		long puzzleValidUntilTime = Long.parseLong(puzzleElement.getAttribute("ValidUntilTime"));
		
		Element dataElement = (Element)puzzleElement.getElementsByTagName("Data").item(0);
		byte[] puzzleData =  Base64.decodeStandard(dataElement.getAttribute("Value"));
		
		IntroductionPuzzle puzzle = new IntroductionPuzzle(puzzleInserter, puzzleID, puzzleType, puzzleMimeType, puzzleData, puzzleValidUntilTime,
				IntroductionPuzzle.getDateFromRequestURI(puzzleURI), IntroductionPuzzle.getIndexFromRequestURI(puzzleURI));
		
		mWoT.getIntroductionPuzzleStore().storeAndCommit(puzzle);
		
		return puzzle;
	}

}
