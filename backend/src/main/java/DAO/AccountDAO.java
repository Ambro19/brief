package DAO;

import Model.Account;
import Util.ConnectionUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access for the account table. SQL only - no business rules live here.
 * Every query uses a PreparedStatement, so user input is never concatenated into SQL.
 */
public class AccountDAO {

    public Account getAccountByUsername(String username) {
        String sql = "select account_id, username, password from account where username = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not look up account by username", e);
        }
    }

    public Account getAccountById(int accountId) {
        String sql = "select account_id, username, password from account where account_id = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not look up account by id", e);
        }
    }

    public List<Account> getAllAccounts() {
        String sql = "select account_id, username, password from account order by account_id";
        List<Account> accounts = new ArrayList<>();
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                accounts.add(mapRow(rs));
            }
            return accounts;
        } catch (SQLException e) {
            throw new DataAccessException("Could not list accounts", e);
        }
    }

    /**
     * Inserts a new account and returns it with its generated id.
     * Returns null if the username is already taken (unique constraint) - this
     * covers the race where two registrations for the same name arrive together.
     */
    public Account saveAccount(Account account) {
        String sql = "insert into account (username, password) values (?, ?)";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, account.getUsername());
            ps.setString(2, account.getPassword());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return new Account(keys.getInt(1), account.getUsername(), account.getPassword());
                }
            }
            return null;
        } catch (SQLIntegrityConstraintViolationException e) {
            return null;
        } catch (SQLException e) {
            throw new DataAccessException("Could not save account", e);
        }
    }

    /** Replaces the stored password value (used to upgrade a legacy plain-text password to a hash). */
    public void updatePassword(int accountId, String storedPassword) {
        String sql = "update account set password = ? where account_id = ?";
        try (Connection connection = ConnectionUtil.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, storedPassword);
            ps.setInt(2, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Could not update password", e);
        }
    }

    private Account mapRow(ResultSet rs) throws SQLException {
        return new Account(rs.getInt("account_id"), rs.getString("username"), rs.getString("password"));
    }
}
