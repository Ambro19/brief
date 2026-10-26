package net.onetechly.brief.repository;

import net.onetechly.brief.entity.EmailBounce;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Data access for addresses that cannot receive mail (2.7). Every method is a derived query. */
public interface EmailBounceRepository extends JpaRepository<EmailBounce, Integer> {

    /** The row for this address, if Resend reported it. Uses the unique index on address. */
    Optional<EmailBounce> findByAddress(String address);

    /** Forgets one address: it was confirmed after all, or its account was deleted. */
    void deleteByAddress(String address);

    /** Forgets every report older than this moment (the 30-day limit the Privacy notice states). */
    void deleteByBouncedAtLessThan(Long before);
}
