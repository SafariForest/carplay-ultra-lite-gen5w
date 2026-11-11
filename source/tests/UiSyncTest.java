package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host test for the uiSync experiment: UiSync follows LIVI ui_sync.rs's test (reset, reset complete,
 * acks, the phone's choices, stale sessions, reopen) without LIVI's own layout requests; Bplist reads
 * a bplist written by Python's plistlib (fixture) and writes one for it (checked by the caller);
 * UiSyncChannel answers a reset over the encrypted stream (cpc-vs-3.key, port 7135).
 * usage: UiSyncTest <dir> <python-written.bplist> <java-written.bplist>
 */
public class UiSyncTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static byte[] framed(Map<String, Object> header) {
        Map<Object, Object> frame = new LinkedHashMap<Object, Object>();
        frame.put("displayUUID", "Cluster_Display");
        frame.put("data", Bplist.encode(header));
        return OpackCodec.encode(frame);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> headerOf(byte[] out) {
        Map<Object, Object> frame = (Map<Object, Object>) OpackCodec.decode(out);
        if (!"Cluster_Display".equals(frame.get("displayUUID"))) throw new AssertionError("panel not copied: " + frame);
        return (Map<String, Object>) Bplist.decode((byte[]) frame.get("data"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> commandOf(Map<String, Object> h) {
        return (Map<String, Object>) Bplist.decode((byte[]) h.get("data"));
    }

    static Long n(Map<String, Object> m, String k) {
        return OpackCodec.asLong(m.get(k));
    }

    static boolean is(Map<String, Object> h, long type, long psn, long asn) {
        return Long.valueOf(type).equals(n(h, "type")) && Long.valueOf(psn).equals(n(h, "psn")) && Long.valueOf(asn).equals(n(h, "asn"));
    }

    static byte[] fromPhone(long psn, long kind, Map<String, Object> data) {
        return framed(map("type", 2L, "ssn", 1L, "psn", psn, "data", Bplist.encode(map("type", kind, "data", data))));
    }

    @SuppressWarnings("unchecked")
    static void stateMachine() {
        UiSync sync = new UiSync();
        byte[] reset = framed(map("type", 0L, "ssn", 1L, "psn", 1L, "asn", 0L, "id", "89858A51", "version", 3L));
        List<byte[]> out = sync.handle(reset);
        Map<String, Object> h = headerOf(out.get(0));
        check("reset answered with reset, ssn 1 psn 1, version 2 and the phone's id",
                out.size() == 1 && is(h, 0, 1, 0) && Long.valueOf(1).equals(n(h, "ssn")) && Long.valueOf(2).equals(n(h, "version"))
                        && "89858A51".equals(h.get("id")));

        byte[] complete = framed(map("type", 1L, "ssn", 1L, "psn", 1L, "version", 3L));
        out = sync.handle(complete);
        check("reset complete: 3 answers (complete, allow layout change, give focus)", out.size() == 3);
        h = headerOf(out.get(0));
        check("complete: type 1 psn 2 asn 1, version 2", is(h, 1, 2, 1) && Long.valueOf(2).equals(n(h, "version")));
        h = headerOf(out.get(1));
        Map<String, Object> c = commandOf(h);
        check("allow layout change: command psn 3, allow true",
                is(h, 2, 3, 1) && Long.valueOf(UiSync.ALLOW_LAYOUT_CHANGE).equals(n(c, "type"))
                        && Boolean.TRUE.equals(((Map<String, Object>) c.get("data")).get("allow")));
        h = headerOf(out.get(2));
        c = commandOf(h);
        check("give focus: command psn 4, token 4711",
                is(h, 2, 4, 1) && Long.valueOf(UiSync.GIVE_FOCUS).equals(n(c, "type"))
                        && Long.valueOf(4711).equals(OpackCodec.asLong(((Map<String, Object>) c.get("data")).get("tokenID"))));

        h = headerOf(sync.handle(framed(map("type", 2L, "ssn", 1L, "psn", 2L))).get(0));
        check("a phone command is acked (type 3, asn 2)", Long.valueOf(3).equals(n(h, "type")) && Long.valueOf(2).equals(n(h, "asn")));

        sync.handle(fromPhone(3, UiSync.START_LAYOUT_TRANSITION, map("layoutID", "0D38B526", "paletteID", "starlight")));
        sync.handle(fromPhone(4, UiSync.PALETTE_CHANGE, map("layoutIdentifier", "0D38B526", "paletteIdentifier", "racingGreen")));
        sync.handle(fromPhone(5, UiSync.APPEARANCE_PREFERENCE_CHANGE, map("preference", 2L)));
        sync.handle(fromPhone(6, UiSync.APPEARANCE_PREFERENCE_CHANGE, map("preference", 7L)));
        check("the phone's choices are recorded: " + sync.choices, sync.choices.equals(Arrays.asList(
                "layout 0D38B526 palette starlight", "palette racingGreen of layout 0D38B526", "appearance dark")));

        out = sync.handle(fromPhone(7, UiSync.REQUEST_FOCUS, map("tokenID", 4711L)));
        check("a focus request: acked, then the focus goes back",
                out.size() == 2 && Long.valueOf(3).equals(n(headerOf(out.get(0)), "type"))
                        && Long.valueOf(UiSync.GIVE_FOCUS).equals(n(commandOf(headerOf(out.get(1))), "type")));

        out = sync.handle(framed(map("type", 2L, "ssn", 1L, "psn", 8L, "noAck", Boolean.TRUE)));
        check("noAck commands get no ack", out.isEmpty());
        check("another session is not ours", sync.handle(framed(map("type", 2L, "ssn", 9L, "psn", 3L))).isEmpty());
        boolean refused = false;
        try {
            sync.handle(new byte[] {(byte) 0xff});
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("garbage is refused (the channel logs it)", refused);

        sync.reopen();
        out = sync.handle(reset);
        check("after reopen: a new reset is answered as session 1", out.size() == 1 && Long.valueOf(1).equals(n(headerOf(out.get(0)), "ssn")));
        check("after reopen: complete again gives 3 answers", sync.handle(complete).size() == 3);
        out = sync.handle(reset);
        check("a second reset before completion is a new session (ssn 2)", out.size() == 1 && Long.valueOf(2).equals(n(headerOf(out.get(0)), "ssn")));
    }

    @SuppressWarnings("unchecked")
    static void layoutRequest() {
        check("the layout setting: Modular for an unknown name, a name picks its layout, off none",
                UiSync.layout("Modular") == UiSync.THEME_LAYOUTS[0] && UiSync.layout("bogus") == UiSync.THEME_LAYOUTS[0]
                        && "DigiRing".equals(UiSync.layout("DigiRing").name) && UiSync.layout("off") == null);
        UiSync sync = new UiSync();
        sync.wanted = UiSync.layout("Modular");
        byte[] reset = framed(map("type", 0L, "ssn", 1L, "psn", 1L, "asn", 0L, "id", "89858A51", "version", 3L));
        byte[] complete = framed(map("type", 1L, "ssn", 1L, "psn", 1L, "version", 3L));
        sync.handle(reset);
        List<byte[]> out = sync.handle(complete);
        check("with a layout: 4 answers (complete, allow, request layout, give focus)", out.size() == 4);
        Map<String, Object> h = headerOf(out.get(2));
        Map<String, Object> c = commandOf(h);
        Map<String, Object> d = (Map<String, Object>) c.get("data");
        Map<String, Object> active = (Map<String, Object>) d.get("activeComponents");
        // LIVI ui_sync.rs's test: layout 0D38B526…, its map zone F0786093… shows component 2B28C705…
        check("request layout: command psn 4, Modular 0D38B526-…, palette blue, dark, fades, map zone component",
                is(h, 2, 4, 1) && Long.valueOf(UiSync.REQUEST_LAYOUT).equals(n(c, "type"))
                        && "0D38B526-1271-575D-8A92-393A86B718F9".equals(d.get("layoutID")) && "blue".equals(d.get("paletteID"))
                        && Long.valueOf(UiSync.DARK).equals(n(d, "appearanceMode")) && Boolean.TRUE.equals(d.get("fadeInNewLayout"))
                        && Boolean.TRUE.equals(d.get("fadeOutOldLayout")) && active != null && active.size() == 1
                        && "2B28C705-6563-5F11-8867-2AAEC2FC0555".equals(active.get("F0786093-7DEF-5F93-A171-2839E58C7EB4")));
        check("give focus moves to psn 5", is(headerOf(out.get(3)), 2, 5, 1)
                && Long.valueOf(UiSync.GIVE_FOCUS).equals(n(commandOf(headerOf(out.get(3))), "type")));
        sync.reopen();
        sync.handle(reset);
        out = sync.handle(complete);
        check("a reopened channel asks for the layout again", out.size() == 4
                && "0D38B526-1271-575D-8A92-393A86B718F9".equals(((Map<String, Object>) commandOf(headerOf(out.get(2))).get("data")).get("layoutID")));
        boolean ids = true;
        for (UiSync.Layout l : UiSync.THEME_LAYOUTS)
            ids &= l.id.length() == 36 && l.zone.length() == 36 && l.component.length() == 36 && !l.palette.isEmpty();
        check("every theme layout has full ids and a palette", ids && UiSync.THEME_LAYOUTS.length == 6);
    }

    @SuppressWarnings("unchecked")
    static void variant() {
        byte[] stock = VsData.bytes(VsData.ACCESSORIES);
        Map<Long, Object> values = new LinkedHashMap<Long, Object>();
        byte[] varied = VehicleState.withVariant(stock, "DB12Coupe", values);
        check("vehicle variant: the accessory list changed and the three values are set",
                varied != null && !Arrays.equals(stock, varied) && "DB12Coupe".equals(values.get(770L))
                        && "DB12Coupe".equals(values.get(771L)) && "DB12Coupe".equals(values.get(772L)) && values.size() == 3);
        List<Object> list = (List<Object>) OpackCodec.decode(varied);
        Map<Object, Object> service = null;
        for (Object a : list)
            for (Object s : (List<Object>) ((Map<Object, Object>) a).get("services"))
                if (Long.valueOf(VehicleState.VARIANT_SERVICE).equals(OpackCodec.asLong(((Map<Object, Object>) s).get("iid")))) service = (Map<Object, Object>) s;
        List<Object> chars = (List<Object>) service.get("characteristics");
        boolean ok = chars.size() == 3;
        long[][] want = {{771, 0x48000003L}, {770, 0x48000004L}, {772, 0x48000005L}};
        for (int i = 0; ok && i < 3; i++) {
            Map<Object, Object> c = (Map<Object, Object>) chars.get(i);
            ok = Long.valueOf(want[i][0]).equals(OpackCodec.asLong(c.get("iid"))) && Long.valueOf(want[i][1]).equals(OpackCodec.asLong(c.get("type")))
                    && "DB12Coupe".equals(c.get("initialValue")) && Boolean.FALSE.equals(c.get("mutable")) && Boolean.FALSE.equals(c.get("writable"))
                    && Long.valueOf(10).equals(OpackCodec.asLong(c.get("format")));
        }
        check("vehicle variant: service 769 = exteriorStyle 771, interiorStyle 770, vehicleLayout 772, fixed strings", ok);
        // everything else stays LIVI's description: put the stock service back and the bytes match
        List<Object> stockList = (List<Object>) OpackCodec.decode(stock);
        for (Object a : stockList)
            for (Object s : (List<Object>) ((Map<Object, Object>) a).get("services"))
                if (Long.valueOf(VehicleState.VARIANT_SERVICE).equals(OpackCodec.asLong(((Map<Object, Object>) s).get("iid"))))
                    service.put("characteristics", ((Map<Object, Object>) s).get("characteristics"));
        check("vehicle variant: the rest of the accessory list is byte-identical to the stock one", Arrays.equals(stock, OpackCodec.encode(list)));
        check("vehicle variant: no interiorStyle service -> null", VehicleState.withVariant(OpackCodec.encode(new ArrayList<Object>()), "DBX", values) == null);
        final List<String> logs = new ArrayList<String>();
        VehicleState.variant = "DBX";
        new VehicleState(new VsChannel.Log() {
            @Override
            public void log(String msg) {
                logs.add(msg);
            }
        });
        VehicleState.variant = "";
        check("a VehicleState with the variant logs it: " + logs, logs.contains("VS vehicle variant DBX (exteriorStyle, interiorStyle, vehicleLayout)"));
    }

    @SuppressWarnings("unchecked")
    static void plists(File fromPython, File forPython) throws Exception {
        Object v = Bplist.decode(read(fromPython));
        Map<String, Object> m = (Map<String, Object>) v;
        check("python bplist: ints, string, unicode, bool, data, list, nested dict, real",
                Long.valueOf(7).equals(m.get("small")) && Long.valueOf(300).equals(m.get("mid")) && Long.valueOf(70000).equals(m.get("wide"))
                        && Long.valueOf(5000000000L).equals(m.get("huge")) && Long.valueOf(-2).equals(m.get("neg"))
                        && "Cluster_Display".equals(m.get("s")) && "Zürich ✓".equals(m.get("u")) && Boolean.TRUE.equals(m.get("t"))
                        && Boolean.FALSE.equals(m.get("f")) && Arrays.equals(new byte[] {1, 2, 3}, (byte[]) m.get("d"))
                        && Arrays.asList(1L, "two").equals(m.get("l")) && Long.valueOf(2).equals(((Map<String, Object>) m.get("n")).get("x"))
                        && Double.valueOf(1.5).equals(m.get("r")) && ((String) m.get("long")).length() == 40);
        Map<String, Object> mine = map("type", 2L, "ssn", 1L, "psn", 70000L, "neg", -2L, "id", "89858A51", "u", "Zürich ✓",
                "ok", Boolean.TRUE, "data", new byte[] {9, 8, 7}, "list", Arrays.asList((Object) 1L, "two", map("x", 2L)),
                "long", "0123456789012345678901234567890123456789", "r", 1.5);
        byte[] enc = Bplist.encode(mine);
        FileOutputStream f = new FileOutputStream(forPython);
        f.write(enc);
        f.close();
        Map<String, Object> back = (Map<String, Object>) Bplist.decode(enc);
        check("java bplist reads back its own writing", back.get("list").toString().equals("[1, two, {x=2}]")
                && Long.valueOf(70000).equals(back.get("psn")) && Arrays.equals(new byte[] {9, 8, 7}, (byte[]) back.get("data"))
                && "Zürich ✓".equals(back.get("u")) && Long.valueOf(-2).equals(back.get("neg")));
        List<Object> many = new ArrayList<Object>();
        for (int i = 0; i < 300; i++) many.add((long) i);
        check("a bplist with over 255 objects (2-byte refs)", ((List<Object>) Bplist.decode(Bplist.encode(many))).equals(many));
    }

    static byte[] read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0;) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static void channel(File dir) throws Exception {
        byte[] rd = new byte[32], wr = new byte[32], keys = new byte[72];
        for (int i = 0; i < 32; i++) { rd[i] = (byte) (0x40 + i); wr[i] = (byte) (0x20 + i); }
        System.arraycopy(rd, 0, keys, 8, 32);
        System.arraycopy(wr, 0, keys, 40, 32);
        FileOutputStream f = new FileOutputStream(new File(dir, UiSyncChannel.KEYS));
        f.write(keys);
        f.close();
        final List<String> logs = new ArrayList<String>();
        new UiSyncChannel(dir, new VsChannel.Log() {
            @Override
            public synchronized void log(String msg) {
                logs.add(msg);
            }
        }, UiSync.layout("Modular")).start();
        Thread.sleep(300);
        Socket s = new Socket("127.0.0.1", UiSyncChannel.PORT);
        // the phone writes with the car's read key and reads with its write key
        ChaChaPoly phoneCipher = new ChaChaPoly(wr, rd);
        VsChannel.Writer phone = new VsChannel.Writer(s, s.getOutputStream(), phoneCipher);
        phone.send(VsChannel.pack(VsChannel.SYNC, VsChannel.COMMAND, 0x1122,
                framed(map("type", 0L, "ssn", 1L, "psn", 1L, "asn", 0L, "id", "89858A51", "version", 3L))));
        s.setSoTimeout(3000);
        InputStream in = s.getInputStream();
        byte[] sealed = new byte[65536];
        int have = 0;
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        List<int[]> kinds = new ArrayList<int[]>();
        Map<String, Object> answer = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (kinds.size() < 2 && System.currentTimeMillis() < deadline) {
            int r = in.read(sealed, have, sealed.length - have);
            if (r < 0) break;
            have += r;
            int used = phoneCipher.open(sealed, have, plain);
            System.arraycopy(sealed, used, sealed, 0, have - used);
            have -= used;
            byte[] p = plain.toByteArray();
            int at = 0;
            while (p.length - at >= VsChannel.HEADER && p.length - at >= VsChannel.be32(p, at)) {
                int size = VsChannel.be32(p, at), kind = VsChannel.be32(p, at + 4), type = VsChannel.be32(p, at + 16);
                long token = (long) VsChannel.be32(p, at + 20) << 32 | (VsChannel.be32(p, at + 24) & 0xffffffffL);
                kinds.add(new int[] {kind, type, (int) token});
                if (kind == VsChannel.ASYNC) answer = headerOf(Arrays.copyOfRange(p, at + VsChannel.HEADER, at + size));
                at += size;
            }
            plain.reset();
            plain.write(p, at, p.length - at);
        }
        s.close();
        Thread.sleep(300);
        check("the sync package is replied to (rply, token 0x1122)",
                !kinds.isEmpty() && kinds.get(0)[0] == VsChannel.REPLY && kinds.get(0)[2] == 0x1122);
        check("the reset's answer comes back as an async cmnd on the stream",
                kinds.size() == 2 && kinds.get(1)[0] == VsChannel.ASYNC && kinds.get(1)[1] == VsChannel.COMMAND
                        && answer != null && Long.valueOf(0).equals(n(answer, "type")) && "89858A51".equals(answer.get("id")));
        boolean phoneLogged = false, carLogged = false;
        synchronized (logs) {
            for (String l : logs) {
                phoneLogged |= l.startsWith("SYNC phone: ");
                carLogged |= l.startsWith("SYNC car: ");
            }
        }
        check("the channel logs both sides: " + logs, phoneLogged && carLogged);
    }

    public static void main(String[] args) throws Exception {
        stateMachine();
        layoutRequest();
        variant();
        plists(new File(args[1]), new File(args[2]));
        channel(new File(args[0]));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
