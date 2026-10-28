package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the push_subscription table (2.8): where to deliver a notification for one browser
 * on one device. The three values come from the browser (PushManager.subscribe) and are only
 * meaningful together. See V6__sessions_and_push.sql and push/WebPush.
 */
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id")
    private Integer subscriptionId;

    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    /** The session that turned notifications on. Signing it out deletes this row (on delete cascade). */
    @Column(name = "session_id", nullable = false)
    private Integer sessionId;

    /** The address at the push service (Google, Mozilla, Apple, Microsoft) that reaches this browser. */
    @Column(name = "endpoint", unique = true, nullable = false, length = 1000)
    private String endpoint;

    /** The browser's public key (P-256, 65 bytes), base64url. */
    @Column(name = "p256dh", nullable = false, length = 100)
    private String p256dh;

    /** The browser's 16-byte secret, base64url. */
    @Column(name = "auth", nullable = false, length = 40)
    private String auth;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected PushSubscription() {
    }

    public PushSubscription(Integer accountId, Integer sessionId, String endpoint, String p256dh, String auth, long createdAt) {
        this.accountId = accountId;
        this.sessionId = sessionId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = createdAt;
    }

    public Integer getSubscriptionId() {
        return subscriptionId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
