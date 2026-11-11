package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small binary property list (bplist00) codec for the uiSync channel (LIVI bplist.rs): dictionaries
 * (LinkedHashMap, string keys), arrays (List), strings, data (byte[]), integers (Long), booleans,
 * reals (Double) and null. Uids and dates are read as Long and Double. No object sharing on write.
 */
final class Bplist {
    static final Charset ASCII = Charset.forName("US-ASCII"), UTF16 = Charset.forName("UTF-16BE");
    static final int MAX_OBJECTS = 100000;

    private final byte[] b;
    private final int offsetSize, refSize, count, tableAt;

    private Bplist(byte[] b) {
        if (b.length < 40 || !"bplist00".equals(new String(b, 0, 8, ASCII))) throw new IllegalArgumentException("not a bplist00");
        this.b = b;
        int t = b.length - 32;
        offsetSize = b[t + 6] & 0xff;
        refSize = b[t + 7] & 0xff;
        long n = be(t + 8, 8), table = be(t + 24, 8);
        if (offsetSize < 1 || offsetSize > 8 || refSize < 1 || refSize > 8 || n < 1 || n > MAX_OBJECTS
                || table < 8 || table + n * offsetSize > t) throw new IllegalArgumentException("bad bplist trailer");
        count = (int) n;
        tableAt = (int) table;
    }

