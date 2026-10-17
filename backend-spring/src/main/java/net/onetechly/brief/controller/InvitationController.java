package net.onetechly.brief.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.onetechly.brief.dto.EmailRequest;
import net.onetechly.brief.dto.InvitationInfo;
import net.onetechly.brief.dto.InvitationLink;
import net.onetechly.brief.dto.InvitationView;
import net.onetechly.brief.dto.TokenRequest;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.security.ClientAddress;
import net.onetechly.brief.security.LoginRateLimiter;
import net.onetechly.brief.service.InvitationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP endpoints for invitations (2.5).
 *
 * Four are for the board owner and need a login token in every mode. One is for the person
 * who opened an invitation link, who has no account yet and so cannot be signed in: their
 * proof is the secret in the link.
 */
@RestController
public class InvitationController {

    private final InvitationService invitations;
    private final AuthGuard authGuard;
    private final LoginRateLimiter rateLimiter;
    private final ClientAddress clientAddress;

    public InvitationController(InvitationService invitations, AuthGuard authGuard,
                                LoginRateLimiter rateLimiter, ClientAddress clientAddress) {
        this.invitations = invitations;
        this.authGuard = authGuard;
        this.rateLimiter = rateLimiter;
        this.clientAddress = clientAddress;
    }

    /**
     * POST /invitations - mails an invitation to {"email": ...}. 200 with the invitation; 401
     * without a token; 403 for anyone but the board owner; 400 if the text is not an address or
     * the address already has an account; 429 after 50 invitations in a day.
     */
    @PostMapping("/invitations")
    public InvitationView invite(@Valid @RequestBody EmailRequest request) {
        return invitations.invite(signedInAccount(), request.email());
    }

    /** POST /invitations/link - an invitation that is not mailed; the answer carries its link, this once. */
    @PostMapping("/invitations/link")
    public InvitationLink createLink() {
        return invitations.createLink(signedInAccount());
    }

    /** GET /invitations - what the board owner sent, newest first. */
    @GetMapping("/invitations")
    public List<InvitationView> list() {
        return invitations.list(signedInAccount());
    }

    /** DELETE /invitations/{id} - withdraws an unused invitation. 404 if it is not the caller's; 400 if it was used. */
    @DeleteMapping("/invitations/{invitationId}")
    public ResponseEntity<Void> withdraw(@PathVariable("invitationId") int invitationId) {
        invitations.withdraw(signedInAccount(), invitationId);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /invitations/lookup - {"token": the secret from the link}. 200 with who sent it, to
     * which address, and until when; 400 if the link is unknown, used, expired or withdrawn.
     * Needs no sign-in. Counted per address like the other things that can be tried.
     */
    @PostMapping("/invitations/lookup")
    public InvitationInfo lookup(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        String key = LoginRateLimiter.keyForAction("invite", clientAddress.of(http));
        rateLimiter.acquire(key);
        InvitationInfo info = invitations.lookup(request.token()).orElseThrow(BadRequestException::new);
        rateLimiter.recordSuccess(key);
        return info;
    }

    private int signedInAccount() {
        Integer accountId = authGuard.currentAccountId();
        if (accountId == null) {
            throw new UnauthorizedException();
        }
        return accountId;
    }
}
