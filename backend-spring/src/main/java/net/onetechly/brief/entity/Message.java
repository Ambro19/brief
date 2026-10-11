package net.onetechly.brief.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the message table, and also the JSON shape of a message in API responses.
 *
 * Java names are camelCase (messageId); the JSON keys stay snake_case (message_id) so the
 * API contract is identical to the Javalin edition. {@code @JsonProperty} sets the JSON name,
 * {@code @Column} sets the database column name.
 */
@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    @JsonProperty("message_id")
    private Integer messageId;

    /** account_id of the author. */
    @Column(name = "posted_by", nullable = false)
    @JsonProperty("posted_by")
    private Integer postedBy;

    @Column(name = "message_text", nullable = false, length = 255)
    @JsonProperty("message_text")
    private String messageText;

    /** Seconds since 1970-01-01 UTC. */
    @Column(name = "time_posted_epoch", nullable = false)
    @JsonProperty("time_posted_epoch")
    private Long timePostedEpoch;

    /** JPA needs a no-argument constructor to create objects from query results. */
    protected Message() {
    }

    public Message(Integer postedBy, String messageText, Long timePostedEpoch) {
        this.postedBy = postedBy;
        this.messageText = messageText;
        this.timePostedEpoch = timePostedEpoch;
    }

    public Integer getMessageId() {
        return messageId;
    }

    public void setMessageId(Integer messageId) {
        this.messageId = messageId;
    }

    public Integer getPostedBy() {
        return postedBy;
    }

    public void setPostedBy(Integer postedBy) {
        this.postedBy = postedBy;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public Long getTimePostedEpoch() {
        return timePostedEpoch;
    }

    public void setTimePostedEpoch(Long timePostedEpoch) {
        this.timePostedEpoch = timePostedEpoch;
    }
}
