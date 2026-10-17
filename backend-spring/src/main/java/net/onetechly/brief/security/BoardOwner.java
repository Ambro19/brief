package net.onetechly.brief.security;

import net.onetechly.brief.entity.Account;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who runs the board (2.5). The owner is the member who can invite people, and the one whose
 * name an invitation carries.
 *
 * The setting is brief.owner-username (environment variable BRIEF_OWNER_USERNAME): the username
 * of an existing account, in any capitals. It is configuration and not a column in the database
 * on purpose: changing the owner is rare, and must not be something a request can do.
 *
 * Without the setting nobody can invite. On an open board that changes nothing; on a closed
 * board it means no new members until it is set, and the start-up log says so.
 */
@Component
public class BoardOwner {

    /** The owner's username in the form names are compared in (Account.keyOf), or null. */
    private final String usernameKey;

    public BoardOwner(@Value("${brief.owner-username:}") String username) {
        this.usernameKey = username == null || username.isBlank() ? null : Account.keyOf(username);
    }

    public boolean isConfigured() {
        return usernameKey != null;
    }

    /** The owner's username in lower case, or null. For look-ups by AccountRepository.findByUsernameKey. */
    public String usernameKey() {
        return usernameKey;
    }

    public boolean isOwner(Account account) {
        return usernameKey != null && account != null && usernameKey.equals(account.getUsernameKey());
    }
}
