package net.onetechly.brief.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.onetechly.brief.dto.AccountResponse;
import net.onetechly.brief.dto.Credentials;
import net.onetechly.brief.dto.PublicAccount;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.security.ClientAddress;
import net.onetechly.brief.security.JwtService;
import net.onetechly.brief.security.LoginRateLimiter;
import net.onetechly.brief.service.AccountService;
import net.onetechly.brief.service.MentionService;
import net.onetechly.brief.service.MessageService;
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

    public AccountController(AccountService accountService, MessageService messageService,
                             MentionService mentionService, JwtService jwtService,
                             LoginRateLimiter loginRateLimiter, ClientAddress clientAddress) {
        this.accountService = accountService;
        this.messageService = messageService;
        this.mentionService = mentionService;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientAddress = clientAddress;
    }

    /**
     * POST /register - 200 with the new account; 400 if the input is invalid, the username or
     * the password does not meet the rule of the current mode, or the username is taken
     * (in any capitals).
     */
    @PostMapping("/register")
    public AccountResponse register(@Valid @RequestBody Credentials credentials) {
        return accountService.register(credentials);
    }

    /**
     * POST /login - 200 with the account and a token (a JWT) in the X-Auth-Token header,
     * 401 if the credentials do not match, 429 after too many failed attempts.
     */
    @PostMapping("/login")
    public ResponseEntity<AccountResponse> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        // Behind a hosting platform's proxy the connection's address is the proxy's; ClientAddress knows which to use.
        String attemptKey = LoginRateLimiter.keyFor(clientAddress.of(request), credentials.username());
        loginRateLimiter.acquire(attemptKey);                           // counts this attempt; throws (429) while locked out
        AccountResponse account = accountService.login(credentials);    // throws (401) on a mismatch: the attempt stays counted
        loginRateLimiter.recordSuccess(attemptKey);                     // right password: clear the count
        return ResponseEntity.ok()
                .header(TOKEN_HEADER, jwtService.issue(account.accountId()))
                .body(account);
    }

    /** POST /logout - always 200. Ending a session that is already gone is not an error. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        jwtService.revoke(JwtService.extractBearerToken(authorization));
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
