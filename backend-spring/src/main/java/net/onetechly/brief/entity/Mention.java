package net.onetechly.brief.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One row of the mention table: "message M mentions account A".
 *
 * The row is written when a message is saved, so that answering "who mentioned me?" is a
 * look-up instead of a search through every message.
 *
 *   The index (account_id, message_id) is what makes that look-up fast: the database goes
 *   straight to the rows for one account, already in message order.
 *
 *   The unique constraint (message_id, account_id) guarantees a message mentions a member
 *   at most once, however many times the name is written in it.
 *
 * This table is never sent to clients, so it has no JSON names.
 */
@Entity
@Table(
        name = "mention",
        uniqueConstraints = @UniqueConstraint(name = "uq_mention_message_account", columnNames = {"message_id", "account_id"}),
        indexes = @Index(name = "idx_mention_account_message", columnList = "account_id, message_id"))
public class Mention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mention_id")
    private Integer mentionId;

    @Column(name = "message_id", nullable = false)
    private Integer messageId;

    /** account_id of the member who is mentioned (never the author of the message). */
    @Column(name = "account_id", nullable = false)
    private Integer accountId;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected Mention() {
    }

    public Mention(Integer messageId, Integer accountId) {
        this.messageId = messageId;
        this.accountId = accountId;
    }

    public Integer getMentionId() {
        return mentionId;
    }

    public Integer getMessageId() {
        return messageId;
    }

    public Integer getAccountId() {
        return accountId;
    }
}
