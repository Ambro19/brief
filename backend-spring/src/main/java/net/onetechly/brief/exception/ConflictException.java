package net.onetechly.brief.exception;

/**
 * Becomes HTTP 409 (2.8): the request is fine, but the state it needs is not there. Used when a
 * token from 2.7 or earlier, which belongs to no session, asks for something that only a session
 * can have (notifications on this device, signing out everywhere else). Signing in again gives
 * the member a session.
 */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
