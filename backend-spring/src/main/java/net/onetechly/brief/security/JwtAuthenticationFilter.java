package net.onetechly.brief.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.onetechly.brief.entity.Account;
import net.onetechly.brief.repository.AccountRepository;
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
 * It never rejects a request itself. With no token, or a bad one, the caller simply stays
 * anonymous; whether an anonymous caller may continue is decided afterwards by the URL
 * rules in SecurityConfig (reading is open, writing is not).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AccountRepository accounts;

    public JwtAuthenticationFilter(JwtService jwtService, AccountRepository accounts) {
        this.jwtService = jwtService;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Integer accountId = accountOf(JwtService.extractBearerToken(request.getHeader("Authorization")));
        if (accountId != null) {
            // principal = the account id, credentials = none (the token was already checked),
            // authorities = one role. The three-argument constructor marks it as authenticated.
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    accountId, null, AuthorityUtils.createAuthorityList("ROLE_MEMBER"));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response); // hand the request on to the next filter, and finally the controller
    }

    /** The account this token signs in, or null if the token, or the account behind it, does not hold. */
    private Integer accountOf(String token) {
        JwtService.TokenInfo info = jwtService.read(token);
        if (info == null) {
            return null;
        }
        Optional<Account> account = accounts.findById(info.accountId());
        if (account.isEmpty() || info.tokenVersion() != account.get().getTokenVersion()) {
            return null; // the account is gone, or its password was replaced after this token was issued
        }
        return info.accountId();
    }
}
