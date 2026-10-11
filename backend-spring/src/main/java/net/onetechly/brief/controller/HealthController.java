package net.onetechly.brief.controller;

import net.onetechly.brief.security.PasswordPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health - a cheap "is the server up" check. The web app uses it for the status dot,
 * and reads password_policy ("basic" or "strong") to show the right hint when registering.
 */
@RestController
public class HealthController {

    private final PasswordPolicy passwordPolicy;

    public HealthController(PasswordPolicy passwordPolicy) {
        this.passwordPolicy = passwordPolicy;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>(); // keeps the keys in the order they were added
        body.put("status", "UP");
        body.put("password_policy", passwordPolicy.name());
        return body;
    }
}
