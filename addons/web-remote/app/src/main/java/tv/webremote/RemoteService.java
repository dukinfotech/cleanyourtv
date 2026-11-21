package tv.webremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The part that acts on the TV. As an accessibility service it can draw the pointer over any app, tap, swipe and drag
 * where the pointer is, send the navigation keys and Back/Home, and set the text of the focused field, all without root
 * or ADB. It also hosts the {@link HttpServer} while the switch is on. It stays alive as long as the service is enabled.
 */
public class RemoteService extends AccessibilityService implements Controller {

    private static final String TAG = "WebRemote";
    private static volatile RemoteService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private CursorView cursor;
    private HttpServer server;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private NodeTouch touch;
    /** Set once the firmware has cancelled a touch gesture: clicks and scrolls then go through the accessibility tree. */
    private volatile boolean gesturesBroken;
    private int screenW = 1920, screenH = 1080;
    private float pointerX, pointerY;
    private boolean dragging, scrollInFlight;
    private float dragFromX, dragFromY, pendingScrollX, pendingScrollY;
    private long dragStartedAt, appsAt;
    private List<AppInfo> appCache = Collections.emptyList();

    static RemoteService instance() {
        return instance;
    }

    boolean serverRunning() {
        HttpServer s = server;
        return s != null && s.isRunning();
    }

    int connectedDevices() {
        HttpServer s = server;
        return s == null ? 0 : s.clientCount();
    }

    java.util.Set<String> connectedDeviceIds() {
        HttpServer s = server;
        return s == null ? java.util.Collections.<String>emptySet() : s.connectedIds();
    }

