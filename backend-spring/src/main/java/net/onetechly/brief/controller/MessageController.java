package net.onetechly.brief.controller;

import jakarta.validation.Valid;
import net.onetechly.brief.dto.MessageRequest;
import net.onetechly.brief.dto.MessageTextRequest;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.security.AuthGuard;
import net.onetechly.brief.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** HTTP endpoints for messages. */
@RestController
public class MessageController {

    private final MessageService messageService;
    private final AuthGuard authGuard;

    public MessageController(MessageService messageService, AuthGuard authGuard) {
        this.messageService = messageService;
        this.authGuard = authGuard;
    }

    /** POST /messages - 200 with the saved message, 400 if it is invalid. */
    @PostMapping("/messages")
    public Message create(@Valid @RequestBody MessageRequest request) {
        authGuard.requireWriteAs(request.postedBy());
        return messageService.create(request);
    }

    /**
     * GET /messages - always 200; an empty list if there are none.
     *
     * Without query parameters: every message, oldest first (the original contract).
     * With ?limit= and/or ?before=: one page, newest first, and an X-Has-More header.
     * 400 if limit is not 1 to 100 or before is not a positive number.
     */
    @GetMapping("/messages")
    public ResponseEntity<List<Message>> getAll(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "before", required = false) Integer before) {
        if (limit == null && before == null) {
            return ResponseEntity.ok(messageService.getAll());
        }
        return Paging.respond(messageService.getPage(null, limit, before));
    }

    /** GET /messages/{messageId} - always 200; the body is empty if there is no such message. */
    @GetMapping("/messages/{messageId}")
    public ResponseEntity<Message> getById(@PathVariable("messageId") int messageId) {
        return okOrEmpty(messageService.findById(messageId));
    }

    /**
     * DELETE /messages/{messageId} - always 200. The body is the deleted message, or empty if it
     * was already gone. DELETE is idempotent: repeating the call changes nothing.
     */
    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Message> delete(@PathVariable("messageId") int messageId) {
        authGuard.requireWriteAs(ownerOf(messageId));
        return okOrEmpty(messageService.delete(messageId));
    }

    /** PATCH /messages/{messageId} - 200 with the full updated message, 400 if the id or the text is invalid. */
    @PatchMapping("/messages/{messageId}")
    public Message update(
            @PathVariable("messageId") int messageId,
            @Valid @RequestBody MessageTextRequest request) {
        authGuard.requireWriteAs(ownerOf(messageId));
        return messageService.updateText(messageId, request.messageText());
    }

    /** The author of a message, or null if the message does not exist (then there is nothing to protect). */
    private Integer ownerOf(int messageId) {
        return messageService.findById(messageId).map(Message::getPostedBy).orElse(null);
    }

    /** 200 with the message as JSON, or 200 with an empty body - the contract's way of saying "nothing there". */
    private static ResponseEntity<Message> okOrEmpty(Optional<Message> message) {
        if (message.isPresent()) {
            return ResponseEntity.ok(message.get());
        }
        return ResponseEntity.ok().build();
    }
}
