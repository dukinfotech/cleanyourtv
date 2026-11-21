package tv.webremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Turns one JSON message from a paired page into a call on the {@link Controller}. Every field is checked and bounded:
 * a malformed or oversized message is dropped, an unknown one is ignored. Typed text is never logged.
 */
final class CommandHandler {

    interface Reply {
        void send(String json);
    }

    static final int MAX_MESSAGE = 8 * 1024, MAX_TEXT = 2000, MAX_URL = 2000;
    private static final float MAX_DELTA = 2000f;

    private final Controller controller;

    CommandHandler(Controller controller) {
        this.controller = controller;
    }

    void handle(String message, Reply reply) {
        if (message == null || message.length() > MAX_MESSAGE) return;
        JSONObject o;
        try {
            o = new JSONObject(message);
        } catch (JSONException malformed) {
            return;
        }
        String type = o.optString("t");
        switch (type) {
            case "move":
                controller.move(delta(o, "dx"), delta(o, "dy"));
                break;
            case "click":
                if ("r".equals(o.optString("b"))) controller.back(); else controller.tap();
                break;
            case "dbl":
                controller.doubleTap();
                break;
            case "down":
                controller.dragStart();
                break;
            case "up":
                controller.dragEnd();
                break;
            case "scroll":
                controller.scroll(delta(o, "dx"), delta(o, "dy"));
                break;
            case "key":
                if (!controller.key(o.optString("k"))) toast(reply, "Tivi này không thực hiện được phím đó");
                break;
            case "text":
                String value = o.optString("v");
                if (value.length() <= MAX_TEXT && !controller.text(value)) toast(reply, "Không có ô nhập nào đang được chọn trên tivi");
                break;
            case "enter":
                if (!controller.enter()) toast(reply, "Không có ô nhập nào đang được chọn trên tivi");
                break;
            case "url":
                String url = o.optString("v");
                if (url.length() > MAX_URL || !isWebLink(url) || !controller.openUrl(url)) toast(reply, "Không mở được liên kết");
                break;
            case "apps":
                reply.send(appsJson(controller.apps()));
                break;
            case "launch":
                if (!controller.launch(o.optString("id"))) toast(reply, "Không mở được ứng dụng");
                break;
            case "quality":
                controller.screenQuality(o.optString("v"));
                break;
            case "at":
                controller.pointAt(fraction(o, "x"), fraction(o, "y"));
                break;
            case "ping":
                reply.send(pongJson(o.optLong("n")));
                break;
            default:
                break;
        }
    }

    private static float fraction(JSONObject o, String key) {
        double v = o.optDouble(key, 0);
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0f;
        return (float) Math.max(0, Math.min(1, v));
    }

    /** {"t":"screen","on":true|false}: the page starts or stops watching the screen. Null for any other message. */
    static Boolean screenRequest(String message) {
        if (message == null || message.length() > MAX_MESSAGE) return null;
        try {
            JSONObject o = new JSONObject(message);
            return "screen".equals(o.optString("t")) ? Boolean.valueOf(o.optBoolean("on")) : null;
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** {"t":"audio","on":true|false}: the page starts or stops listening. Null for any other message. */
    static Boolean audioRequest(String message) {
        if (message == null || message.length() > MAX_MESSAGE) return null;
        try {
            JSONObject o = new JSONObject(message);
            return "audio".equals(o.optString("t")) ? Boolean.valueOf(o.optBoolean("on")) : null;
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** True for {"t":"unpair"}: the page asks the TV to forget the device that sent it. */
    static boolean isUnpair(String message) {
        if (message == null || message.length() > MAX_MESSAGE) return false;
        try {
            return "unpair".equals(new JSONObject(message).optString("t"));
        } catch (JSONException malformed) {
            return false;
        }
    }

    static boolean isWebLink(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("http://") || lower.startsWith("https://")) && lower.indexOf(' ') < 0 && lower.length() > 8;
    }

    private static float delta(JSONObject o, String key) {
        double v = o.optDouble(key, 0);
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0f;
        return (float) Math.max(-MAX_DELTA, Math.min(MAX_DELTA, v));
    }

    static String helloJson(int width, int height, String name, String lang) {
        try {
            return new JSONObject().put("t", "hello").put("w", width).put("h", height).put("name", name).put("lang", lang).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String pongJson(long n) {
        try {
            return new JSONObject().put("t", "pong").put("n", n).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String appsJson(List<Controller.AppInfo> apps) {
        try {
            JSONArray array = new JSONArray();
            for (Controller.AppInfo a : apps) array.put(new JSONObject().put("id", a.id).put("label", a.label));
            return new JSONObject().put("t", "apps").put("apps", array).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void toast(Reply reply, String text) {
        try {
            reply.send(new JSONObject().put("t", "toast").put("v", text).toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
