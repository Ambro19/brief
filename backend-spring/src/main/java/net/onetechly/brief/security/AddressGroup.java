package net.onetechly.brief.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which addresses count as "the same visitor" for the login rate limit (2.3).
 *
 *   IPv4 (203.0.113.7)     the address itself.
 *   IPv6 (2001:db8:1:2::9) the first 64 bits, written 2001:db8:1:2::/64.
 *
 * Why IPv6 is grouped. An internet provider gives one customer not one IPv6 address but a
 * whole block of them, normally a "/64": 18 million million million addresses that all belong
 * to the same line. If each address had its own count, someone guessing passwords would use a
 * new address from their own block for every guess and never reach the limit. Counting by the
 * block closes that door. The price is small: people who share one block (a household, an
 * office) share one count per username, as people behind one IPv4 address always have.
 *
 * Plain Java with its own small parser. java.net.InetAddress would do the same job but may ask
 * the network (DNS) about text it does not recognise, which must never happen here: the text
 * comes from a request header.
 */
public final class AddressGroup {

    private static final int GROUPS_IN_AN_ADDRESS = 8;   // an IPv6 address is 8 groups of 16 bits
    private static final int GROUPS_IN_THE_NETWORK = 4;  // the first 4 groups are the first 64 bits

    private AddressGroup() {
    }

    /**
     * The group an address belongs to. Text that is not an IPv6 address comes back unchanged
     * (trimmed, lower case), so an IPv4 address is its own group. Never null.
     */
    public static String of(String address) {
        if (address == null) {
            return "";
        }
        String text = address.trim().toLowerCase(Locale.ROOT);
        if (text.indexOf(':') < 0) {
            return text;
        }
        int[] groups = ipv6Groups(text);
        if (groups == null) {
            return text; // has a colon but is not an address: leave it as it is
        }
        // An IPv4 address written inside IPv6 (::ffff:203.0.113.7) is still that IPv4 address.
        boolean ipv4Inside = groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0
                && groups[4] == 0 && groups[5] == 0xffff;
        if (ipv4Inside) {
            return (groups[6] >> 8) + "." + (groups[6] & 0xff) + "." + (groups[7] >> 8) + "." + (groups[7] & 0xff);
        }
        StringBuilder network = new StringBuilder();
        for (int i = 0; i < GROUPS_IN_THE_NETWORK; i++) {
            network.append(Integer.toHexString(groups[i])).append(':');
        }
        return network.append(":/64").toString();
    }

    /**
     * The eight 16-bit groups of an IPv6 address, or null if the text is not one.
     * Understands the short form ("::" stands for a run of zero groups), an IPv4 ending
     * (::ffff:203.0.113.7), square brackets, and a zone after "%".
     */
    static int[] ipv6Groups(String text) {
        String s = text;
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int gap = s.indexOf("::");
        if (gap != s.lastIndexOf("::")) {
            return null; // "::" may appear once only
        }
        List<Integer> before = new ArrayList<>();
        List<Integer> after = new ArrayList<>();
        if (gap < 0) {
            if (!readGroups(s, before, true) || before.size() != GROUPS_IN_AN_ADDRESS) {
                return null;
            }
        } else {
            // An IPv4 ending is only allowed at the very end of the address, so only after the "::".
            boolean readable = readGroups(s.substring(0, gap), before, false) && readGroups(s.substring(gap + 2), after, true);
            if (!readable || before.size() + after.size() >= GROUPS_IN_AN_ADDRESS) {
                return null; // "::" must stand for at least one group
            }
        }
        int[] groups = new int[GROUPS_IN_AN_ADDRESS];
        for (int i = 0; i < before.size(); i++) {
            groups[i] = before.get(i);
        }
        for (int i = 0; i < after.size(); i++) {
            groups[GROUPS_IN_AN_ADDRESS - after.size() + i] = after.get(i);
        }
        return groups;
    }

    /** Reads "2001:db8:1" into [0x2001, 0xdb8, 0x1]. An empty text is zero groups. False if it is malformed. */
    private static boolean readGroups(String part, List<Integer> out, boolean mayEndInIpv4) {
        if (part.isEmpty()) {
            return true;
        }
        String[] pieces = part.split(":", -1);
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.indexOf('.') >= 0) {
                // An IPv4 ending fills the last two groups, and can only be the last piece.
                int[] bytes = mayEndInIpv4 && i == pieces.length - 1 ? ipv4Bytes(piece) : null;
                if (bytes == null) {
                    return false;
                }
                out.add((bytes[0] << 8) | bytes[1]);
                out.add((bytes[2] << 8) | bytes[3]);
            } else {
                if (piece.isEmpty() || piece.length() > 4) {
                    return false;
                }
                int value = 0;
                for (int c = 0; c < piece.length(); c++) {
                    int digit = Character.digit(piece.charAt(c), 16);
                    if (digit < 0) {
                        return false;
                    }
                    value = value * 16 + digit;
                }
                out.add(value);
            }
        }
        return true;
    }

    /** The four numbers of "203.0.113.7", or null if the text is not four numbers from 0 to 255. */
    private static int[] ipv4Bytes(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int value = 0;
            for (int c = 0; c < part.length(); c++) {
                char ch = part.charAt(c);
                if (ch < '0' || ch > '9') {
                    return null;
                }
                value = value * 10 + (ch - '0');
            }
            if (value > 255) {
                return null;
            }
            bytes[i] = value;
        }
        return bytes;
    }
}
