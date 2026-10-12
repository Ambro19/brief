package net.onetechly.brief.controller;

import net.onetechly.brief.security.BoardAccess;
import net.onetechly.brief.security.EmailPolicy;
import net.onetechly.brief.security.PasswordPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health - a cheap "is the server up" check. The web app uses it for the status dot,
 * and reads from it which form to show:
 *
 *   password_policy  "basic" or "strong": the hint under the password field
 *   email            "required" or "optional": whether creating an account asks for an address (2.4)
 *   board            "open" or "closed": whether an invitation code is asked for, and whether
 *                    posts can be read without signing in (2.4)
 *
 * A server from before 2.4, and the Javalin edition, send neither of the last two. The web
 * app then shows the forms it always had.
 */
@RestController
public class HealthController {

    private final PasswordPolicy passwordPolicy;
    private final EmailPolicy emailPolicy;
    private final BoardAccess board;

    public HealthController(PasswordPolicy passwordPolicy, EmailPolicy emailPolicy, BoardAccess board) {
        this.passwordPolicy = passwordPolicy;
        this.emailPolicy = emailPolicy;
        this.board = board;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>(); // keeps the keys in the order they were added
        body.put("status", "UP");
        body.put("password_policy", passwordPolicy.name());
        body.put("email", emailPolicy.name());
        body.put("board", board.name());
        return body;
    }
}
