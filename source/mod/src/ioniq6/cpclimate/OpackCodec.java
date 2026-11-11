package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPACK to and from plain Java values: null, Boolean, Long, Double, String, byte[] (data),
 * Uuid, List, Map (in order), and Raw (bytes already OPACK, written as they are). Writes as
 * LIVI opack.rs does (no back-references, so Raw pieces can be put anywhere).
 * Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
 */
final class OpackCodec {
    static final class Uuid {
        final byte[] b;

        Uuid(byte[] b) {
            this.b = b;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Uuid && java.util.Arrays.equals(b, ((Uuid) o).b);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(b);
        }
    }

    /** a value already in OPACK */
    static final class Raw {
        final byte[] b;

        Raw(byte[] b) {
            this.b = b;
        }
    }

    // ---- decode ----

    private final byte[] buf;
    private int at, depth;
    private final List<Object> seen = new ArrayList<Object>();

    private OpackCodec(byte[] buf) {
        this.buf = buf;
    }

    /** buf as one value; IllegalArgumentException if it isn't exactly one OPACK value */
    static Object decode(byte[] buf) {
        try {
            OpackCodec d = new OpackCodec(buf);
            Object v = d.value();
            if (d.at != buf.length) throw new IllegalArgumentException("trailing bytes");
            return v;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }

    private int take() {
        if (at >= buf.length) throw new IllegalArgumentException("short");
        return buf[at++] & 0xff;
    }

    private byte[] take(int n) {
        if (n < 0 || at + n > buf.length) throw new IllegalArgumentException("short");
        byte[] b = java.util.Arrays.copyOfRange(buf, at, at + n);
        at += n;
        return b;
    }

    private long unsigned(int width) {
        long v = 0;
        for (int i = 0; i < width; i++) v |= (long) take() << (8 * i);
        return v;
    }

    private int len(int tag, int base) {
        int n = tag - base;
        if (n <= 0x20) return n;
        long v = unsigned(n - 0x20);
        if (v < 0 || v > buf.length) throw new IllegalArgumentException("length");
        return (int) v;
    }

    private Object remember(Object v) {
        seen.add(v);
        return v;
    }

    private Object value() {
        if (++depth > 64) throw new IllegalArgumentException("deep");
        try {
            return item();
        } finally {
            depth--;
        }
    }

    private Object item() {
        int tag = take();
        if (tag == 0x01) return Boolean.TRUE;
        if (tag == 0x02) return Boolean.FALSE;
        if (tag == 0x04) return null;
        // OPACK's one-byte -1 (the phone sends it for seat cooling level 1; without this the whole write was dropped)
        if (tag == 0x07) return -1L;
        if (tag == 0x05) return remember(new Uuid(take(16)));
        if (tag == 0x06 || tag == 0x36) return remember(Double.longBitsToDouble(unsigned(8)));
        if (tag == 0x35) return remember((double) Float.intBitsToFloat((int) unsigned(4)));
        if (tag >= 0x08 && tag < 0x30) return (long) (tag - 0x08);
        if (tag >= 0x30 && tag <= 0x33) {
            int width = 1 << (tag - 0x30);
            long raw = unsigned(width);
            int shift = 64 - 8 * width;
            return remember((raw << shift) >> shift);
        }
        if (tag >= 0x40 && tag <= 0x64) return remember(new String(take(len(tag, 0x40)), java.nio.charset.Charset.forName("UTF-8")));
        if (tag >= 0x70 && tag <= 0x94) return remember(take(len(tag, 0x70)));
        if (tag >= 0xA0 && tag <= 0xC0) return ref(tag - 0xA0);
        if (tag >= 0xC1 && tag <= 0xC4) return ref((int) unsigned(tag - 0xC0));
        if (tag >= 0xD0 && tag <= 0xDF) {
            boolean open = tag == 0xDF;
            List<Object> items = new ArrayList<Object>();
            for (int i = 0; open || i < tag - 0xD0; i++) {
                if (open && at < buf.length && (buf[at] & 0xff) == 0x03) { at++; break; }
                items.add(value());
            }
            return items;
        }
        if (tag >= 0xE0 && tag <= 0xEF) {
            boolean open = tag == 0xEF;
            Map<Object, Object> m = new LinkedHashMap<Object, Object>();
            for (int i = 0; open || i < tag - 0xE0; i++) {
                if (open && at < buf.length && (buf[at] & 0xff) == 0x03) { at++; break; }
                Object k = value();
                m.put(k, value());
            }
            return m;
        }
        throw new IllegalArgumentException("tag 0x" + Integer.toHexString(tag));
    }

    private Object ref(int i) {
        if (i < 0 || i >= seen.size()) throw new IllegalArgumentException("ref " + i);
        return seen.get(i);
    }

    // ---- encode ----

    static byte[] encode(Object v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, v);
        return out.toByteArray();
    }

    static void writeLen(ByteArrayOutputStream out, int base, int n) {
        if (n <= 0x20) {
            out.write(base + n);
            return;
        }
        int width = n <= 0xFF ? 1 : n <= 0xFFFF ? 2 : n <= 0xFFFFFF ? 3 : 4;
        out.write(base + 0x20 + width);
        for (int i = 0; i < width; i++) out.write(n >>> (8 * i));
    }

    /** true if the count needs an end marker (more than 14 items) */
    static boolean writeCount(ByteArrayOutputStream out, int base, int n) {
        if (n <= 14) {
            out.write(base + n);
            return false;
        }
        out.write(base + 0x0F);
        return true;
    }

    @SuppressWarnings("unchecked")
    static void write(ByteArrayOutputStream out, Object v) {
        if (v == null) {
            out.write(0x04);
        } else if (v instanceof Raw) {
            byte[] b = ((Raw) v).b;
            out.write(b, 0, b.length);
        } else if (v instanceof Boolean) {
            out.write((Boolean) v ? 0x01 : 0x02);
        } else if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            if (n >= 0 && n <= 39) {
                out.write(0x08 + (int) n);
                return;
            }
            int width = n == (byte) n ? 1 : n == (short) n ? 2 : n == (int) n ? 4 : 8;
            out.write(0x30 + Integer.numberOfTrailingZeros(width));
            for (int i = 0; i < width; i++) out.write((int) (n >>> (8 * i)));
        } else if (v instanceof Double || v instanceof Float) {
            long bits = Double.doubleToLongBits(((Number) v).doubleValue());
            out.write(0x36);
            for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)));
        } else if (v instanceof String) {
            byte[] s = ((String) v).getBytes(java.nio.charset.Charset.forName("UTF-8"));
            writeLen(out, 0x40, s.length);
            out.write(s, 0, s.length);
        } else if (v instanceof byte[]) {
            byte[] d = (byte[]) v;
            writeLen(out, 0x70, d.length);
            out.write(d, 0, d.length);
        } else if (v instanceof Uuid) {
            out.write(0x05);
            out.write(((Uuid) v).b, 0, 16);
        } else if (v instanceof List) {
            List<Object> items = (List<Object>) v;
            boolean open = writeCount(out, 0xD0, items.size());
            for (Object item : items) write(out, item);
            if (open) out.write(0x03);
        } else if (v instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) v;
            boolean open = writeCount(out, 0xE0, m.size());
            for (Map.Entry<Object, Object> e : m.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            if (open) out.write(0x03);
        } else {
            throw new IllegalArgumentException("can't write " + v.getClass().getName());
        }
    }

    /** a value read back as a long, or null */
    static Long asLong(Object v) {
        return v instanceof Long ? (Long) v : v instanceof Integer ? Long.valueOf((Integer) v) : null;
    }
}
