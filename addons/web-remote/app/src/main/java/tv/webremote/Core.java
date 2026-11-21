package tv.webremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** State shared by the service and the status screen: the pairing record, the on/off switch and the TV's addresses. */
final class Core {

    static final String PREFS = "web_remote";
    private static final String DEVICES = "devices", SERVER_ON = "server_on";

    private static Pairing pairing;

    private Core() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized Pairing pairing(Context context) {
        if (pairing == null) {
            SharedPreferences p = prefs(context);
            pairing = new Pairing(new Pairing.Store() {
                @Override
                public String load() {
                    return p.getString(DEVICES, null);
                }

                @Override
                public void save(String json) {
                    p.edit().putString(DEVICES, json).apply();
                }
            });
        }
        return pairing;
    }

    static boolean serverEnabled(Context context) {
        return prefs(context).getBoolean(SERVER_ON, false);
    }

    /** Turns the server on or off, now if the service is running and at its next start if not. */
    static void setServerEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(SERVER_ON, on).apply();
        RemoteService service = RemoteService.instance();
        if (service != null) service.applyServerSetting();
    }

    /** The TV's IPv4 addresses a device could use: the home network's and, if present, Tailscale's. */
    static List<String> addresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && NetPolicy.isAllowedPeer(a)) found.add(a.getHostAddress());
                }
            }
        } catch (java.net.SocketException none) {
            // no network
        }
        return found;
    }

    static boolean isTailscale(String address) {
        return NetPolicy.isTailscaleAddress(address);
    }
}
