package net.onetechly.brief.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.onetechly.brief.dto.AccountDeletion;
import net.onetechly.brief.dto.AccountResponse;
import net.onetechly.brief.dto.Credentials;
import net.onetechly.brief.dto.PublicAccount;
import net.onetechly.brief.dto.Registration;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.security.BoardAccess;
import net.onetechly.brief.security.ClientAddress;
import net.onetechly.brief.security.JwtService;
import net.onetechly.brief.security.LoginRateLimiter;
import net.onetechly.brief.service.AccountDeletionService;
import net.onetechly.brief.service.AccountService;
import net.onetechly.brief.service.InvitationService;
import net.onetechly.brief.service.MentionService;
import net.onetechly.brief.service.MessageService;
import net.onetechly.brief.service.Sessions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP endpoints for accounts.
 *
 * {@code @RestController}: each method's return value is written to the response as JSON.
 * A method that returns normally answers 200; errors are thrown as exceptions and turned
 * into status codes by ApiExceptionHandler.
 */
@RestController
public class AccountController {

    /** Response header that carries the login token after a successful POST /login. */
    public static final String TOKEN_HEADER = "X-Auth-Token";

    private final AccountService accountService;
    private final MessageService messageService;
    private final MentionService mentionService;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientAddress clientAddress;
    private final BoardAccess board;
    private final InvitationService invitationService;
    private final AccountDeletionService deletionService;
    private final AuthGuard authGuard;
    private final Sessions sessions;

    public AccountController(AccountService accountService, MessageService messageService,
                             MentionService mentionService, JwtService jwtService,
                             LoginRateLimiter loginRateLimiter, ClientAddress clientAddress, BoardAccess board,
                             InvitationService invitationService, AccountDeletionService deletionService,
                             AuthGuard authGuard, Sessions sessions) {
        this.accountService = accountService;
        this.messageService = messageService;
        this.mentionService = mentionService;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientAddress = clientAddress;
        this.board = board;
        this.invitationService = invitationService;
        this.deletionService = deletionService;
        this.authGuard = authGuard;
        this.sessions = sessions;
    }

    /**
     * POST /register - 200 with the new account; 400 if the input is invalid, the username,
     * the password or the email does not meet the rule of the current mode, or the username
     * (in any capitals) or the email is taken.
     *
     * On a closed board (2.4): 403 without the right invitation code, and 429 after too many
     * wrong codes from one address, so the code cannot be found by trying.
     */
    @PostMapping("/register")
    public AccountResponse register(@Valid @RequestBody Registration registration, HttpServletRequest request) {
        boolean invitationGiven = registration.invitation() != null && !registration.invitation().isBlank();
        if (board.isClosed() || invitationGiven) {
            // Invitation links cannot be guessed (256 random bits), but the attempts are counted
            // anyway, as with logins: five bad ones from an address and it waits 15 minutes.
            String attemptKey = LoginRateLimiter.keyForAction("invite", clientAddress.of(request));
            loginRateLimiter.acquire(attemptKey);                       // counts this try; throws (429) while locked out
            if (invitationService.usable(registration.invitation()).isEmpty()) {
                throw new ForbiddenException();                         // the try stays counted
            }
            loginRateLimiter.recordSuccess(attemptKey);                 // a good invitation: clear the count
        }
        return accountService.register(registration);                  // checks the invitation again, and uses it
    }

