package net.onetechly.brief.integration;

import com.fasterxml.jackson.databind.JsonNode;
import net.onetechly.brief.service.MentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The eight requirements of the original project, plus the endpoints added since, checked end
 * to end over HTTP in compat mode. Sections 1 to 8 and the first cases under "added since the
 * original project" are the same as the Javalin edition's ContractTest: both editions must give
 * the same answers. The sections marked (2.1), (2.2) and (2.3) - paging, the mention table, and
 * the deployable build - exist only in this edition.
 *
 * The database is H2 in memory unless BRIEF_TEST_DB_URL names another one (see ApiTestBase).
 * Either way its tables come from the Flyway migrations, not from Hibernate.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "brief.auth.mode=compat",
                // Stated here so that a variable set on the machine that runs the tests can never
                // close the board under them, or make them send real mail.
                "brief.invite-code=",
                "brief.board=open",
                "brief.owner-username=",
                "brief.mail.resend-api-key=",
                "brief.push.vapid-public-key=",   // 2.8: notifications off, whatever the machine has set
                "brief.push.vapid-private-key=",
                "spring.datasource.url=${BRIEF_TEST_DB_URL:jdbc:h2:mem:brief-contract;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${BRIEF_TEST_DB_USER:sa}",
                "spring.datasource.password=${BRIEF_TEST_DB_PASSWORD:sa}"})
class ContractTest extends ApiTestBase {

    // ---- 1: registration ----

    @Test
    void registerSucceeds() throws Exception {
        HttpResponse<String> response = send("POST", "/register", accountJson("user", "password"), null);
        assertEquals(200, response.statusCode());
        assertAccount(response.body(), 2, "user", "password");
    }

    @Test
    void registerDuplicateUsernameFails() throws Exception {
        assertEquals(200, send("POST", "/register", accountJson("user", "password"), null).statusCode());
        HttpResponse<String> second = send("POST", "/register", accountJson("user", "password"), null);
        assertEquals(400, second.statusCode());
        assertEquals("", second.body());
    }

