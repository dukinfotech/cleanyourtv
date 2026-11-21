package tv.webremote;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * The status screen on the TV, as four cards: the state of the service and the server with the addresses to open, pairing
 * with its PIN, the paired devices (newest first, numbered, each with a dot for online/offline and the address it came
 * from, home network or Tailscale), and the shortcut to Tailscale for use away from home.
 */
public class MainActivity extends Activity {

    private static final String TAILSCALE = "com.tailscale.ipn";
    private static final int REQUEST_SHARE = 7;
    /** Every text size in this screen is scaled by this, so more fits on the TV at once. */
    private static final float TEXT_SCALE = 0.82f;
    private static final int BG = 0xFF101319, CARD = 0xFF1B1F27, TEXT = 0xFFFFFFFF, DIM = 0xFFA7B0BE;
    private static final int GREEN = 0xFF4ADE80, ORANGE = 0xFFFFA726, RED = 0xFFFF6B6B, GREY = 0xFF6B7280;
    private static final int BLUE = 0xFF3B82F6, PURPLE = 0xFF8B5CF6;
    private static final int PANEL2 = 0xFF202634, PRESSED = 0xFF2D3850, ACCENT = 0xFF4DA3FF, DANGER = 0xFFF27A7A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private float density;
    private String builtSignature;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        int pad = dp(32);
        page.setPadding(pad, dp(20), pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
        setContentView(scroll);
        applyExtras(getIntent());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SHARE) return;
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data));
        }
        builtSignature = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyExtras(intent);
    }

    /** adb shell am start -n tv.webremote/.MainActivity --ez server true --ez pair true --ez share true */
    private void applyExtras(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra("server")) Core.setServerEnabled(this, intent.getBooleanExtra("server", false));
        if (intent.getBooleanExtra("pair", false)) Core.pairing(this).openWindow(System.currentTimeMillis());
        if (intent.hasExtra("service") && canSwitchService()) switchService(intent.getBooleanExtra("service", true));
        if (intent.hasExtra("share") && intent.getBooleanExtra("share", false) == (ScreenCapture.current == null)) toggleShare();
    }

    @Override
    protected void onStart() {
        super.onStart();
        builtSignature = null;
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    private int dp(int value) {
        return (int) (value * density);
    }

    /** True when this app may edit the list of accessibility services itself (granted once with adb). */
    private boolean canSwitchService() {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** True when the service is on Android's enabled list. Entries may be written in the short form (tv.webremote/.RemoteService). */
    private boolean serviceInList() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        ComponentName me = new ComponentName(this, RemoteService.class);
        for (String entry : enabled.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private boolean serviceEnabled() {
        return serviceInList() || RemoteService.instance() != null;
    }

    /** Adds or removes only this service in the enabled list; every other accessibility service is left as it was. */
    private void switchService(boolean on) {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName me = new ComponentName(this, RemoteService.class);
            java.util.List<String> keep = new java.util.ArrayList<>();
            if (enabled != null) {
                for (String entry : enabled.split(":")) {
                    if (!entry.isEmpty() && !me.equals(ComponentName.unflattenFromString(entry))) keep.add(entry);
                }
            }
            if (on) keep.add(me.flattenToShortString());
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    android.text.TextUtils.join(":", keep));
            if (on) Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException notAllowed) {
            android.widget.Toast.makeText(this, R.string.service_switch_failed, android.widget.Toast.LENGTH_LONG).show();
        }
        builtSignature = null;
    }

    private Set<String> connectedIds() {
        RemoteService service = RemoteService.instance();
        return service == null ? java.util.Collections.<String>emptySet() : service.connectedDeviceIds();
    }

    /** Everything whose change needs the layout rebuilt. Countdowns, dots and last-seen times update in place. */
    private String signature() {
        Pairing pairing = Core.pairing(this);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(ScreenCapture.current != null).append(canSwitchService()).append(serviceEnabled()).append(Core.serverEnabled(this)).append(pairing.currentPin(now) != null)
                .append(pairing.lockedSeconds(now) > 0).append(tailscaleLaunchIntent() != null).append(Core.addresses());
        for (Pairing.Device d : pairing.devicesNewestFirst()) sb.append('|').append(d.id).append(d.lastIp);
        return sb.toString();
    }

    private void render() {
        String signature = signature();
        if (!signature.equals(builtSignature)) {
            Object focused = page.findFocus() == null ? null : page.findFocus().getTag();
            build();
            builtSignature = signature;
            if (focused != null) {
                View again = page.findViewWithTag(focused);
                if (again != null) again.requestFocus();
            }
        }
        updateLive();
    }

    private void build() {
        page.removeAllViews();
        Pairing pairing = Core.pairing(this);
        boolean on = Core.serverEnabled(this);
        boolean service = serviceEnabled();
        long now = System.currentTimeMillis();

        // Title
        LinearLayout title = row();
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.banner);
        logo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(88), dp(50)));
        logo.setClipToOutline(true);
        logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        title.addView(logo);
        LinearLayout names = column();
        names.setPadding(dp(12), 0, 0, 0);
        TextView name = text(getString(R.string.app_name), 30, TEXT);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        names.addView(name);
        names.addView(text(getString(R.string.subtitle), 15, DIM));
        title.addView(names);
        page.addView(title);

        // Card 1: status. One row each for the service, the server and screen sharing, with its switch on the right.
        LinearLayout status = card("📡", getString(R.string.status_title));
        boolean canSwitch = canSwitchService();
        View serviceButton = canSwitch
                ? toggleSwitch("service", service, () -> switchService(!serviceEnabled()))
                : (service ? null : button("open", getString(R.string.open_accessibility),
                        () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        status.addView(statusRow(service ? getString(R.string.service_on) : getString(R.string.service_off), service ? GREEN : RED,
                serviceButton));
        if (!canSwitch) status.addView(text(getString(R.string.service_grant_hint), 12, DIM));
        status.addView(statusRow(on ? getString(R.string.server_on) : getString(R.string.server_off), on ? GREEN : GREY,
                toggleSwitch("toggle", on, () -> {
                    Core.setServerEnabled(this, !Core.serverEnabled(this));
                    builtSignature = null;
                })));
        boolean sharing = ScreenCapture.current != null;
        LinearLayout shareRow = statusRow(sharing ? getString(R.string.share_on) : getString(R.string.share_off), sharing ? GREEN : GREY,
                toggleSwitch("share", sharing, this::toggleShare));
        ((TextView) shareRow.getChildAt(1)).setTag("sharetext");
        status.addView(shareRow);
        // The sound goes with the picture. It needs the recording permission, given once with adb.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.addView(text(getString(R.string.audio_permission), 12, DIM));
        }
        if (on) {
            status.addView(text(getString(R.string.open_on_device), 15, DIM));
            for (String address : Core.addresses()) {
                boolean ts = Core.isTailscale(address);
                LinearLayout line = row();
                line.addView(chip(ts ? getString(R.string.address_tailscale) : getString(R.string.address_home), ts ? PURPLE : BLUE));
                TextView url = text("  http://" + address + ":" + HttpServer.PORT, 22, TEXT);
                line.addView(url);
                status.addView(line);
            }
        }

        // Card 2: pairing
        LinearLayout pair = card("🔑", getString(R.string.pairing_title));
        String pin = pairing.currentPin(now);
        int locked = pairing.lockedSeconds(now);
        if (locked > 0) {
            TextView lock = text(getString(R.string.pairing_locked, locked), 17, RED);
            lock.setTag("lock");
            pair.addView(lock);
        } else {
            if (pin != null) {
                TextView pinView = text(pin.substring(0, 3) + " " + pin.substring(3), 56, TEXT);
                pinView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                pair.addView(pinView);
                TextView countdown = text("", 16, DIM);
                countdown.setTag("pin");
                pair.addView(countdown);
            } else {
                pair.addView(text(getString(R.string.pairing_hint), 16, DIM));
            }
            pair.addView(button("pair", getString(R.string.pair_new), () -> {
                if (!Core.serverEnabled(this)) Core.setServerEnabled(this, true);
                Core.pairing(this).openWindow(System.currentTimeMillis());
                builtSignature = null;
            }));
        }

        // Card 3: paired devices, newest first and numbered
        List<Pairing.Device> devices = pairing.devicesNewestFirst();
        LinearLayout list = card("📱", getString(R.string.devices_title, devices.size()));
        if (devices.isEmpty()) {
            list.addView(text(getString(R.string.no_devices), 16, DIM));
        } else {
            list.addView(text(getString(R.string.devices_hint), 14, DIM));
        }
        int number = 1;
        for (Pairing.Device d : devices) list.addView(deviceRow(number++, d, pairing));

        // Card 4: Tailscale for away from home
        LinearLayout remote = card("🌐", getString(R.string.tailscale_title));
        remote.addView(text(getString(R.string.tailscale_hint), 16, DIM));
        if (tailscaleLaunchIntent() == null) {
            // Not installed: only the way to get it.
            remote.addView(button("tailscale", getString(R.string.tailscale_install), this::openTailscale));
        } else {
            boolean connected = false;
            for (String a : Core.addresses()) if (Core.isTailscale(a)) connected = true;
            final boolean connectedNow = connected;
            remote.addView(statusRow(connected ? getString(R.string.tailscale_connected) : getString(R.string.tailscale_disconnected),
                    connected ? GREEN : GREY, toggleSwitch("tailscale_toggle", connected, () -> switchTailscale(!connectedNow))));
            remote.addView(button("tailscale", getString(R.string.tailscale_open), this::openTailscale));
        }

        // The last item: an info icon that opens the guide and the developer's name.
        LinearLayout about = row();
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        aboutParams.topMargin = dp(14);
        about.setLayoutParams(aboutParams);
        about.addView(infoButton());
        View language = languageButton();
        ((LinearLayout.LayoutParams) language.getLayoutParams()).leftMargin = dp(10);
        about.addView(language);
        page.addView(about);
    }

    private View infoButton() {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setTag("info");
        button.setContentDescription(getString(R.string.info_description));
        button.setImageResource(R.drawable.ic_info);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(states(shape(PANEL2, 0, 0, 12), shape(PRESSED, 0, 0, 12), shape(PRESSED, ACCENT, 2, 12)));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(40)));
        button.setOnClickListener(v -> showInfo());
        return button;
    }

    private View languageButton() {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setTag("language");
        button.setContentDescription(getString(R.string.language_description));
        button.setImageResource(R.drawable.ic_language);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(states(shape(PANEL2, 0, 0, 12), shape(PRESSED, 0, 0, 12), shape(PRESSED, ACCENT, 2, 12)));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(40)));
        button.setOnClickListener(v -> showLanguages());
        return button;
    }

    /** Pick Automatic (the TV's language), English or Vietnamese. The web pages follow the new language at once. */
    private void showLanguages() {
        String[] values = {Lang.AUTO, Lang.EN, Lang.VI};
        String[] labels = {getString(R.string.lang_auto), getString(R.string.lang_en), getString(R.string.lang_vi)};
        int current = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(Lang.choice(this))) current = i;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    Lang.setChoice(this, values[which]);
                    dialog.dismiss();
                    RemoteService.languageChanged();
                    recreate();
                })
                .setNegativeButton(R.string.info_close, null)
                .show();
    }

    /** The short guide for this app and who made it. */
    private void showInfo() {
        String message = getString(R.string.info_body) + "\n\n" + getString(R.string.info_developer) + ": " + getString(R.string.info_made_by);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.info_title)
                .setMessage(message)
                .setPositiveButton(R.string.info_close, null)
                .show();
    }

    /** Starts screen sharing (Android asks for its own permission first) or stops it. */
    private void toggleShare() {
        if (ScreenCapture.current != null) {
            stopService(new Intent(this, CaptureService.class));
            builtSignature = null;
            return;
        }
        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SHARE);
        } catch (android.content.ActivityNotFoundException unsupported) {
            android.widget.Toast.makeText(this, R.string.share_unsupported, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Asks the Tailscale app to connect or disconnect through the broadcasts it listens for. Another app cannot switch a VPN
     * on its own, so if this build of Tailscale ignores them, nothing changes and the app is opened instead.
     */
    private void switchTailscale(boolean on) {
        sendBroadcast(new Intent(on ? "com.tailscale.ipn.CONNECT_VPN" : "com.tailscale.ipn.DISCONNECT_VPN").setPackage(TAILSCALE));
        android.widget.Toast.makeText(this, on ? R.string.tailscale_turning_on : R.string.tailscale_turning_off,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private View deviceRow(int number, Pairing.Device d, Pairing pairing) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setBackground(rounded(0xFF232834, 14));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        TextView n = text(String.valueOf(number), 22, TEXT);
        n.setTypeface(null, android.graphics.Typeface.BOLD);
        n.setGravity(Gravity.CENTER);
        n.setBackground(rounded(0xFF2F3645, 18));
        n.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        n.setPadding(0, 0, 0, 0);
        row.addView(n);

        View dot = new View(this);
        dot.setTag("dot:" + d.id);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotParams.setMargins(dp(12), 0, dp(12), 0);
        dot.setLayoutParams(dotParams);
        row.addView(dot);

        LinearLayout info = column();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = text(d.name, 20, TEXT);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setPadding(0, 0, 0, dp(2));
        info.addView(name);
        LinearLayout where = row();
        if (d.lastIp != null) {
            boolean ts = NetPolicy.isTailscaleAddress(d.lastIp);
            where.addView(chip(ts ? getString(R.string.address_tailscale) : getString(R.string.address_home), ts ? PURPLE : BLUE));
            where.addView(text("  " + d.lastIp, 16, DIM));
        } else {
            where.addView(text(getString(R.string.device_no_ip), 16, DIM));
        }
        info.addView(where);
        TextView state = text("", 15, DIM);
        state.setTag("state:" + d.id);
        info.addView(state);
        row.addView(info);

        row.addView(unplugButton("revoke:" + d.id, getString(R.string.revoke_button), () -> {
            pairing.revoke(d.id);
            RemoteService s = RemoteService.instance();
            if (s != null) s.disconnectDevice(d.id);
            builtSignature = null;
        }));
        return row;
    }

    /** Countdowns, the online dots and the last-seen texts change every second without rebuilding the screen. */
    private void updateLive() {
        Pairing pairing = Core.pairing(this);
        long now = System.currentTimeMillis();
        TextView pin = page.findViewWithTag("pin");
        if (pin != null) pin.setText(getString(R.string.pin_valid, Math.max(0, (pairing.windowEndsAt() - now + 999) / 1000)));
        TextView lock = page.findViewWithTag("lock");
        if (lock != null) lock.setText(getString(R.string.pairing_locked, pairing.lockedSeconds(now)));
        ScreenCapture capture = ScreenCapture.current;
        TextView shareText = page.findViewWithTag("sharetext");
        if (shareText != null && capture != null) {
            shareText.setText(getString(R.string.share_on) + " · " + getString(R.string.share_viewers, capture.viewers()));
        }
        Set<String> connected = connectedIds();
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (Pairing.Device d : pairing.devicesNewestFirst()) {
            View dot = page.findViewWithTag("dot:" + d.id);
            TextView state = page.findViewWithTag("state:" + d.id);
            boolean online = connected.contains(d.id);
            if (dot != null) dot.setBackground(rounded(online ? GREEN : ORANGE, 8));
            if (state != null) {
                state.setText(online ? getString(R.string.device_online)
                        : getString(R.string.device_offline, format.format(new Date(d.lastSeen))));
                state.setTextColor(online ? GREEN : ORANGE);
            }
        }
    }

    private Intent tailscaleLaunchIntent() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(TAILSCALE);
        return intent != null ? intent : pm.getLaunchIntentForPackage(TAILSCALE);
    }

    /** Opens Tailscale if it is installed, else its page in the Play Store (or the web page if the store is missing). */
    private void openTailscale() {
        Intent launch = tailscaleLaunchIntent();
        try {
            if (launch != null) {
                startActivity(launch);
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + TAILSCALE))
                        .setPackage("com.android.vending"));
            } catch (android.content.ActivityNotFoundException noStore) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + TAILSCALE)));
            }
        } catch (android.content.ActivityNotFoundException nothingToOpenIt) {
            android.widget.Toast.makeText(this, R.string.tailscale_failed, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // --- small view helpers ---

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);   // a focused button zooms a little past its box
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    /** A rounded card with an icon and a heading, added to the page; returns the card to fill. */
    private LinearLayout card(String icon, String heading) {
        LinearLayout card = column();
        card.setClipChildren(false);
        card.setBackground(rounded(CARD, 20));
        card.setPadding(dp(20), dp(12), dp(20), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        card.setLayoutParams(params);
        LinearLayout header = row();
        header.addView(text(icon, 26, TEXT));
        TextView h = text("  " + heading, 22, TEXT);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(h);
        card.addView(header);
        page.addView(card);
        return card;
    }

    /** A status line (dot and text) with an optional switch (or button) at its right end. */
    private LinearLayout statusRow(String label, int color, View action) {
        LinearLayout line = row();
        View dot = new View(this);
        dot.setBackground(rounded(color, 8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(12), dp(12));
        p.rightMargin = dp(10);
        dot.setLayoutParams(p);
        line.addView(dot);
        TextView text = text(label, 18, TEXT);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(text);
        if (action != null) {
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ap.topMargin = dp(3);
            ap.bottomMargin = dp(3);
            action.setLayoutParams(ap);
            line.addView(action);
        }
        return line;
    }

    private LinearLayout statusLine(String label, int color) {
        LinearLayout line = row();
        View dot = new View(this);
        dot.setBackground(rounded(color, 8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(14), dp(14));
        p.rightMargin = dp(12);
        dot.setLayoutParams(p);
        line.addView(dot);
        line.addView(text(label, 18, TEXT));
        return line;
    }

    private TextView chip(String label, int color) {
        TextView chip = text(label, 14, TEXT);
        chip.setTypeface(null, android.graphics.Typeface.BOLD);
        chip.setPadding(dp(12), dp(3), dp(12), dp(3));
        chip.setBackground(rounded(color, 12));
        return chip;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * TEXT_SCALE);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(4));
        view.setGravity(Gravity.START);
        return view;
    }

    private Button button(String tag, String label, Runnable action) {
        Button button = new Button(this);
        button.setTag(tag);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(18), dp(6), dp(18), dp(6));
        button.setTextColor(TEXT);
        button.setStateListAnimator(null);
        // Like the web page's buttons: a flat panel, a lighter blue-grey while pressed, an accent ring while focused.
        button.setBackground(states(shape(PANEL2, 0, 0, 14), shape(PRESSED, 0, 0, 14), shape(PRESSED, ACCENT, 2, 14)));
        // Full width with a little air above when stacked in a card; the rows that hold a switch set their own size.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private final java.util.Map<String, Boolean> lastSwitch = new java.util.HashMap<>();

    /**
     * An on/off switch at the end of a status row: a track with a round thumb that slides. Pressing OK flips it; on the
     * remote, right turns it on and left turns it off. The screen is rebuilt after a change, so the new switch starts where
     * the old one was and slides from there.
     */
    private View toggleSwitch(String tag, boolean on, Runnable action) {
        Boolean before = lastSwitch.put(tag, on);
        SwitchView view = new SwitchView(on, before == null ? on : before);
        view.setTag(tag);
        view.setContentDescription(getString(on ? R.string.turn_off : R.string.turn_on));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private final class SwitchView extends View {
        private static final int OFF_TRACK = 0xFF4A5266;
        private final boolean on;
        private float position;
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumb = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.animation.ArgbEvaluator blend = new android.animation.ArgbEvaluator();

        SwitchView(boolean on, boolean from) {
            super(MainActivity.this);
            this.on = on;
            position = from ? 1f : 0f;
            setFocusable(true);
            setClickable(true);
            thumb.setColor(0xFFFFFFFF);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(ACCENT);   // the focus ring is the same blue ring as on every other control
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            float target = on ? 1f : 0f;
            if (position == target) return;
            android.animation.ValueAnimator slide = android.animation.ValueAnimator.ofFloat(position, target);
            slide.setDuration(160);
            slide.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            slide.start();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(dp(64), dp(36));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float w = getWidth(), h = getHeight();
            android.graphics.RectF body = new android.graphics.RectF(dp(3), dp(4), w - dp(3), h - dp(4));
            track.setColor((Integer) blend.evaluate(position, OFF_TRACK, GREEN));
            canvas.drawRoundRect(body, body.height() / 2, body.height() / 2, track);
            float radius = body.height() / 2 - dp(3);
            float travel = body.width() - 2 * (dp(3) + radius);
            canvas.drawCircle(body.left + dp(3) + radius + position * travel, body.centerY(), radius, thumb);
            if (isFocused()) {
                float inset = dp(1);
                canvas.drawRoundRect(new android.graphics.RectF(inset, inset, w - inset, h - inset), h / 2, h / 2, ring);
            }
        }

        @Override
        public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && !on) {
                performClick();
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && on) {
                performClick();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
    }

    private GradientDrawable shape(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private android.graphics.drawable.StateListDrawable states(android.graphics.drawable.Drawable normal,
            android.graphics.drawable.Drawable pressed, android.graphics.drawable.Drawable focused) {
        android.graphics.drawable.StateListDrawable list = new android.graphics.drawable.StateListDrawable();
        list.addState(new int[]{android.R.attr.state_pressed}, pressed);
        list.addState(new int[]{android.R.attr.state_focused}, focused);
        list.addState(new int[]{}, normal);
        return list;
    }

    /**
     * The Thu hồi button: the web page's Hủy ghép button, an unplug icon in a red outline. Its hover is the faint red fill,
     * its press the stronger one; focused with the remote it also gets the accent ring.
     */
    private View unplugButton(String tag, String description, Runnable action) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setTag(tag);
        button.setContentDescription(description);
        button.setImageResource(R.drawable.ic_unplug);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setStateListAnimator(null);
        button.setBackground(states(shape(0x00000000, DANGER, 1, 12), shape(0x47F27A7A, DANGER, 1, 12), shape(0x24F27A7A, ACCENT, 2, 12)));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(38)));
        button.setOnClickListener(v -> action.run());
        return button;
    }
}
