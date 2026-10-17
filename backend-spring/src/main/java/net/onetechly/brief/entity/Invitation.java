package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the invitation table (2.5): a link with which one person can create an account
 * on a closed board.
 *
 * As with AccountToken, the secret in the link is not stored, only its SHA-256 hash. An
 * invitation works once, until it expires, and only while it has not been withdrawn (a
 * withdrawn invitation is deleted).
 */
@Entity
@Table(name = "invitation")
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invitation_id")
    private Integer invitationId;

    /** The member who sent it. */
    @Column(name = "inviter_id", nullable = false)
    private Integer inviterId;

    /** The address it was mailed to, in lower case; null for a link the inviter passes on by other means. */
    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "token_hash", unique = true, nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /** Seconds since 1970. From this second on the invitation no longer works. */
    @Column(name = "expires_at", nullable = false)
    private Long expiresAt;

    /** When an account was created with it, or null. */
    @Column(name = "used_at")
    private Long usedAt;

    /** The account created with it, while that account exists. */
    @Column(name = "used_by")
    private Integer usedBy;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected Invitation() {
    }

    public Invitation(Integer inviterId, String email, String tokenHash, long createdAt, long expiresAt) {
        this.inviterId = inviterId;
        this.email = email;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Integer getInvitationId() {
        return invitationId;
    }

    public Integer getInviterId() {
        return inviterId;
    }

    public String getEmail() {
        return email;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public Long getUsedAt() {
        return usedAt;
    }

    public Integer getUsedBy() {
        return usedBy;
    }

    /** Records that an account was created with this invitation. */
    public void markUsed(long nowSeconds, Integer accountId) {
        this.usedAt = nowSeconds;
        this.usedBy = accountId;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    /** True while an account can still be created with it. */
    public boolean isUsableAt(long nowSeconds) {
        return usedAt == null && nowSeconds < expiresAt;
    }
}
