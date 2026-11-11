package ioniq6.cpclimate;

import java.io.File;

/**
 * Host test of the Java side: ChaCha20-Poly1305 against RFC 8439 2.8.2, and VsChannel serving
 * tests/phone_sim.py (an independent implementation playing the iPhone), M3's answers included.
 * usage: VsChannelTest <dir> <python> <phone_sim.py>
 */
public class VsChannelTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static byte[] hex(String s) {
        s = s.replaceAll("\\s", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] a) throws Exception {
        // RFC 8439 2.8.2
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        int[] nonce = ChaChaPoly.words(hex("070000004041424344454647"), 0, 3);
        byte[] aad = hex("50515253c0c1c2c3c4c5c6c7");
        byte[] plain = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, "
                + "sunscreen would be it.").getBytes("US-ASCII");
        byte[] sealed = ChaChaPoly.seal(ChaChaPoly.words(key, 0, 8), nonce, plain, 0, plain.length, aad);
        check("RFC 8439 2.8.2 ciphertext", Opack.hex(java.util.Arrays.copyOfRange(sealed, 0, 16)).equals("d31a8d34648e60db7b86afbc53ef7ec2"));
        check("RFC 8439 2.8.2 tag", Opack.hex(java.util.Arrays.copyOfRange(sealed, plain.length, plain.length + 16)).equals("1ae10b594f09e26a7e902ecbd0600691"));
        check("opens again", java.util.Arrays.equals(ChaChaPoly.open(ChaChaPoly.words(key, 0, 8), nonce, sealed, 0, plain.length, aad, 0, aad.length), plain));
        sealed[3] ^= 1;
        check("a changed byte doesn't open", ChaChaPoly.open(ChaChaPoly.words(key, 0, 8), nonce, sealed, 0, plain.length, aad, 0, aad.length) == null);

        File dir = new File(a[0]);
        final StringBuffer log = new StringBuffer();
        VsChannel[] lanes = new VsChannel[2];
        VsChannel ch = new VsChannel(0, dir, new VsChannel.Log() {
            public void log(String m) {
                System.out.println("  log: " + (m.length() > 200 ? m.substring(0, 200) + "..." : m));
                log.append(m).append('\n');
            }
        }, new VehicleState[1], lanes);
        lanes[0] = ch;
        ch.start();
        for (int i = 0; i < 50 && ch.server == null; i++) Thread.sleep(50);
        Process p = new ProcessBuilder(a[1], "-I", a[2], a[0], String.valueOf(VsChannel.PORT)).inheritIO().start();
        check("the phone got its reply and the answers", p.waitFor() == 0);
        Thread.sleep(500);
        String l = log.toString();
        check("sync logged with the inner message decoded",
                l.contains("sync/comm") && l.contains("\"command\": \"follow\"") && l.contains("\"iids\": [520, 534, 525]"));
        check("async logged", l.contains("asyn/comm") && l.contains("\"command\": \"read\""));
        check("a 20 KB body over two frames arrives whole", l.contains("<20000 bytes"));
        check("a fresh vehicle state for the session", l.contains("VS0 new session"));
        check("the write logged by name", l.contains("phone set driverTargetTemperature to 22.5"));
        check("closed cleanly", l.contains("closed by the phone after 5 message(s)"));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
