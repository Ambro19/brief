package net.onetechly.brief.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onetechly.brief.entity.EmailBounce;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.exception.UnauthorizedException;
import net.onetechly.brief.mail.ResendWebhookVerifier;
import net.onetechly.brief.service.EmailBounces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * POST /webhooks/resend (2.7): Resend tells Brief! what happened to a mail after it left.
 *
 * Brief! acts on two events (chosen when the webhook is added in Resend's dashboard):
 *   email.bounced     the receiving server refused the mail. Only "Permanent" bounces count; a
 *                     "Temporary" one (a full mailbox, a server that is down) is retried by
 *                     Resend and ignored here.
 *   email.suppressed  Resend did not send it, because the address bounced or complained before.
 * Every other event is acknowledged (200) and ignored, so adding events in the dashboard never
 * makes Resend retry. email.complained (marked as spam) was acted on in the first draft of 2.7;
 * the independent review pointed out that it blocked confirmation and password mail to the
 * person who complained. Resend suppresses such an address itself, and that arrives as
 * email.suppressed the next time Brief! mails it.
 *
 * Answers:
 *   404  BRIEF_RESEND_WEBHOOK_SECRET is not set: the endpoint does not exist for this server.
 *   401  the signature headers are missing, or the signature is wrong or more than five minutes old.
 *   413  the body is larger than 256 KB (Resend's events are a few hundred bytes).
 *   400  signed, but not the JSON Resend sends.
 *   200  recorded, nothing to record, or a message already handled (same svix-id).
 * Resend retries anything but 200 for about a day, so a short outage of Brief! loses nothing.
 *
 * No login token: Resend cannot sign in. The signature is the authentication. A call without the
 * three headers is refused before its body is read, and a body is read at most to 256 KB, so a
 * large unsigned upload cannot fill the server's memory (independent review of 2.7.0).
 */
@RestController
public class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Larger bodies are refused unread past this point. */
    static final int MAX_BODY_BYTES = 256 * 1024;
    /** svix-ids handled in the last minutes: a call replayed inside the five-minute window does nothing. */
    private static final int SEEN_IDS = 1000;
    private final Map<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_IDS;
        }
    };

    private final ResendWebhookVerifier verifier;
    private final EmailBounces bounces;
    /** "@briefmessage.net" from BRIEF_MAIL_FROM: reports about mail from other senders on the same Resend account are ignored. */
    private final String senderDomain;

    public ResendWebhookController(ResendWebhookVerifier verifier, EmailBounces bounces,
                                   @Value("${brief.mail.from:}") String from) {
        this.verifier = verifier;
        this.bounces = bounces;
        this.senderDomain = domainOf(from);
    }

    /**
     * The domain of a sender, with its "@": "Brief! <hello@briefmessage.net>" and
     * "hello@briefmessage.net" both give "@briefmessage.net". The address inside &lt;...&gt; counts,
     * not a display name that happens to contain an @. Null if there is no address.
     */
    static String domainOf(String from) {
        if (from == null) {
            return null;
        }
        String address = from.trim();
        int open = address.lastIndexOf('<');
        int close = address.lastIndexOf('>');
        if (open >= 0 && close > open) {
            address = address.substring(open + 1, close);
        }
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        return address.substring(at).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The body is read from the request as bytes, exactly as it arrived (the signature covers them;
     * parsing first and printing again could change spaces or the order of fields), and only after
     * the three headers are there, and only up to MAX_BODY_BYTES.
     */
    @PostMapping("/webhooks/resend")
    public ResponseEntity<Void> receive(@RequestHeader(value = "svix-id", required = false) String id,
                                        @RequestHeader(value = "svix-timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "svix-signature", required = false) String signature,
                                        HttpServletRequest request) throws IOException {
        if (!verifier.isConfigured()) {
            throw new NotFoundException();
        }
        if (id == null || timestamp == null || signature == null) {
            throw new UnauthorizedException();
        }
        byte[] bytes = readAtMost(request.getInputStream(), MAX_BODY_BYTES);
        if (bytes == null) {
            return ResponseEntity.status(413).build();
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (!verifier.verify(id, timestamp, signature, body)) {
            throw new UnauthorizedException();
        }
        synchronized (seen) {
            if (seen.containsKey(id)) {
                return ResponseEntity.ok().build(); // handled before: a replay, or Resend retrying after a lost answer
            }
        }
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        if (event == null || !event.isObject()) {
            return ResponseEntity.badRequest().build();
        }
        String type = event.path("type").asText("");
        JsonNode data = event.path("data");
        String kind = kindOf(type, data);
        if (kind == null || !fromThisSite(data)) {
            bounces.purgeOld();
            return ResponseEntity.ok().build();
        }
        String reason = data.path("bounce").path("message").asText(null);
        for (JsonNode to : data.path("to")) {
            if (to.isTextual()) {
                bounces.record(to.asText(), kind, reason);
            }
        }
        bounces.purgeOld();
        synchronized (seen) {
            seen.put(id, Boolean.TRUE); // only once handled: a call answered 409 must work when Resend sends it again
        }
        // The address is personal data: the log names the event, never the recipient.
        log.info("Resend reported {} ({}) for {} recipient(s).", type, kind, data.path("to").size());
        return ResponseEntity.ok().build();
    }

    /** The whole stream, or null if it is longer than max bytes. */
    static byte[] readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** What the event means for Brief!, or null if it means nothing. */
    private static String kindOf(String type, JsonNode data) {
        switch (type) {
            case "email.bounced": {
                String bounceType = data.path("bounce").path("type").asText("Permanent");
                if (!"permanent".equalsIgnoreCase(bounceType)) {
                    return null; // temporary: Resend keeps trying, and it may still arrive
                }
                String subType = data.path("bounce").path("subType").asText("");
                return "suppressed".equalsIgnoreCase(subType) ? EmailBounce.SUPPRESSED : EmailBounce.BOUNCED;
            }
            case "email.suppressed":
                return EmailBounce.SUPPRESSED;
            default:
                return null;
        }
    }

    /**
     * True if the mail was sent from this site's domain. When BRIEF_MAIL_FROM names no domain every
     * report counts; when it does, a report without a sender does not.
     */
    private boolean fromThisSite(JsonNode data) {
        if (senderDomain == null) {
            return true;
        }
        return senderDomain.equals(domainOf(data.path("from").asText(null)));
    }
}
