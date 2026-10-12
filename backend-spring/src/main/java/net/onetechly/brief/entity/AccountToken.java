package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the account_token table: a one-time link that was emailed to a member (2.4).
 *
 * The link itself is not in the row. It carries a random secret; this row holds only the
 * SHA-256 hash of that secret. When the link comes back, its secret is hashed again and looked
 * up here. See service/AccountTokens.
 */
@Entity
@Table(name = "account_token")
public class AccountToken {

    /** The link confirms an email address. */
    public static final String VERIFY = "verify";
    /** The link lets its holder set a new password. */
    public static final String RESET = "reset";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Integer tokenId;

    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    @Column(name = "purpose", nullable = false, length = 16)
    private String purpose;

    @Column(name = "token_hash", unique = true, nullable = false, length = 64)
    private String tokenHash;

    /** Seconds since 1970. From this second on the link no longer works. */
    @Column(name = "expires_at", nullable = false)
    private Long expiresAt;

    /** Seconds since 1970, or null while the link has not been used. A link works once. */
    @Column(name = "used_at")
    private Long usedAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected AccountToken() {
    }

    public AccountToken(Integer accountId, String purpose, String tokenHash, long createdAt, long expiresAt) {
        this.accountId = accountId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Integer getTokenId() {
        return tokenId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public Long getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Long usedAt) {
        this.usedAt = usedAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** True while the link can still be used: not used before, and not past its time. */
    public boolean isUsableAt(long nowSeconds) {
        return usedAt == null && nowSeconds < expiresAt;
    }
}
