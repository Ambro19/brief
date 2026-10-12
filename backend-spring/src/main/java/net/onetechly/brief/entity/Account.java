package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.util.Locale;

/**
 * One row of the account table.
 *
 * This class is never sent to a client: it carries the password hash. The web layer uses the
 * records in the dto package instead (AccountResponse, PublicAccount).
 */
@Entity
@Table(name = "account")
// Write only the columns that changed (2.4). Without this, Hibernate's UPDATE names every column,
// so two changes to one account that overlap in time would undo each other: confirming the address
// would write the old password back over one that was replaced in the same moment, and the reverse.
@DynamicUpdate
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Integer accountId;

    /** The name as the member typed it. This is what is shown. */
    @Column(name = "username", unique = true, nullable = false, length = 255)
    private String username;

    /**
     * The name in the form used to compare names: lower case, without spaces around it (2.3).
     * It is unique, so "Maya" and "maya" cannot both register, and signing in works whatever
     * capitals are typed. It is kept as its own column so the database can enforce the rule
     * with an ordinary unique index, on H2 and PostgreSQL alike.
     */
    @Column(name = "username_key", unique = true, nullable = false, length = 255)
    private String usernameKey;

    /** A PBKDF2 hash (see PasswordHasher), or plain text in a legacy row that has not signed in yet. */
    @Column(name = "password", nullable = false, length = 255)
    private String password;

    /**
     * The member's email address in lower case, or null (2.4). Accounts from before 2.4 have
     * none until their owner adds one. Never shown to other members.
     */
    @Column(name = "email", unique = true, length = 254)
    private String email;

    /** When the address was confirmed through the emailed link (seconds since 1970), or null. */
    @Column(name = "email_verified_at")
    private Long emailVerifiedAt;

    /**
     * Which "generation" of login tokens is current for this account (2.4). A token carries
     * the number it was issued under and is accepted only while that is still this number.
     * Setting a new password adds one: every token issued before is then refused.
     *
     * A number, not a time ("tokens older than ... are refused"): a login that is being
     * checked at the very moment the password is replaced would otherwise get a token with a
     * time after the cut-off although it used the old password. With a number, that token
     * carries the old number, whenever it is finally issued.
     */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected Account() {
    }

    public Account(String username, String password) {
        this.username = username;
        this.usernameKey = keyOf(username);
        this.password = password;
    }

    /**
     * The form in which two usernames are compared. One method decides it for the whole
     * application: registering, signing in, and this class when a name is stored.
     * Locale.ROOT makes "lower case" mean the same thing on every computer.
     */
    public static String keyOf(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    public Integer getAccountId() {
        return accountId;
    }

    public void setAccountId(Integer accountId) {
        this.accountId = accountId;
    }

    public String getUsername() {
        return username;
    }

    /** Changing the name changes the key with it, so the two can never disagree. */
    public void setUsername(String username) {
        this.username = username;
        this.usernameKey = keyOf(username);
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public String getEmail() {
        return email;
    }

    /** A new address is not confirmed yet, whatever the old one was. */
    public void setEmail(String email) {
        this.email = email;
        this.emailVerifiedAt = null;
    }

    public Long getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Long emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public boolean isEmailVerified() {
        return email != null && emailVerifiedAt != null;
    }

    /**
     * True for an account that gave an address and has not confirmed it yet. Such a member can
     * read and cannot post. An account without any address (from before 2.4) is not waiting
     * for anything, so this is false for it.
     */
    public boolean isWaitingForVerification() {
        return email != null && emailVerifiedAt == null;
    }

    public int getTokenVersion() {
        return tokenVersion == null ? 0 : tokenVersion;
    }

    /** Makes every login token issued so far worthless. Takes effect when the account is saved. */
    public void endAllSessions() {
        this.tokenVersion = getTokenVersion() + 1;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