    @Test
    void registerBlankUsernameFails() throws Exception {
        HttpResponse<String> response = send("POST", "/register", accountJson("", "password"), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void registerShortPasswordFails() throws Exception {
        HttpResponse<String> response = send("POST", "/register", accountJson("user", "pas"), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void registerMalformedJsonFails() throws Exception {
        HttpResponse<String> response = send("POST", "/register", "{not json", null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 2: login ----

    @Test
    void loginSucceedsForSeedUser() throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson("testuser1", "password"), null);
        assertEquals(200, response.statusCode());
        assertAccount(response.body(), 1, "testuser1", "password");
    }

    @Test
    void loginStillWorksAfterThePasswordWasUpgradedToAHash() throws Exception {
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());

        String stored = jdbc.queryForObject("select password from account where account_id = 1", String.class);
        assertNotEquals("password", stored, "the plain-text seed password should have been replaced by a hash");
    }

    @Test
    void loginSucceedsForNewlyRegisteredUser() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);
        HttpResponse<String> response = send("POST", "/login", accountJson("user", "password"), null);
        assertEquals(200, response.statusCode());
        assertAccount(response.body(), 2, "user", "password");
    }

    @Test
    void loginWrongPasswordFails() throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson("testuser1", "pass123"), null);
        assertEquals(401, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void loginUnknownUserFails() throws Exception {
        HttpResponse<String> response = send("POST", "/login", accountJson("testuser404", "password"), null);
        assertEquals(401, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 3: create message ----

    @Test
    void createMessageSucceeds() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(1, "hello message", 1669947792L), null);
        assertEquals(200, response.statusCode());
        assertMessage(response.body(), 2, 1, "hello message", 1669947792L);
    }

    @Test
    void createMessageBlankTextFails() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(1, "", 1669947792L), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void createMessageOver255CharactersFails() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(1, repeat('a', 256), 1669947792L), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void createMessageOfExactly255CharactersSucceeds() throws Exception {
        assertEquals(200, send("POST", "/messages", messageJson(1, repeat('a', 255), 1669947792L), null).statusCode());
    }

    @Test
    void createMessageUnknownUserFails() throws Exception {
        HttpResponse<String> response = send("POST", "/messages", messageJson(3, "hello message", 1669947792L), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 4: all messages ----

    @Test
    void getAllMessagesReturnsSeedMessage() throws Exception {
        HttpResponse<String> response = get("/messages");
        assertEquals(200, response.statusCode());
        JsonNode list = mapper.readTree(response.body());
        assertEquals(1, list.size());
        assertSeedMessage(list.get(0));
    }

    @Test
    void getAllMessagesReturnsEmptyListWhenThereAreNone() throws Exception {
        send("DELETE", "/messages/1", null, null);
        HttpResponse<String> response = get("/messages");
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }

    // ---- 5: message by id ----

    @Test
    void getMessageByIdFound() throws Exception {
        HttpResponse<String> response = get("/messages/1");
        assertEquals(200, response.statusCode());
        assertSeedMessage(mapper.readTree(response.body()));
    }

    @Test
    void getMessageByIdNotFoundIsEmpty200() throws Exception {
        HttpResponse<String> response = get("/messages/100");
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
    }

    // ---- 6: delete ----

    @Test
    void deleteReturnsTheDeletedMessage() throws Exception {
        HttpResponse<String> response = send("DELETE", "/messages/1", null, null);
        assertEquals(200, response.statusCode());
        assertSeedMessage(mapper.readTree(response.body()));
        assertEquals("", get("/messages/1").body());
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        send("DELETE", "/messages/1", null, null);
        HttpResponse<String> again = send("DELETE", "/messages/1", null, null);
        assertEquals(200, again.statusCode());
        assertEquals("", again.body());
    }

    // ---- 7: update ----

    @Test
    void updateReturnsTheFullUpdatedMessage() throws Exception {
        HttpResponse<String> response = send("PATCH", "/messages/1", textJson("updated message"), null);
        assertEquals(200, response.statusCode());
        assertMessage(response.body(), 1, 1, "updated message", 1669947792L);
        assertMessage(get("/messages/1").body(), 1, 1, "updated message", 1669947792L);
    }

    @Test
    void updateUnknownMessageFails() throws Exception {
        HttpResponse<String> response = send("PATCH", "/messages/2", textJson("updated message"), null);
        assertEquals(400, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void updateBlankTextFails() throws Exception {
        HttpResponse<String> response = send("PATCH", "/messages/1", textJson(""), null);
        assertEquals(400, response.statusCode());
        assertSeedMessage(mapper.readTree(get("/messages/1").body()));
    }

    @Test
    void updateOver255CharactersFails() throws Exception {
        assertEquals(400, send("PATCH", "/messages/1", textJson(repeat('a', 256)), null).statusCode());
    }

    // ---- 8: messages by user ----

    @Test
    void getMessagesByUser() throws Exception {
        HttpResponse<String> response = get("/accounts/1/messages");
        assertEquals(200, response.statusCode());
        JsonNode list = mapper.readTree(response.body());
        assertEquals(1, list.size());
        assertSeedMessage(list.get(0));
    }

    @Test
    void getMessagesByUserWithNoMessagesIsEmptyList() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);
        HttpResponse<String> response = get("/accounts/2/messages");
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }

    // ---- added since the original project ----

    @Test
    void accountDirectoryNeverExposesPasswords() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);
        HttpResponse<String> list = get("/accounts");
        assertEquals(200, list.statusCode());
        assertFalse(list.body().contains("password"));
        assertTrue(list.body().contains("testuser1"));

        HttpResponse<String> one = get("/accounts/2");
        assertEquals(200, one.statusCode());
        JsonNode account = mapper.readTree(one.body());
        assertEquals(2, account.size(), "a public account has exactly two fields");
        assertEquals(2, account.get("account_id").asInt());
        assertEquals("user", account.get("username").asText());

        HttpResponse<String> missing = get("/accounts/99");
        assertEquals(404, missing.statusCode());
        assertEquals("", missing.body());
    }

    @Test
    void mentionsListsMessagesByOthersThatNameTheAccount() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                              // account 2
        send("POST", "/messages", messageJson(2, "hey TestUser1 are you around?", 1700000000L), null);  // message 2
        send("POST", "/messages", messageJson(2, "nothing to see here", 1700000001L), null);            // message 3
        send("POST", "/messages", messageJson(1, "testuser1 talking about myself", 1700000002L), null); // own message

        HttpResponse<String> response = get("/accounts/1/mentions");
        assertEquals(200, response.statusCode());
        JsonNode mentions = mapper.readTree(response.body());
        assertEquals(1, mentions.size());
        assertMessage(mentions.get(0), 2, 2, "hey TestUser1 are you around?", 1700000000L);
    }

