package DAO;

import Model.Message;
import Util.ConnectionUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access for the message table. SQL only - validation lives in MessageService.
 */
public class MessageDAO {

    private static final String COLUMNS = "message_id, posted_by, message_text, time_posted_epoch";

    /** True if an account with this id exists (a message must be posted by a real user). */
    public boolean userExists(int accountId) {
        String sql = "select 1 from account where account_id = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not check whether the user exists", e);
        }
    }

    /** Inserts a message and returns it with its generated id. */
    public Message saveMessage(Message message) {
        String sql = "insert into message (posted_by, message_text, time_posted_epoch) values (?, ?, ?)";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, message.getPosted_by());
            ps.setString(2, message.getMessage_text());
            ps.setLong(3, message.getTime_posted_epoch());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return new Message(keys.getInt(1), message.getPosted_by(),
                            message.getMessage_text(), message.getTime_posted_epoch());
                }
            }
            return null;
        } catch (SQLException e) {
            throw new DataAccessException("Could not save message", e);
        }
    }

    public List<Message> getAllMessages() {
        String sql = "select " + COLUMNS + " from message order by message_id";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return mapRows(rs);
        } catch (SQLException e) {
            throw new DataAccessException("Could not list messages", e);
        }
    }

    public Message getMessageById(int messageId) {
        String sql = "select " + COLUMNS + " from message where message_id = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not look up message", e);
        }
    }

    /**
     * Deletes a message and returns what was deleted, or null if there was nothing to delete.
     * Calling it twice is safe: the second call simply returns null (idempotent).
     */
    public Message deleteMessage(int messageId) {
        Message existing = getMessageById(messageId);
        if (existing == null) {
            return null;
        }
        String sql = "delete from message where message_id = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, messageId);
            return ps.executeUpdate() > 0 ? existing : null;
        } catch (SQLException e) {
            throw new DataAccessException("Could not delete message", e);
        }
    }

    /** Updates only the text. Returns the full updated message, or null if the id does not exist. */
    public Message updateMessage(int messageId, Message updatedMessage) {
        String sql = "update message set message_text = ? where message_id = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, updatedMessage.getMessage_text());
            ps.setInt(2, messageId);
            if (ps.executeUpdate() == 0) {
                return null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not update message", e);
        }
        return getMessageById(messageId);
    }

    public List<Message> getMessagesByUser(int accountId) {
        String sql = "select " + COLUMNS + " from message where posted_by = ? order by message_id";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapRows(rs);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not list messages for user", e);
        }
    }

    private List<Message> mapRows(ResultSet rs) throws SQLException {
        List<Message> messages = new ArrayList<>();
        while (rs.next()) {
            messages.add(mapRow(rs));
        }
        return messages;
    }

    private Message mapRow(ResultSet rs) throws SQLException {
        return new Message(rs.getInt("message_id"), rs.getInt("posted_by"),
                rs.getString("message_text"), rs.getLong("time_posted_epoch"));
    }
}
