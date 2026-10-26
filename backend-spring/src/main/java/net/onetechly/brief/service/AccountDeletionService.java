package net.onetechly.brief.service;

import net.onetechly.brief.entity.Account;
import net.onetechly.brief.entity.Message;
import net.onetechly.brief.exception.BadRequestException;
import net.onetechly.brief.exception.ForbiddenException;
import net.onetechly.brief.exception.NotFoundException;
import net.onetechly.brief.repository.AccountRepository;
import net.onetechly.brief.repository.InvitationRepository;
import net.onetechly.brief.repository.MessageRepository;
import net.onetechly.brief.security.BoardOwner;
import net.onetechly.brief.util.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Deleting an account (2.5), at its owner's request.
 *
 * What goes, and how:
 *   the account row                    deleted here
 *   its posts                          deleted here, before the account (a post must have an author)
 *   mention rows of those posts,
 *   and rows that mention the member   deleted by the database (on delete cascade, V2)
 *   its emailed links (account_token)  deleted by the database (on delete cascade, V3)
 *   invitations it sent                deleted by the database (on delete cascade, V4)
 *   a bounce report on its address     deleted here (2.7)
 *   invitations it was created with    deleted here: a mailed one holds the member's address
 *                                      (until the review of 2.5.0 the row stayed, with used_by
 *                                      emptied by "on delete set null", and kept the address)
 *   its login tokens                   refused from now on: the filter finds no account
 *
 * Nothing is kept "deactivated": a deleted account is gone, and its name and address are free.
 * Posts by others that mention the member keep their text; the name in them is just a word now.
 */
@Service
public class AccountDeletionService {

    private final AccountRepository accounts;
    private final MessageRepository messages;
    private final InvitationRepository invitations;
    private final EmailBounces bounces;
    private final BoardOwner owner;

    public AccountDeletionService(AccountRepository accounts, MessageRepository messages,
                                  InvitationRepository invitations, EmailBounces bounces, BoardOwner owner) {
        this.accounts = accounts;
        this.messages = messages;
        this.invitations = invitations;
        this.bounces = bounces;
        this.owner = owner;
    }

    /**
     * Deletes the account and everything that belongs to it. 404 if there is no such account;
     * 403 if the password is wrong (not 401: the caller IS signed in, and a web app that sees
     * 401 signs its member out); 400 for the board owner, who must hand the board over first.
     *
     * {@code @Transactional}: the posts, the invitation and the account go together or not at all.
     */
    @Transactional
    public void delete(int accountId, String password) {
        Account account = accounts.findById(accountId).orElseThrow(NotFoundException::new);
        if (!passwordMatches(account, password)) {
            throw new ForbiddenException();
        }
        if (owner.isOwner(account)) {
            throw new BadRequestException(); // a closed board without its owner could never invite anyone again
        }
        List<Message> posts = messages.findByPostedByOrderByMessageIdAsc(accountId);
        if (!posts.isEmpty()) {
            messages.deleteAll(posts);
        }
        invitations.deleteByUsedBy(accountId); // the invitation this member joined with, and the address in it
        if (account.getEmail() != null) {
            bounces.forget(account.getEmail()); // 2.7: a bounce report about the address goes too
        }
        accounts.delete(account);
    }

    private static boolean passwordMatches(Account account, String password) {
        if (password == null) {
            return false;
        }
        String stored = account.getPassword();
        return PasswordHasher.isHashed(stored)
                ? PasswordHasher.verify(password, stored)
                : PasswordHasher.constantTimeEquals(stored, password); // a row from before hashing existed
    }
}
