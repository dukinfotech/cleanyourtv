package tv.webremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class NetPolicyTest {

    private static boolean allowed(String ip) throws UnknownHostException {
        return NetPolicy.isAllowedPeer(InetAddress.getByName(ip));
    }

    @Test
    public void homeNetworkLoopbackAndTailscaleAreAllowed() throws UnknownHostException {
        for (String ip : new String[]{"127.0.0.1", "192.168.1.20", "192.168.100.44", "10.1.2.3", "172.16.0.5", "172.31.255.1",
                "100.64.0.1", "100.101.102.103", "100.127.255.254", "::1", "fe80::1", "fd12:3456::1"}) {
            assertTrue(ip, allowed(ip));
        }
    }

    @Test
    public void thePublicInternetIsNot() throws UnknownHostException {
        for (String ip : new String[]{"8.8.8.8", "1.1.1.1", "203.0.113.9", "100.63.255.255", "100.128.0.1", "172.32.0.1",
                "172.15.255.255", "2001:4860:4860::8888", "fb00::1"}) {
            assertFalse(ip, allowed(ip));
        }
    }

    @Test
    public void onlyAnAddressOrAKnownLocalNameIsAnAcceptableHost() {
        assertTrue(NetPolicy.isAcceptableHost("192.168.100.44:8765"));
        assertTrue(NetPolicy.isAcceptableHost("100.99.1.2:8765"));
        assertTrue(NetPolicy.isAcceptableHost("[fd00::1]:8765"));
        assertTrue(NetPolicy.isAcceptableHost("localhost:8765"));
        assertTrue(NetPolicy.isAcceptableHost("mitv.tail1234.ts.net:8765"));
        assertTrue(NetPolicy.isAcceptableHost("mitv.local"));
        assertFalse("a name an attacker owns could be rebound to the TV", NetPolicy.isAcceptableHost("evil.example.com:8765"));
        assertFalse(NetPolicy.isAcceptableHost("192.168.100.44.evil.com"));
        assertFalse(NetPolicy.isAcceptableHost(""));
        assertFalse(NetPolicy.isAcceptableHost(null));
    }

    @Test
    public void anOriginMustBeThePageServedByThisAddress() {
        assertTrue(NetPolicy.originMatches("http://192.168.100.44:8765", "192.168.100.44:8765"));
        assertTrue(NetPolicy.originMatches(null, "192.168.100.44:8765"));
        assertFalse(NetPolicy.originMatches("http://evil.example.com", "192.168.100.44:8765"));
        assertFalse(NetPolicy.originMatches("http://192.168.100.44:7777", "192.168.100.44:8765"));
        assertFalse(NetPolicy.originMatches("null", "192.168.100.44:8765"));
    }

    @Test
    public void tailscaleAddressesAreToldApartFromTheHomeNetwork() {
        assertTrue(NetPolicy.isTailscaleAddress("100.64.0.1"));
        assertTrue(NetPolicy.isTailscaleAddress("100.101.102.103"));
        assertTrue(NetPolicy.isTailscaleAddress("fd7a:115c:a1e0::1"));
        assertFalse(NetPolicy.isTailscaleAddress("100.63.0.1"));
        assertFalse(NetPolicy.isTailscaleAddress("100.128.0.1"));
        assertFalse(NetPolicy.isTailscaleAddress("192.168.100.20"));
        assertFalse(NetPolicy.isTailscaleAddress(null));
    }

    @Test
    public void hostOfStripsThePort() {
        assertEquals("192.168.1.1", NetPolicy.hostOf("192.168.1.1:80"));
        assertEquals("fd00::1", NetPolicy.hostOf("[fd00::1]:80"));
        assertEquals("localhost", NetPolicy.hostOf("LOCALHOST"));
    }
}
