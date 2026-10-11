package Controller;

import DAO.AccountDAO;
import DAO.DataAccessException;
import DAO.MessageDAO;
import DTO.PublicAccount;
import Model.Account;
import Model.Message;
import Service.AccountService;
import Service.MentionService;
import Service.MessageService;
import Service.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * HTTP layer: turns requests into service calls and service results into status codes.
 * No SQL and no business rules live here.
 *
 * Two modes:
 *
 *   compat (default, new SocialMediaController())
 *     Exactly the training contract. Anyone may post, edit or delete; the client says
 *     who the author is. The original test suite runs in this mode.
 *
 *   strict (new SocialMediaController(true), used by Main)
 *     Write operations need a login token, and you may only post as yourself and only
 *     edit or delete your own messages. This is what the web app runs against.
 *
 * Error responses have an empty body in both modes, as the original contract requires;
 * the status code carries the meaning.
 */
public class SocialMediaController {

    /** Response header that carries the login token after a successful POST /login. */
    public static final String TOKEN_HEADER = "X-Auth-Token";

    private static final Logger log = LoggerFactory.getLogger(SocialMediaController.class);

    private final AccountService accountService;
    private final MessageService messageService;
    private final SessionService sessionService;
    private final MentionService mentionService;
    private final boolean strictAuth;
    private final ObjectMapper mapper;

    public SocialMediaController() {
        this(false);
    }

    public SocialMediaController(boolean strictAuth) {
        this(new AccountService(new AccountDAO()), new MessageService(new MessageDAO()),
                new SessionService(), new MentionService(new AccountDAO(), new MessageDAO()), strictAuth);
    }

    public SocialMediaController(AccountService accountService, MessageService messageService,
                                 SessionService sessionService, MentionService mentionService,
                                 boolean strictAuth) {
        this.accountService = accountService;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.mentionService = mentionService;
        this.strictAuth = strictAuth;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Builds the Javalin app with every endpoint registered. The caller starts it
     * (the tests start it on their own port; Main starts it on 8080).
     */
    public Javalin startAPI() {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // Serve the built web app from the same server, if it has been built into the jar.
            if (SocialMediaController.class.getResource("/public/index.html") != null) {
                config.staticFiles.add("/public", Location.CLASSPATH);
            }
        });

        // --- Accounts ---
        app.post("/register", this::registerHandler);
        app.post("/login", this::loginHandler);
        app.post("/logout", this::logoutHandler);
        app.get("/accounts", this::getAllAccountsHandler);
        app.get("/accounts/{account_id}", this::getAccountHandler);
        app.get("/accounts/{account_id}/messages", this::getMessagesByAccountHandler);
        app.get("/accounts/{account_id}/mentions", this::getMentionsHandler);

        // --- Messages ---
        app.post("/messages", this::createMessageHandler);
        app.get("/messages", this::getAllMessagesHandler);
        app.get("/messages/{message_id}", this::getMessageByIdHandler);
        app.delete("/messages/{message_id}", this::deleteMessageHandler);
        app.patch("/messages/{message_id}", this::updateMessageHandler);

        // --- Operations ---
        app.get("/health", ctx -> ctx.json(Collections.singletonMap("status", "UP")));

        // --- Errors: bad input is the client's fault (400), database trouble is ours (500) ---
        app.exception(JsonProcessingException.class, (e, ctx) -> ctx.status(400));
        app.exception(NumberFormatException.class, (e, ctx) -> ctx.status(400));
        app.exception(DataAccessException.class, (e, ctx) -> {
            log.error("Database error on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500);
        });

        return app;
    }

    // ------------------------------------------------------------------ accounts

    /** POST /register - 200 with the new account, 400 if the input is invalid or the username is taken. */
    private void registerHandler(Context ctx) throws JsonProcessingException {
        Account request = mapper.readValue(ctx.body(), Account.class);
        Account created = accountService.registerAccount(request);
        if (created == null) {
            ctx.status(400);
            return;
        }
        ctx.json(created);
    }

    /** POST /login - 200 with the account and a token header, 401 if the credentials do not match. */
    private void loginHandler(Context ctx) throws JsonProcessingException {
        Account request = mapper.readValue(ctx.body(), Account.class);
        Account account = accountService.login(request);
        if (account == null) {
            ctx.status(401);
            return;
        }
        ctx.header(TOKEN_HEADER, sessionService.issue(account.getAccount_id()));
        ctx.json(account);
    }

    /** POST /logout - always 200. Ending a session that is already gone is not an error. */
    private void logoutHandler(Context ctx) {
        sessionService.revoke(SessionService.extractBearerToken(ctx.header("Authorization")));
        ctx.status(200);
    }

