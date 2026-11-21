package tv.webremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who is allowed to control the TV. A device pairs with a six-digit PIN the TV shows for one minute after the owner asks
 * for it; it then gets a long random token, kept on the TV only as a hash. Five wrong PINs lock pairing for five minutes,
 * and the lock outlives the window: opening a new one does not lift it. Each device can be revoked.
 */
final class Pairing {

    interface Store {
        String load();

        void save(String json);
    }

    static final long WINDOW_MS = 60_000, LOCK_MS = 300_000;
    static final int FAILS_BEFORE_LOCK = 5;
    private static final int MAX_DEVICES = 20, MAX_NAME = 40;

    enum Status { OK, BAD_PIN, LOCKED, CLOSED }

    static final class Result {
        final Status status;
        final String token;
        final int retrySeconds;

        Result(Status status, String token, int retrySeconds) {
            this.status = status;
            this.token = token;
            this.retrySeconds = retrySeconds;
        }
    }

    static final class Device {
        final String id, name, hash;
        final long createdAt;
        long lastSeen;
        /** Address of the latest connection, or null before the first one. */
        String lastIp;

        Device(String id, String name, String hash, long createdAt, long lastSeen) {
            this.id = id;
            this.name = name;
            this.hash = hash;
            this.createdAt = createdAt;
            this.lastSeen = lastSeen;
        }
    }

    private final Store store;
    private final SecureRandom random = new SecureRandom();
    private final List<Device> devices = new ArrayList<>();
    private String pin;
    private long windowEnd, lockedUntil;
    private int fails;

    Pairing(Store store) {
        this.store = store;
        load();
    }

    /** Starts a pairing window and returns the new PIN. */
    synchronized String openWindow(long now) {
        pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        windowEnd = now + WINDOW_MS;
        fails = 0;
        return pin;
    }

    /** Seconds left of a lock after too many wrong PINs, or 0. */
    synchronized int lockedSeconds(long now) {
        return now < lockedUntil ? (int) ((lockedUntil - now + 999) / 1000) : 0;
    }

    synchronized void closeWindow() {
        pin = null;
        windowEnd = 0;
    }

    synchronized boolean windowOpen(long now) {
        return pin != null && now < windowEnd;
    }

    /** The PIN to show, or null when no window is open. */
    synchronized String currentPin(long now) {
        return windowOpen(now) ? pin : null;
    }

    synchronized long windowEndsAt() {
        return windowEnd;
    }

    synchronized Result pair(String attempt, String name, long now) {
        if (now < lockedUntil) return new Result(Status.LOCKED, null, lockedSeconds(now));
        if (!windowOpen(now)) return new Result(Status.CLOSED, null, 0);
        if (attempt != null && constantTimeEquals(attempt, pin)) {
            if (devices.size() >= MAX_DEVICES) devices.remove(0);
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            String token = hex(raw);
            byte[] idBytes = new byte[4];
            random.nextBytes(idBytes);
            devices.add(new Device(hex(idBytes), clean(name), sha256(token), now, now));
            save();
            closeWindow();
            return new Result(Status.OK, token, 0);
        }
        fails++;
        if (fails >= FAILS_BEFORE_LOCK) {
            fails = 0;
            lockedUntil = now + LOCK_MS;
            closeWindow();
            return new Result(Status.LOCKED, null, (int) (LOCK_MS / 1000));
        }
        return new Result(Status.BAD_PIN, null, 0);
    }

    /** The device a token belongs to, or null. */
    synchronized Device authenticate(String token, long now) {
        if (token == null || token.length() != 64) return null;
        String hash = sha256(token);
        Device found = null;
        for (Device d : devices) if (constantTimeEquals(d.hash, hash)) found = d;
        if (found != null) found.lastSeen = now;
        return found;
    }

    /** Remembers the address a device last connected from. */
    synchronized void noteAddress(String id, String ip) {
        if (ip == null) return;
        int scope = ip.indexOf('%');
        String clean = (scope >= 0 ? ip.substring(0, scope) : ip);
        if (clean.length() > 64) return;
        for (Device d : devices) {
            if (d.id.equals(id) && !clean.equals(d.lastIp)) {
                d.lastIp = clean;
                save();
            }
        }
    }

    /** Paired devices, the most recently paired first. */
    synchronized List<Device> devicesNewestFirst() {
        List<Device> list = new ArrayList<>(devices);
        java.util.Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    synchronized boolean revoke(String id) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).id.equals(id)) {
                devices.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    synchronized List<Device> devices() {
        return new ArrayList<>(devices);
    }

    private void load() {
        String json = store.load();
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Device d = new Device(o.getString("id"), o.getString("name"), o.getString("hash"),
                        o.optLong("created"), o.optLong("seen"));
                d.lastIp = o.has("ip") ? o.optString("ip") : null;
                devices.add(d);
            }
        } catch (JSONException unreadable) {
            devices.clear();
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        try {
            for (Device d : devices) {
                JSONObject o = new JSONObject().put("id", d.id).put("name", d.name).put("hash", d.hash)
                        .put("created", d.createdAt).put("seen", d.lastSeen);
                if (d.lastIp != null) o.put("ip", d.lastIp);
                array.put(o);
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        store.save(array.toString());
    }

    private static String clean(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}<>\"']", "").trim();
        if (n.isEmpty()) n = "Thiết bị";
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    static String sha256(String text) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
