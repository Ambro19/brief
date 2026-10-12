package net.onetechly.brief.unit;

import net.onetechly.brief.entity.AccountToken;
import net.onetechly.brief.service.AccountTokens;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The secrets of emailed links: made, stored as a hash, good once and for a limited time. */
class AccountTokensTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final InMemoryTokens table = new InMemoryTokens();
    private final AccountTokens tokens = new AccountTokens(table.repository, now::get);

    @Test
    void aSecretIsLongRandomAndSafeToPutIntoALink() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String secret = tokens.issue(i, AccountToken.VERIFY);
            assertEquals(43, secret.length(), "32 bytes in Base64URL without padding");
            assertTrue(secret.matches("[A-Za-z0-9_-]+"), "only characters that need no escaping in a link: " + secret);
            assertTrue(seen.add(secret), "no secret is made twice");
        }
    }

    @Test
    void onlyTheHashOfASecretIsStored() {
        String secret = tokens.issue(7, AccountToken.RESET);

        AccountToken row = table.rows.get(0);
        assertEquals(64, row.getTokenHash().length());
        assertTrue(row.getTokenHash().matches("[0-9a-f]{64}"), "SHA-256 as hexadecimal digits");
        assertNotEquals(secret, row.getTokenHash());
        assertFalse(row.getTokenHash().contains(secret));
        assertEquals(Integer.valueOf(7), row.getAccountId());
        assertEquals(AccountToken.RESET, row.getPurpose());
        assertNull(row.getUsedAt());
    }

    @Test
    void aLinkWorksOnce() {
        String secret = tokens.issue(7, AccountToken.VERIFY);

        Optional<AccountToken> first = tokens.redeem(secret, AccountToken.VERIFY);
        assertTrue(first.isPresent());
        assertEquals(Integer.valueOf(7), first.get().getAccountId());
        assertEquals(Long.valueOf(1_000_000), first.get().getUsedAt());

        assertTrue(tokens.redeem(secret, AccountToken.VERIFY).isEmpty(), "used before");
        assertTrue(tokens.find(secret, AccountToken.VERIFY).isEmpty());
    }

    @Test
    void lookingALinkUpDoesNotUseIt() {
        String secret = tokens.issue(7, AccountToken.RESET);
        assertTrue(tokens.find(secret, AccountToken.RESET).isPresent());
        assertTrue(tokens.find(secret, AccountToken.RESET).isPresent());
        assertTrue(tokens.redeem(secret, AccountToken.RESET).isPresent(), "still good after two looks");
    }

    @Test
    void aLinkIsGoodOnlyForItsOwnPurpose() {
        String verify = tokens.issue(7, AccountToken.VERIFY);
        String reset = tokens.issue(7, AccountToken.RESET);

        assertTrue(tokens.redeem(verify, AccountToken.RESET).isEmpty(), "a confirmation link must not set a password");
        assertTrue(tokens.redeem(reset, AccountToken.VERIFY).isEmpty());
        assertTrue(tokens.redeem(verify, AccountToken.VERIFY).isPresent(), "and the wrong attempts did not use it up");
        assertTrue(tokens.redeem(reset, AccountToken.RESET).isPresent());
    }

    @Test
    void aConfirmationLinkLastsADayAndAPasswordLinkAnHour() {
        String verify = tokens.issue(7, AccountToken.VERIFY);
        String reset = tokens.issue(7, AccountToken.RESET);

        now.addAndGet(60 * 60 - 1);
        assertTrue(tokens.find(reset, AccountToken.RESET).isPresent(), "one second before the hour is over");
        now.addAndGet(1);
        assertTrue(tokens.find(reset, AccountToken.RESET).isEmpty(), "the hour is over");
        assertTrue(tokens.redeem(reset, AccountToken.RESET).isEmpty());

        now.set(1_000_000 + 24 * 60 * 60 - 1);
        assertTrue(tokens.find(verify, AccountToken.VERIFY).isPresent(), "one second before the day is over");
        now.addAndGet(1);
        assertTrue(tokens.redeem(verify, AccountToken.VERIFY).isEmpty(), "the day is over");
    }

    @Test
    void aNewLinkReplacesTheEarlierOnesForTheSamePurposeAndAccountOnly() {
        String first = tokens.issue(7, AccountToken.VERIFY);
        String resetOfSeven = tokens.issue(7, AccountToken.RESET);
        String verifyOfEight = tokens.issue(8, AccountToken.VERIFY);

        String second = tokens.issue(7, AccountToken.VERIFY);

        assertEquals(3, table.rows.size(), "the first confirmation link of account 7 is gone, nothing else");
        assertTrue(tokens.find(first, AccountToken.VERIFY).isEmpty());
        assertTrue(tokens.find(second, AccountToken.VERIFY).isPresent());
        assertTrue(tokens.find(resetOfSeven, AccountToken.RESET).isPresent());
        assertTrue(tokens.find(verifyOfEight, AccountToken.VERIFY).isPresent());
    }

    @Test
    void unknownEmptyOrAlteredSecretsFindNothing() {
        String secret = tokens.issue(7, AccountToken.VERIFY);
        for (String wrong : new String[] {null, "", "   ", "no-such-secret", secret + "x", secret.substring(1),
                secret.toLowerCase(), table.rows.get(0).getTokenHash()}) {
            assertTrue(tokens.redeem(wrong, AccountToken.VERIFY).isEmpty(), "should find nothing: " + wrong);
        }
        assertTrue(tokens.redeem(" " + secret + "\n", AccountToken.VERIFY).isPresent(), "spaces around a pasted secret are forgiven");
    }

    @Test
    void theTimeSinceTheLastLinkIsKnownPerAccountAndPurpose() {
        assertEquals(-1, tokens.secondsSinceLastIssue(7, AccountToken.RESET), "none yet");
        tokens.issue(7, AccountToken.RESET);
        assertEquals(0, tokens.secondsSinceLastIssue(7, AccountToken.RESET));
        now.addAndGet(45);
        assertEquals(45, tokens.secondsSinceLastIssue(7, AccountToken.RESET));
        assertEquals(-1, tokens.secondsSinceLastIssue(7, AccountToken.VERIFY));
        assertEquals(-1, tokens.secondsSinceLastIssue(8, AccountToken.RESET));
    }
}
