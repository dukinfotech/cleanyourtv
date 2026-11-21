package tv.webremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandHandlerTest {

    private static final class Recorder implements Controller {
        final List<String> calls = new ArrayList<>();
        boolean accept = true;

        @Override public int screenWidth() { return 1920; }
        @Override public int screenHeight() { return 1080; }
        @Override public void move(float dx, float dy) { calls.add("move " + dx + " " + dy); }
        @Override public void tap() { calls.add("tap"); }
        @Override public void doubleTap() { calls.add("dbl"); }
        @Override public void back() { calls.add("back"); }
        @Override public void dragStart() { calls.add("down"); }
        @Override public void dragEnd() { calls.add("up"); }
        @Override public void scroll(float dx, float dy) { calls.add("scroll " + dx + " " + dy); }
        @Override public boolean key(String key) { calls.add("key " + key); return accept; }
        @Override public boolean text(String value) { calls.add("text " + value); return accept; }
        @Override public boolean enter() { calls.add("enter"); return accept; }
        @Override public boolean openUrl(String url) { calls.add("url " + url); return accept; }
        @Override public List<AppInfo> apps() { return Collections.singletonList(new AppInfo("pkg.a", "Ứng dụng A")); }
        @Override public boolean launch(String id) { calls.add("launch " + id); return accept; }
        @Override public byte[] appIcon(String id) { return null; }
        @Override public byte[] appBanner(String id) { return null; }
        @Override public String language() { return "en"; }
        @Override public boolean audioReady() { return false; }
        @Override public boolean audioSubscribe(AudioSink sink) { return false; }
        @Override public void audioUnsubscribe(AudioSink sink) { }
        @Override public boolean screenReady() { return false; }
        @Override public void screenSubscribe(FrameSink sink) { }
        @Override public void screenUnsubscribe(FrameSink sink) { }
        @Override public void screenQuality(String mode) { calls.add("quality " + mode); }
        @Override public int screenEncodeMs() { return 0; }
        @Override public void pointAt(float fx, float fy) { calls.add("at " + fx + " " + fy); }
    }

    private final Recorder controller = new Recorder();
    private final CommandHandler handler = new CommandHandler(controller);
    private final List<String> replies = new ArrayList<>();

    private void send(String json) {
        handler.handle(json, replies::add);
    }

    @Test
    public void everyCommandReachesTheController() {
        send("{\"t\":\"move\",\"dx\":12,\"dy\":-3}");
        send("{\"t\":\"click\",\"b\":\"l\"}");
        send("{\"t\":\"click\",\"b\":\"r\"}");
        send("{\"t\":\"dbl\"}");
        send("{\"t\":\"down\"}");
        send("{\"t\":\"up\"}");
        send("{\"t\":\"scroll\",\"dx\":0,\"dy\":120}");
        send("{\"t\":\"key\",\"k\":\"vol_up\"}");
        send("{\"t\":\"text\",\"v\":\"chào bạn\"}");
        send("{\"t\":\"enter\"}");
        send("{\"t\":\"url\",\"v\":\"https://example.com/a?b=c\"}");
        send("{\"t\":\"launch\",\"id\":\"pkg.a\"}");
        assertEquals(java.util.Arrays.asList("move 12.0 -3.0", "tap", "back", "dbl", "down", "up", "scroll 0.0 120.0",
                "key vol_up", "text chào bạn", "enter", "url https://example.com/a?b=c", "launch pkg.a"), controller.calls);
        assertTrue(replies.isEmpty());
    }

    @Test
    public void pointingAtTheScreenIsClampedToItsEdges() {
        send("{\"t\":\"at\",\"x\":0.25,\"y\":0.5}");
        send("{\"t\":\"at\",\"x\":7,\"y\":-3}");
        assertEquals(java.util.Arrays.asList("at 0.25 0.5", "at 1.0 0.0"), controller.calls);
    }

    @Test
    public void audioRequestsAreRecognised() {
        assertEquals(Boolean.TRUE, CommandHandler.audioRequest("{\"t\":\"audio\",\"on\":true}"));
        assertEquals(Boolean.FALSE, CommandHandler.audioRequest("{\"t\":\"audio\",\"on\":false}"));
        assertEquals(null, CommandHandler.audioRequest("{\"t\":\"screen\",\"on\":true}"));
        assertEquals(null, CommandHandler.audioRequest("nonsense"));
    }

    @Test
    public void theStreamQualityIsPassedOn() {
        send("{\"t\":\"quality\",\"v\":\"smooth\"}");
        assertEquals(java.util.Arrays.asList("quality smooth"), controller.calls);
    }

    @Test
    public void screenAndUnpairRequestsAreRecognisedAndNothingElseIs() {
        assertEquals(Boolean.TRUE, CommandHandler.screenRequest("{\"t\":\"screen\",\"on\":true}"));
        assertEquals(Boolean.FALSE, CommandHandler.screenRequest("{\"t\":\"screen\",\"on\":false}"));
        assertEquals(Boolean.FALSE, CommandHandler.screenRequest("{\"t\":\"screen\"}"));
        assertEquals(null, CommandHandler.screenRequest("{\"t\":\"key\",\"k\":\"up\"}"));
        assertEquals(null, CommandHandler.screenRequest("not json"));
        assertTrue(CommandHandler.isUnpair("{\"t\":\"unpair\"}"));
        assertFalse(CommandHandler.isUnpair("{\"t\":\"screen\"}"));
    }

    @Test
    public void pingIsAnsweredAndAppsAreListed() throws Exception {
        send("{\"t\":\"ping\",\"n\":42}");
        assertEquals(42, new JSONObject(replies.get(0)).getLong("n"));
        assertEquals("pong", new JSONObject(replies.get(0)).getString("t"));
        send("{\"t\":\"apps\"}");
        JSONObject apps = new JSONObject(replies.get(1));
        assertEquals("apps", apps.getString("t"));
        assertEquals("Ứng dụng A", apps.getJSONArray("apps").getJSONObject(0).getString("label"));
    }

    @Test
    public void garbageAndUnknownMessagesDoNothing() {
        send("not json");
        send("{\"t\":\"format-disk\"}");
        send("{}");
        send("[1,2]");
        send(null);
        assertTrue(controller.calls.isEmpty());
        assertTrue(replies.isEmpty());
    }

    @Test
    public void numbersAreBoundedAndNonFiniteOnesBecomeZero() {
        send("{\"t\":\"move\",\"dx\":1e30,\"dy\":-1e30}");
        send("{\"t\":\"move\",\"dx\":\"NaN\",\"dy\":\"x\"}");
        assertEquals("move 2000.0 -2000.0", controller.calls.get(0));
        assertEquals("move 0.0 0.0", controller.calls.get(1));
    }

    @Test
    public void onlyWebLinksOpenAndOversizeTextIsDropped() {
        send("{\"t\":\"url\",\"v\":\"javascript:alert(1)\"}");
        send("{\"t\":\"url\",\"v\":\"file:///data/data/x\"}");
        send("{\"t\":\"url\",\"v\":\"intent://x#Intent;end\"}");
        send("{\"t\":\"url\",\"v\":\"https://a b\"}");
        send("{\"t\":\"text\",\"v\":\"" + "x".repeat(2001) + "\"}");
        assertTrue(controller.calls.isEmpty());
        assertEquals("every refused link says so", 4, replies.size());
        assertTrue(CommandHandler.isWebLink("HTTPS://Example.com"));
    }

    @Test
    public void aMessageOverTheLimitIsIgnored() {
        send("{\"t\":\"text\",\"v\":\"" + "y".repeat(9000) + "\"}");
        assertTrue(controller.calls.isEmpty());
    }

    @Test
    public void whenTheTvCannotDoSomethingThePageIsToldWhy() throws Exception {
        controller.accept = false;
        send("{\"t\":\"key\",\"k\":\"up\"}");
        send("{\"t\":\"text\",\"v\":\"abc\"}");
        send("{\"t\":\"enter\"}");
        send("{\"t\":\"launch\",\"id\":\"x\"}");
        assertEquals(4, replies.size());
        for (String r : replies) assertEquals("toast", new JSONObject(r).getString("t"));
    }
}
