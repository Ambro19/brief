package net.onetechly.brief.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs once at the start of every request, before any controller.
 *
 * It looks for "Authorization: Bearer <token>". If the token is valid, it records who the
 * caller is in Spring Security's SecurityContext for the rest of this request.
 *
 * It never rejects a request itself. With no token, or a bad one, the caller simply stays
 * anonymous; whether an anonymous caller may continue is decided afterwards by the URL
 * rules in SecurityConfig (reading is open, writing is not).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Integer accountId = jwtService.resolve(JwtService.extractBearerToken(request.getHeader("Authorization")));
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
}
