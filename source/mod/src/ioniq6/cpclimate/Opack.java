package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple's OPACK decoder (as LIVI opack.rs reads it), producing a readable text for the log.
 * Data that is itself OPACK (the vehicle state messages carry theirs in "pluginData") is shown
 * decoded too. Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
 */
final class Opack {
    static final int MAX_DEPTH = 64;

    private final byte[] buf;
    private int at, depth;
    private final List<String> seen = new ArrayList<String>();

    private Opack(byte[] buf) {
        this.buf = buf;
    }

    /** buf as text, or null if it isn't one whole OPACK value */
    static String show(byte[] buf) {
        try {
            Opack o = new Opack(buf);
            String v = o.value();
            return o.at == buf.length ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int take() {
        if (at >= buf.length) throw new IllegalStateException("short");
        return buf[at++] & 0xff;
    }

    private long unsigned(int width) {
        long v = 0;
        for (int i = 0; i < width; i++) v |= (long) take() << (8 * i);
        return v;
    }

    private long signed(int width) {
        long raw = unsigned(width);
        int shift = 64 - 8 * width;
        return (raw << shift) >> shift;
    }

    private int len(int tag, int base) {
        int n = tag - base;
        if (n <= 0x20) return n;
        long v = unsigned(n - 0x20);
        if (v < 0 || v > buf.length) throw new IllegalStateException("length");
        return (int) v;
    }

    private String remember(String v) {
        seen.add(v);
        return v;
    }

    private String value() {
        if (++depth > MAX_DEPTH) throw new IllegalStateException("deep");
        try {
            return item();
        } finally {
            depth--;
        }
    }

    private String item() {
        int tag = take();
        if (tag == 0x01) return "true";
        if (tag == 0x02) return "false";
        if (tag == 0x04) return "null";
        if (tag == 0x05) {
            StringBuilder u = new StringBuilder("uuid:");
            for (int i = 0; i < 16; i++) u.append(String.format("%02x", take()));
            return remember(u.toString());
        }
        if (tag == 0x06 || tag == 0x36) return remember(String.valueOf(Double.longBitsToDouble(unsigned(8))));
        if (tag == 0x35) return remember(String.valueOf(Float.intBitsToFloat((int) unsigned(4))));
        if (tag >= 0x08 && tag < 0x30) return String.valueOf(tag - 0x08);
        if (tag >= 0x30 && tag <= 0x33) return remember(String.valueOf(signed(1 << (tag - 0x30))));
        if (tag >= 0x40 && tag <= 0x64) {
            int n = len(tag, 0x40);
            if (at + n > buf.length) throw new IllegalStateException("string");
            String s = new String(buf, at, n, java.nio.charset.Charset.forName("UTF-8"));
            at += n;
            return remember('"' + s + '"');
        }
        if (tag >= 0x70 && tag <= 0x94) {
            int n = len(tag, 0x70);
            if (at + n > buf.length) throw new IllegalStateException("data");
            byte[] d = new byte[n];
            System.arraycopy(buf, at, d, 0, n);
            at += n;
            String inner = n > 0 ? show(d) : null;
            return remember(inner != null ? "opack" + inner : "<" + n + " bytes" + (n <= 16 ? " " + hex(d) : "") + ">");
        }
        if (tag >= 0xA0 && tag <= 0xC0) return ref(tag - 0xA0);
        if (tag >= 0xC1 && tag <= 0xC4) return ref((int) unsigned(tag - 0xC0));
        if (tag >= 0xD0 && tag <= 0xDF) {
            boolean open = tag - 0xD0 == 0x0F;
            int count = tag - 0xD0;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; open || i < count; i++) {
                if (open && at < buf.length && (buf[at] & 0xff) == 0x03) { at++; break; }
                if (i > 0) b.append(", ");
                b.append(value());
            }
            return b.append(']').toString();
        }
        if (tag >= 0xE0 && tag <= 0xEF) {
            boolean open = tag - 0xE0 == 0x0F;
            int count = tag - 0xE0;
            StringBuilder b = new StringBuilder("{");
            for (int i = 0; open || i < count; i++) {
                if (open && at < buf.length && (buf[at] & 0xff) == 0x03) { at++; break; }
                if (i > 0) b.append(", ");
                b.append(value()).append(": ").append(value());
            }
            return b.append('}').toString();
        }
        throw new IllegalStateException("tag " + tag);
    }

    private String ref(int i) {
        if (i < 0 || i >= seen.size()) throw new IllegalStateException("ref");
        return seen.get(i);
    }

    static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }
}