    @Test
    void mentionsIsEmptyWhenNobodyNamedTheAccountAnd404ForUnknownAccount() throws Exception {
        HttpResponse<String> none = get("/accounts/1/mentions");
        assertEquals(200, none.statusCode());
        assertEquals("[]", none.body());

        assertEquals(404, get("/accounts/99/mentions").statusCode());
        assertEquals(400, get("/accounts/abc/mentions").statusCode());
    }

    @Test
    void nonNumericIdIsABadRequest() throws Exception {
        assertEquals(400, get("/messages/abc").statusCode());
        assertEquals(400, get("/accounts/abc/messages").statusCode());
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertEquals("UP", mapper.readTree(response.body()).get("status").asText());
        assertEquals("basic", mapper.readTree(response.body()).get("password_policy").asText());
    }

    @Test
    void loginIsNotRateLimitedInCompatMode() throws Exception {
        for (int attempt = 1; attempt <= 8; attempt++) {
            assertEquals(401, send("POST", "/login", accountJson("testuser1", "wrong"), null).statusCode());
        }
        assertEquals(200, send("POST", "/login", accountJson("testuser1", "password"), null).statusCode());
    }

    // ---- paging (2.1) ----

    /** Adds messages 2, 3, 4 and 5 by the seed user, so that with the seed message there are five. */
    private void postFourMore() throws Exception {
        for (int n = 2; n <= 5; n++) {
            assertEquals(200, send("POST", "/messages", messageJson(1, "message " + n, 1700000000L + n), null).statusCode());
        }
    }

    private static String hasMore(HttpResponse<String> response) {
        return response.headers().firstValue("X-Has-More").orElse(null);
    }

    @Test
    void withoutPagingParametersTheListIsCompleteOldestFirstAndHasNoPagingHeader() throws Exception {
        postFourMore();
        HttpResponse<String> response = get("/messages");
        JsonNode list = mapper.readTree(response.body());
        assertEquals(5, list.size());
        assertEquals(1, list.get(0).get("message_id").asInt());
        assertEquals(5, list.get(4).get("message_id").asInt());
        assertNull(hasMore(response));
    }

    @Test
    void firstPageIsNewestFirstAndSaysThereIsMore() throws Exception {
        postFourMore();
        HttpResponse<String> response = get("/messages?limit=2");
        assertEquals(200, response.statusCode());
        JsonNode page = mapper.readTree(response.body());
        assertEquals(2, page.size());
        assertMessage(page.get(0), 5, 1, "message 5", 1700000005L);
        assertMessage(page.get(1), 4, 1, "message 4", 1700000004L);
        assertEquals("true", hasMore(response));
    }

    @Test
    void followingTheCursorReachesTheEndWithoutRepeatsOrGaps() throws Exception {
        postFourMore();

        HttpResponse<String> second = get("/messages?limit=2&before=4");
        JsonNode page = mapper.readTree(second.body());
        assertEquals(2, page.size());
        assertEquals(3, page.get(0).get("message_id").asInt());
        assertEquals(2, page.get(1).get("message_id").asInt());
        assertEquals("true", hasMore(second));

        HttpResponse<String> last = get("/messages?limit=2&before=2");
        page = mapper.readTree(last.body());
        assertEquals(1, page.size());
        assertSeedMessage(page.get(0));
        assertEquals("false", hasMore(last));

        HttpResponse<String> beyond = get("/messages?limit=2&before=1");
        assertEquals("[]", beyond.body());
        assertEquals("false", hasMore(beyond));
    }

