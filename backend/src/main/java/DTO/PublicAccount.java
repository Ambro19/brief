package DTO;

/**
 * What the API shows about an account to other users: id and username only.
 * The Account model carries the password, so it is never returned from the directory endpoints.
 */
public class PublicAccount {
    public int account_id;
    public String username;

    public PublicAccount() {
    }

    public PublicAccount(int account_id, String username) {
        this.account_id = account_id;
        this.username = username;
    }

    public int getAccount_id() {
        return account_id;
    }

    public void setAccount_id(int account_id) {
        this.account_id = account_id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
