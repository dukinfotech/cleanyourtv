package tv.webremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PairingTest {

    private static final class MemoryStore implements Pairing.Store {
        String json;

        @Override
        public String load() {
            return json;
        }

        @Override
        public void save(String saved) {
            json = saved;
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final Pairing pairing = new Pairing(store);
    private static final long T0 = 1_000_000L;

    private static String wrong(String pin) {
        return pin.equals("000000") ? "111111" : "000000";
    }

    @Test
    public void nothingPairsWhileNoWindowIsOpen() {
        assertEquals(Pairing.Status.CLOSED, pairing.pair("123456", "x", T0).status);
        assertNull(pairing.currentPin(T0));
    }

    @Test
    public void thePinIsSixDigitsAndExpiresAfterOneMinute() {
        String pin = pairing.openWindow(T0);
        assertTrue(pin.matches("\\d{6}"));
        assertEquals(60_000, Pairing.WINDOW_MS);
        assertEquals(pin, pairing.currentPin(T0 + 59_000));
        assertNull(pairing.currentPin(T0 + Pairing.WINDOW_MS));
        assertEquals(Pairing.Status.CLOSED, pairing.pair(pin, "x", T0 + Pairing.WINDOW_MS + 1).status);
    }

    @Test
    public void theRightPinGivesATokenThatAuthenticatesAndTheWindowCloses() {
        String pin = pairing.openWindow(T0);
        Pairing.Result r = pairing.pair(pin, "Chrome trên Windows", T0 + 5_000);
        assertEquals(Pairing.Status.OK, r.status);
        assertEquals(64, r.token.length());
        Pairing.Device d = pairing.authenticate(r.token, T0 + 6_000);
        assertNotNull(d);
        assertEquals("Chrome trên Windows", d.name);
        assertFalse("one PIN pairs one device", pairing.windowOpen(T0 + 7_000));
        assertEquals(Pairing.Status.CLOSED, pairing.pair(pin, "again", T0 + 7_000).status);
    }

    @Test
    public void aTokenIsNeverStoredInTheClear() {
        String pin = pairing.openWindow(T0);
        Pairing.Result r = pairing.pair(pin, "phone", T0);
        assertFalse(store.json.contains(r.token));
        assertTrue(store.json.contains(Pairing.sha256(r.token)));
    }

    @Test
    public void wrongTokensDoNotAuthenticate() {
        String pin = pairing.openWindow(T0);
        pairing.pair(pin, "phone", T0);
        assertNull(pairing.authenticate(null, T0));
        assertNull(pairing.authenticate("", T0));
        assertNull(pairing.authenticate("0".repeat(64), T0));
        assertNull(pairing.authenticate("short", T0));
    }

    @Test
    public void fiveWrongPinsLockForFiveMinutesEvenForTheRightOne() {
        String pin = pairing.openWindow(T0);
        for (int i = 0; i < 4; i++) assertEquals(Pairing.Status.BAD_PIN, pairing.pair(wrong(pin), "x", T0 + i).status);
        Pairing.Result fifth = pairing.pair(wrong(pin), "x", T0 + 5);
        assertEquals(Pairing.Status.LOCKED, fifth.status);
        assertEquals(300, fifth.retrySeconds);
        assertEquals(300_000, Pairing.LOCK_MS);
        Pairing.Result locked = pairing.pair(pin, "x", T0 + 10_000);
        assertEquals("the right PIN is refused during the lock", Pairing.Status.LOCKED, locked.status);
        assertTrue(locked.retrySeconds > 280 && locked.retrySeconds <= 300);
        assertEquals("285.005 s left, rounded up", 286, pairing.lockedSeconds(T0 + 15_000));
    }

    @Test
    public void theLockOutlivesTheWindowAndOpeningANewOneDoesNotLiftIt() {
        String pin = pairing.openWindow(T0);
        for (int i = 0; i < 5; i++) pairing.pair(wrong(pin), "x", T0 + i);
        assertFalse("a lock closes the window", pairing.windowOpen(T0 + 10));

        long later = T0 + 120_000;
        String next = pairing.openWindow(later);
        assertEquals(Pairing.Status.LOCKED, pairing.pair(next, "x", later + 1).status);
        assertTrue(pairing.lockedSeconds(later + 1) > 0);

        long after = T0 + 5 + Pairing.LOCK_MS + 1;
        assertEquals(0, pairing.lockedSeconds(after));
        String fresh = pairing.openWindow(after);
        assertEquals(Pairing.Status.OK, pairing.pair(fresh, "x", after + 1).status);
    }

    @Test
    public void fewerThanFiveWrongPinsDoNotLock() {
        String pin = pairing.openWindow(T0);
        for (int i = 0; i < 4; i++) pairing.pair(wrong(pin), "x", T0 + i);
        assertEquals(0, pairing.lockedSeconds(T0 + 10));
        assertEquals(Pairing.Status.OK, pairing.pair(pin, "x", T0 + 20).status);
    }

    @Test
    public void devicesComeNewestPairedFirstAndRememberTheirAddress() {
        String first = pairing.openWindow(T0);
        pairing.pair(first, "old", T0);
        String second = pairing.openWindow(T0 + 1_000);
        pairing.pair(second, "new", T0 + 1_000);
        assertEquals("new", pairing.devicesNewestFirst().get(0).name);
        assertEquals("old", pairing.devicesNewestFirst().get(1).name);

        String id = pairing.devicesNewestFirst().get(0).id;
        pairing.noteAddress(id, "100.100.1.2");
        pairing.noteAddress("nope", "1.2.3.4");
        assertEquals("100.100.1.2", new Pairing(store).devicesNewestFirst().get(0).lastIp);
        pairing.noteAddress(id, "fe80::1%wlan0");
        assertEquals("fe80::1", pairing.devicesNewestFirst().get(0).lastIp);
    }

    @Test
    public void aDeviceCanBeRevokedAndSurvivesARestart() {
        String pin = pairing.openWindow(T0);
        String token = pairing.pair(pin, "tablet", T0).token;
        Pairing reloaded = new Pairing(store);
        Pairing.Device d = reloaded.authenticate(token, T0 + 1);
        assertNotNull("still paired after the TV restarts", d);
        assertTrue(reloaded.revoke(d.id));
        assertNull(reloaded.authenticate(token, T0 + 2));
        assertNull(new Pairing(store).authenticate(token, T0 + 3));
        assertFalse(reloaded.revoke("nope"));
    }

    @Test
    public void namesAreCleanedAndShortened() {
        String pin = pairing.openWindow(T0);
        pairing.pair(pin, "<script>\"x\"</script>" + "a".repeat(100), T0);
        String name = pairing.devices().get(0).name;
        assertFalse(name.contains("<"));
        assertFalse(name.contains("\""));
        assertTrue(name.length() <= 40);
        pin = pairing.openWindow(T0);
        pairing.pair(pin, "   ", T0);
        assertEquals("Thiết bị", pairing.devices().get(1).name);
    }

    @Test
    public void aCorruptStoreStartsEmptyInsteadOfCrashing() {
        store.json = "{not json";
        assertTrue(new Pairing(store).devices().isEmpty());
    }
}