    @Test
    void aPageThatHoldsExactlyTheRestSaysThereIsNoMore() throws Exception {
        postFourMore();
        HttpResponse<String> response = get("/messages?limit=5");
        assertEquals(5, mapper.readTree(response.body()).size());
        assertEquals("false", hasMore(response));
    }

    @Test
    void beforeWithoutLimitUsesTheDefaultPageSize() throws Exception {
        postFourMore();
        HttpResponse<String> response = get("/messages?before=4");
        assertEquals(200, response.statusCode());
        assertEquals(3, mapper.readTree(response.body()).size());
        assertEquals("false", hasMore(response));
    }

    @Test
    void pagingParametersOutOfRangeAreABadRequest() throws Exception {
        assertEquals(400, get("/messages?limit=0").statusCode());
        assertEquals(400, get("/messages?limit=101").statusCode());
        assertEquals(400, get("/messages?limit=abc").statusCode());
        assertEquals(400, get("/messages?limit=2&before=0").statusCode());
        assertEquals("", get("/messages?limit=0").body());
        assertEquals(200, get("/messages?limit=100").statusCode());
    }

    @Test
    void messagesByUserCanBePagedTheSameWay() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                       // account 2
        postFourMore();                                                                         // messages 2-5 by account 1
        send("POST", "/messages", messageJson(2, "from the other member", 1700000009L), null);  // message 6 by account 2

        HttpResponse<String> response = get("/accounts/1/messages?limit=3");
        JsonNode page = mapper.readTree(response.body());
        assertEquals(3, page.size());
        assertEquals(5, page.get(0).get("message_id").asInt(), "message 6 belongs to someone else");
        assertEquals("true", hasMore(response));

        HttpResponse<String> rest = get("/accounts/1/messages?limit=3&before=3");
        assertEquals(2, mapper.readTree(rest.body()).size());
        assertEquals("false", hasMore(rest));

