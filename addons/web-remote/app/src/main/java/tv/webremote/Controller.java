package tv.webremote;

import java.util.List;

/** What a paired device can make the TV do. Implemented by {@link RemoteService}; called from connection threads. */
interface Controller {

    final class AppInfo {
        final String id, label;

        AppInfo(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** "en" or "vi": the app's language, which the web page follows. */
    String language();

    int screenWidth();

    int screenHeight();

    /** Moves the pointer by a touchpad delta; the controller applies its own gain. */
    void move(float dx, float dy);

    /** A tap where the pointer is. */
    void tap();

    void doubleTap();

    void back();

    /** Starts holding at the pointer; {@link #dragEnd} drops where the pointer has moved to. */
    void dragStart();

    void dragEnd();

    /** dy greater than 0 reads further down the page. */
    void scroll(float dx, float dy);

    /** One of the key names the page sends; false when this TV cannot do it. */
    boolean key(String key);

    /** Sets the focused text field to exactly this string; false when no field is focused. */
    boolean text(String value);

    boolean enter();

    boolean openUrl(String url);

    List<AppInfo> apps();

    boolean launch(String id);

    /** A page watching the TV's screen: gets each JPEG frame, and a call when sharing stops. Frames are never modified. */
    interface FrameSink {
        void frame(byte[] jpeg);

        void ended();
    }

    /** A page listening to the TV's sound: gets small PCM chunks (tag byte 1, then 16-bit little-endian mono at 24 kHz). */
    interface AudioSink {
        void audio(byte[] chunk);

        void ended();
    }

    /** True while the TV lets pages listen: screen sharing is on (the sound is shared with the picture) and the recording permission is given. */
    boolean audioReady();

    /** Starts the sound capture if it is not running. False when it cannot start. */
    boolean audioSubscribe(AudioSink sink);

    void audioUnsubscribe(AudioSink sink);

    /** True while the person has allowed screen sharing on the TV. */
    boolean screenReady();

    void screenSubscribe(FrameSink sink);

    void screenUnsubscribe(FrameSink sink);

    /** "smooth", "balanced" or "sharp": the picture size and quality of the screen stream. Unknown names are ignored. */
    void screenQuality(String mode);

    /** Average milliseconds the TV spends encoding one frame, for the page to show. */
    int screenEncodeMs();

    /** Puts the pointer at a place on the screen, as fractions of its width and height. */
    void pointAt(float fx, float fy);

    /**
     * The app's banner as a PNG, the wide picture the TV's launcher draws as its tile, or null when it has none (then the page
     * shows its icon on a dark tile, as the launcher does).
     */
    byte[] appBanner(String id);

    /** The app's icon as a small PNG, as the TV shows it; null when the id is not a launchable app. */
    byte[] appIcon(String id);
}
