package net.onetechly.brief.exception;

/**
 * Becomes HTTP 422 (2.7): Brief! will not mail this address, because mail to it bounced
 * recently (see service/EmailBounces.java). The member is asked for a different address; the
 * board owner is asked to check the spelling of an invitation.
 */
public class UndeliverableAddressException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