        assertEquals(400, get("/accounts/1/messages?limit=0").statusCode());
    }

    @Test
    void theWebAppIsServedAtTheRoot() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<div id=\"root\">"), "index.html should be served from classpath:/public");
    }

    // ---- the mention table (2.2) ----

    /** Used only by the rebuild test below; every other test goes through HTTP. */
    @Autowired
    private MentionService mentionService;

    private int mentionRows() {
        return jdbc.queryForObject("select count(*) from mention", Integer.class);
    }

    @Test
    void aNameWrittenSeveralTimesInOnePostIsOneMention() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                           // account 2
        send("POST", "/messages", messageJson(2, "testuser1! TestUser1? @TESTUSER1.", 1700000000L), null);

        assertEquals(1, mentionRows());
        assertEquals(1, mapper.readTree(get("/accounts/1/mentions").body()).size());
    }

    @Test
    void editingAPostUpdatesWhoItMentions() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                           // account 2
        send("POST", "/messages", messageJson(2, "hello testuser1", 1700000000L), null);            // message 2
        assertEquals(1, mapper.readTree(get("/accounts/1/mentions").body()).size());

        assertEquals(200, send("PATCH", "/messages/2", textJson("hello nobody in particular"), null).statusCode());
        assertEquals("[]", get("/accounts/1/mentions").body());
        assertEquals(0, mentionRows());

        assertEquals(200, send("PATCH", "/messages/2", textJson("testuser1 again, yes testuser1"), null).statusCode());
        JsonNode mentions = mapper.readTree(get("/accounts/1/mentions").body());
        assertEquals(1, mentions.size());
        assertMessage(mentions.get(0), 2, 2, "testuser1 again, yes testuser1", 1700000000L);

        // An edit that still mentions the same member keeps working (the row is kept, not written twice).
        assertEquals(200, send("PATCH", "/messages/2", textJson("testuser1 once more"), null).statusCode());
        assertEquals(1, mentionRows());
    }

    @Test
    void deletingAPostRemovesItsMentions() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                           // account 2
        send("POST", "/messages", messageJson(2, "hello testuser1", 1700000000L), null);            // message 2
        assertEquals(1, mentionRows());

        assertEquals(200, send("DELETE", "/messages/2", null, null).statusCode());

        assertEquals("[]", get("/accounts/1/mentions").body());
        assertEquals(0, mentionRows());
    }

    @Test
    void onePostCanMentionSeveralMembersAndEachSeesIt() throws Exception {
        send("POST", "/register", accountJson("maya", "password"), null);                           // account 2
        send("POST", "/register", accountJson("devon", "password"), null);                          // account 3
        send("POST", "/messages", messageJson(1, "@maya and @devon: review at 3", 1700000000L), null); // message 2

        assertEquals(2, mentionRows());
        assertEquals(1, mapper.readTree(get("/accounts/2/mentions").body()).size());
        assertEquals(1, mapper.readTree(get("/accounts/3/mentions").body()).size());
        assertEquals("[]", get("/accounts/1/mentions").body(), "the author is not mentioned by their own post");
    }

    @Test
    void aNameWrittenBeforeThatMemberRegisteredIsNotAMention() throws Exception {
        send("POST", "/messages", messageJson(1, "saving a seat for latecomer", 1700000000L), null);
        send("POST", "/register", accountJson("latecomer", "password"), null);                      // account 2

        assertEquals("[]", get("/accounts/2/mentions").body());
    }

    @Test
    void rebuildingFindsTheMentionsOfPostsThatWereNeverIndexed() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                           // account 2
        // Written straight into the table, the way every post from before 2.2 is: no mention row exists for it.
        jdbc.update("insert into message (posted_by, message_text, time_posted_epoch) values (?, ?, ?)",
                2, "an old post for testuser1", 1700000000L);                                       // message 2
        assertEquals("[]", get("/accounts/1/mentions").body());

        assertEquals(2, mentionService.rebuildAll());

        JsonNode mentions = mapper.readTree(get("/accounts/1/mentions").body());
        assertEquals(1, mentions.size());
        assertMessage(mentions.get(0), 2, 2, "an old post for testuser1", 1700000000L);
    }

    // ---- usernames are one name whatever the capitals (2.3) ----

    @Test
    void aNameThatDiffersOnlyInCapitalsIsAlreadyTaken() throws Exception {
        assertEquals(200, send("POST", "/register", accountJson("Maya", "password"), null).statusCode());   // account 2

        assertEquals(400, send("POST", "/register", accountJson("maya", "password"), null).statusCode());
        assertEquals(400, send("POST", "/register", accountJson("MAYA", "password"), null).statusCode());
        assertEquals(400, send("POST", "/register", accountJson("TestUser1", "password"), null).statusCode(),
                "the seed account testuser1 counts too");
        assertEquals(2, mapper.readTree(get("/accounts").body()).size(), "no third account was created");
    }

    @Test
    void signingInWorksWhateverCapitalsAreTypedAndAnswersWithTheRegisteredSpelling() throws Exception {
        send("POST", "/register", accountJson("Maya", "password"), null);                                   // account 2

        HttpResponse<String> response = send("POST", "/login", accountJson("mAYA", "password"), null);
        assertEquals(200, response.statusCode());
        assertAccount(response.body(), 2, "Maya", "password");

        assertEquals(401, send("POST", "/login", accountJson("maya", "PASSWORD"), null).statusCode(), "the password stays exact");
    }

    // ---- mentions, a page at a time (2.3) ----

    /** Account 2 writes four posts that name testuser1 (messages 2 to 5) and one that does not (message 6). */
    private void fourMentionsOfTheSeedUser() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                                   // account 2
        for (int n = 2; n <= 5; n++) {
            assertEquals(200, send("POST", "/messages", messageJson(2, "note " + n + " for testuser1", 1700000000L + n), null).statusCode());
        }
        assertEquals(200, send("POST", "/messages", messageJson(2, "nothing to see here", 1700000006L), null).statusCode());
    }

    @Test
    void mentionsWithoutPagingParametersAreStillCompleteOldestFirstWithNoPagingHeader() throws Exception {
        fourMentionsOfTheSeedUser();

        HttpResponse<String> response = get("/accounts/1/mentions");
        JsonNode list = mapper.readTree(response.body());
        assertEquals(4, list.size());
        assertEquals(2, list.get(0).get("message_id").asInt());
        assertEquals(5, list.get(3).get("message_id").asInt());
        assertNull(hasMore(response), "no paging was asked for, so there is no paging header");
    }

    @Test
    void mentionsCanBePagedNewestFirstAndTheCursorReachesTheEnd() throws Exception {
        fourMentionsOfTheSeedUser();

        HttpResponse<String> first = get("/accounts/1/mentions?limit=3");
        JsonNode firstPage = mapper.readTree(first.body());
        assertEquals(3, firstPage.size());
        assertMessage(firstPage.get(0), 5, 2, "note 5 for testuser1", 1700000005L);
        assertEquals(3, firstPage.get(2).get("message_id").asInt());
        assertEquals("true", hasMore(first));

        HttpResponse<String> rest = get("/accounts/1/mentions?limit=3&before=3");
        JsonNode restPage = mapper.readTree(rest.body());
        assertEquals(1, restPage.size());
        assertEquals(2, restPage.get(0).get("message_id").asInt());
        assertEquals("false", hasMore(rest));

        HttpResponse<String> exact = get("/accounts/1/mentions?limit=4");
        assertEquals(4, mapper.readTree(exact.body()).size());
        assertEquals("false", hasMore(exact), "a page that holds exactly the rest says there is no more");
    }

    @Test
    void mentionPagingRejectsBadValuesAndUnknownAccounts() throws Exception {
        fourMentionsOfTheSeedUser();

        assertEquals(400, get("/accounts/1/mentions?limit=0").statusCode());
        assertEquals(400, get("/accounts/1/mentions?limit=101").statusCode());
        assertEquals(400, get("/accounts/1/mentions?limit=abc").statusCode());
        assertEquals(400, get("/accounts/1/mentions?before=0").statusCode());
        assertEquals(404, get("/accounts/99/mentions?limit=5").statusCode());

        HttpResponse<String> nobody = get("/accounts/2/mentions?limit=5");
        assertEquals("[]", nobody.body());
        assertEquals("false", hasMore(nobody));
    }

    // ---- a character PostgreSQL cannot store (2.3) ----

    @Test
    void textContainingTheNulCharacterIsRefusedTheSameWayOnEveryDatabase() throws Exception {
        // In JSON, \u0000 stands for the character with code 0. H2 would store it; PostgreSQL cannot.
        HttpResponse<String> created = send("POST", "/messages", messageJson(1, "before\\u0000after", 1700000000L), null);
        assertEquals(400, created.statusCode());
        assertEquals("", created.body());
        assertEquals(400, send("PATCH", "/messages/1", textJson("before\\u0000after"), null).statusCode());
        assertSeedMessage(mapper.readTree(get("/messages/1").body()));

        assertEquals(401, send("POST", "/login", accountJson("test\\u0000user1", "password"), null).statusCode());
        assertEquals(400, send("POST", "/register", accountJson("new\\u0000name", "password"), null).statusCode());
        // Line breaks and tabs are ordinary text and stay allowed.
        assertEquals(200, send("POST", "/messages", messageJson(1, "line one\\nline two\\tend", 1700000000L), null).statusCode());
    }

    // ---- the schema comes from the Flyway migrations (2.3) ----

    @Test
    void bothMigrationsWereAppliedAndRecorded() {
        // Flyway creates its history table with every name in double quotes and lower case.
        // H2 turns a name without quotes into upper case, so here the quotes are required.
        Integer applied = jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"success\" = true and \"version\" in ('1', '2')",
                Integer.class);
        assertEquals(2, applied, "V1 and V2 should both be recorded as successful");
    }

    @Test
    void theDatabaseItselfRefusesAPostWithoutAnAuthor() {
        // Written straight into the table, past the application's own check.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into message (posted_by, message_text, time_posted_epoch) values (?, ?, ?)", 99, "from nobody", 1700000000L));
        assertEquals(1, jdbc.queryForObject("select count(*) from message", Integer.class));
    }

    @Test
    void theDatabaseItselfRefusesASecondAccountWithTheSameNameKey() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into account (username, username_key, password) values (?, ?, ?)", "TESTUSER1", "testuser1", "x"));
    }

    @Test
    void theDatabaseRemovesMentionRowsWithTheirPostAndProtectsAnAuthorsAccount() throws Exception {
        send("POST", "/register", accountJson("user", "password"), null);                                   // account 2
        send("POST", "/messages", messageJson(2, "hello testuser1", 1700000000L), null);                    // message 2
        assertEquals(1, mentionRows());

        // An account that has posts cannot be removed from under them.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("delete from account where account_id = 2"));

        // A post deleted behind the application's back takes its mention rows with it (on delete cascade).
        assertEquals(1, jdbc.update("delete from message where message_id = 2"));
        assertEquals(0, mentionRows());
    }

    // ---- the web app's files may be kept by the browser; answers from the API may not (2.3) ----

    @Test
    void fingerprintedAssetsAreCacheableForAYearAndEverythingElseIsNot() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(cacheControl(page).contains("no-store"), "index.html must be fetched fresh: it names the current assets");

        // index.html refers to its script as /assets/index-<fingerprint>.js
        Matcher script = Pattern.compile("/assets/[A-Za-z0-9_.-]+\\.js").matcher(page.body());
        assertTrue(script.find(), "index.html should refer to a script under /assets/");

        HttpResponse<String> asset = get(script.group());
        assertEquals(200, asset.statusCode());
        assertTrue(cacheControl(asset).contains("max-age=31536000"), "was: " + cacheControl(asset));
        assertFalse(cacheControl(asset).contains("no-store"), "was: " + cacheControl(asset));

        assertTrue(cacheControl(get("/messages")).contains("no-store"), "API answers are never cached");
        assertTrue(cacheControl(get("/health")).contains("no-store"));
        assertEquals(404, get("/assets/no-such-file.js").statusCode());
    }

    // ---- 2.4: email and the closed board do not change the original contract ----

    @Test
    void theOriginalContractNeedsNoEmailAndReportsAnOpenBoard() throws Exception {
        JsonNode health = mapper.readTree(get("/health").body());
        assertEquals("optional", health.get("email").asText());
        assertEquals("open", health.get("board").asText());

        // exactly the two fields of the training contract, and the member posts at once
        HttpResponse<String> created = send("POST", "/register", accountJson("plain", "pass"), null);
        assertEquals(200, created.statusCode());
        assertAccount(created.body(), 2, "plain", "pass");
        assertEquals(3, mapper.readTree(created.body()).size(), "register still answers with exactly three fields");
        assertEquals(200, send("POST", "/messages", messageJson(2, "no email needed", 1L), null).statusCode());
        assertTrue(mailbox().recent().isEmpty(), "no address, no mail");
    }

    @Test
    void anEmailGivenAnywayIsStoredAndCanBeConfirmedButIsNeverRequired() throws Exception {
        assertEquals(400, send("POST", "/register", registrationJson("mailer", "pass", "not an address"), null).statusCode(),
                "an address that is given must be one");
        assertEquals(200, send("POST", "/register", registrationJson("mailer", "pass", "Mailer@Example.Test"), null).statusCode());
        assertEquals(200, send("POST", "/messages", messageJson(2, "compat mode never waits for a confirmation", 1L), null).statusCode());

        String token = loginToken("mailer", "pass");
        JsonNode me = mapper.readTree(send("GET", "/me", null, token).body());
        assertEquals("mailer@example.test", me.get("email").asText());
        assertFalse(me.get("email_verified").asBoolean());
        assertTrue(me.get("can_post").asBoolean());
        confirmEmail("mailer@example.test");
        assertTrue(mapper.readTree(send("GET", "/me", null, token).body()).get("email_verified").asBoolean());
    }

    @Test
    void theAccountEndpointsNeedATokenEvenInCompatMode() throws Exception {
        assertEquals(401, get("/me").statusCode());
        assertEquals(401, send("POST", "/accounts/1/email", emailJson("x@example.test"), null).statusCode());
        assertEquals(401, send("POST", "/email/verification", null, null).statusCode());
        // and the public ones answer without one
        assertEquals(200, send("POST", "/password/forgot", emailJson("nobody@example.test"), null).statusCode());
        assertEquals(400, send("POST", "/email/verify", tokenJson("no-such-secret"), null).statusCode());
        assertEquals(400, send("POST", "/password/forgot", "{}", null).statusCode(), "a body without an address is unusable");
    }

    @Test
    void migrationThreeAddedTheEmailColumnsAndTheTableOfLinks() throws Exception {
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"success\" = true and \"version\" in ('1', '2', '3')",
                Integer.class));
        // an account from before 2.4 (the seed row) got the defaults
        assertEquals(0L, jdbc.queryForObject("select token_version from account where account_id = 1", Long.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from account where account_id = 1 and email is null "
                + "and email_verified_at is null", Integer.class));
        // two accounts may both have no address, but not the same one
        jdbc.update("insert into account (username, username_key, password) values ('noaddress', 'noaddress', 'x')");
        jdbc.update("insert into account (username, username_key, password, email) values ('a1', 'a1', 'x', 'same@example.test')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into account (username, username_key, password, email) values ('a2', 'a2', 'x', 'same@example.test')"));
        // no two links with the same hash: a secret leads to exactly one row
        jdbc.update("insert into account_token (account_id, purpose, token_hash, expires_at, created_at) values (1, 'verify', 'same-hash', 1, 1)");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into account_token "
                + "(account_id, purpose, token_hash, expires_at, created_at) values (1, 'reset', 'same-hash', 1, 1)"));
        // a link must belong to an account
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into account_token "
                + "(account_id, purpose, token_hash, expires_at, created_at) values (424242, 'verify', 'h', 1, 1)"));
    }

    @Test
    void migrationFourAddedTheTableOfInvitations() throws Exception {
        assertEquals(4, jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"success\" = true and \"version\" in ('1', '2', '3', '4')",
                Integer.class));
        jdbc.update("insert into account (username, username_key, password) values ('joined', 'joined', 'x')");
        jdbc.update("insert into invitation (inviter_id, email, token_hash, created_at, expires_at, used_at, used_by) "
                + "values (1, 'joined@example.test', 'hash-1', 1, 2, 1, 2)");
        // no two invitations with the same hash
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into invitation "
                + "(inviter_id, token_hash, created_at, expires_at) values (1, 'hash-1', 1, 2)"));
        // an invitation is sent by an account that exists
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into invitation "
                + "(inviter_id, token_hash, created_at, expires_at) values (424242, 'hash-2', 1, 2)"));
        // the account that joined is deleted: the invitation stays, and forgets by whom
        jdbc.update("delete from account where account_id = 2");
        assertEquals(1, jdbc.queryForObject("select count(*) from invitation where used_by is null and used_at = 1", Integer.class));
        // the sender is deleted: the invitations go with them
        jdbc.update("delete from message where posted_by = 1");
        jdbc.update("delete from account where account_id = 1");
        assertEquals(0, jdbc.queryForObject("select count(*) from invitation", Integer.class));
    }

    @Test
    void compatModeLetsAMemberDeleteTheirAccountWithTheirToken() throws Exception {
        assertEquals(401, send("POST", "/accounts/1/deletion", "{\"password\":\"password\"}", null).statusCode(),
                "deleting is never open to anyone, not even in compat mode");
        String token = loginToken("testuser1", "password");
        assertEquals(200, send("POST", "/accounts/1/deletion", "{\"password\":\"password\"}", token).statusCode());
        assertEquals(404, get("/accounts/1").statusCode());
        assertEquals("", get("/messages/1").body(), "the posts went with the account");
    }

    private static String cacheControl(HttpResponse<String> response) {
        return response.headers().firstValue("Cache-Control").orElse("");
    }
}
