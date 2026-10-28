package net.onetechly.brief.controller;

import net.onetechly.brief.dto.SessionView;
import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.exception.ConflictException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.security.JwtService;
import net.onetechly.brief.service.Sessions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where a member is signed in (2.8), for the "Where you're signed in" card in Your account.
 *
 *   GET    /sessions                    200 with the member's sessions, newest first
 *   DELETE /sessions/{id}               200; ends one of them (404 if it is not the member's)
 *   POST   /sessions/sign-out-others    200 with {"ended": n} and a new token in X-Auth-Token
 *
 * All three need a token (401 otherwise), in every mode: a session is never anyone else's
 * business. "Sign out everywhere else" also needs the token to belong to a session (409 for a
 * token from 2.7 or earlier: the member signs in again first).
 */
@RestController
public class SessionController {

    private final Sessions sessions;
    private final JwtService jwtService;
    private final AuthGuard authGuard;

    public SessionController(Sessions sessions, JwtService jwtService, AuthGuard authGuard) {
        this.sessions = sessions;
        this.jwtService = jwtService;
        this.authGuard = authGuard;
    }

    @GetMapping("/sessions")
    public List<SessionView> list() {
        int accountId = authGuard.requireSignedIn();
        Integer current = authGuard.currentSessionId();
        List<SessionView> views = new ArrayList<>();
        for (AccountSession session : sessions.list(accountId)) {
            views.add(new SessionView(session.getSessionId(), session.getDevice(), session.isStay(),
                    session.getCreatedAt(), session.getLastUsedAt(), session.getExpiresAt(),
                    session.getSessionId().equals(current)));
        }
        return views;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> end(@PathVariable("sessionId") int sessionId) {
        int accountId = authGuard.requireSignedIn();
        if (!sessions.end(sessionId, accountId)) {
            throw new NotFoundException(); // no such session, or someone else's: the same answer
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sessions/sign-out-others")
    public ResponseEntity<Map<String, Integer>> signOutOthers() {
        int accountId = authGuard.requireSignedIn();
        Integer current = authGuard.currentSessionId();
        if (current == null) {
            throw new ConflictException();
        }
        Sessions.OthersEnded result = sessions.endOthersEverywhere(accountId, current);
        // The account's token generation moved on, so this session needs a token of the new generation.
        AccountSession kept = sessions.list(accountId).stream()
                .filter(session -> session.getSessionId().equals(current))
                .findFirst().orElseThrow(ConflictException::new);
        return ResponseEntity.ok()
                .header(AccountController.TOKEN_HEADER,
                        jwtService.issue(accountId, result.tokenVersion(), current, sessions.tokenExpiry(kept)))
                .body(Map.of("ended", result.ended()));
    }
}