    static Object decode(byte[] b) {
        try {
            Bplist p = new Bplist(b);
            long top = p.be(b.length - 16, 8);
            return p.object(top, 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }

    private long be(int at, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = v << 8 | (b[at + i] & 0xff);
        return v;
    }

    private Object object(long ref, int depth) {
        if (ref < 0 || ref >= count || depth > 32) throw new IllegalArgumentException("bad object ref");
        int at = (int) be(tableAt + (int) ref * offsetSize, offsetSize);
        if (at < 8 || at >= tableAt) throw new IllegalArgumentException("bad object offset");
        int marker = b[at] & 0xff, kind = marker >> 4, low = marker & 15;
        switch (kind) {
            case 0x0:
                if (low == 8) return Boolean.FALSE;
                if (low == 9) return Boolean.TRUE;
                return null;
            case 0x1: {
                int n = 1 << low;
                if (n > 8) throw new IllegalArgumentException("int too wide");
                return be(at + 1, n);
            }
            case 0x2: {
                int n = 1 << low;
                return n == 4 ? (double) Float.intBitsToFloat((int) be(at + 1, 4)) : Double.longBitsToDouble(be(at + 1, 8));
            }
            case 0x3:
                return Double.longBitsToDouble(be(at + 1, 8));
            case 0x8:
                return be(at + 1, low + 1);
        }
        int[] len = length(at, low);
        int start = len[1], n = len[0];
        switch (kind) {
            case 0x4:
                return copy(start, n);
            case 0x5:
                return new String(b, start, n, ASCII);
            case 0x6:
                return new String(b, start, n * 2, UTF16);
            case 0x7:
                return new String(b, start, n, Charset.forName("UTF-8"));
            case 0xA: {
                List<Object> list = new ArrayList<Object>(n);
                for (int i = 0; i < n; i++) list.add(object(be(start + i * refSize, refSize), depth + 1));
                return list;
            }
            case 0xD: {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                for (int i = 0; i < n; i++) {
                    Object k = object(be(start + i * refSize, refSize), depth + 1);
                    map.put(String.valueOf(k), object(be(start + (n + i) * refSize, refSize), depth + 1));
                }
                return map;
            }
        }
        throw new IllegalArgumentException("unknown bplist marker 0x" + Integer.toHexString(marker));
    }

    /** {count, first byte of the content} */
    private int[] length(int at, int low) {
        if (low != 15) return new int[] {low, at + 1};
        int m = b[at + 1] & 0xff;
        if (m >> 4 != 1) throw new IllegalArgumentException("bad bplist length");
        int w = 1 << (m & 15);
        long n = be(at + 2, w);
        if (n < 0 || n > b.length) throw new IllegalArgumentException("bad bplist length");
        return new int[] {(int) n, at + 2 + w};
    }

    private byte[] copy(int at, int n) {
        if (at + n > tableAt) throw new IllegalArgumentException("short bplist data");
        byte[] d = new byte[n];
        System.arraycopy(b, at, d, 0, n);
        return d;
    }

    // ---- encode ----

    static byte[] encode(Object top) {
        List<Object> flat = new ArrayList<Object>();
        flatten(top, flat);
        int n = flat.size(), ref = n < 256 ? 1 : n < 65536 ? 2 : 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b');
        for (byte c : "plist00".getBytes(ASCII)) out.write(c);
        long[] offsets = new long[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = out.size();
            write(out, flat.get(i), flat, i, ref);
        }
        long table = out.size();
        int off = table < 256 ? 1 : table < 65536 ? 2 : table < (1L << 32) ? 4 : 8;
        for (long o : offsets) put(out, o, off);
        for (int i = 0; i < 6; i++) out.write(0);
        out.write(off);
        out.write(ref);
        put(out, n, 8);
        put(out, 0, 8);
        put(out, table, 8);
        return out.toByteArray();
    }

    /** objects in write order: each container is followed by its children (keys, then values) */
    @SuppressWarnings("unchecked")
    private static void flatten(Object v, List<Object> flat) {
        flat.add(v);
        if (v instanceof Map) {
            for (Object k : ((Map<Object, Object>) v).keySet()) flatten(String.valueOf(k), flat);
            for (Object x : ((Map<Object, Object>) v).values()) flatten(x, flat);
        } else if (v instanceof List) {
            for (Object x : (List<Object>) v) flatten(x, flat);
        }
    }

    /** the number of objects v takes in the flat list, itself included */
    @SuppressWarnings("unchecked")
    private static int size(Object v) {
        int n = 1;
        if (v instanceof Map) {
            n += ((Map<Object, Object>) v).size();
            for (Object x : ((Map<Object, Object>) v).values()) n += size(x);
        } else if (v instanceof List) {
            for (Object x : (List<Object>) v) n += size(x);
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static void write(ByteArrayOutputStream out, Object v, List<Object> flat, int self, int ref) {
        if (v == null) {
            out.write(0x00);
        } else if (v instanceof Boolean) {
            out.write((Boolean) v ? 0x09 : 0x08);
        } else if (v instanceof Integer || v instanceof Long) {
            long x = ((Number) v).longValue();
            int w = x < 0 ? 8 : x < 256 ? 1 : x < 65536 ? 2 : x < (1L << 32) ? 4 : 8;
            out.write(0x10 | Integer.numberOfTrailingZeros(w));
            put(out, x, w);
        } else if (v instanceof Double || v instanceof Float) {
            out.write(0x23);
            put(out, Double.doubleToLongBits(((Number) v).doubleValue()), 8);
        } else if (v instanceof byte[]) {
            byte[] d = (byte[]) v;
            header(out, 0x40, d.length);
            out.write(d, 0, d.length);
        } else if (v instanceof String) {
            String s = (String) v;
            boolean ascii = true;
            for (int i = 0; i < s.length(); i++) ascii &= s.charAt(i) < 0x80;
            byte[] d = s.getBytes(ascii ? ASCII : UTF16);
            header(out, ascii ? 0x50 : 0x60, ascii ? d.length : d.length / 2);
            out.write(d, 0, d.length);
        } else if (v instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) v;
            header(out, 0xD0, m.size());
            int at = self + 1;
            for (int i = 0; i < m.size(); i++) put(out, at++, ref);
            for (Object x : m.values()) {
                put(out, at, ref);
                at += size(x);
            }
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            header(out, 0xA0, l.size());
            int at = self + 1;
            for (Object x : l) {
                put(out, at, ref);
                at += size(x);
            }
        } else {
            throw new IllegalArgumentException("can't write " + v.getClass().getName());
        }
    }

    private static void header(ByteArrayOutputStream out, int base, int n) {
        if (n < 15) {
            out.write(base | n);
            return;
        }
        out.write(base | 15);
        int w = n < 256 ? 1 : n < 65536 ? 2 : 4;
        out.write(0x10 | Integer.numberOfTrailingZeros(w));
        put(out, n, w);
    }

    private static void put(ByteArrayOutputStream out, long v, int w) {
        for (int i = w - 1; i >= 0; i--) out.write((int) (v >>> (8 * i)));
    }
}
