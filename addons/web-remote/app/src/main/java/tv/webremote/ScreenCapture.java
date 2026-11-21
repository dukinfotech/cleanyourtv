package tv.webremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The TV's screen as a stream of JPEG frames, taken through MediaProjection. The virtual display only draws while at least
 * one paired page is watching, so nothing is encoded when nobody looks. Frames are capped at about 60 a second and scaled
 * to the width of the chosen mode; a viewer that cannot keep up is given the newest frame and skips the rest.
 */
final class ScreenCapture {

    /** The running capture, or null while sharing is off. */
    static volatile ScreenCapture current;

    static final int MIN_INTERVAL_MS = 16;

    private final MediaProjection projection;
    private final java.util.concurrent.CopyOnWriteArrayList<Controller.AudioSink> audioSinks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private AudioCapture audio;
    private final HandlerThread thread = new HandlerThread("web-remote-capture");
    private final Handler handler;
    private ImageReader reader;
    private final VirtualDisplay display;
    private final int screenW, screenH, dpi;
    private int width, height, quality = 60;
    private volatile float encodeMs;
    private final CopyOnWriteArrayList<Controller.FrameSink> sinks = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(96 * 1024);
    private Bitmap padded;
    private volatile byte[] last;
    private long lastAt;
    private boolean pullScheduled, stopped;

    ScreenCapture(Context context, MediaProjection projection) {
        this.projection = projection;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
        dpi = metrics.densityDpi;
        width = Math.min(960, screenW);
        height = heightFor(width);
        thread.start();
        handler = new Handler(thread.getLooper());
        // Android 14 wants the callback registered before the first virtual display is made.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stop();
            }
        }, handler);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> pull(), handler);
        display = projection.createVirtualDisplay("web-remote", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler);
        current = this;
    }

    private int heightFor(int w) {
        return Math.max(2, (screenH * w / screenW) & ~1);
    }

    /** Average milliseconds spent turning one frame into a JPEG. */
    int encodeMs() {
        return Math.round(encodeMs);
    }

    /**
     * Picture size and JPEG quality: "smooth" (640 px wide), "balanced" (960) or "sharp" (1280). Every viewer gets the
     * setting, since there is one capture. Anything else is ignored.
     */
    void setMode(String mode) {
        final int w, q;
        switch (mode == null ? "" : mode) {
            case "smooth": w = 640; q = 50; break;
            case "balanced": w = 960; q = 60; break;
            case "sharp": w = 1280; q = 70; break;
            default: return;
        }
        final int target = Math.min(w, screenW);
        handler.post(() -> {
            if (stopped || (target == width && q == quality)) return;
            quality = q;
            if (target == width) return;
            ImageReader fresh = ImageReader.newInstance(target, heightFor(target), PixelFormat.RGBA_8888, 2);
            fresh.setOnImageAvailableListener(r -> pull(), handler);
            ImageReader old = reader;
            width = target;
            height = heightFor(target);
            reader = fresh;
            if (padded != null) {
                padded.recycle();
                padded = null;
            }
            // Android 14 allows one virtual display per projection, so the same one is resized and pointed at the new reader.
            display.resize(width, height, dpi);
            display.setSurface(sinks.isEmpty() ? null : fresh.getSurface());
            old.close();
        });
    }

    /** Starts listening for this sink, starting the sound capture if it is the first. False when it cannot start. */
    synchronized boolean audioSubscribe(Controller.AudioSink sink) {
        if (stopped || android.os.Build.VERSION.SDK_INT < 29) return false;   // capturing sound needs Android 10
        if (audio == null) {
            try {
                AudioCapture started = new AudioCapture(projection, chunk -> {
                    for (Controller.AudioSink s : audioSinks) s.audio(chunk);
                });
                started.start();
                audio = started;
            } catch (RuntimeException cannot) {
                return false;
            }
        }
        audioSinks.add(sink);
        return true;
    }

    synchronized void audioUnsubscribe(Controller.AudioSink sink) {
        audioSinks.remove(sink);
        if (audioSinks.isEmpty() && audio != null) {
            audio.stop();
            audio = null;
        }
    }

    /** The sound switch was turned off, or sharing stopped: tells every listener and stops the capture. */
    synchronized void audioEnded() {
        for (Controller.AudioSink s : audioSinks) s.ended();
        audioSinks.clear();
        if (audio != null) {
            audio.stop();
            audio = null;
        }
    }

    int listeners() {
        return audioSinks.size();
    }

    int viewers() {
        return sinks.size();
    }

    void subscribe(Controller.FrameSink sink) {
        sinks.add(sink);
        handler.post(() -> {
            if (!stopped && sinks.size() == 1) display.setSurface(reader.getSurface());
        });
        byte[] latest = last;
        if (latest != null) sink.frame(latest);
    }

    void unsubscribe(Controller.FrameSink sink) {
        sinks.remove(sink);
        handler.post(() -> {
            if (!stopped && sinks.isEmpty()) display.setSurface(null);
        });
    }

    /** Takes the newest frame, unless the last one went out less than MIN_INTERVAL_MS ago: then it tries again shortly. */
    private void pull() {
        if (stopped) return;
        long wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastAt);
        if (wait > 0) {
            // Skipping the frame for good could leave a still screen showing an old picture.
            if (!pullScheduled) {
                pullScheduled = true;
                handler.postDelayed(() -> {
                    pullScheduled = false;
                    pull();
                }, wait);
            }
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            if (sinks.isEmpty()) return;
            lastAt = SystemClock.uptimeMillis();
            byte[] frame = encode(image);
            float took = SystemClock.uptimeMillis() - lastAt;
            encodeMs = encodeMs == 0 ? took : encodeMs * 0.85f + took * 0.15f;
            last = frame;
            for (Controller.FrameSink sink : sinks) sink.frame(frame);
        } catch (RuntimeException | OutOfMemoryError skipped) {
            // one bad frame is not worth stopping the stream
        } finally {
            if (image != null) image.close();
        }
    }

    private byte[] encode(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        if (padded == null || padded.getWidth() != paddedWidth) {
            if (padded != null) padded.recycle();
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        }
        padded.copyPixelsFromBuffer(pixels);
        jpeg.reset();
        if (paddedWidth == width) {
            padded.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
        } else {
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
            cropped.recycle();
        }
        return jpeg.toByteArray();
    }

    void stop() {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
        }
        if (current == this) current = null;
        for (Controller.FrameSink sink : sinks) sink.ended();
        sinks.clear();
        audioEnded();
        try {
            display.release();
            reader.close();
            projection.stop();
        } catch (RuntimeException alreadyGone) {
            // released by the system first
        }
        thread.quitSafely();
        if (padded != null) padded.recycle();
    }
}
