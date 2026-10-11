package net.onetechly.brief.exception;

/** Becomes HTTP 429. Carries how long the caller should wait, for the Retry-After header. */
public class TooManyRequestsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
