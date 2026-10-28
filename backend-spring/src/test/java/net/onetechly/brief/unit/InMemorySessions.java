package net.onetechly.brief.unit;

import net.onetechly.brief.entity.AccountSession;
import net.onetechly.brief.repository.AccountSessionRepository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The account_session table (2.8), played by a list, as InMemoryTokens does for account_token. A
 * Mockito mock of the repository answers from the list, so the session logic can be tested
 * without a database, and the tests can look at what is stored.
 */
final class InMemorySessions {

    final List<AccountSession> rows = new ArrayList<>();
    final AccountSessionRepository repository = mock(AccountSessionRepository.class);
    /** How many times touch() changed a row: the "one write an hour" the class promises. */
    int touches;

    InMemorySessions() {
        when(repository.save(any(AccountSession.class))).thenAnswer(call -> {
            AccountSession session = call.getArgument(0);
            if (session.getSessionId() == null) {
                set(session, "sessionId", rows.stream().mapToInt(AccountSession::getSessionId).max().orElse(0) + 1);
            }
            if (!rows.contains(session)) {
                rows.add(session);
            }
            return session;
        });
        when(repository.findById(anyInt())).thenAnswer(call ->
                rows.stream().filter(s -> s.getSessionId().equals(call.getArgument(0))).findFirst());
        when(repository.findByAccountIdOrderBySessionIdDesc(any())).thenAnswer(call -> {
            List<AccountSession> found = new ArrayList<>();
            for (AccountSession session : rows) {
                if (session.getAccountId().equals(call.getArgument(0))) {
                    found.add(session);
                }
            }
            found.sort(Comparator.comparing(AccountSession::getSessionId).reversed());
            return found;
        });
        doAnswer(call -> rows.remove((AccountSession) call.getArgument(0))).when(repository).delete(any(AccountSession.class));
        doAnswer(call -> {
            List<AccountSession> gone = new ArrayList<>();
            ((Iterable<?>) call.getArgument(0)).forEach(s -> gone.add((AccountSession) s));
            rows.removeAll(gone);
            return null;
        }).when(repository).deleteAll(any(Iterable.class));
        doAnswer(call -> rows.removeIf(s -> s.getExpiresAt() <= (Long) call.getArgument(0)))
                .when(repository).deleteByExpiresAtLessThanEqual(anyLong());
        // touch: the conditional UPDATE, with its WHERE clause played out in Java
        when(repository.touch(any(), any(), any(), any())).thenAnswer(call -> {
            Optional<AccountSession> found = rows.stream().filter(s -> s.getSessionId().equals(call.getArgument(0))).findFirst();
            long now = call.getArgument(1);
            if (found.isPresent() && found.get().getLastUsedAt() < (Long) call.getArgument(3) && found.get().getExpiresAt() > now) {
                set(found.get(), "lastUsedAt", now);
                set(found.get(), "expiresAt", call.getArgument(2));
                touches++;
                return 1;
            }
            return 0;
        });
    }

    AccountSession byId(int id) {
        return rows.stream().filter(s -> s.getSessionId() == id).findFirst().orElse(null);
    }

    static void set(AccountSession session, String field, Object value) {
        try {
            Field f = AccountSession.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(session, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
