package tv.webremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The real server on localhost, talked to over plain sockets the way a browser would. */
public class HttpServerTest {

    private static final byte[] PAGE = "<html>remote</html>".getBytes(StandardCharsets.UTF_8);

    private static final class Fake implements Controller {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override public int screenWidth() { return 1920; }
        @Override public int screenHeight() { return 1080; }
        @Override public void move(float dx, float dy) { calls.add("move"); }
        @Override public void tap() { calls.add("tap"); }
        @Override public void doubleTap() { calls.add("dbl"); }
        @Override public void back() { calls.add("back"); }
        @Override public void dragStart() { }
        @Override public void dragEnd() { }
        @Override public void scroll(float dx, float dy) { }
        @Override public boolean key(String key) { calls.add("key " + key); return true; }
        @Override public boolean text(String value) { calls.add("text " + value); return true; }
        @Override public boolean enter() { return true; }
        @Override public boolean openUrl(String url) { return true; }
        @Override public List<AppInfo> apps() { return Collections.emptyList(); }
        @Override public boolean launch(String id) { return true; }
        volatile String lang = "en";
        @Override public String language() { return lang; }
        volatile boolean audioOk = true;
        volatile AudioSink audioSink;
        @Override public boolean audioReady() { return audioOk; }
        @Override public boolean audioSubscribe(AudioSink s) { audioSink = s; calls.add("audio on"); return true; }
        @Override public void audioUnsubscribe(AudioSink s) { audioSink = null; calls.add("audio off"); }
        volatile boolean ready = true;
        volatile FrameSink sink;
        @Override public boolean screenReady() { return ready; }
        @Override public void screenSubscribe(FrameSink s) { sink = s; calls.add("subscribe"); }
        @Override public void screenUnsubscribe(FrameSink s) { sink = null; calls.add("unsubscribe"); }
        volatile String quality = "";
        @Override public void screenQuality(String mode) { quality = mode; }
        @Override public int screenEncodeMs() { return 12; }
        @Override public void pointAt(float fx, float fy) { calls.add("at " + fx + " " + fy); }
        @Override public byte[] appBanner(String id) { return id.equals("com.example.app") ? new byte[]{(byte) 0x89, 'P', 'N', 'G', 1} : null; }
        @Override public byte[] appIcon(String id) { return id.equals("com.example.app") ? new byte[]{(byte) 0x89, 'P', 'N', 'G'} : null; }
    }

    private Pairing pairing;
    private Fake controller;
    private HttpServer server;
    private int port;

    @Before
    public void start() throws IOException {
        pairing = new Pairing(new Pairing.Store() {
            String json;

            @Override public String load() { return json; }
            @Override public void save(String s) { json = s; }
        });
        controller = new Fake();
        server = new HttpServer(0, PAGE, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, pairing, controller, "Test TV");
        server.start();
        port = server.port();
    }

    @After
    public void stop() {
        server.stop();
    }

    private String host() {
        return "127.0.0.1:" + port;
    }

