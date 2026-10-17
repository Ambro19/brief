package net.onetechly.brief.dto;

/**
 * A new invitation that is not mailed (2.5): the inviter passes the link on by other means, a
 * text message for instance. This is the only time the link is shown; it is not stored.
 */
public record InvitationLink(String link, InvitationView invitation) {
}
