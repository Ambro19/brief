package net.onetechly.brief.unit;

import net.onetechly.brief.entity.AccountToken;
import net.onetechly.brief.repository.AccountTokenRepository;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The account_token table, played by a list. A Mockito mock of the repository is taught to
 * answer from that list, so the unit tests of the link logic need no database and can still
 * look at "what is stored".
 */
final class InMemoryTokens {

    /** The rows, in the order they were saved. */
    final List<AccountToken> rows = new ArrayList<>();
    final AccountTokenRepository repository = mock(AccountTokenRepository.class);

    InMemoryTokens() {
        when(repository.save(any(AccountToken.class))).thenAnswer(call -> {
            AccountToken token = call.getArgument(0);
            if (!rows.contains(token)) {
                rows.add(token);
            }
            return token;
        });
        when(repository.findByTokenHash(anyString())).thenAnswer(call ->
                rows.stream().filter(token -> token.getTokenHash().equals(call.getArgument(0))).findFirst());
        when(repository.findByAccountIdAndPurpose(any(), anyString())).thenAnswer(call -> {
            List<AccountToken> found = new ArrayList<>();
            for (AccountToken token : rows) {
                if (token.getAccountId().equals(call.getArgument(0)) && token.getPurpose().equals(call.getArgument(1))) {
                    found.add(token);
                }
            }
            return found;
        });
        doAnswer(call -> {
            Iterable<AccountToken> toDelete = call.getArgument(0);
            for (AccountToken token : toDelete) {
                rows.remove(token);
            }
            return null;
        }).when(repository).deleteAll(ArgumentMatchers.<Iterable<AccountToken>>any());
    }
}
