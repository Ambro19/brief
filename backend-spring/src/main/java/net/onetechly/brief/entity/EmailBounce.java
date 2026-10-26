package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the email_bounce table (2.7): an address that cannot receive Brief!'s mail, as
 * reported by Resend. See V5__email_bounces.sql and service/EmailBounces.java.
 */
@Entity
@Table(name = "email_bounce")
public class EmailBounce {

    /** The receiving server refused the mail for good. */
    public static final String BOUNCED = "bounced";
    /** Resend did not even try: the address bounced recently, for Brief! or for another sender. */
    public static final String SUPPRESSED = "suppressed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bounce_id")
    private Integer bounceId;

    /** Lower case, as stored on accounts and invitations. */
    @Column(name = "address", unique = true, nullable = false, length = 254)
    private String address;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /** What the receiving server said, shortened to 300 characters; may be null. */
    @Column(name = "reason", length = 300)
    private String reason;

    /** Seconds since 1970: the latest report. */
    @Column(name = "bounced_at", nullable = false)
    private Long bouncedAt;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected EmailBounce() {
    }

    public EmailBounce(String address, String kind, String reason, long bouncedAt) {
        this.address = address;
        this.kind = kind;
        this.reason = reason;
        this.bouncedAt = bouncedAt;
    }

    public Integer getBounceId() {
        return bounceId;
    }

    public String getAddress() {
        return address;
    }

    public String getKind() {
        return kind;
    }

    public String getReason() {
        return reason;
    }

    public Long getBouncedAt() {
        return bouncedAt;
    }

    /** A newer report about the same address replaces what the row says. */
    public void update(String kind, String reason, long bouncedAt) {
        this.kind = kind;
        this.reason = reason;
        this.bouncedAt = bouncedAt;
    }
}
