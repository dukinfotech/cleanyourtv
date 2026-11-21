package tv.webremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WebSocketFramesTest {

    /** A masked client frame, as a browser sends it. */
    static byte[] clientFrame(int opcode, boolean fin, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write(payload.length >> 8);
            out.write(payload.length & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (((long) payload.length >> shift) & 0xFF));
        }
        out.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
        return out.toByteArray();
    }

    private static String read(byte[] frames, ByteArrayOutputStream reply, int max) throws IOException {
        return WebSocketFrames.readText(new ByteArrayInputStream(frames), reply, max);
    }

    @Test
    public void acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void readsTheRfcMaskedHello() throws IOException {
        byte[] frame = {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xfa, 0x21, 0x3d, 0x7f, (byte) 0x9f, 0x4d, 0x51, 0x58};
        assertEquals("Hello", read(frame, new ByteArrayOutputStream(), 100));
    }

    @Test
    public void readsLongAndFragmentedMessages() throws IOException {
        byte[] big = new byte[70_000];
        java.util.Arrays.fill(big, (byte) 'a');
        assertEquals(70_000, read(clientFrame(1, true, big), new ByteArrayOutputStream(), 100_000).length());

        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.write(clientFrame(1, false, "xin ".getBytes(StandardCharsets.UTF_8)));
        both.write(clientFrame(0, true, "chào".getBytes(StandardCharsets.UTF_8)));
        assertEquals("xin chào", read(both.toByteArray(), new ByteArrayOutputStream(), 100));
    }

    @Test
    public void answersAPingAndKeepsReading() throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(clientFrame(9, true, "p".getBytes(StandardCharsets.UTF_8)));
        frames.write(clientFrame(1, true, "next".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        assertEquals("next", read(frames.toByteArray(), reply, 100));
        assertArrayEquals(new byte[]{(byte) 0x8A, 1, 'p'}, reply.toByteArray());
    }

    @Test
    public void aCloseFrameEndsTheConversation() throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        assertNull(read(clientFrame(8, true, new byte[0]), reply, 100));
        assertEquals((byte) 0x88, reply.toByteArray()[0]);
    }

    @Test
    public void refusesUnmaskedOversizedAndReservedFrames() {
        try {
            read(new byte[]{(byte) 0x81, 0x01, 'x'}, new ByteArrayOutputStream(), 100);
            fail("unmasked");
        } catch (IOException expected) {
            // ok
        }
        try {
            read(clientFrame(1, true, new byte[200]), new ByteArrayOutputStream(), 100);
            fail("too large");
        } catch (IOException expected) {
            // ok
        }
        try {
            read(new byte[]{(byte) 0xC1, (byte) 0x80, 0, 0, 0, 0}, new ByteArrayOutputStream(), 100);
            fail("reserved bits");
        } catch (IOException expected) {
            // ok
        }
        try {
            read(clientFrame(0, true, new byte[]{1}), new ByteArrayOutputStream(), 100);
            fail("continuation with nothing to continue");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void writesServerFramesUnmaskedWithTheRightLengthForm() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketFrames.writeText(out, "hi");
        assertArrayEquals(new byte[]{(byte) 0x81, 2, 'h', 'i'}, out.toByteArray());

        out.reset();
        byte[] medium = new byte[300];
        java.util.Arrays.fill(medium, (byte) 'b');
        WebSocketFrames.writeText(out, new String(medium, StandardCharsets.US_ASCII));
        byte[] written = out.toByteArray();
        assertEquals((byte) 126, written[1]);
        assertEquals(300, ((written[2] & 0xFF) << 8) | (written[3] & 0xFF));
        assertEquals(304, written.length);

        out.reset();
        WebSocketFrames.writeText(out, new String(new byte[70_000], StandardCharsets.US_ASCII));
        assertEquals((byte) 127, out.toByteArray()[1]);
        assertEquals(70_010, out.size());
    }
}
