package unit;

import DAO.MessageDAO;
import Model.Message;
import Service.MessageService;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Tests the message rules with an in-memory stand-in for the database. */
public class MessageServiceTest {

    private static class FakeMessageDAO extends MessageDAO {
        final Map<Integer, Message> rows = new LinkedHashMap<>();
        int nextId = 1;

        @Override
        public boolean userExists(int accountId) {
            return accountId == 1 || accountId == 2;
        }

        @Override
        public Message saveMessage(Message m) {
            Message saved = new Message(nextId++, m.getPosted_by(), m.getMessage_text(), m.getTime_posted_epoch());
            rows.put(saved.getMessage_id(), saved);
            return saved;
        }

        @Override
        public List<Message> getAllMessages() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public Message getMessageById(int messageId) {
            return rows.get(messageId);
        }

        @Override
        public Message deleteMessage(int messageId) {
            return rows.remove(messageId);
        }

        @Override
        public Message updateMessage(int messageId, Message updated) {
            Message existing = rows.get(messageId);
            if (existing == null) return null;
            existing.setMessage_text(updated.getMessage_text());
            return existing;
        }

        @Override
        public List<Message> getMessagesByUser(int accountId) {
            List<Message> result = new ArrayList<>();
            for (Message m : rows.values()) {
                if (m.getPosted_by() == accountId) result.add(m);
            }
            return result;
        }
    }

    private static String textOfLength(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
    }

    private MessageService service;

    @Before
    public void setUp() {
        service = new MessageService(new FakeMessageDAO());
    }

    @Test
    public void createReturnsMessageWithId() {
        Message created = service.createMessage(new Message(1, "hello", 1669947792L));
        assertEquals(new Message(1, 1, "hello", 1669947792L), created);
    }

    @Test
    public void createRejectsBlankText() {
        assertNull(service.createMessage(new Message(1, "", 1L)));
        assertNull(service.createMessage(new Message(1, "   ", 1L)));
        assertNull(service.createMessage(new Message(1, null, 1L)));
    }

    @Test
    public void createAcceptsExactly255CharactersAndRejects256() {
        assertNotNull(service.createMessage(new Message(1, textOfLength(255), 1L)));
        assertNull(service.createMessage(new Message(1, textOfLength(256), 1L)));
    }

    @Test
    public void createRejectsUnknownAuthor() {
        assertNull(service.createMessage(new Message(99, "hello", 1L)));
    }

    @Test
    public void updateChangesOnlyTheText() {
        service.createMessage(new Message(1, "first", 1669947792L));
        Message patch = new Message();
        patch.setMessage_text("edited");
        assertEquals(new Message(1, 1, "edited", 1669947792L), service.updateMessage(1, patch));
    }

    @Test
    public void updateRejectsBadTextAndUnknownId() {
        service.createMessage(new Message(1, "first", 1L));
        Message blank = new Message();
        blank.setMessage_text("");
        Message tooLong = new Message();
        tooLong.setMessage_text(textOfLength(256));
        Message fine = new Message();
        fine.setMessage_text("ok");

        assertNull(service.updateMessage(1, blank));
        assertNull(service.updateMessage(1, tooLong));
        assertNull(service.updateMessage(42, fine));
        assertEquals("first", service.getMessageById(1).getMessage_text());
    }

    @Test
    public void deleteReturnsTheMessageOnceThenNull() {
        service.createMessage(new Message(1, "first", 1L));
        assertNotNull(service.deleteMessage(1));
        assertNull(service.deleteMessage(1)); // idempotent: second call finds nothing, no error
        assertTrue(service.getAllMessages().isEmpty());
    }

    @Test
    public void messagesByUserReturnsOnlyThatUsersMessages() {
        service.createMessage(new Message(1, "mine", 1L));
        service.createMessage(new Message(2, "theirs", 2L));
        service.createMessage(new Message(1, "mine again", 3L));
        assertEquals(2, service.getMessagesByUser(1).size());
        assertEquals(1, service.getMessagesByUser(2).size());
        assertTrue(service.getMessagesByUser(3).isEmpty());
    }
}