    void disconnectDevice(String id) {
        HttpServer s = server;
        if (s != null) s.disconnect(id);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        touch = new NodeTouch(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        pointerX = screenW / 2f;
        pointerY = screenH / 2f;
        cursor = new CursorView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowManager.addView(cursor, lp);
        applyServerSetting();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutDown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutDown();
        super.onDestroy();
    }

    private void shutDown() {
        stopServer();
        worker.shutdownNow();
        if (cursor != null && windowManager != null) {
            try {
                windowManager.removeView(cursor);
            } catch (IllegalArgumentException alreadyGone) {
                // the window was removed with the service
            }
        }
        cursor = null;
        if (instance == this) instance = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** Starts or stops the server to match the switch. */
    synchronized void applyServerSetting() {
        if (Core.serverEnabled(this)) startServer(); else stopServer();
    }

    private synchronized void startServer() {
        if (server != null && server.isRunning()) return;
        try {
            server = new HttpServer(HttpServer.PORT, readAsset("index.html"), readAsset("apple-touch-icon.png"), Core.pairing(this), this, Build.MODEL);
            server.start();
        } catch (IOException failed) {
            server = null;
        }
    }

    private synchronized void stopServer() {
        if (server != null) server.stop();
        server = null;
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    // --- Controller ---

    /** The language was changed in the app: the connected pages follow. */
    static void languageChanged() {
        RemoteService s = instance;
        HttpServer h = s == null ? null : s.server;
        if (h != null) h.broadcastLanguage(Lang.effective(s));
    }

    @Override
    public String language() {
        return Lang.effective(this);
    }

    @Override
    public int screenWidth() {
        return screenW;
    }

    @Override
    public int screenHeight() {
        return screenH;
    }

    @Override
    public void move(float dx, float dy) {
        float speed = (float) Math.hypot(dx, dy);
        float gain = 1.6f + Math.min(2.6f, speed / 10f);
        pointerX = Math.max(0, Math.min(screenW - 1, pointerX + dx * gain));
        pointerY = Math.max(0, Math.min(screenH - 1, pointerY + dy * gain));
        main.post(() -> {
            if (cursor != null) cursor.moveTo(pointerX, pointerY);
        });
    }

    /**
     * A tap where the pointer is. A touch gesture first; if the firmware cancels it, the element under the pointer is
     * clicked through the accessibility tree instead, and later taps go straight there.
     */
    @Override
    public void tap() {
        float x = pointerX, y = pointerY;
        main.post(() -> {
            if (cursor != null) cursor.moveTo(x, y);
            if (gesturesBroken) {
                worker.execute(() -> touch.click(x, y));
                return;
            }
            Path p = new Path();
            p.moveTo(x, y);
            boolean sent = gesture(p, 0, 50, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    gesturesBroken = false;
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.i(TAG, "touch gestures are cancelled on this TV: using the accessibility tree");
                    gesturesBroken = true;
                    worker.execute(() -> touch.click(x, y));
                }
            });
            if (!sent) {
                gesturesBroken = true;
                worker.execute(() -> touch.click(x, y));
            }
        });
    }

    /** The page sends the first tap as a click, then this: the second tap of a double tap. */
    @Override
    public void doubleTap() {
        tap();
    }

    @Override
    public void back() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override
    public void dragStart() {
        dragging = true;
        dragFromX = pointerX;
        dragFromY = pointerY;
        dragStartedAt = System.currentTimeMillis();
        main.post(() -> {
            if (cursor != null) cursor.setDragging(true);
        });
    }

    /** Drops where the pointer is now: one swipe from where it was picked up. With no movement it is a long press. */
    @Override
    public void dragEnd() {
        if (!dragging) return;
        dragging = false;
        float fromX = dragFromX, fromY = dragFromY, toX = pointerX, toY = pointerY;
        long held = System.currentTimeMillis() - dragStartedAt;
        main.post(() -> {
            if (cursor != null) cursor.setDragging(false);
            boolean moved = Math.hypot(toX - fromX, toY - fromY) > 8;
            if (gesturesBroken) {
                // Without touch injection a drag cannot be done; holding still is a long press.
                if (!moved) worker.execute(() -> touch.longPress(fromX, fromY));
                return;
            }
            Path p = new Path();
            p.moveTo(fromX, fromY);
            if (moved) p.lineTo(toX, toY);
            gesture(p, 0, moved ? Math.max(200, Math.min(900, held)) : 700, null);
        });
    }

    @Override
    public void scroll(float dx, float dy) {
        main.post(() -> {
            pendingScrollX += dx;
            pendingScrollY += dy;
            pumpScroll();
        });
    }

    /** One swipe at a time, centred on the pointer, carrying everything that piled up while the last one ran. */
    private void pumpScroll() {
        if (scrollInFlight) return;
        float sx = pendingScrollX, sy = pendingScrollY;
        float length = (float) Math.hypot(sx, sy);
        if (length < (gesturesBroken ? 40f : 8f)) return;
        pendingScrollX = 0;
        pendingScrollY = 0;
        if (gesturesBroken) {
            // One scroll step per call, at most about seven a second, carrying what piled up in between.
            float x = pointerX, y = pointerY;
            scrollInFlight = true;
            worker.execute(() -> {
                touch.scroll(x, y, sx, sy);
                main.postDelayed(() -> {
                    scrollInFlight = false;
                    pumpScroll();
                }, 140);
            });
            return;
        }
        float scale = Math.max(60f, Math.min(700f, length * 1.2f)) / length;
        float vx = -sx * scale, vy = -sy * scale;
        float fromX = clamp(pointerX - vx / 2f, 0, screenW - 1), fromY = clamp(pointerY - vy / 2f, 0, screenH - 1);
        float toX = clamp(fromX + vx, 0, screenW - 1), toY = clamp(fromY + vy, 0, screenH - 1);
        Path p = new Path();
        p.moveTo(fromX, fromY);
        p.lineTo(toX, toY);
        scrollInFlight = true;
        boolean sent = gesture(p, 0, 160, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                scrollInFlight = false;
                pumpScroll();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                // The firmware cancels touch gestures: hand the same scroll to the accessibility tree.
                gesturesBroken = true;
                pendingScrollX += sx;
                pendingScrollY += sy;
                scrollInFlight = false;
                pumpScroll();
            }
        });
        if (!sent) {
            gesturesBroken = true;
            pendingScrollX += sx;
            pendingScrollY += sy;
            scrollInFlight = false;
            pumpScroll();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private final GestureResultCallback gestureLog = new GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            Log.i(TAG, "gesture completed");
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            Log.i(TAG, "gesture cancelled");
        }
    };

    private boolean gesture(Path path, long start, long duration, GestureResultCallback callback) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, start, duration));
        boolean accepted = dispatchGesture(builder.build(), callback != null ? callback : gestureLog, main);
        if (!accepted) Log.w(TAG, "gesture refused by the system");
        return accepted;
    }

    @Override
    public boolean key(String key) {
        switch (key) {
            case "up": return dpad(16);
            case "down": return dpad(17);
            case "left": return dpad(18);
            case "right": return dpad(19);
            case "ok": return dpad(20);
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "settings": return openSettings();
            case "apps": return openAppsKey();
            case "power": return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case "vol_up": return volume(AudioManager.ADJUST_RAISE);
            case "vol_down": return volume(AudioManager.ADJUST_LOWER);
            case "mute": return volume(AudioManager.ADJUST_TOGGLE_MUTE);
            case "play_pause": return media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case "next": return media(KeyEvent.KEYCODE_MEDIA_NEXT);
            case "prev": return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case "stop": return media(KeyEvent.KEYCODE_MEDIA_STOP);
            case "rewind": return media(KeyEvent.KEYCODE_MEDIA_REWIND);
            case "ff": return media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            default: return false;
        }
    }

    /** The d-pad global actions exist from Android 13 (API 33): the global-action ids 16 to 20. */
    /** The remote's Settings button: the TV's Settings app opens, and failing that the system's quick-settings panel. */
    private boolean openSettings() {
        PackageManager pm = getPackageManager();
        Intent open = new Intent(android.provider.Settings.ACTION_SETTINGS);
        if (pm.resolveActivity(open, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            // Some TV firmware has the AOSP TV settings app with no filter for the standard action.
            open = new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings");
        }
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException unavailable) {
            return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        }
    }

    /**
     * The remote's Apps button. On this Xiaomi TV the launcher's theme maps it to Xiaomi's TV manager app, so that is what
     * opens; on a TV without it, the list of recent apps does.
     */
    private boolean openAppsKey() {
        PackageManager pm = getPackageManager();
        Intent open = pm.getLeanbackLaunchIntentForPackage(APPS_KEY_PACKAGE);
        if (open == null) open = pm.getLaunchIntentForPackage(APPS_KEY_PACKAGE);
        if (open != null) {
            try {
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException unavailable) {
                // fall through to the recent apps
            }
        }
        return performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    private static final String APPS_KEY_PACKAGE = "com.xiaomi.mitv.tvmanager";

    private boolean dpad(int action) {
        return Build.VERSION.SDK_INT >= 33 && performGlobalAction(action);
    }

    private boolean volume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return false;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        return true;
    }

    private boolean media(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return false;
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        return true;
    }

    /** The text field that has input focus, else the one last clicked with the pointer, or null. */
    private AccessibilityNodeInfo focusedField() {
        AccessibilityNodeInfo focused = inputFocus();
        return focused != null ? focused : touch.clickedField();
    }

    private AccessibilityNodeInfo inputFocus() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) {
            AccessibilityNodeInfo accessible = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            node = accessible != null && accessible.isEditable() ? accessible : null;
        }
        return node;
    }

    @Override
    public boolean text(String value) {
        AccessibilityNodeInfo field = focusedField();
        if (field == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        field.recycle();
        return done;
    }

    @Override
    public boolean enter() {
        if (Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo field = focusedField();
        if (field == null) return false;
        boolean done = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        field.recycle();
        return done;
    }

    @Override
    public boolean openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException noBrowser) {
            return false;
        }
    }

    @Override
    public synchronized List<AppInfo> apps() {
        long now = System.currentTimeMillis();
        if (now - appsAt < 60_000 && !appCache.isEmpty()) return appCache;
        PackageManager pm = getPackageManager();
        Map<String, AppInfo> byPackage = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String id = info.activityInfo.packageName;
                if (!byPackage.containsKey(id)) byPackage.put(id, new AppInfo(id, String.valueOf(info.loadLabel(pm))));
            }
        }
        List<AppInfo> list = new ArrayList<>(byPackage.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        appCache = list;
        appsAt = now;
        return list;
    }

    @Override
    public boolean audioReady() {
        return Build.VERSION.SDK_INT >= 29 && ScreenCapture.current != null
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean audioSubscribe(AudioSink sink) {
        ScreenCapture c = ScreenCapture.current;
        return c != null && audioReady() && c.audioSubscribe(sink);
    }

    @Override
    public void audioUnsubscribe(AudioSink sink) {
        ScreenCapture c = ScreenCapture.current;
        if (c != null) c.audioUnsubscribe(sink);
    }

    @Override
    public boolean screenReady() {
        return ScreenCapture.current != null;
    }

    @Override
    public void screenSubscribe(FrameSink sink) {
        ScreenCapture c = ScreenCapture.current;
        if (c != null) c.subscribe(sink);
    }

    @Override
    public void screenUnsubscribe(FrameSink sink) {
        ScreenCapture c = ScreenCapture.current;
        if (c != null) c.unsubscribe(sink);
    }

    @Override
    public void screenQuality(String mode) {
        ScreenCapture c = ScreenCapture.current;
        if (c != null) c.setMode(mode);
    }

    @Override
    public int screenEncodeMs() {
        ScreenCapture c = ScreenCapture.current;
        return c == null ? 0 : c.encodeMs();
    }

    @Override
    public void pointAt(float fx, float fy) {
        if (Float.isNaN(fx) || Float.isNaN(fy)) return;
        pointerX = Math.max(0, Math.min(screenW - 1, fx * screenW));
        pointerY = Math.max(0, Math.min(screenH - 1, fy * screenH));
        main.post(() -> {
            if (cursor != null) cursor.moveTo(pointerX, pointerY);
        });
    }

    private final Map<String, byte[]> bannerCache = new HashMap<>();

    @Override
    public byte[] appBanner(String id) {
        boolean known = false;
        for (AppInfo a : apps()) if (a.id.equals(id)) known = true;
        if (!known) return null;
        synchronized (bannerCache) {
            byte[] cached = bannerCache.get(id);
            if (cached != null) return cached.length == 0 ? null : cached;
        }
        byte[] png = renderBanner(id);
        synchronized (bannerCache) {
            if (bannerCache.size() > 400) bannerCache.clear();
            bannerCache.put(id, png == null ? new byte[0] : png);
        }
        return png;
    }

    /** The banner the launcher would draw for this app: the activity's, else the app's; a square one does not count. */
    private byte[] renderBanner(String id) {
        PackageManager pm = getPackageManager();
        ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(id), 0);
        if (ri == null || ri.activityInfo == null) return null;
        try {
            android.graphics.drawable.Drawable banner = null;
            int res = ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ri.activityInfo.applicationInfo.banner;
            if (res != 0) {
                try {
                    banner = pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
                            .getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XHIGH, null);
                } catch (PackageManager.NameNotFoundException | android.content.res.Resources.NotFoundException none) {
                    // falls back to the activity's own loader below
                }
            }
            if (banner == null) banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) return null;
            int w = banner.getIntrinsicWidth(), h = banner.getIntrinsicHeight();
            if (w > 0 && h > 0 && w < h * 1.4f) return null;
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
            banner.setBounds(0, 0, 320, 180);
            banner.draw(new android.graphics.Canvas(bitmap));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            return png.toByteArray();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static final int ICON_PX = 96;
    private final Map<String, byte[]> iconCache = new HashMap<>();

    @Override
    public byte[] appIcon(String id) {
        boolean known = false;
        for (AppInfo a : apps()) if (a.id.equals(id)) known = true;
        if (!known) return null;
        synchronized (iconCache) {
            byte[] cached = iconCache.get(id);
            if (cached != null) return cached;
        }
        try {
            android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(id);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(ICON_PX, ICON_PX, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            d.setBounds(0, 0, ICON_PX, ICON_PX);
            d.draw(canvas);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            byte[] bytes = png.toByteArray();
            synchronized (iconCache) {
                if (iconCache.size() > 400) iconCache.clear();
                iconCache.put(id, bytes);
            }
            return bytes;
        } catch (PackageManager.NameNotFoundException | RuntimeException gone) {
            return null;
        }
    }

    @Override
    public boolean launch(String id) {
        boolean known = false;
        for (AppInfo a : apps()) if (a.id.equals(id)) known = true;
        if (!known) return false;
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(id);
        if (intent == null) intent = pm.getLaunchIntentForPackage(id);
        if (intent == null) return false;
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException gone) {
            return false;
        }
    }
}
