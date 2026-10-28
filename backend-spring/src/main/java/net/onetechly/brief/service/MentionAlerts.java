package net.onetechly.brief.service;

import net.onetechly.brief.entity.Message;

import java.util.Set;

/**
 * Told when a post newly mentions members (2.8), so that they can be alerted. MessageService
 * calls it after creating or editing a post, with only the members the post did not mention
 * before: correcting a typo in a post does not alert the same people twice.
 *
 * The one implementation is PushNotifications. It is an interface so that MessageService does not
 * depend on how alerts travel, and its unit tests can watch the calls.
 */
public interface MentionAlerts {

    /** Does nothing: for tests, and for code that has no alerts to send. */
    MentionAlerts NONE = (message, accountIds) -> { };

    /** The post {@code message} now mentions these members, who were not mentioned in it before. */
    void mentioned(Message message, Set<Integer> accountIds);
}
