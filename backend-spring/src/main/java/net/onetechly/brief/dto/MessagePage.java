package net.onetechly.brief.dto;

import net.onetechly.brief.entity.Message;

import java.util.List;

/**
 * One page of messages, newest first, plus whether older messages exist beyond it.
 * This never becomes JSON as a whole: the controller sends the items as the body and
 * hasMore as the X-Has-More header, so the body stays a plain list like every other list.
 */
public record MessagePage(List<Message> items, boolean hasMore) {
}
