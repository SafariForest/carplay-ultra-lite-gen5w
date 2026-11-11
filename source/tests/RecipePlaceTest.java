import hmgapi.engine.MemRecipe;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * Applies the M1 recipe to the stock library in a fake process memory (library at BASE,
 * the segment wherever mapCode puts it) and checks every branch and table entry lands where
 * the build meant: the two b.w go to the stubs, and each rel32 resolves to BASE + target.
 * usage: RecipePlaceTest <stock lib> <recipe> <text end> <segment hint offset>
 */
public class RecipePlaceTest {
    static final long BASE = 0x40000000L;
    static final int SIZE = 0x200000;
    static int fails;

    static void check(String what, long want, long got) {
        boolean ok = want == got;
        if (!ok) fails++;
        System.out.printf("%s %s: want %#x, got %#x%n", ok ? "ok  " : "FAIL", what, want, got);
    }

    public static void main(String[] a) throws Exception {
        byte[] lib = Files.readAllBytes(Paths.get(a[0]));
        String text = new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8");
        long textEnd = Long.decode(a[2]);
        final byte[] mem = new byte[SIZE];
        System.arraycopy(lib, 0, mem, 0, (int) textEnd);
        final long[] mapped = { 0 };
        MemRecipe.Memory m = new MemRecipe.Memory() {
            public byte[] peek(long addr, int len) {
                byte[] b = new byte[len];
                System.arraycopy(mem, (int) (addr - BASE), b, 0, len);
                return b;
            }

            public boolean poke(long addr, byte[] d) {
                System.arraycopy(d, 0, mem, (int) (addr - BASE), d.length);
                return true;
            }

            public long mapCode(long hint, int len) {
                // a different place than the build's SEG_VA, to prove nothing depends on it
                long at = BASE + 0x100000;
                mapped[0] = at;
                return at;
            }
        };
        StringBuilder h = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(lib)) h.append(String.format("%02x", b));
        MemRecipe r = MemRecipe.parse(text);
        MemRecipe.Result res = r.apply(m, BASE, textEnd, h.toString());
        System.out.println("apply: ok=" + res.ok() + " : " + res.message);
        if (!res.ok()) System.exit(1);
        long seg = mapped[0];
        check("b.w at 0x9c20 -> stub_info", seg + 0x0, bwTarget(mem, 0x9c20) );
        check("b.w at 0x12430 -> stub_setup", seg + 0x26, bwTarget(mem, 0x12430));
        if (text.contains("branch 0x11718")) check("b.w at 0x11718 -> stub_stream", seg + 0x46, bwTarget(mem, 0x11718));
        if (text.contains("branch 0xaf10")) check("b.w at 0xaf10 -> stub_command", seg + 0x74, bwTarget(mem, 0xaf10));
        for (String line : text.split("\n")) {
            String[] w = line.trim().split("\\s+");
            if (!w[0].equals("rel32")) continue;
            long off = Long.decode(w[1]), anchor = Long.decode(w[2]), target = Long.decode(w[3]);
            int v = (mem[(int) (seg - BASE + off)] & 0xff) | (mem[(int) (seg - BASE + off) + 1] & 0xff) << 8
                    | (mem[(int) (seg - BASE + off) + 2] & 0xff) << 16 | (mem[(int) (seg - BASE + off) + 3]) << 24;
            check("table +" + w[1], BASE + target, seg + anchor + v);
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** absolute target of the Thumb-2 b.w at library offset at */
    static long bwTarget(byte[] mem, int at) {
        int p = (int) (at);
        int hw1 = (mem[p] & 0xff) | (mem[p + 1] & 0xff) << 8, hw2 = (mem[p + 2] & 0xff) | (mem[p + 3] & 0xff) << 8;
        int s = (hw1 >> 10) & 1, imm10 = hw1 & 0x3ff, j1 = (hw2 >> 13) & 1, j2 = (hw2 >> 11) & 1, imm11 = hw2 & 0x7ff;
        int i1 = 1 - (j1 ^ s), i2 = 1 - (j2 ^ s);
        long off = (s << 24) | (i1 << 23) | (i2 << 22) | (imm10 << 12) | (imm11 << 1);
        if (s == 1) off -= 1L << 25;
        return BASE + at + 4 + off;
    }
}
