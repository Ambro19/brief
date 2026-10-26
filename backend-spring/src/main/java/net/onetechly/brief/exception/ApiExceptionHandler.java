package net.onetechly.brief.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * One place that decides how every error is answered.
 *
 * The API contract says error responses have an EMPTY body; the status code carries the meaning.
 * Without this class Spring Boot would answer errors with its own JSON document.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** A rule of the application was broken (unknown author, username taken, no such message to edit). */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Void> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /**
     * The request itself was unusable:
     *   HttpMessageNotReadableException      the body is missing or is not valid JSON
     *   MethodArgumentNotValidException      a @Valid body broke a constraint (@NotBlank, @Size, ...)
     *   MethodArgumentTypeMismatchException  a path or query value such as /messages/abc or ?limit=abc is not a number
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Void> unusableRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /** "I do not know who you are": wrong login, or a missing, unknown or expired token. */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Void> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /** "I know who you are, and you may not do this." */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Void> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Void> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * The database refused a write because it would break a constraint - in practice, two
     * requests changing the same thing at the same instant. 409 Conflict: "try again".
     * Without this method Spring Boot would answer 500 with its own JSON document.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Void> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * 2.7: the request would mail an address that bounced recently. 422 Unprocessable Entity:
     * the request is well formed, but this address cannot be used. Distinct from 400 so that the
     * web app can say "that address does not receive mail" and not "that address is taken".
     */
    @ExceptionHandler(UndeliverableAddressException.class)
    public ResponseEntity<Void> undeliverable(UndeliverableAddressException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
    }

    /** Too many failed logins. Retry-After tells the client how many seconds to wait. */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Void> tooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .build();
    }
}
