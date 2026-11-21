package tv.webremote;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;

/**
 * The sound other apps are playing, through the screen-capture permission (Android 10 and later). Apps that keep their sound
 * from being captured, DRM video among them, come out silent. The stream is 48 kHz stereo made into 24 kHz mono, 20 ms at
 * a time, so a page needs about 380 kbit/s to listen.
 */
@android.annotation.TargetApi(android.os.Build.VERSION_CODES.Q)
final class AudioCapture {

    /** Where the chunks go. */
    interface Out {
        void chunk(byte[] tagged);
    }

    static final byte TAG = 1;
    private static final int RATE = 48_000;

    private final AudioRecord record;
    private final Out out;
    private volatile boolean running;
    private Thread thread;

    /** The recording permission is checked by the caller (RemoteService.audioReady) before this is reached. */
    @android.annotation.SuppressLint("MissingPermission")
    AudioCapture(MediaProjection projection, Out out) {
        this.out = out;
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min, 16_384)).build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("audio capture did not start");
        }
    }

    void start() {
        record.startRecording();
        running = true;
        thread = new Thread(this::loop, "web-remote-audio");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        short[] in = new short[1920];   // 20 ms of stereo at 48 kHz
        while (running) {
            int n = record.read(in, 0, in.length);
            if (n < 0) break;
            int outSamples = n / 4;     // two samples per frame, then half the frames
            if (outSamples == 0) continue;
            byte[] chunk = new byte[1 + 2 * outSamples];
            chunk[0] = TAG;
            for (int j = 0; j < outSamples; j++) {
                int i = 4 * j;
                int mono = (in[i] + in[i + 1] + in[i + 2] + in[i + 3]) / 4;
                chunk[1 + 2 * j] = (byte) (mono & 0xFF);
                chunk[2 + 2 * j] = (byte) ((mono >> 8) & 0xFF);
            }
            out.chunk(chunk);
        }
    }

    void stop() {
        running = false;
        try {
            record.stop();
        } catch (IllegalStateException alreadyStopped) {
            // fine
        }
        record.release();
    }
}
