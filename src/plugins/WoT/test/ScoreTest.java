/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.test;

import java.net.MalformedURLException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Score;
import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.UnknownIdentityException;

import com.db4o.Db4o;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends DatabaseBasedTest {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private OwnIdentity a;
	private Identity b;


	@Override
	protected void setUp() throws Exception {
		super.setUp();
		
		a = new OwnIdentity(uriA, uriA, "A", true);
		b = new Identity(uriB, "B", true);
		db.store(a);
		db.store(b);
		Score score = new Score(a,b,100,1,40);
		db.store(score);
		db.commit();
	}
	
	/* FIXME: Add some logic to make db4o deactivate everything which is not used before loading the objects from the db!
	 * Otherwise these tests might not be sufficient. 
	 * Put this logic into the DatabaseBasedTest base class. */

	public void testScoreCreation() throws NotInTrustTreeException, DuplicateScoreException  {
		
		Score score = b.getScore(a, db);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
	
	public void testScorePersistence() throws NotInTrustTreeException, DuplicateScoreException, MalformedURLException, UnknownIdentityException, DuplicateIdentityException {
		
		db.close();
		
		System.gc();
		System.runFinalization();
		
		db = Db4o.openFile(getDatabaseFilename());
		
		a = OwnIdentity.getByURI(db, uriA);
		b = Identity.getByURI(db, uriB);
		Score score = b.getScore(a, db);
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTreeOwner() == a);
		assertTrue(score.getTarget() == b);
	}
}