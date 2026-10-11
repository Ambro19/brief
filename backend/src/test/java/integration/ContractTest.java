package integration;

import Model.Account;
import Model.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The eight requirements from the project README, checked end to end over HTTP
 * in compat mode (the original contract). Seed data: account 1 = testuser1 / password,
 * message 1 = "test message 1" posted by account 1.
 */
public class ContractTest extends ApiTestBase {

    private static final Message SEED_MESSAGE = new Message(1, 1, "test message 1", 1669947792L);

    @Override
    protected boolean strictAuth() {
        return false;
    }

    // ---- 1: registration ----

    @Test
    public void registerSucceeds() throws Exception {
        HttpResponse<String> response = send("POST", "/register", accountJson("user", "password"), null);
        assertEquals(200, response.statusCode());
        assertEquals(new Account(2, "user", "password"), mapper.readValue(response.body(), Account.class));
    }

    @Test
    public void registerDuplicateUsernameFails() throws Exception {
        assertEquals(200, send("POST", "/register", accountJson("user", "password"), null).statusCode());
        HttpResponse<String> second = send("POST", "/register", accountJson("user", "password"), null);
        assertEquals(400, second.statusCode());
        assertEquals("", second.body());
    }

    @Test
    public void registerBlankUsernameFails() throws Exception {
        HttpResponse<String> response = send("POST", "/register", accountJson("", "password"), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void registerShortPasswordFails() throws Exception {
        HttpResponse<String> response = send("POST", "/register", accountJson("user", "pas"), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void registerMalformedJsonFails() throws Exception {
        assertEquals(400, send("POST", "/register", "{not json", null).statusCode());
    }

    // ---- 2: login ----

    @Test
    public void loginSucceedsForSeedUser() throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson("testuser1", "password"), null);
        assertEquals(200, response.statusCode());
        assertEquals(new Account(1, "testuser1", "password"), mapper.readValue(response.body(), Account.class));
    }

    @Test
    public void loginStillWorksAfterThePasswordWasUpgradedToAHash() throws Exception {
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
    }

    @Test
    public void loginSucceedsForNewlyRegisteredUser() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);
        HttpResponse<String> response = send("POST", "/login", accountJson("user", "password"), null);
        assertEquals(200, response.statusCode());
        assertEquals(new Account(2, "user", "password"), mapper.readValue(response.body(), Account.class));
    }