    /** One HTTP exchange; returns the whole response text. */
    private String http(String method, String path, String host, String origin, String body) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5_000);
            StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
            if (body != null) {
                req.append("Content-Type: application/json\r\nContent-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
                        .append("\r\n");
            }
            req.append("\r\n");
            if (body != null) req.append(body);
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) all.write(buffer, 0, n);
            return all.toString("UTF-8");
        }
    }

    private String pairNow() throws Exception {
        String pin = pairing.openWindow(System.currentTimeMillis());
        String response = http("POST", "/api/pair", host(), null, "{\"pin\":\"" + pin + "\",\"name\":\"test\"}");
        assertTrue(response, response.startsWith("HTTP/1.1 200"));
        return new JSONObject(response.substring(response.indexOf("\r\n\r\n") + 4)).getString("token");
    }

    /** Opens a WebSocket; returns the connected socket after the handshake, or null when it was refused. */
    private Socket webSocket(String token, String origin) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5_000);
        String req = "GET /ws?token=" + token + " HTTP/1.1\r\nHost: " + host() + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                + (origin == null ? "" : "Origin: " + origin + "\r\n") + "\r\n";
        s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        StringBuilder head = new StringBuilder();
        InputStream in = s.getInputStream();
        int b;
        while (!head.toString().endsWith("\r\n\r\n") && (b = in.read()) >= 0) head.append((char) b);
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            s.close();
            return null;
        }
        assertTrue(head.toString().contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        return s;
    }

    private static void send(Socket s, String json) throws IOException {
        s.getOutputStream().write(WebSocketFramesTest.clientFrame(1, true, json.getBytes(StandardCharsets.UTF_8)));
        s.getOutputStream().flush();
    }

    /** A server frame (unmasked); null on end of stream. */
    private static String receive(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        int b0 = in.read();
        if (b0 < 0) return null;
        int len = in.read() & 0x7F;
        if (len == 126) len = (in.read() << 8) | in.read();
        byte[] payload = new byte[len];
        int done = 0;
        while (done < len) done += in.read(payload, done, len - done);
        return (b0 & 0x0F) == 1 ? new String(payload, StandardCharsets.UTF_8) : "";
    }

    /** The payload of the next server frame, which must be a binary one. */
    private static byte[] receiveBinary(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        int b0 = in.read();
        assertEquals("a binary frame", 0x82, b0);
        int len = in.read() & 0x7F;
        if (len == 126) len = (in.read() << 8) | in.read();
        byte[] payload = new byte[len];
        int done = 0;
        while (done < len) done += in.read(payload, done, len - done);
        return payload;
    }

    @Test
    public void theScreenIsStreamedOnlyToAPageThatAskedForIt() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, null)) {
            receive(s);
            assertEquals(null, controller.sink);
            send(s, "{\"t\":\"screen\",\"on\":true}");
            assertEquals("{\"t\":\"screen\",\"state\":\"on\",\"shared\":true}", receive(s));
            assertTrue(controller.sink != null);
            controller.sink.frame(new byte[]{1, 2, 3});
            assertEquals(3, receiveBinary(s).length);
            assertEquals("{\"t\":\"stat\",\"enc\":12}", receive(s));
            send(s, "{\"t\":\"screen\",\"on\":false}");
            assertEquals("{\"t\":\"screen\",\"state\":\"off\",\"shared\":true}", receive(s));
            assertEquals(null, controller.sink);
        }
    }

    @Test
    public void theHelloNamesTheLanguageAndAChangeIsPushedToEveryPage() throws Exception {
        String token = pairNow();
        controller.lang = "vi";
        try (Socket s = webSocket(token, null)) {
            String hello = receive(s);
            assertTrue(hello, hello.contains("\"lang\":\"vi\""));
            server.broadcastLanguage("en");
            assertEquals("{\"t\":\"lang\",\"v\":\"en\"}", receive(s));
            server.broadcastLanguage("fr");
            assertEquals("anything else is English", "{\"t\":\"lang\",\"v\":\"en\"}", receive(s));
        }
    }

    @Test
    public void soundIsStreamedOnlyToAPageThatAskedAndOnlyWhenTheTvAllowsIt() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, null)) {
            receive(s);
            send(s, "{\"t\":\"audio\",\"on\":true}");
            assertEquals("{\"t\":\"audio\",\"state\":\"on\",\"allowed\":true}", receive(s));
            controller.audioSink.audio(new byte[]{1, 5, 6, 7, 8});
            assertEquals(5, receiveBinary(s).length);
            send(s, "{\"t\":\"audio\",\"on\":false}");
            assertEquals("{\"t\":\"audio\",\"state\":\"off\",\"allowed\":true}", receive(s));
            assertEquals(null, controller.audioSink);

            controller.audioOk = false;
            send(s, "{\"t\":\"audio\",\"on\":true}");
            assertEquals("{\"t\":\"audio\",\"state\":\"off\",\"allowed\":false}", receive(s));
            assertEquals(null, controller.audioSink);
        }
    }

    @Test
    public void aListenerIsToldWhenTheSoundStopsAndCanAskAgain() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, null)) {
            receive(s);
            send(s, "{\"t\":\"audio\",\"on\":true}");
            receive(s);
            controller.audioSink.ended();
            assertEquals("{\"t\":\"audio\",\"state\":\"off\",\"allowed\":false}", receive(s));
            send(s, "{\"t\":\"audio\",\"on\":true}");
            assertEquals("{\"t\":\"audio\",\"state\":\"on\",\"allowed\":true}", receive(s));
            assertTrue(controller.audioSink != null);
        }
    }

    @Test
    public void theScreenIsRefusedWhileSharingIsOffAndAWatcherIsToldWhenItStops() throws Exception {
        String token = pairNow();
        controller.ready = false;
        try (Socket s = webSocket(token, null)) {
            receive(s);
            send(s, "{\"t\":\"screen\",\"on\":true}");
            assertEquals("{\"t\":\"screen\",\"state\":\"off\",\"shared\":false}", receive(s));
            assertEquals(null, controller.sink);
            controller.ready = true;
            send(s, "{\"t\":\"screen\",\"on\":true}");
            assertEquals("{\"t\":\"screen\",\"state\":\"on\",\"shared\":true}", receive(s));
            controller.sink.ended();
            assertEquals("{\"t\":\"screen\",\"state\":\"off\",\"shared\":false}", receive(s));
        }
    }

    @Test
    public void aPageThatAsksAgainAfterSharingRestartedGetsAFreshFeed() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, null)) {
            receive(s);
            send(s, "{\"t\":\"screen\",\"on\":true}");
            receive(s);
            controller.sink.ended();
            receive(s);
            controller.calls.clear();
            send(s, "{\"t\":\"screen\",\"on\":true}");
            assertEquals("{\"t\":\"screen\",\"state\":\"on\",\"shared\":true}", receive(s));
            assertTrue(controller.calls.contains("subscribe"));
            controller.sink.frame(new byte[]{9});
            assertEquals(1, receiveBinary(s).length);
        }
    }

    @Test
    public void closingThePageStopsItsScreenFeed() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, null)) {
            receive(s);
            send(s, "{\"t\":\"screen\",\"on\":true}");
            receive(s);
        }
        long until = System.currentTimeMillis() + 3_000;
        while (controller.sink != null && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(null, controller.sink);
    }

    @Test
    public void theHomeScreenIconIsServedToAnyoneOnTheNetworkWithoutPairing() throws IOException {
        String r = http("GET", "/apple-touch-icon.png", host(), null, null);
        assertTrue(r.startsWith("HTTP/1.1 200"));
        assertTrue(r.contains("Content-Type: image/png"));
        assertTrue(r.contains("Cache-Control: public"));
        assertTrue(http("GET", "/apple-touch-icon.png", "evil.example.com", null, null).startsWith("HTTP/1.1 421"));
    }

    @Test
    public void servesThePageWithSecurityHeaders() throws IOException {
        String r = http("GET", "/", host(), null, null);
        assertTrue(r.startsWith("HTTP/1.1 200"));
        assertTrue(r.contains("<html>remote</html>"));
        assertTrue(r.contains("Content-Security-Policy:"));
        assertTrue(r.contains("Cache-Control: no-store"));
        assertTrue(r.contains("X-Content-Type-Options: nosniff"));
        assertTrue(http("GET", "/nothing", host(), null, null).startsWith("HTTP/1.1 404"));
    }

    @Test
    public void aRebindableHostNameIsRefused() throws IOException {
        assertTrue(http("GET", "/", "evil.example.com:" + port, null, null).startsWith("HTTP/1.1 421"));
        assertTrue(http("POST", "/api/pair", "evil.example.com", null, "{}").startsWith("HTTP/1.1 421"));
    }

    @Test
    public void aForeignOriginIsRefusedOnEveryRoute() throws IOException {
        assertTrue(http("GET", "/", host(), "http://evil.example.com", null).startsWith("HTTP/1.1 403"));
        assertTrue(http("POST", "/api/pair", host(), "http://evil.example.com", "{\"pin\":\"1\"}").startsWith("HTTP/1.1 403"));
        assertTrue(http("GET", "/", host(), "http://" + host(), null).startsWith("HTTP/1.1 200"));
    }

    @Test
    public void pairingNeedsAnOpenWindowAndTheRightPin() throws Exception {
        String closed = http("POST", "/api/pair", host(), null, "{\"pin\":\"123456\",\"name\":\"x\"}");
        assertTrue(closed.startsWith("HTTP/1.1 403"));
        assertTrue(closed.contains("\"closed\""));

        String pin = pairing.openWindow(System.currentTimeMillis());
        String wrong = http("POST", "/api/pair", host(), null, "{\"pin\":\"" + (pin.equals("000000") ? "111111" : "000000") + "\"}");
        assertTrue(wrong.contains("\"bad_pin\""));
        assertTrue(http("POST", "/api/pair", host(), null, "{\"pin\":\"" + pin + "\",\"name\":\"phone\"}").contains("\"token\""));
    }

    @Test
    public void pairingRejectsOtherContentTypesAndHugeBodies() throws IOException {
        pairing.openWindow(System.currentTimeMillis());
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5_000);
            s.getOutputStream().write(("POST /api/pair HTTP/1.1\r\nHost: " + host()
                    + "\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}").getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[64];
            int n = s.getInputStream().read(buffer);
            assertTrue(new String(buffer, 0, n, StandardCharsets.UTF_8).startsWith("HTTP/1.1 415"));
        }
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5_000);
            s.getOutputStream().write(("POST /api/pair HTTP/1.1\r\nHost: " + host()
                    + "\r\nContent-Type: application/json\r\nContent-Length: 99999\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[64];
            int n = s.getInputStream().read(buffer);
            assertTrue(new String(buffer, 0, n, StandardCharsets.UTF_8).startsWith("HTTP/1.1 413"));
        }
    }

    @Test
    public void aWebSocketWithoutAValidTokenIsRefused() throws IOException {
        assertEquals(null, webSocket("", null));
        assertEquals(null, webSocket("f".repeat(64), null));
    }

    @Test
    public void aPairedDeviceGetsHelloAndItsCommandsRunOnTheTv() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, "http://" + host())) {
            JSONObject hello = new JSONObject(receive(s));
            assertEquals("hello", hello.getString("t"));
            assertEquals(1920, hello.getInt("w"));
            assertEquals("Test TV", hello.getString("name"));

            send(s, "{\"t\":\"key\",\"k\":\"home\"}");
            send(s, "{\"t\":\"text\",\"v\":\"xin chào\"}");
            send(s, "{\"t\":\"ping\",\"n\":7}");
            JSONObject pong = new JSONObject(receive(s));
            assertEquals("pong", pong.getString("t"));
            assertEquals(7, pong.getInt("n"));
            assertEquals(java.util.Arrays.asList("key home", "text xin chào"), controller.calls);
            assertEquals(1, server.clientCount());
        }
    }

    @Test
    public void aWebSocketFromAnotherSiteIsRefusedEvenWithAValidToken() throws Exception {
        String token = pairNow();
        assertEquals(null, webSocket(token, "http://evil.example.com"));
    }

    @Test
    public void revokingADeviceTellsItAndDropsItAndItsTokenStopsWorking() throws Exception {
        String token = pairNow();
        String id = pairing.devices().get(0).id;
        try (Socket s = webSocket(token, null)) {
            receive(s);
            pairing.revoke(id);
            server.disconnect(id);
            assertEquals("{\"t\":\"revoked\"}", receive(s));
            assertEquals(null, receive(s));
        }
        assertEquals(null, webSocket(token, null));
        assertFalse(server.clientCount() > 0);
    }

    @Test
    public void unpairFromThePageRevokesThatDeviceOnTheTv() throws Exception {
        String token = pairNow();
        assertEquals(1, pairing.devices().size());
        try (Socket s = webSocket(token, null)) {
            receive(s);
            send(s, "{\"t\":\"unpair\"}");
            assertEquals("{\"t\":\"revoked\"}", receive(s));
        }
        assertTrue(pairing.devices().isEmpty());
        assertEquals(null, webSocket(token, null));
    }

    @Test
    public void aDevicesAddressIsRemembered() throws Exception {
        String token = pairNow();
        assertEquals(null, pairing.devices().get(0).lastIp);
        try (Socket s = webSocket(token, null)) {
            receive(s);
        }
        assertEquals("127.0.0.1", pairing.devices().get(0).lastIp);
    }

    @Test
    public void bannersFollowTheSameRulesAsIcons() throws Exception {
        String token = pairNow();
        String ok = http("GET", "/banner?id=com.example.app&token=" + token, host(), null, null);
        assertTrue(ok.startsWith("HTTP/1.1 200"));
        assertTrue(ok.contains("image/png"));
        assertTrue(http("GET", "/banner?id=com.example.app", host(), null, null).startsWith("HTTP/1.1 401"));
        assertTrue("an app without a banner is a 404, so the page shows its icon",
                http("GET", "/banner?id=no.banner&token=" + token, host(), null, null).startsWith("HTTP/1.1 404"));
    }

    @Test
    public void iconsAreServedOnlyToAPairedDeviceAndOnlyForKnownApps() throws Exception {
        String token = pairNow();
        String ok = http("GET", "/icon?id=com.example.app&token=" + token, host(), null, null);
        assertTrue(ok.startsWith("HTTP/1.1 200"));
        assertTrue(ok.contains("image/png"));
        assertTrue(http("GET", "/icon?id=com.example.app&token=" + "0".repeat(64), host(), null, null).startsWith("HTTP/1.1 401"));
        assertTrue(http("GET", "/icon?id=com.example.app", host(), null, null).startsWith("HTTP/1.1 401"));
        assertTrue(http("GET", "/icon?id=no.such.app&token=" + token, host(), null, null).startsWith("HTTP/1.1 404"));
        assertTrue(http("GET", "/icon?id=../etc&token=" + token, host(), null, null).startsWith("HTTP/1.1 404"));
    }

    @Test
    public void stoppingTheServerClosesEveryConnection() throws Exception {
        String token = pairNow();
        try (Socket s = webSocket(token, null)) {
            receive(s);
            server.stop();
            assertEquals(null, receive(s));
        }
    }

    @Test
    public void garbageOnTheWireDoesNotKillTheServer() throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(new byte[]{0, 1, 2, 3, '\n', '\n'});
            s.getOutputStream().flush();
        }
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + "x".repeat(9000) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
        } catch (IOException ignored) {
            // the server may drop it before the write ends
        }
        assertTrue(http("GET", "/", host(), null, null).startsWith("HTTP/1.1 200"));
    }
}
