package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Host test of M3: VehicleState answers a phone's session (tests/vs_script.py) byte for byte as
 * LIVI's own VehicleState does (the answers of vsdump --replay on the same file).
 * usage: VehicleStateTest <script file> <LIVI's answers file>
 */
public class VehicleStateTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static byte[] read(String path) throws Exception {
        InputStream in = new FileInputStream(new File(path));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    static void record(ByteArrayOutputStream out, int lane, byte[] m) {
        out.write(lane);
        for (int i = 0; i < 4; i++) out.write(m.length >>> (8 * i));
        out.write(m, 0, m.length);
    }

    public static void main(String[] a) throws Exception {
        byte[] script = read(a[0]), livi = read(a[1]);
        final StringBuilder log = new StringBuilder();
        VehicleState vs = new VehicleState(new VsChannel.Log() {
            public void log(String m) {
                log.append(m).append('\n');
            }
        });
        ByteArrayOutputStream mine = new ByteArrayOutputStream();
        int messages = 0, answers = 0;
        for (int at = 0; at + 5 <= script.length; ) {
            int lane = script[at], n = le32(script, at + 1);
            byte[] m = java.util.Arrays.copyOfRange(script, at + 5, at + 5 + n);
            at += 5 + n;
            messages++;
            List<VehicleState.Out> out = vs.handle(lane, m);
            for (VehicleState.Out o : out) record(mine, o.lane, o.message);
            answers += out.size();
            record(mine, 0xff, new byte[0]);
        }
        byte[] got = mine.toByteArray();
        System.out.println(messages + " messages, " + answers + " answers (" + got.length + " B; LIVI " + livi.length + " B)");
        check("answers match LIVI byte for byte", java.util.Arrays.equals(got, livi));
        if (!java.util.Arrays.equals(got, livi)) firstDifference(got, livi);
        String l = log.toString();
        check("logs the description read", l.contains("phone reads the vehicle description"));
        check("logs a climate write by name", l.contains("phone set driverTargetTemperature to 23.5"));
        check("logs a rule's change", l.contains("car set passengerTargetTemperature to 23.5"));
        check("logs a refused write", l.contains("phone may not write value 521"));
        check("logs an unknown command", l.contains("phone sent command 4999, the car has none such"));
        check("drops a non-OPACK message", l.contains("is not OPACK"));
        check("drops another plugin's message", l.contains("message for plugin 1 without readable data"));
        System.out.println(l.length() > 4000 ? l.substring(0, 4000) + "..." : l);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** prints the first answer that differs, decoded both ways */
    static void firstDifference(byte[] got, byte[] want) {
        int i = 0, j = 0, k = 0;
        while (i + 5 <= got.length && j + 5 <= want.length) {
            int n = le32(got, i + 1), m = le32(want, j + 1);
            byte[] x = java.util.Arrays.copyOfRange(got, i + 5, i + 5 + n), y = java.util.Arrays.copyOfRange(want, j + 5, j + 5 + m);
            if (got[i] != want[j] || !java.util.Arrays.equals(x, y)) {
                System.out.println("answer " + k + ": java lane " + got[i] + " " + show(x));
                System.out.println("answer " + k + ": LIVI lane " + want[j] + " " + show(y));
                return;
            }
            i += 5 + n;
            j += 5 + m;
            k++;
        }
        System.out.println("one ends early after " + k + " records");
    }

    static String show(byte[] m) {
        if (m.length == 0) return "(end of a message's answers)";
        try {
            Object outer = OpackCodec.decode(m);
            Object inner = VehicleState.get(outer, "pluginData");
            return inner instanceof byte[] ? Opack.show((byte[]) inner) : Opack.show(m);
        } catch (Exception e) {
            return Opack.hex(m);
        }
    }
}
