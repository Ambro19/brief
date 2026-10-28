package net.onetechly.brief.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.service.Sessions;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Runs once at the start of every request, before any controller.
 *
 * It looks for "Authorization: Bearer <token>". If the token is valid, it records who the
 * caller is in Spring Security's SecurityContext for the rest of this request.
 *
 * Valid means two things since 2.4. First, what JwtService checks from the token alone: the
 * signature, the expiry time, not signed out. Second, a look at the account row: the account
 * still exists, and the token was issued since the account's password was last replaced. That
 * second check costs one look-up by primary key for every request that carries a token. It
 * is what makes "set a new password" end every older session, on every server, and after a
 * restart. Requests without a token cost nothing extra.
 *
 * Since 2.8 a token names its session ("sid"), and a third check applies: the session row exists,
 * belongs to the account, and has not ended (service/Sessions). Signing out deletes the row, so
 * the token is refused from then on, on every server and after a restart. A token from 2.7 or
 * earlier has no session and is checked as before, until it expires (12 hours at most).
 * The session id travels with the authentication, as its "details", for the endpoints that act
 * on the current session (sign-out, "Sign out everywhere else", notifications on this device).
 *
 * It never rejects a request itself. With no token, or a bad one, the caller simply stays
 * anonymous; whether an anonymous caller may continue is decided afterwards by the URL
 * rules in SecurityConfig (reading is open, writing is not).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AccountRepository accounts;
    private final Sessions sessions;

    public JwtAuthenticationFilter(JwtService jwtService, AccountRepository accounts, Sessions sessions) {
        this.jwtService = jwtService;
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        JwtService.TokenInfo info = holding(JwtService.extractBearerToken(request.getHeader("Authorization")));
        if (info != null) {
            int accountId = info.accountId();
            // principal = the account id, credentials = none (the token was already checked),
            // authorities = one role. The three-argument constructor marks it as authenticated.
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    accountId, null, AuthorityUtils.createAuthorityList("ROLE_MEMBER"));
            authentication.setDetails(info.sessionId()); // 2.8: null for a token from 2.7 or earlier
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response); // hand the request on to the next filter, and finally the controller
    }

    /** What this token says, or null if the token, the account behind it, or its session does not hold. */
    private JwtService.TokenInfo holding(String token) {
        JwtService.TokenInfo info = jwtService.read(token);
        if (info == null) {
            return null;
        }
        Optional<Account> account = accounts.findById(info.accountId());
        if (account.isEmpty() || info.tokenVersion() != account.get().getTokenVersion()) {
            return null; // the account is gone, or its password was replaced after this token was issued
        }
        if (info.sessionId() != null && !sessions.isLive(info.sessionId(), info.accountId())) {
            return null; // 2.8: signed out, ended from another device, or expired
        }
        return info;
    }
}
