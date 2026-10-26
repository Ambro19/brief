package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import net.onetechly.brief.mail.ResendWebhookVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mail that bounces (2.7), from Resend's report to what the member and the board owner see.
 *
 * The application runs with a webhook secret, as briefmessage.net will; the tests play Resend by
 * signing their calls with the same secret (ResendWebhookVerifier.signatureFor). Mail itself is
 * not sent: it goes to the LogMailer, where the tests read the links, as everywhere else.
 *
 * The case that started it, on October 21: a member typed lovepetschoice@ for lovepetschoices@,
 * Gmail refused the mail, and Brief! kept saying "Check your email".
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=strict",
                "brief.board=open",
                "brief.owner-username=TestUser1",
                "brief.invite-code=",
                "brief.mail.resend-api-key=",
                "brief.mail.from=Brief! <hello@briefmessage.net>",
                "brief.mail.resend-webhook-secret=" + EmailBounceTest.SECRET,
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-bounce;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class EmailBounceTest extends ApiTestBase {

    /** "whsec_" and 32 bytes in base64, the form Resend shows. Test value only. */
    static final String SECRET = "whsec_YnJpZWYtMi43LXRlc3Qtc2lnbmluZy1rZXktMzJiISE=";

    private static final String TYPO = "lovepetschoice@gmail.example";
    private static final String RIGHT = "lovepetschoices@gmail.example";

    @Autowired
    private ResendWebhookVerifier verifier;

    private int messageNumber;

    /** A webhook call as Resend would make it: the event, signed, sent now. */
    private HttpResponse<String> report(String json) throws Exception {
        return report("msg_test_" + (++messageNumber) + "_" + System.nanoTime(), json);
    }

    /** As above, with a chosen svix-id (to send the same message twice). */
    private HttpResponse<String> report(String id, String json) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        return send("POST", "/webhooks/resend", json, null, Map.of(
                "svix-id", id, "svix-timestamp", timestamp, "svix-signature", verifier.signatureFor(id, timestamp, json)));
    }

    private static String bounced(String to, String type, String subType) {
        return "{\"type\":\"email.bounced\",\"created_at\":\"2026-10-21T23:41:12.126Z\",\"data\":{"
                + "\"email_id\":\"56761188-7520-42d8-8898-ff6fc54ce618\",\"from\":\"Brief! <hello@briefmessage.net>\","
                + "\"to\":[\"" + to + "\"],\"subject\":\"Confirm your email address for Brief!\","
                + "\"bounce\":{\"message\":\"The email account that you tried to reach does not exist.\","
                + "\"subType\":\"" + subType + "\",\"type\":\"" + type + "\"}}}";
    }

    private static String event(String type, String to, String from) {
        return "{\"type\":\"" + type + "\",\"created_at\":\"2026-10-21T23:41:12.126Z\",\"data\":{"
                + "\"email_id\":\"e1\",\"from\":\"" + from + "\",\"to\":[\"" + to + "\"],\"subject\":\"s\"}}";
    }

    private JsonNode me(String token) throws Exception {
        HttpResponse<String> response = send("GET", "/me", null, token);
        assertEquals(200, response.statusCode());
        return mapper.readTree(response.body());
    }

    private int rows() {
        return jdbc.queryForObject("select count(*) from email_bounce", Integer.class);
    }

    @Test
    void onlyResendCanReport() throws Exception {
        String json = bounced(TYPO, "Permanent", "General");
        assertEquals(401, send("POST", "/webhooks/resend", json, null).statusCode(), "no signature");
        String now = String.valueOf(System.currentTimeMillis() / 1000);
        assertEquals(401, send("POST", "/webhooks/resend", json, null, Map.of(
                "svix-id", "msg_1", "svix-timestamp", now, "svix-signature", "v1,AAAA")).statusCode(), "wrong signature");
        String old = String.valueOf(System.currentTimeMillis() / 1000 - 600);
        assertEquals(401, send("POST", "/webhooks/resend", json, null, Map.of(
                "svix-id", "msg_1", "svix-timestamp", old, "svix-signature", verifier.signatureFor("msg_1", old, json))).statusCode(),
                "a signed call recorded ten minutes ago cannot be replayed");
        String other = json.replace(TYPO, "someone.else@gmail.example");
        assertEquals(401, send("POST", "/webhooks/resend", other, null, Map.of(
                "svix-id", "msg_1", "svix-timestamp", now, "svix-signature", verifier.signatureFor("msg_1", now, json))).statusCode(),
                "the signature of one body does not cover another");
        assertEquals(0, rows());
        assertEquals(200, report(json).statusCode());
        assertEquals(1, rows());
    }

    @Test
    void aMistypedAddressIsToldToTheMemberWhoCanThenFixIt() throws Exception {
        // October 21, replayed: a member joins with a typo in the address.
        assertEquals(200, send("POST", "/register", registrationJson("Mayayo", "secret12", TYPO), null).statusCode());
        String token = loginToken("mayayo", "secret12");
        assertFalse(me(token).get("email_bounced").asBoolean(), "nothing known yet: Check your email");

        assertEquals(200, report(bounced(TYPO, "Permanent", "General")).statusCode());
        JsonNode bounced = me(token);
        assertTrue(bounced.get("email_bounced").asBoolean(), "the web app now says the mail could not be delivered");
        assertFalse(bounced.get("can_post").asBoolean());

        mailbox().clear();
        assertEquals(422, send("POST", "/email/verification", null, token).statusCode(), "sending again would only bounce again");
        assertNull(mailbox().lastTo(TYPO), "and nothing was sent");
        assertEquals(422, send("POST", "/accounts/2/email", emailJson(TYPO.toUpperCase()), token).statusCode(), "the same typo, typed again");

        assertEquals(200, send("POST", "/accounts/2/email", emailJson(RIGHT), token).statusCode());
        assertFalse(me(token).get("email_bounced").asBoolean());
        confirmEmail(RIGHT);
        assertTrue(me(token).get("can_post").asBoolean());
    }

    @Test
    void aNewMemberCannotRegisterWithAnAddressThatJustBounced() throws Exception {
        report(bounced(TYPO, "Permanent", "General"));
        assertEquals(422, send("POST", "/register", registrationJson("Mayayo", "secret12", TYPO), null).statusCode());
        assertEquals(200, send("POST", "/register", registrationJson("Mayayo", "secret12", RIGHT), null).statusCode());
    }

    @Test
    void temporaryBouncesAndOtherEventsChangeNothing() throws Exception {
        assertEquals(200, report(bounced(TYPO, "Temporary", "MailboxFull")).statusCode(), "Resend keeps trying; it may still arrive");
        assertEquals(200, report(event("email.delivered", TYPO, "Brief! <hello@briefmessage.net>")).statusCode());
        assertEquals(200, report(event("email.opened", TYPO, "Brief! <hello@briefmessage.net>")).statusCode());
        assertEquals(200, report(event("email.suppressed", TYPO, "PixelPerfect <noreply@pixelperfectapi.net>")).statusCode(),
                "a report about another product's mail on the same Resend account");
        assertEquals(200, report(event("email.suppressed", TYPO, "hello@briefmessage.net.example.com")).statusCode(),
                "a domain that only starts like ours");
        assertEquals(200, report(event("email.suppressed", TYPO, "@briefmessage.net <noreply@pixelperfectapi.net>")).statusCode(),
                "our domain in the display name only");
        assertEquals(200, report("{\"type\":\"email.suppressed\",\"data\":{\"to\":[\"" + TYPO + "\"]}}").statusCode(),
                "no sender at all");
        assertEquals(200, report(event("email.complained", TYPO, "Brief! <hello@briefmessage.net>")).statusCode(),
                "marked as spam: Resend suppresses the address itself; Brief! does not block its own links over it");
        assertEquals(0, rows());
        assertEquals(400, report("not json").statusCode(), "signed, but not an event");
        assertEquals(0, rows());
    }

    @Test
    void suppressedIsRecordedToo() throws Exception {
        report(event("email.suppressed", "a@gmail.example", "hello@briefmessage.net"));
        report(bounced("c@gmail.example", "Permanent", "Suppressed"));
        assertEquals("suppressed", jdbc.queryForObject("select kind from email_bounce where address = 'a@gmail.example'", String.class));
        assertEquals("suppressed", jdbc.queryForObject("select kind from email_bounce where address = 'c@gmail.example'", String.class));
        report(bounced("c@gmail.example", "Permanent", "General"));
        assertEquals(2, rows(), "a second report updates the first");
        assertEquals("bounced", jdbc.queryForObject("select kind from email_bounce where address = 'c@gmail.example'", String.class));
    }

    @Test
    void theSameMessageTwiceIsHandledOnce() throws Exception {
        assertEquals(200, report("msg_once", bounced(TYPO, "Permanent", "General")).statusCode());
        jdbc.execute("delete from email_bounce"); // say the member fixed things in between
        assertEquals(200, report("msg_once", bounced(TYPO, "Permanent", "General")).statusCode(), "a replay inside the five minutes");
        assertEquals(0, rows(), "changes nothing");
    }

    @Test
    void anUnsignedCallIsRefusedBeforeItsBodyIsReadAndAHugeBodyIsRefused() throws Exception {
        String huge = "{\"type\":\"email.bounced\",\"pad\":\"" + "x".repeat(300 * 1024) + "\"}";
        assertEquals(401, send("POST", "/webhooks/resend", huge, null).statusCode(), "no headers: refused unread");
        assertEquals(413, report(huge).statusCode(), "signed or not, 300 KB is no Resend event");
    }

    @Test
    void afterADayAMemberCanTryTheSameAddressOnceMore() throws Exception {
        // A report can be wrong (the address was suppressed because of another sender, say). After a
        // day the member may try again; if it bounces again, Resend says so again within seconds.
        assertEquals(200, send("POST", "/register", registrationJson("Mayayo", "secret12", RIGHT), null).statusCode());
        String token = loginToken("mayayo", "secret12");
        long yesterday = System.currentTimeMillis() / 1000 - 25L * 60 * 60;
        jdbc.update("insert into email_bounce (address, kind, bounced_at) values (?, ?, ?)", RIGHT, "suppressed", yesterday);
        assertTrue(me(token).get("email_bounced").asBoolean(), "still shown");
        mailbox().clear();
        jdbc.update("update account_token set created_at = created_at - 120"); // past the one-mail-a-minute limit
        assertEquals(200, send("POST", "/email/verification", null, token).statusCode(), "but no longer refused");
        assertNotNull(mailbox().lastTo(RIGHT), "the link goes out");
        assertFalse(me(token).get("email_bounced").asBoolean(), "and the page says Check your email again");
        assertEquals(0, rows());
    }

    @Test
    void theOwnerSeesBouncedAndTakenInvitations() throws Exception {
        String owner = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/invitations", emailJson(TYPO), owner).statusCode());
        assertEquals(200, send("POST", "/invitations", emailJson("gil@gmail.example"), owner).statusCode());
        report(bounced(TYPO, "Permanent", "General"));
        // On an open board anyone may join; Gilberto does so with the address he was invited at.
        assertEquals(200, send("POST", "/register", registrationJson("Gilberto", "secret12", "gil@gmail.example"), null).statusCode());
        assertEquals("pending", mapper.readTree(send("GET", "/invitations", null, owner).body()).get(0).get("status").asText(),
                "an address claimed but not confirmed is not yet a member's (review of 2.7.0)");
        confirmEmail("gil@gmail.example");

        JsonNode sent = mapper.readTree(send("GET", "/invitations", null, owner).body());
        assertEquals("taken", sent.get(0).get("status").asText(), "the address now belongs to a member");
        assertEquals("bounced", sent.get(1).get("status").asText(), "the mail could not be delivered");
        assertEquals(422, send("POST", "/invitations", emailJson(TYPO), owner).statusCode(), "inviting the same typo again");
        assertEquals(200, send("POST", "/invitations", emailJson(RIGHT), owner).statusCode());
    }

    @Test
    void aBouncedAddressGetsNoPasswordLinkAndAConfirmationForgetsTheReport() throws Exception {
        String token = registerConfirmed("Ada", "secret12");
        report(bounced("ada@example.test", "Permanent", "General"));
        assertTrue(me(token).get("email_bounced").asBoolean(), "a confirmed address can stop working later");
        mailbox().clear();
        assertEquals(200, send("POST", "/password/forgot", emailJson("ada@example.test"), null).statusCode(), "same answer as always");
        assertNull(mailbox().lastTo("ada@example.test"), "but no mail to an address known to bounce");

        // A late or mistaken report about an address whose link is then opened after all:
        assertEquals(200, send("POST", "/register", registrationJson("Mayayo", "secret12", RIGHT), null).statusCode());
        String secret = secretMailedTo(RIGHT, "verify");
        report(bounced(RIGHT, "Permanent", "General"));
        jdbc.update("delete from email_bounce where address = ?", "ada@example.test");
        assertEquals(1, rows());
        assertEquals(200, send("POST", "/email/verify", tokenJson(secret), null).statusCode(), "the mail did arrive after all");
        assertEquals(0, rows(), "so the report is forgotten");
    }

    @Test
    void deletingTheAccountForgetsTheReportAboutItsAddress() throws Exception {
        assertEquals(200, send("POST", "/register", registrationJson("Mayayo", "secret12", TYPO), null).statusCode());
        String token = loginToken("mayayo", "secret12");
        report(bounced(TYPO, "Permanent", "General"));
        assertEquals(1, rows());
        assertEquals(200, send("POST", "/accounts/2/deletion", "{\"password\":\"secret12\"}", token).statusCode());
        assertEquals(0, rows());
    }

    @Test
    void reportsOlderThanThirtyDaysAreForgotten() throws Exception {
        long old = System.currentTimeMillis() / 1000 - 31L * 24 * 60 * 60;
        jdbc.update("insert into email_bounce (address, kind, bounced_at) values (?, ?, ?)", TYPO, "bounced", old);
        assertEquals(200, send("POST", "/register", registrationJson("Mayayo", "secret12", TYPO), null).statusCode(),
                "a report a month old no longer blocks the address");
        assertFalse(me(loginToken("mayayo", "secret12")).get("email_bounced").asBoolean(), "nor shows on the page");
    }
}