    /** GET /accounts - everyone who has an account (id and username only). */
    private void getAllAccountsHandler(Context ctx) {
        ctx.json(accountService.getAllPublicAccounts());
    }

    /** GET /accounts/{account_id} - 200 with id and username, 404 if there is no such account. */
    private void getAccountHandler(Context ctx) {
        int accountId = Integer.parseInt(ctx.pathParam("account_id"));
        PublicAccount account = accountService.getPublicAccount(accountId);
        if (account == null) {
            ctx.status(404);
            return;
        }
        ctx.json(account);
    }

    /** GET /accounts/{account_id}/messages - always 200; an empty list if the user has no messages. */
    private void getMessagesByAccountHandler(Context ctx) {
        int accountId = Integer.parseInt(ctx.pathParam("account_id"));
        ctx.json(messageService.getMessagesByUser(accountId));
    }

    /**
     * GET /accounts/{account_id}/mentions - 200 with the messages by other people that mention
     * this account's username (an empty list if there are none), 404 if there is no such account.
     */
    private void getMentionsHandler(Context ctx) {
        int accountId = Integer.parseInt(ctx.pathParam("account_id"));
        List<Message> mentions = mentionService.getMentionsOf(accountId);
        if (mentions == null) {
            ctx.status(404);
            return;
        }
        ctx.json(mentions);
    }

    // ------------------------------------------------------------------ messages

    /** POST /messages - 200 with the saved message, 400 if it is invalid. */
    private void createMessageHandler(Context ctx) throws JsonProcessingException {
        Message request = mapper.readValue(ctx.body(), Message.class);
        if (!allowWriteAs(ctx, request.getPosted_by())) {
            return;
        }
        if (request.getTime_posted_epoch() <= 0) {
            request.setTime_posted_epoch(System.currentTimeMillis() / 1000);
        }
        Message created = messageService.createMessage(request);
        if (created == null) {
            ctx.status(400);
            return;
        }
        ctx.json(created);
    }

    /** GET /messages - always 200; an empty list if there are none. */
    private void getAllMessagesHandler(Context ctx) {
        ctx.json(messageService.getAllMessages());
    }

    /** GET /messages/{message_id} - always 200; the body is empty if there is no such message. */
    private void getMessageByIdHandler(Context ctx) {
        int messageId = Integer.parseInt(ctx.pathParam("message_id"));
        Message message = messageService.getMessageById(messageId);
        if (message != null) {
            ctx.json(message);
        } else {
            ctx.status(200);
        }
    }

    /**
     * DELETE /messages/{message_id} - always 200. The body is the deleted message, or empty
     * if it was already gone. DELETE is idempotent: repeating the call changes nothing.
     */
    private void deleteMessageHandler(Context ctx) {
        int messageId = Integer.parseInt(ctx.pathParam("message_id"));
        if (strictAuth) {
            Message existing = messageService.getMessageById(messageId);
            Integer owner = existing == null ? null : existing.getPosted_by();
            if (!allowWriteAs(ctx, owner)) {
                return;
            }
        }
        Message deleted = messageService.deleteMessage(messageId);
        if (deleted != null) {
            ctx.json(deleted);
        } else {
            ctx.status(200);
        }
    }

    /** PATCH /messages/{message_id} - 200 with the full updated message, 400 if the id or the text is invalid. */
    private void updateMessageHandler(Context ctx) throws JsonProcessingException {
        int messageId = Integer.parseInt(ctx.pathParam("message_id"));
        Message request = mapper.readValue(ctx.body(), Message.class);
        if (strictAuth) {
            Message existing = messageService.getMessageById(messageId);
            Integer owner = existing == null ? null : existing.getPosted_by();
            if (!allowWriteAs(ctx, owner)) {
                return;
            }
        }
        Message updated = messageService.updateMessage(messageId, request);
        if (updated == null) {
            ctx.status(400);
            return;
        }
        ctx.json(updated);
    }

    // ------------------------------------------------------------------ authorization

    /**
     * Decides whether this request may write on behalf of {@code ownerId}.
     *
     * compat mode: always yes.
     * strict mode: the caller must be signed in (else 401), and if an owner is known
     * the caller must be that owner (else 403). A null owner means "there is nothing
     * to protect" - for example deleting a message that does not exist.
     *
     * Returns false after setting the response status when the request must stop.
     */
    private boolean allowWriteAs(Context ctx, Integer ownerId) {
        if (!strictAuth) {
            return true;
        }
        Integer callerId = sessionService.resolveAuthorizationHeader(ctx.header("Authorization"));
        if (callerId == null) {
            ctx.status(401);
            return false;
        }
        if (ownerId != null && !ownerId.equals(callerId)) {
            ctx.status(403);
            return false;
        }
        return true;
    }
}