    /**
     * POST /accounts/{accountId}/deletion - {"password": ...}. Deletes the account and its posts
     * (2.5). 200; 401 without a token; 403 for someone else's account or a wrong password; 400
     * for the board owner; 429 after five wrong passwords, counted together with sign-ins.
     *
     * A POST to a "deletion" address rather than DELETE /accounts/{id}: a DELETE request with a
     * body is allowed, but some proxies and libraries drop the body of a DELETE.
     */
    @PostMapping("/accounts/{accountId}/deletion")
    public ResponseEntity<Void> deleteAccount(@PathVariable("accountId") int accountId,
                                              @Valid @RequestBody AccountDeletion deletion, HttpServletRequest request) {
        authGuard.requireSelf(accountId);
        String attemptKey = LoginRateLimiter.keyFor(clientAddress.of(request), accountService.usernameOf(accountId));
        loginRateLimiter.acquire(attemptKey);
        deletionService.delete(accountId, deletion.password());
        loginRateLimiter.recordSuccess(attemptKey);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /login - 200 with the account and a token (a JWT) in the X-Auth-Token header,
     * 401 if the credentials do not match, 429 after too many failed attempts.
     *
     * 2.8: each sign-in starts a session (service/Sessions), and the token names it. With
     * "stay": true in the body the session lasts 30 days from its last use; without, 12 hours.
     */
    @PostMapping("/login")
    public ResponseEntity<AccountResponse> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        // Behind a hosting platform's proxy the connection's address is the proxy's; ClientAddress knows which to use.
        String attemptKey = LoginRateLimiter.keyFor(clientAddress.of(request), credentials.username());
        loginRateLimiter.acquire(attemptKey);                           // counts this attempt; throws (429) while locked out
        Account account = accountService.authenticate(credentials);    // throws (401) on a mismatch: the attempt stays counted
        loginRateLimiter.recordSuccess(attemptKey);                     // right password: clear the count
        AccountSession session = sessions.open(account.getAccountId(), credentials.staysSignedIn(),
                request.getHeader("User-Agent"));
        return ResponseEntity.ok()
                // The token carries the account's token generation; a later password reset makes it worthless.
                // Since 2.8 it also names its session, which signing out deletes.
                .header(TOKEN_HEADER, jwtService.issue(account.getAccountId(), account.getTokenVersion(),
                        session.getSessionId(), sessions.tokenExpiry(session)))
                .body(new AccountResponse(account.getAccountId(), account.getUsername(), credentials.password()));
    }

    /**
     * POST /logout - always 200. Ending a session that is already gone is not an error.
     *
     * 2.8: a token with a session ends it for good (the row is deleted, and with it this device's
     * push subscription). A token from 2.7 or earlier is remembered in memory, as before.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // The session the filter accepted for this request: a token that no longer holds (replaced by
        // "Sign out everywhere else", say) must not end the session that replaced it (independent review of 2.8.0).
        Integer sessionId = authGuard.currentSessionId();
        if (sessionId != null) {
            sessions.end(sessionId);
        } else {
            jwtService.revoke(JwtService.extractBearerToken(authorization));
        }
        return ResponseEntity.ok().build();
    }

    /** GET /accounts - everyone who has an account (id and username only). */
    @GetMapping("/accounts")
    public List<PublicAccount> listAccounts() {
        return accountService.listPublicAccounts();
    }

    /** GET /accounts/{accountId} - 200 with id and username, 404 if there is no such account. */
    @GetMapping("/accounts/{accountId}")
    public PublicAccount getAccount(@PathVariable("accountId") int accountId) {
        return accountService.getPublicAccount(accountId);
    }

    /**
     * GET /accounts/{accountId}/messages - always 200; an empty list if the member has no messages.
     * Pages the same way as GET /messages when ?limit= and/or ?before= is given.
     */
    @GetMapping("/accounts/{accountId}/messages")
    public ResponseEntity<List<Message>> getMessagesByAccount(
            @PathVariable("accountId") int accountId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "before", required = false) Integer before) {
        if (limit == null && before == null) {
            return ResponseEntity.ok(messageService.getByAuthor(accountId));
        }
        return Paging.respond(messageService.getPage(accountId, limit, before));
    }

    /**
     * GET /accounts/{accountId}/mentions - 200 with posts by others that name this member, 404 if no such account.
     *
     * Without query parameters: every mention, oldest first (as in 2.2).
     * With ?limit= and/or ?before=: one page, newest first, and an X-Has-More header, exactly
     * like GET /messages. 400 if limit is not 1 to 100 or before is not a positive number.
     */
    @GetMapping("/accounts/{accountId}/mentions")
    public ResponseEntity<List<Message>> getMentions(
            @PathVariable("accountId") int accountId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "before", required = false) Integer before) {
        if (limit == null && before == null) {
            return ResponseEntity.ok(mentionService.getMentionsOf(accountId));
        }
        return Paging.respond(mentionService.getMentionsPage(accountId, limit, before));
    }
}
