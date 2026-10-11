package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Locale;

/**
 * One row of the account table.
 *
 * This class is never sent to a client: it carries the password hash. The web layer uses the
 * records in the dto package instead (AccountResponse, PublicAccount).
 */
@Entity
@Table(name = "account")
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
