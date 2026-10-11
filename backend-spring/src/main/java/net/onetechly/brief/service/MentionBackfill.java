package net.onetechly.brief.service;

import net.onetechly.brief.repository.MentionRepository;
import net.onetechly.brief.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once each time the application has started.
 *
 * A database that was created before 2.2 has messages but an empty mention table. This
 * fills the table from those messages, so nobody's mentions disappear with the upgrade.
 *
 * The test "no mention rows at all" is deliberately simple. On a board where truly nobody
 * has ever been mentioned it repeats the work at every start, which changes nothing and,
 * for a few thousand messages, costs a moment. A larger system would do the work in
 * batches and record that it has run.
 *
 * Why this is not one of the Flyway migrations (2.3): Flyway changes the SHAPE of the
 * database with SQL. Working out who a post mentions needs the whole-word rule in
 * MentionService, which is Java. So Flyway creates the table (it runs first), and this
 * class fills it once the application is up.
 *
 * If it fails, the application still starts, which is better than no application at all.
 * Mentions of old posts are then missing. The next start tries again only if the mention
 * table is still empty; otherwise MentionService.rebuildAll() has to be run once by hand.
 */
@Component
public class MentionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MentionBackfill.class);

    private final MentionService mentionService;
    private final MentionRepository mentions;
    private final MessageRepository messages;

    public MentionBackfill(MentionService mentionService, MentionRepository mentions, MessageRepository messages) {
        this.mentionService = mentionService;
        this.mentions = mentions;
        this.messages = messages;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (mentions.count() == 0 && messages.count() > 0) {
                int looked = mentionService.rebuildAll();
                log.info("Mention table was empty: indexed the mentions of {} existing messages ({} found).",
                        looked, mentions.count());
            }
        } catch (RuntimeException e) {
            log.error("Could not index the mentions of existing messages. Starting anyway.", e);
        }
    }
}
