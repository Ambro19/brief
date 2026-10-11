package unit;

import DAO.AccountDAO;
import DAO.MessageDAO;
import Model.Account;
import Model.Message;
import Service.MentionService;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Tests the mention rules with in-memory stand-ins for the database. */
public class MentionServiceTest {

    private static class FakeAccountDAO extends AccountDAO {
        final List<Account> rows = new ArrayList<>();

        @Override
        public Account getAccountById(int accountId) {
            for (Account a : rows) {
                if (a.getAccount_id() == accountId) return a;
            }
            return null;
        }
    }

    private static class FakeMessageDAO extends MessageDAO {
        final List<Message> rows = new ArrayList<>();

        @Override
        public List<Message> getAllMessages() {
            return new ArrayList<>(rows);
        }
    }

    private FakeAccountDAO accounts;
    private FakeMessageDAO messages;
    private MentionService service;

    @Before
    public void setUp() {
        accounts = new FakeAccountDAO();
        messages = new FakeMessageDAO();
        service = new MentionService(accounts, messages);
        accounts.rows.add(new Account(1, "TestUser1", "x"));
        accounts.rows.add(new Account(2, "TestUser2", "x"));
    }

    // ---- the matching rule ----

    @Test
    public void nameAsAWholeWordIsAMention() {
        assertTrue(MentionService.mentions("Hey TestUser1, are you there?", "TestUser1"));
        assertTrue(MentionService.mentions("TestUser1", "TestUser1"));
        assertTrue(MentionService.mentions("thanks TestUser1", "TestUser1"));
        assertTrue(MentionService.mentions("(TestUser1) see above", "TestUser1"));
    }

    @Test
    public void letterCaseDoesNotMatter() {
        assertTrue(MentionService.mentions("hey testuser1!", "TestUser1"));
        assertTrue(MentionService.mentions("HEY TESTUSER1", "TestUser1"));
    }

    @Test
    public void aLeadingAtSignIsAllowed() {
        assertTrue(MentionService.mentions("ping @TestUser1 please", "TestUser1"));
    }

    @Test
    public void aLongerNameIsNotAMention() {
        assertFalse(MentionService.mentions("TestUser10 said hi", "TestUser1"));
        assertFalse(MentionService.mentions("myTestUser1 said hi", "TestUser1"));
        assertFalse(MentionService.mentions("TestUser1_backup", "TestUser1"));
    }

    @Test
    public void aLaterWholeWordStillCountsAfterAPartialOne() {
        assertTrue(MentionService.mentions("TestUser10 and TestUser1 agreed", "TestUser1"));
    }

    @Test
    public void emptyOrMissingInputIsNeverAMention() {
        assertFalse(MentionService.mentions(null, "TestUser1"));
        assertFalse(MentionService.mentions("hello", null));
        assertFalse(MentionService.mentions("hello", ""));
        assertFalse(MentionService.mentions("hello", "   "));
        assertFalse(MentionService.mentions("", "TestUser1"));
    }

    // ---- which messages come back ----

    @Test
    public void returnsMessagesByOthersThatMentionTheAccount() {
        messages.rows.add(new Message(1, 2, "Hey TestUser1, game tonight?", 100L));
        messages.rows.add(new Message(2, 2, "Nothing to do with anyone", 101L));
        messages.rows.add(new Message(3, 2, "one more for testuser1", 102L));

        List<Message> mentions = service.getMentionsOf(1);
        assertEquals(2, mentions.size());
        assertEquals(1, mentions.get(0).getMessage_id());
        assertEquals(3, mentions.get(1).getMessage_id());
    }

    @Test
    public void writingYourOwnNameIsNotAMention() {
        messages.rows.add(new Message(1, 1, "TestUser1 here, signing off", 100L));
        assertTrue(service.getMentionsOf(1).isEmpty());
    }

    @Test
    public void aMessageCanMentionSeveralPeople() {
        messages.rows.add(new Message(1, 3, "TestUser1 and TestUser2: standup in 5", 100L));
        assertEquals(1, service.getMentionsOf(1).size());
        assertEquals(1, service.getMentionsOf(2).size());
    }

    @Test
    public void noMentionsIsAnEmptyListAndUnknownAccountIsNull() {
        assertTrue(service.getMentionsOf(2).isEmpty());
        assertNull(service.getMentionsOf(99));
    }
}
