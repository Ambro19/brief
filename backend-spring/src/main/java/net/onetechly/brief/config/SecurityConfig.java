package net.onetechly.brief.config;

import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.security.AuthMode;
import net.onetechly.brief.security.BoardAccess;
import net.onetechly.brief.security.JwtAuthenticationFilter;
import net.onetechly.brief.security.JwtService;
import net.onetechly.brief.service.Sessions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security set-up.
 *
 * Spring Security works as a chain of servlet filters that every request passes through
 * before it reaches a controller. This class describes that chain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, AuthMode authMode,
                                                   BoardAccess board, AccountRepository accounts,
                                                   Sessions sessions) throws Exception {
        http
                // CSRF protection defends cookie-based logins. This API has no cookies: the token
                // travels in a header that a foreign website cannot add, so the defence is not needed.
                .csrf(csrf -> csrf.disable())
                // Spring Security's own POST /logout handler would answer before AccountController does.
                .logout(logout -> logout.disable())
                // Stateless: no server-side HTTP session. Every request proves itself with its token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The API contract says errors have an empty body, so answer with the bare status code.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.FORBIDDEN.value())))
                // Which requests need a signed-in caller. Rules are checked top to bottom; first match wins.
                .authorizeHttpRequests(requests -> {
                    if (authMode.isStrict()) {
                        requests.requestMatchers(HttpMethod.POST, "/messages").authenticated();
                        requests.requestMatchers(HttpMethod.PATCH, "/messages/*").authenticated();
                        requests.requestMatchers(HttpMethod.DELETE, "/messages/*").authenticated();
                    }
                    if (board.isClosed()) {
                        // A closed board (2.4): what members wrote, and who they are, is for members.
                        // Listed one by one, so that adding an endpoint is a decision and not an accident.
                        // HEAD as well as GET: Spring answers a HEAD request with the headers of the GET
                        // (the size of the list, whether there is more), which is not for visitors either.
                        for (HttpMethod reading : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD}) {
                            requests.requestMatchers(reading,
                                    "/messages", "/messages/*",
                                    "/accounts", "/accounts/*", "/accounts/*/messages", "/accounts/*/mentions")
                                    .authenticated();
                        }
                    }
                    // Everything else: register, login, the email links, /health, the web app, the docs.
                    // On an open board that includes reading.
                    requests.anyRequest().permitAll();
                })
                // Read the token before Spring Security decides whether the caller is signed in.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, accounts, sessions),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
