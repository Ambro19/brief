package net.onetechly.brief.repository;

import net.onetechly.brief.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Data access for accounts. There is no implementation class: Spring Data writes one at startup.
 *
 * JpaRepository already provides save, findById, existsById, findAll, delete and more.
 * The methods below are "derived queries": Spring reads the method name and builds the SQL.
 * findByUsernameKey becomes  select ... from account where username_key = ?
 */
public interface AccountRepository extends JpaRepository<Account, Integer> {

    /**
     * The account with this name, whatever capitals it was registered with. The argument must
     * be a key as made by {@link Account#keyOf}. The look-up uses the unique index on username_key.
     */
    Optional<Account> findByUsernameKey(String usernameKey);

    /** The account with this email address (2.4). The argument must be in lower case, as it is stored. */
    Optional<Account> findByEmail(String email);

    List<Account> findAllByOrderByAccountIdAsc();

    /**
     * The accounts whose username occurs somewhere inside the given text.
     *
     * A method name cannot express "the column is contained in the parameter", so this query is
     * written out with {@code @Query}. The language is JPQL: it looks like SQL but names the Java
     * class (Account) and field (username), not the table and column. locate(a, b) gives the
     * position of a inside b, or 0 if it is not there.
     *
     * Both sides go through lower(), so the comparison ignores letter case, and one engine (the
     * database) decides what "lower case" means for both.
     *
     * This is a first, coarse filter done by the database: "TestUser1" is also found inside
     * "TestUser10". MentionService then applies the exact whole-word rule to these few rows.
     */
    @Query("select a from Account a where locate(lower(a.username), lower(:text)) > 0")
    List<Account> findByUsernameContainedIn(@Param("text") String text);
}
