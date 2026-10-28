package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the account_session table (2.8): one sign-in, on one device. The login token
 * carries the row id in its "sid" claim, and JwtAuthenticationFilter accepts the token only
 * while this row exists and has not expired. See V6__sessions_and_push.sql and service/Sessions.
 */
@Entity
@Table(name = "account_session")
public class AccountSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Integer sessionId;

    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    /** True for "Stay signed in on this device": 30 days from the last use. False: 12 hours from signing in. */
    @Column(name = "stay", nullable = false)
    private Boolean stay;

    /** A short label such as "Chrome on Windows", made from the User-Agent (security/DeviceLabel). */
    @Column(name = "device", nullable = false, length = 60)
    private String device;

    /** Seconds since 1970. */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Long lastUsedAt;

    /** From this second on the session no longer counts. */
    @Column(name = "expires_at", nullable = false)
    private Long expiresAt;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected AccountSession() {
    }

    public AccountSession(Integer accountId, boolean stay, String device, long createdAt, long expiresAt) {
        this.accountId = accountId;
        this.stay = stay;
        this.device = device;
        this.createdAt = createdAt;
        this.lastUsedAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public boolean isStay() {
        return Boolean.TRUE.equals(stay);
    }

    public String getDevice() {
        return device;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    /** True while the session counts: from signing in until expiresAt. */
    public boolean isLiveAt(long nowSeconds) {
        return nowSeconds < expiresAt;
    }
}
