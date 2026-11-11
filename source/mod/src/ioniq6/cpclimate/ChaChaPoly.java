package ioniq6.cpclimate;

/**
 * ChaCha20-Poly1305 (RFC 8439), which Android 4.4 doesn't have, and AirPlay's framing of it on
 * a data stream (LIVI control_cipher.rs): each frame is a 2-byte little-endian length, that many
 * bytes sealed, and the 16-byte tag; the length is the additional data, and each direction counts
 * its frames in the nonce (4 zero bytes, then the count as 8 bytes little-endian).
 */
final class ChaChaPoly {
    static final int MAX_PAYLOAD = 0x4000;

    private final int[] readKey, writeKey;
    private long readCount, writeCount;

    ChaChaPoly(byte[] readKey, byte[] writeKey) {
        this.readKey = words(readKey, 0, 8);
        this.writeKey = words(writeKey, 0, 8);
    }

    /** the whole frames at the start of in (opened, in order); the bytes used, or -1 if one didn't authenticate */
    int open(byte[] in, int len, java.io.ByteArrayOutputStream plain) {
        int at = 0;
        while (len - at >= 2) {
            int n = (in[at] & 0xff) | (in[at + 1] & 0xff) << 8;
            if (len - at < 2 + n + 16) break;
            byte[] out = open(readKey, nonce(readCount), in, at + 2, n, in, at, 2);
            if (out == null) return -1;
            plain.write(out, 0, out.length);
            readCount++;
            at += 2 + n + 16;
        }
        return at;
    }

    /** plain as sealed frames */
    byte[] seal(byte[] plain) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = 0;
        do {
            int n = Math.min(MAX_PAYLOAD, plain.length - i);
            byte[] head = { (byte) n, (byte) (n >> 8) };
            byte[] sealed = seal(writeKey, nonce(writeCount++), plain, i, n, head);
            out.write(head, 0, 2);
            out.write(sealed, 0, sealed.length);
            i += MAX_PAYLOAD;
        } while (i < plain.length);
        return out.toByteArray();
    }

    static int[] nonce(long count) {
        return new int[] { 0, (int) count, (int) (count >>> 32) };
    }

    // ---- RFC 8439 ----

    static int[] words(byte[] b, int off, int n) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            int p = off + 4 * i;
            w[i] = (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
        }
        return w;
    }

    /** one 64-byte ChaCha20 block */
    static void block(int[] key, int counter, int[] nonce, byte[] out) {
        int[] s = { 0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                key[0], key[1], key[2], key[3], key[4], key[5], key[6], key[7],
                counter, nonce[0], nonce[1], nonce[2] };
        int[] x = s.clone();
        for (int r = 0; r < 10; r++) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15);
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14);
        }
        for (int i = 0; i < 16; i++) {
            int v = x[i] + s[i];
            out[4 * i] = (byte) v; out[4 * i + 1] = (byte) (v >> 8); out[4 * i + 2] = (byte) (v >> 16); out[4 * i + 3] = (byte) (v >> 24);
        }
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    static void xor(int[] key, int counter, int[] nonce, byte[] in, int off, int n, byte[] out) {
        byte[] ks = new byte[64];
        for (int i = 0; i < n; i += 64) {
            block(key, counter++, nonce, ks);
            for (int j = 0; j < 64 && i + j < n; j++) out[i + j] = (byte) (in[off + i + j] ^ ks[j]);
        }
    }

    static byte[] seal(int[] key, int[] nonce, byte[] plain, int off, int n, byte[] aad) {
        byte[] out = new byte[n + 16];
        xor(key, 1, nonce, plain, off, n, out);
        byte[] tag = tag(key, nonce, aad, 0, aad.length, out, 0, n);
        System.arraycopy(tag, 0, out, n, 16);
        return out;
    }

    /** n sealed bytes at in[off] followed by their tag; null if the tag doesn't match */
    static byte[] open(int[] key, int[] nonce, byte[] in, int off, int n, byte[] aad, int aadOff, int aadLen) {
        byte[] tag = tag(key, nonce, aad, aadOff, aadLen, in, off, n);
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= tag[i] ^ in[off + n + i];
        if (diff != 0) return null;
        byte[] out = new byte[n];
        xor(key, 1, nonce, in, off, n, out);
        return out;
    }

    static byte[] tag(int[] key, int[] nonce, byte[] aad, int aadOff, int aadLen, byte[] c, int cOff, int cLen) {
        byte[] otk = new byte[64];
        block(key, 0, nonce, otk);
        Poly1305 p = new Poly1305(otk);
        p.update(aad, aadOff, aadLen); p.pad();
        p.update(c, cOff, cLen); p.pad();
        byte[] lens = new byte[16];
        for (int i = 0; i < 8; i++) { lens[i] = (byte) ((long) aadLen >>> (8 * i)); lens[8 + i] = (byte) ((long) cLen >>> (8 * i)); }
        p.update(lens, 0, 16);
        return p.finish();
    }

    /** Poly1305 with BigInteger: slow but simple, and these messages are small */
    static final class Poly1305 {
        static final java.math.BigInteger P = java.math.BigInteger.ONE.shiftLeft(130).subtract(java.math.BigInteger.valueOf(5));
        final java.math.BigInteger r, s;
        java.math.BigInteger acc = java.math.BigInteger.ZERO;
        final byte[] buf = new byte[16];
        int have;

        Poly1305(byte[] otk) {
            byte[] rb = new byte[16];
            System.arraycopy(otk, 0, rb, 0, 16);
            rb[3] &= 15; rb[7] &= 15; rb[11] &= 15; rb[15] &= 15; rb[4] &= 252; rb[8] &= 252; rb[12] &= 252;
            r = le(rb, 0, 16);
            s = le(otk, 16, 16);
        }

        static java.math.BigInteger le(byte[] b, int off, int n) {
            byte[] be = new byte[n + 1];
            for (int i = 0; i < n; i++) be[n - i] = b[off + i];
            return new java.math.BigInteger(be);
        }

        void update(byte[] b, int off, int n) {
            for (int i = 0; i < n; i++) {
                buf[have++] = b[off + i];
                if (have == 16) chunk(16);
            }
        }

        /** pads the current partial block with zeros (RFC 8439 2.8) */
        void pad() {
            if (have == 0) return;
            for (int i = have; i < 16; i++) buf[i] = 0;
            have = 16;
            chunk(16);
        }

        private void chunk(int n) {
            byte[] m = new byte[n + 1];
            System.arraycopy(buf, 0, m, 0, n);
            m[n] = 1;
            acc = acc.add(le(m, 0, n + 1)).multiply(r).mod(P);
            have = 0;
        }

        byte[] finish() {
            if (have > 0) chunk(have);
            java.math.BigInteger t = acc.add(s);
            byte[] out = new byte[16];
            for (int i = 0; i < 16; i++) out[i] = t.shiftRight(8 * i).byteValue();
            return out;
        }
    }
}
