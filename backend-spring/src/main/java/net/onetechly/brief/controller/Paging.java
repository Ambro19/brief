package net.onetechly.brief.controller;

import net.onetechly.brief.dto.MessagePage;
import net.onetechly.brief.entity.Message;
import org.springframework.http.ResponseEntity;

import java.util.List;

/** Shared by the two controllers that can answer with a page of messages. */
final class Paging {

    /** Response header: "true" if older messages exist beyond this page, otherwise "false". */
    static final String HAS_MORE_HEADER = "X-Has-More";

    private Paging() {
    }

    /** 200 with the page's messages as the JSON body and X-Has-More as a header. */
    static ResponseEntity<List<Message>> respond(MessagePage page) {
        return ResponseEntity.ok()
                .header(HAS_MORE_HEADER, String.valueOf(page.hasMore()))
                .body(page.items());
    }
}
