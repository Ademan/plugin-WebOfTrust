/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.ui.web;

import java.util.Collection;
import java.util.Date;
import java.util.TreeMap;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Trust;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;

import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.CurrentTimeUTC;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;


/**
 * The page where users can manage others identities.
 * 
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class KnownIdentitiesPage extends WebPageImpl {

	private final String identitiesPageURI;
	
	/**
	 * Creates a new KnownIdentitiesPage
	 * 
	 * @param myWebInterface A reference to the WebInterface which created the page, used to get resources the page needs. 
	 * @param myRequest The request sent by the user.
	 */
	public KnownIdentitiesPage(WebInterfaceToadlet toadlet, HTTPRequest myRequest, ToadletContext context) {
		super(toadlet, myRequest, context);
		identitiesPageURI = toadlet.webInterface.getURI() + "/ShowIdentity";
	}

	public void make() {
		if(request.isPartSet("AddIdentity")) {
			try {
				wot.addIdentity(request.getPartAsString("IdentityURI", 1024));
				HTMLNode successBox = addContentBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.Success.Header"));
				successBox.addChild("#", WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.Success.Text"));
			}
			catch(Exception e) {
				addErrorBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.Failed"), e);
			}
		}
		
		if(request.isPartSet("SetTrust")) {
			String trusterID = request.getPartAsString("OwnerID", 128);
			String trusteeID = request.isPartSet("Trustee") ? request.getPartAsString("Trustee", 128) : null;
			String value = request.getPartAsString("Value", 4);
			// TODO: getPartAsString() will return an empty String if the length is exceeded, it should rather return a too long string so that setTrust throws
			// an exception. It's not a severe problem though since we limit the length of the text input field anyway.
			String comment = request.getPartAsString("Comment", Trust.MAX_TRUST_COMMENT_LENGTH + 1);
			
			try {
				if(trusteeID == null) /* For AddIdentity */
					trusteeID = Identity.getIDFromURI(new FreenetURI(request.getPartAsString("IdentityURI", 1024)));
				
				if(value.trim().equals(""))
					wot.removeTrust(trusterID, trusteeID);
				else
					wot.setTrust(trusterID, trusteeID, Byte.parseByte(value), comment);
			} catch(InvalidParameterException e) {
				addErrorBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.SetTrust.Failed"), e.getMessage());
			} catch(Exception e) {
				addErrorBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.SetTrust.Failed"), e);
			}
		}

		OwnIdentity treeOwner = null;
		ObjectContainer db = wot.getDB();
		PluginRespirator _pr = wot.getPluginRespirator();
		int nbOwnIdentities = 1;
		String ownerID = request.getPartAsString("OwnerID", 128);
		
		if(!ownerID.equals("")) {
			try {
				treeOwner = wot.getOwnIdentityByID(ownerID);
			} catch (Exception e) {
				Logger.error(this, "Error while selecting the OwnIdentity", e);
				addErrorBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.SelectOwnIdentity.Failed"), e);
			}
		} else {
			synchronized(wot) {
				ObjectSet<OwnIdentity> allOwnIdentities = wot.getAllOwnIdentities();
				nbOwnIdentities = allOwnIdentities.size();
				if(nbOwnIdentities == 1)
					treeOwner = allOwnIdentities.next();
			}
		}
			
		makeAddIdentityForm(_pr, treeOwner);

		if(treeOwner != null) {
			try {
				makeKnownIdentitiesList(treeOwner, db, _pr);
			} catch (Exception e) {
				Logger.error(this, "Error", e);
				addErrorBox("Error", e);
			}
		} else if(nbOwnIdentities > 1)
			makeSelectTreeOwnerForm(db, _pr);
		else
			makeNoOwnIdentityWarning();
	}
	
	/**
	 * Makes a form where the user can enter the requestURI of an Identity he knows.
	 * 
	 * @param _pr a reference to the {@link PluginRespirator}
	 * @param treeOwner The owner of the known identity list. Not used for adding the identity but for showing the known identity list properly after adding.
	 */
	private void makeAddIdentityForm(PluginRespirator _pr, OwnIdentity treeOwner) {
		
		// TODO Add trust value and comment fields and make them mandatory
		// The user should only add an identity he trusts
		HTMLNode addBoxContent = addContentBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.Header"));
	
		HTMLNode createForm = _pr.addFormChild(addBoxContent, uri, "AddIdentity");
		if(treeOwner != null)
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "OwnerID", treeOwner.getID()});
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "AddIdentity" });
		createForm.addChild("span", new String[] {"title", "style"}, 
				new String[] { 
		            WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.IdentityURI.Tooltip"), 
		            "border-bottom: 1px dotted; cursor: help;"} , 
		            WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.IdentityURI") + ": ");
		
		createForm.addChild("input", new String[] {"type", "name", "size"}, new String[] {"text", "IdentityURI", "70"});
		createForm.addChild("br");
		
		if(treeOwner != null) {
			createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "SetTrust", "true"});
			
			createForm.addChild("span", WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.Trust") + ": ")
				.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Value", "4", "" });
			
			createForm.addChild("span", " " + WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.Comment") + ": ")
				.addChild("input", new String[] { "type", "name", "size", "value" }, new String[] { "text", "Comment", "20", "" });
			
			createForm.addChild("br");
		}
		
		createForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "AddIdentity", WoT.getBaseL10n().getString("KnownIdentitiesPage.AddIdentity.AddButton") });
	}

	private void makeNoOwnIdentityWarning() {
		addErrorBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.NoOwnIdentityWarning.Header"), WoT.getBaseL10n().getString("KnownIdentitiesPage.NoOwnIdentityWarning.Text"));
	}
	
	private void makeSelectTreeOwnerForm(ObjectContainer db, PluginRespirator _pr) {

		HTMLNode listBoxContent = addContentBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.SelectTreeOwner.Header"));
		HTMLNode selectForm = _pr.addFormChild(listBoxContent, uri, "ViewTree");
		selectForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "ViewTree" });
		HTMLNode selectBox = selectForm.addChild("select", "name", "OwnerID");

		synchronized(wot) {
			for(OwnIdentity ownIdentity : wot.getAllOwnIdentities())
				selectBox.addChild("option", "value", ownIdentity.getID(), ownIdentity.getNickname());
		}

		selectForm.addChild(
		        "input", 
		        new String[] { "type", "name", "value" }, 
		        new String[] { "submit", "select", WoT.getBaseL10n().getString("KnownIdentitiesPage.SelectTreeOwner.ViewOwnersTreeButton") });
	}
	
	private String formatTimeDelta(long delta) {
		long days = delta / (1000 * 60 * 60 * 24);
		long hours = (delta % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
		long minutes = ((delta % (1000 * 60 * 60 * 24)) % (1000 * 60 * 60)) / (1000 * 60);
		
		final String daysMnemonic = WoT.getBaseL10n().getString("Common.daysMnemonic");
		final String hoursMnemonic = WoT.getBaseL10n().getString("Common.hoursMnemonic");
		final String minutesMnemonic = WoT.getBaseL10n().getString("Common.minutesMnemonic");
		final String ago = WoT.getBaseL10n().getString("Common.ago");
		
		if(days > 3)
			return days + daysMnemonic + " " + ago;
		else if(days > 0)
			return days + daysMnemonic + " " + hours + hoursMnemonic + " " + ago;
		else if(hours > 0)
			return hours + hoursMnemonic + " " + ago;
		else
			return minutes + minutesMnemonic + " " + ago; 
	}

	/**
	 * Get a xHTML color (#abcdef) corresponding to a trust level.
	 * Red : -100 or below
	 * Green : 100 or above
	 * Yellow : 0
	 * And all the intermediate colors between.
	 *
	 * @param trustLevel Level of trust
	 * @return Color (format #abcdef)
	 */
	public static String getTrustColor(int trustLevel) {
		final int r;
		final int g;

		if(trustLevel < 0) {
			r = 0xff;
			g = Math.max(0xff + (int)((trustLevel)*2.55f), 0x00);
		} else {
			g = 0xff;
			r = Math.max(0xff - (int)((trustLevel)*2.55f), 0x00);
		}

		String sR = Integer.toHexString(r);
		if(sR.length() == 1) {
			sR = "0" + sR;
		}
		String sG = Integer.toHexString(g);
		if(sG.length() == 1) {
			sG = "0" + sG;
		}

		return "#" + sR + sG + "00";
	}
	
	/**
	 * Makes the list of Identities known by the tree owner.
	 * 
	 * @param db a reference to the database 
	 * @param _pr a reference to the {@link PluginRespirator}
	 * @param treeOwner owner of the trust tree we want to display 
	 */
	private void makeKnownIdentitiesList(OwnIdentity treeOwner, ObjectContainer db, PluginRespirator _pr) throws DuplicateScoreException, DuplicateTrustException {

		String nickFilter = request.isPartSet("nickfilter") ? request.getPartAsString("nickfilter", 100).trim() : "";
		String sortBy = request.isPartSet("sortby") ? request.getPartAsString("sortby", 100).trim() : "nickname";
		String sortType = request.isPartSet("sorttype") ? request.getPartAsString("sorttype", 100).trim() : "asc";

		HTMLNode filters = addContentBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.Header"));
		HTMLNode filtersForm = _pr.addFormChild(filters, uri, "Filters").addChild("p");
		filtersForm.addChild("#", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.ShowOnlyNicksContaining") + " : ");
		filtersForm.addChild("input", new String[]{"type", "size", "name", "value"}, new String[]{"text", "15", "nickfilter", nickFilter});
		filtersForm.addChild("#", " " + WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy") + " : ");
		HTMLNode option = filtersForm.addChild("select", new String[]{"name", "id"}, new String[]{"sortby", "sortby"});
		TreeMap<String, String> options = new TreeMap<String, String>();
		options.put("nickname", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Nickname"));
		options.put("score", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Score"));
		options.put("localtrust", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.LocalTrust"));
		for(String e : options.keySet()) {
			HTMLNode newOption = option.addChild("option", "value", e, options.get(e));
			if(e.equals(sortBy)) {
				newOption.addAttribute("selected", "selected");
			}
		}

		option = filtersForm.addChild("select", new String[]{"name", "id"}, new String[]{"sorttype", "sorttype"});
		options = new TreeMap<String, String>();
		options.put("asc", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Ascending"));
		options.put("desc", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.Descending"));
		for(String e : options.keySet()) {
			HTMLNode newOption = option.addChild("option", "value", e, options.get(e));
			if(e.equals(sortType)) {
				newOption.addAttribute("selected", "selected");
			}
		}

		filtersForm.addChild("input", new String[]{"type", "value"}, new String[]{"submit", WoT.getBaseL10n().getString("KnownIdentitiesPage.FiltersAndSorting.SortIdentitiesBy.SubmitButton")});


		HTMLNode listBoxContent = addContentBox(WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.Header"));

		// Display the list of known identities
		HTMLNode identitiesTable = listBoxContent.addChild("table", "border", "0");
		HTMLNode row=identitiesTable.addChild("tr");
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Nickname"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Added"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Fetched"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.PublishesTrustlist"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.ScoreAndRank"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.TrustAndComment"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Trusters"));
		row.addChild("th", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.TableHeader.Trustees"));
		
		synchronized(wot) {
		long currentTime = CurrentTimeUTC.getInMillis();

		Collection<Identity> ids = null;

		if(sortBy.equals("nickname")) {
			TreeMap<String, Identity> identities = new TreeMap<String, Identity>();
			for(Identity id : wot.getAllIdentities()) {
				String nick = id.getNickname();
				if(nick == null) {
					nick = id.getID();
				} else {
					nick = nick.toLowerCase() + id.getID();
				}
				identities.put(nick, id);
			}
		
			ids = identities.values();
		} else if(sortBy.equals("score")) {
			TreeMap<Long, Identity> identities = new TreeMap<Long, Identity>();
			for(Identity id : wot.getAllIdentities()) {
				Long score = null;
				try {
					score = new Long(wot.getScore((OwnIdentity) treeOwner, id).getScore());
					// We want to avoid conflicts, it has to be unique and the value doesn't matter!
					score *= 10000000;
				} catch (NotInTrustTreeException ex) {
					// Trust is null
					score = Long.MIN_VALUE;
				}
				while(identities.containsKey(score)) {
					score++;
				}

				identities.put(score, id);
			}

			ids = identities.values();
		} else if(sortBy.equals("localtrust")) {
			TreeMap<Long, Identity> identities = new TreeMap<Long, Identity>();
			for(Identity id : wot.getAllIdentities()) {
				Long localScore = null;
				try {
					localScore = new Long(wot.getTrust((OwnIdentity) treeOwner, id).getValue());
				} catch (NotTrustedException ex) {
					localScore = Long.MIN_VALUE;
				} catch (DuplicateTrustException ex) {
					localScore = Long.MIN_VALUE;
				}
				localScore *= 10000000;
				while(identities.containsKey(localScore)) {
					localScore++;
				}

				identities.put(localScore, id);
			}

			ids = identities.values();
		}

		Identity[] identitiesArray = ids.toArray(new Identity[0]);
		if(sortType.equals("desc")) {
			Identity[] reverse = new Identity[identitiesArray.length];
			for(int i = 0; i < reverse.length; ++i) {
				reverse[reverse.length - i - 1] = identitiesArray[i];
			}
			identitiesArray = reverse;
		}

		for(Identity id : identitiesArray) {
			if(id == treeOwner) continue;

			if(!nickFilter.equals("")) {
				if(id.getNickname() == null) continue;
				if(!id.getNickname().toLowerCase().contains(nickFilter.toLowerCase())) continue;
			}

			row=identitiesTable.addChild("tr");
			
			// NickName
			HTMLNode nameLink = row.addChild("td", new String[] {"title", "style"}, new String[] {id.getRequestURI().toString(), "cursor: help;"})
				.addChild("a", "href", identitiesPageURI+"?id=" + id.getID());
			
			String nickName = id.getNickname();
			if(nickName != null)
				nameLink.addChild("#", nickName + "@" + id.getID().substring(0, 5) + "...");
			else
				nameLink.addChild("span", "class", "alert-error").addChild("#", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.Table.NicknameNotDownloadedYet"));
			
			// Added date
			row.addChild("td", formatTimeDelta(currentTime - id.getAddedDate().getTime()));
			
			// Last fetched date
			Date lastFetched = id.getLastFetchedDate();
			if(!lastFetched.equals(new Date(0)))
				row.addChild("td", formatTimeDelta(currentTime - lastFetched.getTime()));
			else
				row.addChild("td", WoT.getBaseL10n().getString("Common.Never"));
			
			// Publish TrustList
			row.addChild("td", new String[] { "align" }, new String[] { "center" } , id.doesPublishTrustList() ? WoT.getBaseL10n().getString("Common.Yes") : WoT.getBaseL10n().getString("Common.No"));
			
			//Score
			try {
				final int score = wot.getScore((OwnIdentity)treeOwner, id).getScore();
				row.addChild("td", new String[] { "align", "style" }, new String[] { "center", "background-color:" + KnownIdentitiesPage.getTrustColor(score) + ";" } ,
						Integer.toString(score) +" ("+
						wot.getScore((OwnIdentity)treeOwner, id).getRank()+")");
			}
			catch (NotInTrustTreeException e) {
				// This only happen with identities added manually by the user
				// TODO Maybe we should give the opportunity to trust it at creation time
				row.addChild("td", "null");	
			}
			
			// Own Trust
			row.addChild(getReceivedTrustForm(treeOwner, id));
			
			// Nb Trusters
			HTMLNode trustersCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trustersCell.addChild(new HTMLNode("a", "href", identitiesPageURI + "?id="+id.getID(),
					Long.toString(wot.getReceivedTrusts(id).size())));
			
			// Nb Trustees
			HTMLNode trusteesCell = row.addChild("td", new String[] { "align" }, new String[] { "center" });
			trusteesCell.addChild(new HTMLNode("a", "href", identitiesPageURI + "?id="+id.getID(),
					Long.toString(wot.getGivenTrusts(id).size())));
		}
		}
	}
	
	private HTMLNode getReceivedTrustForm (OwnIdentity truster, Identity trustee) throws DuplicateTrustException {

		String trustValue = "";
		String trustComment = "";
		Trust trust;
		
		try {
			trust = wot.getTrust(truster, trustee);
			trustValue = String.valueOf(trust.getValue());
			trustComment = trust.getComment();
		}
		catch (NotTrustedException e) {
			Logger.debug(this, truster.getNickname() + " does not trust " + trustee.getNickname());
		} 
			
		HTMLNode cell = new HTMLNode("td");
		if(trustValue.length()>0) {
			cell.addAttribute("style", "background-color:" + KnownIdentitiesPage.getTrustColor(Integer.parseInt(trustValue)) + ";");
		}

		HTMLNode trustForm = pr.addFormChild(cell, uri, "SetTrust");
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "page", "SetTrust" });
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "OwnerID", truster.getID() });
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "Trustee", trustee.getID() });
		
		// Trust value input field
		trustForm.addChild("input", new String[] { "type", "name", "size", "maxlength", "value" }, 
				new String[] { "text", "Value", "4", "4", trustValue });
		
		// Trust comment input field
		trustForm.addChild("input", new String[] { "type", "name", "size", "maxlength", "value" }, 
				new String[] { "text", "Comment", "50", Integer.toString(Trust.MAX_TRUST_COMMENT_LENGTH), trustComment });
		
		trustForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "SetTrust", WoT.getBaseL10n().getString("KnownIdentitiesPage.KnownIdentities.Table.UpdateTrustButton") });

		return cell;
	}
}
