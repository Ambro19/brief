package net.onetechly.brief.unit;

import net.onetechly.brief.security.AddressGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Which addresses the login rate limit treats as the same visitor. */
class AddressGroupTest {

    @Test
    void anIpv4AddressIsItsOwnGroup() {
        assertEquals("203.0.113.7", AddressGroup.of("203.0.113.7"));
        assertEquals("203.0.113.7", AddressGroup.of("  203.0.113.7 "));
        assertNotEquals(AddressGroup.of("203.0.113.7"), AddressGroup.of("203.0.113.8"));
    }

    @Test
    void ipv6AddressesInTheSameBlockOf64BitsAreOneGroup() {
        String group = AddressGroup.of("2001:db8:12:34:1111:2222:3333:4444");
        assertEquals("2001:db8:12:34::/64", group);
        // Every other address the same line could use lands in the same group.
        assertEquals(group, AddressGroup.of("2001:db8:12:34::1"));
        assertEquals(group, AddressGroup.of("2001:0DB8:0012:0034:ffff:ffff:ffff:ffff"));
        assertEquals(group, AddressGroup.of("[2001:db8:12:34::9]"));
        assertEquals(group, AddressGroup.of("2001:db8:12:34::9%eth0"));
    }

    @Test
    void ipv6AddressesInDifferentBlocksAreDifferentGroups() {
        assertNotEquals(AddressGroup.of("2001:db8:12:34::1"), AddressGroup.of("2001:db8:12:35::1"));
        assertNotEquals(AddressGroup.of("2001:db8:12:34::1"), AddressGroup.of("2001:db9:12:34::1"));
    }

    @Test
    void theShortFormIsExpandedCorrectly() {
        assertEquals("2001:db8:0:0::/64", AddressGroup.of("2001:db8::1"));
        assertEquals("0:0:0:0::/64", AddressGroup.of("::1"));
        assertEquals("0:0:0:0::/64", AddressGroup.of("::"));
        assertEquals("fe80:0:0:0::/64", AddressGroup.of("fe80::a:b:c:d"));
        assertEquals("2001:db8:1:2::/64", AddressGroup.of("2001:db8:1:2:3:4:5::"));
        assertEquals("0:0:0:1::/64", AddressGroup.of("::1:2:3:4:5"));
    }

    @Test
    void anIpv4AddressWrittenInsideIpv6IsStillThatIpv4Address() {
        assertEquals("203.0.113.7", AddressGroup.of("::ffff:203.0.113.7"));
        assertEquals("203.0.113.7", AddressGroup.of("::ffff:cb00:7107"));
        assertEquals("127.0.0.1", AddressGroup.of("0:0:0:0:0:ffff:7f00:1"));
    }

    @Test
    void textThatIsNotAnAddressComesBackUnchangedAndNeverCausesAnError() {
        assertEquals("", AddressGroup.of(null));
        assertEquals("", AddressGroup.of("  "));
        assertEquals("not-an-address", AddressGroup.of("Not-An-Address"));
        for (String odd : new String[] {":", ":::", "1::2::3", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "12345::1", "g::1",
                "::1.2.3", "::1.2.3.256", "1.2.3.4::1", "-1::2", "1:2:3:4:5:6:7::8", "::ffff:1.2.3.4.5"}) {
            assertEquals(odd, AddressGroup.of(odd), odd);
        }
    }
}
