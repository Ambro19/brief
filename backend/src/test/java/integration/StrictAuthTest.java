package integration;

import Controller.SocialMediaController;
import Model.Message;
import org.junit.Test;

import java.net.http.HttpResponse;

import static org.junit.Assert.*;

/**
 * Strict mode: writing needs a login token, and users can only act as themselves.
 * Reading stays public.
 */
public class StrictAuthTest extends ApiTestBase {

    @Override
    protected boolean strictAuth() {
        return true;
    }

    private String loginToken(String username, String password) throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson(username, password), null);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue(SocialMediaController.TOKEN_HEADER).orElseThrow();
    }

    /** Registers a second user (account id 2) and returns their token. */
    private String registerSecondUser() throws Exception {
        assertEquals(200, send("POST", "/register", accountJson("second", "password"), null).statusCode());
        return loginToken("second", "password");
    }

    @Test
    public void readingIsPublic() throws Exception {
        assertEquals(200, get("/messages").statusCode());
        assertEquals(200, get("/messages/1").statusCode());
        assertEquals(200, get("/accounts/1/messages").statusCode());
        assertEquals(200, get("/accounts").statusCode());
    }

    @Test
    public void writingWithoutATokenIs401AndChangesNothing() throws Exception {
        assertEquals(401, send("POST", "/messages", messageJson(1, "hello", 1L), null).statusCode());
        assertEquals(401, send("PATCH", "/messages/1", textJson("hacked"), null).statusCode());
        assertEquals(401, send("DELETE", "/messages/1", null, null).statusCode());
        assertEquals(401, send("DELETE", "/messages/1", null, "made-up-token").statusCode());

        assertEquals(new Message(1, 1, "test message 1", 1669947792L),
                mapper.readValue(get("/messages/1").body(), Message.class));
    }

    @Test
    public void ownerCanPostEditAndDelete() throws Exception {
        String token = loginToken("testuser1", "password");

        HttpResponse<String> created = send("POST", "/messages", messageJson(1, "mine", 1700000000L), token);
        assertEquals(200, created.statusCode());
        assertEquals(new Message(2, 1, "mine", 1700000000L), mapper.readValue(created.body(), Message.class));

        HttpResponse<String> updated = send("PATCH", "/messages/2", textJson("mine, edited"), token);
        assertEquals(200, updated.statusCode());
        assertEquals("mine, edited", mapper.readValue(updated.body(), Message.class).getMessage_text());

        HttpResponse<String> deleted = send("DELETE", "/messages/2", null, token);
        assertEquals(200, deleted.statusCode());
        assertEquals("", get("/messages/2").body());
    }

    @Test
    public void cannotPostAsSomeoneElse() throws Exception {
        String second = registerSecondUser();
        assertEquals(403, send("POST", "/messages", messageJson(1, "pretending to be user 1", 1L), second).statusCode());
        assertEquals(200, send("POST", "/messages", messageJson(2, "as myself", 1L), second).statusCode());
    }

    @Test
    public void cannotEditOrDeleteSomeoneElsesMessage() throws Exception {
        String second = registerSecondUser();
        assertEquals(403, send("PATCH", "/messages/1", textJson("not mine"), second).statusCode());
        assertEquals(403, send("DELETE", "/messages/1", null, second).statusCode());
        assertEquals("test message 1", mapper.readValue(get("/messages/1").body(), Message.class).getMessage_text());
    }

    @Test
    public void deleteOfMissingMessageIsStill200ForASignedInUser() throws Exception {
        String token = loginToken("testuser1", "password");
        HttpResponse<String> response = send("DELETE", "/messages/999", null, token);
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    public void serverFillsInThePostTimeWhenTheClientSendsNone() throws Exception {
        String token = loginToken("testuser1", "password");
        long before = System.currentTimeMillis() / 1000;
        HttpResponse<String> created = send("POST", "/messages", messageJson(1, "no time sent", 0L), token);
        assertEquals(200, created.statusCode());
        assertTrue(mapper.readValue(created.body(), Message.class).getTime_posted_epoch() >= before);
    }

    @Test
    public void logoutEndsTheSession() throws Exception {
        String token = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/logout", null, token).statusCode());
        assertEquals(401, send("POST", "/messages", messageJson(1, "after logout", 1L), token).statusCode());
        assertEquals(200, send("POST", "/logout", null, token).statusCode()); // repeat is fine
    }
}
