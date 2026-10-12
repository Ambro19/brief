package net.onetechly.brief.mail;

/** One email to send: who it goes to, its subject line, and its text. Plain text only. */
public record Mail(String to, String subject, String text) {
}
