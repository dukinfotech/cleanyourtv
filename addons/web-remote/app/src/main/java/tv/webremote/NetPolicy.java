package tv.webremote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * Who may talk to the server. Only the home network, this device and Tailscale's range: a connection from anywhere else
 * (a router that forwards the port to the internet, say) is dropped before a byte is read. The Host header must be an
 * address, not a name an attacker controls, which is what closes the DNS-rebinding hole.
 */
final class NetPolicy {

    private NetPolicy() {}

    static boolean isAllowedPeer(InetAddress peer) {
        if (peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress()) return true;
        byte[] b = peer.getAddress();
        if (peer instanceof Inet4Address) {
            // 100.64.0.0/10, the carrier-grade NAT range Tailscale hands out.
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
        }
        // fc00::/7, unique local addresses.
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    /** True for an address in Tailscale's ranges (100.64.0.0/10, fd7a:115c:a1e0::/48), false for the home network. */
    static boolean isTailscaleAddress(String address) {
        if (address == null) return false;
        String a = address.toLowerCase(Locale.ROOT);
        if (a.startsWith("fd7a:115c:a1e0:")) return true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^100\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}$").matcher(a);
        if (!m.matches()) return false;
        int second = Integer.parseInt(m.group(1));
        return second >= 64 && second <= 127;
    }

    /** The host part of a Host header or an Origin authority, without the port. Brackets are kept off IPv6. */
    static String hostOf(String authority) {
        if (authority == null) return "";
        String a = authority.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            return end < 0 ? a : a.substring(1, end);
        }
        int colon = a.lastIndexOf(':');
        return colon >= 0 && a.indexOf(':') == colon ? a.substring(0, colon) : a;
    }

    /** True for an IPv4 or IPv6 literal, localhost, or a Tailscale or mDNS name. Anything else could be a rebound name. */
    static boolean isAcceptableHost(String hostHeader) {
        String host = hostOf(hostHeader);
        if (host.isEmpty() || host.length() > 253) return false;
        if (host.equals("localhost") || host.endsWith(".ts.net") || host.endsWith(".local")) return true;
        if (host.indexOf(':') >= 0) return host.matches("[0-9a-f:.]+");
        return host.matches("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    }

    /** A request that names an Origin must come from a page served by this very address. */
    static boolean originMatches(String origin, String hostHeader) {
        if (origin == null || origin.isEmpty()) return true;
        int scheme = origin.indexOf("://");
        if (scheme < 0) return false;
        String authority = origin.substring(scheme + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        return authority.equalsIgnoreCase(hostHeader == null ? "" : hostHeader.trim());
    }
}
