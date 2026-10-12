package net.onetechly.brief.config;

import net.onetechly.brief.mail.LogMailer;
import net.onetechly.brief.mail.Mailer;
import net.onetechly.brief.mail.ResendMailer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Decides how the application sends mail (2.4).
 *
 * A method marked {@code @Bean} makes an object for Spring to hand to whoever asks for its
 * type. Here that type is the Mailer interface: the services ask for "a Mailer" and never
 * learn which one they got.
 *
 *   BRIEF_RESEND_API_KEY set:      ResendMailer, real mail.
 *   BRIEF_RESEND_API_KEY not set:  LogMailer, the message is written to the log.
 */
@Configuration
public class MailConfig {

    @Bean
    public Mailer mailer(@Value("${brief.mail.resend-api-key:}") String apiKey,
                         @Value("${brief.mail.from:}") String from,
                         @Value("${brief.mail.resend-url:}") String endpoint) {
        if (apiKey == null || apiKey.isBlank()) {
            return new LogMailer();
        }
        return new ResendMailer(endpoint, apiKey, from);
    }
}
