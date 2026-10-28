package net.onetechly.brief.repository;

import net.onetechly.brief.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Data access for push subscriptions (2.8). Callers run inside a transaction (service/PushNotifications). */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Integer> {

    /** Every device on which a member turned notifications on. Uses the index on account_id. */
    List<PushSubscription> findByAccountId(Integer accountId);

    /** The subscriptions of one session (in practice none or one). */
    List<PushSubscription> findBySessionId(Integer sessionId);

    /** The row for this browser address, if any. Uses the unique index on endpoint. */
    Optional<PushSubscription> findByEndpoint(String endpoint);

    long countByAccountId(Integer accountId);
}
