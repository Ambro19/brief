package net.onetechly.brief.unit;

import net.onetechly.brief.entity.EmailBounce;
import net.onetechly.brief.exception.UndeliverableAddressException;
import net.onetechly.brief.repository.EmailBounceRepository;
import net.onetechly.brief.service.EmailBounces;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What Brief! remembers about addresses that bounce (2.7), and for how long. */
class EmailBouncesTest {

    private final AtomicLong now = new AtomicLong(10_000_000);
    private final EmailBounceRepository table = mock(EmailBounceRepository.class);
    private final EmailBounces bounces = new EmailBounces(table, now::get);

    @Test
    void addressesAreComparedInLowerCaseShownThirtyDaysAndBlockedOne() {
        when(table.findByAddress("maya@gmail.example")).thenReturn(Optional.of(
                new EmailBounce("maya@gmail.example", EmailBounce.BOUNCED, null, now.get())));
        assertTrue(bounces.isBounced("  Maya@Gmail.EXAMPLE "));
        assertTrue(bounces.isBlocked("maya@gmail.example"));
        assertThrows(UndeliverableAddressException.class, () -> bounces.allowSending("maya@gmail.example"));
        assertFalse(bounces.isBounced("other@gmail.example"));
        assertFalse(bounces.isBounced(null));
        assertFalse(bounces.isBounced("not an address"));

        now.addAndGet(EmailBounces.BLOCK_SECONDS);
        assertThrows(UndeliverableAddressException.class, () -> bounces.allowSending("maya@gmail.example"), "the whole first day");
        now.addAndGet(1);
        assertFalse(bounces.isBlocked("maya@gmail.example"));
        assertTrue(bounces.isBounced("maya@gmail.example"), "still shown");
        bounces.allowSending("maya@gmail.example");
        verify(table).deleteByAddress("maya@gmail.example"); // one more try clears the report

        now.addAndGet(EmailBounces.KEEP_SECONDS);
        assertFalse(bounces.isBounced("maya@gmail.example"), "after 30 days nothing, even before the row is deleted");
    }

    @Test
    void oldReportsArePurgedAtMostOnceAnHour() {
        bounces.purgeOld();
        bounces.purgeOld();
        verify(table, times(1)).deleteByBouncedAtLessThan(now.get() - EmailBounces.KEEP_SECONDS);
        now.addAndGet(60 * 60);
        bounces.purgeOld();
        verify(table).deleteByBouncedAtLessThan(now.get() - EmailBounces.KEEP_SECONDS);
    }

    @Test
    void recordingStoresALowerCaseAddressAndAShortReason() {
        bounces.record("Maya@Gmail.example", EmailBounce.BOUNCED, "x".repeat(1000));
        ArgumentCaptor<EmailBounce> saved = ArgumentCaptor.forClass(EmailBounce.class);
        verify(table).save(saved.capture());
        assertEquals("maya@gmail.example", saved.getValue().getAddress());
        assertEquals(300, saved.getValue().getReason().length());
        assertEquals(now.get(), saved.getValue().getBouncedAt());
    }

    @Test
    void aSecondReportUpdatesTheFirst() {
        EmailBounce first = new EmailBounce("maya@gmail.example", EmailBounce.BOUNCED, "first", now.get() - 100);
        when(table.findByAddress("maya@gmail.example")).thenReturn(Optional.of(first));
        bounces.record("maya@gmail.example", EmailBounce.SUPPRESSED, null);
        verify(table).save(first);
        assertEquals(EmailBounce.SUPPRESSED, first.getKind());
        assertEquals(now.get(), first.getBouncedAt());
    }

    @Test
    void textThatIsNotAnAddressIsIgnored() {
        bounces.record("not an address", EmailBounce.BOUNCED, null);
        bounces.forget(null);
        verify(table, never()).save(any());
        verify(table, never()).deleteByAddress(any());
    }
}
