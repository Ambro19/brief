package net.onetechly.brief.unit;

import net.onetechly.brief.entity.Account;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The small rules the account object keeps by itself (2.4): what "confirmed" and "signed out everywhere" mean. */
class AccountTest {

    @Test
    void anAccountWithoutAnAddressIsNotWaitingForAnything() {
        Account account = new Account("Maya", "hash");
        assertNull(account.getEmail());
        assertFalse(account.isEmailVerified());
        assertFalse(account.isWaitingForVerification(), "members from before 2.4 keep posting");
    }

    @Test
    void anAddressWaitsUntilItIsConfirmed() {
        Account account = new Account("Maya", "hash");
        account.setEmail("maya@example.test");
        assertTrue(account.isWaitingForVerification());
        assertFalse(account.isEmailVerified());

        account.setEmailVerifiedAt(1_000_000L);
        assertTrue(account.isEmailVerified());
        assertFalse(account.isWaitingForVerification());
    }

    @Test
    void aNewAddressHasToBeConfirmedAgain() {
        Account account = new Account("Maya", "hash");
        account.setEmail("maya@example.test");
        account.setEmailVerifiedAt(1_000_000L);

        account.setEmail("other@example.test");

        // The proof was for the old inbox. It says nothing about the new one.
        assertFalse(account.isEmailVerified());
        assertNull(account.getEmailVerifiedAt());
        assertTrue(account.isWaitingForVerification());
    }

    @Test
    void endingAllSessionsMovesTheTokenGenerationOn() {
        Account account = new Account("Maya", "hash");
        assertEquals(0, account.getTokenVersion());
        account.endAllSessions();
        account.endAllSessions();
        assertEquals(2, account.getTokenVersion());
    }
}
