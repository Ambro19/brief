package Service;

import DAO.AccountDAO;
import DAO.MessageDAO;
import Model.Account;
import Model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the messages that mention a given account by username.
 *
 * A message mentions "TestUser1" when that name appears in the text as a whole word,
 * in any letter case, with or without a leading @:
 *
 *   "Hey TestUser1, are you there?"   yes
 *   "hey @testuser1"                  yes
 *   "TestUser10 said hi"              no   (a different name)
 *   "myTestUser1"                     no   (part of a longer word)
 *
 * Messages a person wrote themselves never count as mentions of that person.
 *
 * v1.1 trade-off: this reads every message and filters in Java. That is simple and easy
 * to test, and fine for a small board. At scale the mentions should be stored in their own
 * table when a message is saved, so this becomes one indexed query.
 */
public class MentionService {

    private final AccountDAO accountDAO;
    private final MessageDAO messageDAO;

    public MentionService(AccountDAO accountDAO, MessageDAO messageDAO) {
        this.accountDAO = accountDAO;
        this.messageDAO = messageDAO;
    }

    /**
     * Messages by other people that mention this account, oldest first.
     * Returns null if the account does not exist (the controller turns that into 404).
     */
    public List<Message> getMentionsOf(int accountId) {
        Account account = accountDAO.getAccountById(accountId);
        if (account == null) {
            return null;
        }
        List<Message> mentions = new ArrayList<>();
        for (Message message : messageDAO.getAllMessages()) {
            if (message.getPosted_by() != accountId && mentions(message.getMessage_text(), account.getUsername())) {
                mentions.add(message);
            }
        }
        return mentions;
    }

    /** True if {@code text} contains {@code username} as a whole word, ignoring letter case. */
    public static boolean mentions(String text, String username) {
        if (text == null || username == null || username.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = username.toLowerCase(Locale.ROOT);

        int from = 0;
        while (true) {
            int start = haystack.indexOf(needle, from);
            if (start < 0) {
                return false;
            }
            int end = start + needle.length();
            boolean startsCleanly = start == 0 || !isNameCharacter(haystack.charAt(start - 1));
            boolean endsCleanly = end == haystack.length() || !isNameCharacter(haystack.charAt(end));
            if (startsCleanly && endsCleanly) {
                return true;
            }
            from = start + 1;
        }
    }

    /** Characters that can be part of a name: if one touches the match, it is a longer word, not a mention. */
    private static boolean isNameCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
