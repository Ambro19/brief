package DAO;

/**
 * Unchecked wrapper for SQLException.
 *
 * The DAOs throw this instead of swallowing database errors, so a real failure
 * (database down, bad SQL) surfaces as HTTP 500 rather than looking like "not found".
 */
public class DataAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
