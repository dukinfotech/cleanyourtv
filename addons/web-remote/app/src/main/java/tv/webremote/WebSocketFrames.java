package tv.webremote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Just enough of RFC 6455 for text messages between a browser and this server. */
final class WebSocketFrames {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketFrames() {}

    /** The Sec-WebSocket-Accept value for a client's Sec-WebSocket-Key. */
    static String acceptKey(String clientKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((clientKey.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Reads the next text message, joining fragments. Answers pings itself. Returns null when the peer closes. Frames
     * from a client must be masked and no message may exceed maxLength bytes: either breach throws.
     */
    static String readText(InputStream in, OutputStream out, int maxLength) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inText = false;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.read();
            if (b1 < 0) throw new EOFException();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if ((b0 & 0x70) != 0) throw new IOException("reserved bits set");
            if ((b1 & 0x80) == 0) throw new IOException("client frame is not masked");
            long length = b1 & 0x7F;
            if (length == 126) {
                length = (readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
            }
            if (length < 0 || length > maxLength) throw new IOException("frame too large");
            byte[] mask = new byte[4];
            readFully(in, mask, 4);
            byte[] payload = new byte[(int) length];
            readFully(in, payload, payload.length);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (opcode) {
                case 0x8:
                    writeFrame(out, 0x8, new byte[0]);
                    return null;
                case 0x9:
                    writeFrame(out, 0xA, payload);
                    break;
                case 0xA:
                    break;
                case 0x1:
                case 0x0:
                    if (opcode == 0x1) {
                        message.reset();
                        inText = true;
                    } else if (!inText) {
                        throw new IOException("unexpected continuation");
                    }
                    if (message.size() + payload.length > maxLength) throw new IOException("message too large");
                    message.write(payload);
                    if (fin) return new String(message.toByteArray(), StandardCharsets.UTF_8);
                    break;
                default:
                    break;
            }
        }
    }

    static void writeText(OutputStream out, String text) throws IOException {
        writeFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** One binary message: used for the screen's JPEG frames. */
    static void writeBinary(OutputStream out, byte[] data) throws IOException {
        writeFrame(out, 0x2, data);
    }

    static void writeClose(OutputStream out) throws IOException {
        writeFrame(out, 0x8, new byte[0]);
    }

    /** A server frame is never masked. Writers to one socket must not interleave: callers hold a lock on out. */
    static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (((long) payload.length >> shift) & 0xFF));
            }
            out.write(payload);
            out.flush();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void readFully(InputStream in, byte[] buffer, int count) throws IOException {
        int done = 0;
        while (done < count) {
            int n = in.read(buffer, done, count - done);
            if (n < 0) throw new EOFException();
            done += n;
        }
    }
}
