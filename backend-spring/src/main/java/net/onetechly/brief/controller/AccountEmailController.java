package net.onetechly.brief.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.onetechly.brief.dto.EmailRequest;
import net.onetechly.brief.dto.PasswordResetRequest;
import net.onetechly.brief.dto.Profile;
import net.onetechly.brief.dto.TokenRequest;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.security.ClientAddress;
import net.onetechly.brief.security.LoginRateLimiter;
import net.onetechly.brief.service.AccountEmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for a member's own account and its email address (2.4).
 *
 * Two of them are called by someone who is NOT signed in, and cannot be: a member who forgot
 * the password, and a member who opens an emailed link on another device. Their proof is the
 * secret in the link, not a login token.
 */
@RestController
public class AccountEmailController {

    private final AccountEmailService emailService;
    private final AuthGuard authGuard;
    private final LoginRateLimiter rateLimiter;
    private final ClientAddress clientAddress;

    public AccountEmailController(AccountEmailService emailService, AuthGuard authGuard,
                                  LoginRateLimiter rateLimiter, ClientAddress clientAddress) {
        this.emailService = emailService;
        this.authGuard = authGuard;
        this.rateLimiter = rateLimiter;
        this.clientAddress = clientAddress;
    }

    /** GET /me - 200 with the caller's own account, including the email address; 401 without a valid token. */
    @GetMapping("/me")
    public Profile me() {
        return emailService.profile(signedInAccount());
    }

    /**
     * POST /accounts/{accountId}/email - gives the account an address, or replaces one that was
     * never confirmed, and mails the confirmation link. 200 with the account; 401 without a
     * token, 403 for someone else's account, 400 if the address is not acceptable.
     */
    @PostMapping("/accounts/{accountId}/email")
    public Profile setEmail(@PathVariable("accountId") int accountId, @Valid @RequestBody EmailRequest request) {
        authGuard.requireSelf(accountId);
        return emailService.setEmail(accountId, request.email());
    }

    /**
     * POST /email/verification - mails the confirmation link again. 200; 401 without a token,
     * 400 if there is nothing to confirm, 429 if the last one was sent less than a minute ago.
     */
    @PostMapping("/email/verification")
    public ResponseEntity<Void> resendVerification() {
        emailService.resendVerification(signedInAccount());
        return ResponseEntity.ok().build();
    }

    /** POST /email/verify - uses a confirmation link. 200, or 400 if the link is not good (any more). */
    @PostMapping("/email/verify")
    public ResponseEntity<Void> verify(@Valid @RequestBody TokenRequest request) {
        emailService.verify(request.token());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /password/forgot - asks for a password link. Always 200 for a readable request,
     * whether or not the address belongs to an account: the answer must not tell. 429 after
     * too many requests from one address (strict mode).
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        rateLimiter.acquire(LoginRateLimiter.keyForAction("forgot", clientAddress.of(http)));
        emailService.requestPasswordReset(request.email());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /password/reset - uses a password link to set a new password. 200; 400 if the link
     * is not good (any more) or the password does not meet the rule of the current mode.
     */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        emailService.resetPassword(request.token(), request.password());
        return ResponseEntity.ok().build();
    }

    private int signedInAccount() {
        Integer accountId = authGuard.currentAccountId();
        if (accountId == null) {
            throw new UnauthorizedException();
        }
        return accountId;
    }
}
