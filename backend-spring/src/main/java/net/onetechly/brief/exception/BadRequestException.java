package net.onetechly.brief.exception;

/** Thrown by services and the auth guard; ApiExceptionHandler turns it into HTTP 400 with an empty body. */
public class BadRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