    @Test
    public void loginWrongPasswordFails() throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson("testuser1", "pass123"), null);
        assertEquals(401, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void loginUnknownUserFails() throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson("testuser404", "password"), null);
        assertEquals(401, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 3: create message ----

    @Test
    public void createMessageSucceeds() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(1, "hello message", 1669947792L), null);
        assertEquals(200, response.statusCode());
        assertEquals(new Message(2, 1, "hello message", 1669947792L), mapper.readValue(response.body(), Message.class));
    }

    @Test
    public void createMessageBlankTextFails() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(1, "", 1669947792L), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void createMessageOver255CharactersFails() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(1, repeat('a', 256), 1669947792L), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void createMessageOfExactly255CharactersSucceeds() throws Exception {
        assertEquals(200, send("POST", "/messages", messageJson(1, repeat('a', 255), 1669947792L), null).statusCode());
    }

    @Test
    public void createMessageUnknownUserFails() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(3, "hello message", 1669947792L), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 4: all messages ----

    @Test
    public void getAllMessagesReturnsSeedMessage() throws Exception {
        HttpResponse<String> response = get("/messages");
        assertEquals(200, response.statusCode());
        List<Message> messages = mapper.readValue(response.body(), new TypeReference<List<Message>>() {});
        assertEquals(List.of(SEED_MESSAGE), messages);
    }

    @Test
    public void getAllMessagesReturnsEmptyListWhenThereAreNone() throws Exception {
        send("DELETE", "/messages/1", null, null);
        HttpResponse<String> response = get("/messages");
        assertEquals(200, response.statusCode());
        assertTrue(mapper.readValue(response.body(), new TypeReference<List<Message>>() {}).isEmpty());
    }

    // ---- 5: message by id ----

    @Test
    public void getMessageByIdFound() throws Exception {
        HttpResponse<String> response = get("/messages/1");
        assertEquals(200, response.statusCode());
        assertEquals(SEED_MESSAGE, mapper.readValue(response.body(), Message.class));
    }

    @Test
    public void getMessageByIdNotFoundIsEmpty200() throws Exception {
        HttpResponse<String> response = get("/messages/100");
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 6: delete ----

    @Test
    public void deleteReturnsTheDeletedMessage() throws Exception {
        HttpResponse<String> response = send("DELETE", "/messages/1", null, null);
        assertEquals(200, response.statusCode());
        assertEquals(SEED_MESSAGE, mapper.readValue(response.body(), Message.class));
        assertEquals("", get("/messages/1").body());
    }

    @Test
    public void deleteIsIdempotent() throws Exception {
        send("DELETE", "/messages/1", null, null);
        HttpResponse<String> again = send("DELETE", "/messages/1", null, null);
        assertEquals(200, again.statusCode());
        assertEquals("", again.body());
    }

    // ---- 7: update ----

    @Test
    public void updateReturnsTheFullUpdatedMessage() throws Exception {
        HttpResponse<String> response = send("PATCH", "/messages/1", textJson("updated message"), null);
        assertEquals(200, response.statusCode());
        assertEquals(new Message(1, 1, "updated message", 1669947792L), mapper.readValue(response.body(), Message.class));
        assertEquals(new Message(1, 1, "updated message", 1669947792L), mapper.readValue(get("/messages/1").body(), Message.class));
    }

    @Test
    public void updateUnknownMessageFails() throws Exception {
        HttpResponse<String> response = send("PATCH", "/messages/2", textJson("updated message"), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void updateBlankTextFails() throws Exception {
        HttpResponse<String> response = send("PATCH", "/messages/1", textJson(""), null);
        assertEquals(400, response.statusCode());
        assertEquals(SEED_MESSAGE, mapper.readValue(get("/messages/1").body(), Message.class));
    }

    @Test
    public void updateOver255CharactersFails() throws Exception {
        assertEquals(400, send("PATCH", "/messages/1", textJson(repeat('a', 256)), null).statusCode());
    }

    // ---- 8: messages by user ----

    @Test
    public void getMessagesByUser() throws Exception {
        HttpResponse<String> response = get("/accounts/1/messages");
        assertEquals(200, response.statusCode());
        assertEquals(List.of(SEED_MESSAGE), mapper.readValue(response.body(), new TypeReference<List<Message>>() {}));
    }

    @Test
    public void getMessagesByUserWithNoMessagesIsEmptyList() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);
        HttpResponse<String> response = get("/accounts/2/messages");
        assertEquals(200, response.statusCode());
        assertTrue(mapper.readValue(response.body(), new TypeReference<List<Message>>() {}).isEmpty());
    }

    // ---- additions in v1.0 ----

    @Test
    public void accountDirectoryNeverExposesPasswords() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);
        HttpResponse<String> list = get("/accounts");
        assertEquals(200, list.statusCode());
        assertFalse(list.body().contains("password"));
        assertTrue(list.body().contains("testuser1"));

        HttpResponse<String> one = get("/accounts/2");
        assertEquals(200, one.statusCode());
        assertTrue(one.body().contains("\"username\":\"user\""));
        assertFalse(one.body().contains("password"));

        assertEquals(404, get("/accounts/99").statusCode());
    }

    @Test
    public void mentionsListsMessagesByOthersThatNameTheAccount() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                         // account 2
        send("POST", "/messages", messageJson(2, "hey TestUser1 are you around?", 1700000000L), null);  // message 2
        send("POST", "/messages", messageJson(2, "nothing to see here", 1700000001L), null);            // message 3
        send("POST", "/messages", messageJson(1, "testuser1 talking about myself", 1700000002L), null); // own message

        HttpResponse<String> response = get("/accounts/1/mentions");
        assertEquals(200, response.statusCode());
        List<Message> mentions = mapper.readValue(response.body(), new TypeReference<List<Message>>() {});
        assertEquals(List.of(new Message(2, 2, "hey TestUser1 are you around?", 1700000000L)), mentions);
    }

    @Test
    public void mentionsIsEmptyWhenNobodyNamedTheAccountAnd404ForUnknownAccount() throws Exception {
        HttpResponse<String> none = get("/accounts/1/mentions");
        assertEquals(200, none.statusCode());
        assertTrue(mapper.readValue(none.body(), new TypeReference<List<Message>>() {}).isEmpty());

        assertEquals(404, get("/accounts/99/mentions").statusCode());
        assertEquals(400, get("/accounts/abc/mentions").statusCode());
    }

    @Test
    public void nonNumericIdIsABadRequest() throws Exception {
        assertEquals(400, get("/messages/abc").statusCode());
        assertEquals(400, get("/accounts/abc/messages").statusCode());
    }

    @Test
    public void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("UP"));
    }
}
