package ioniq6.cpclimate.check;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ioniq6.cpclimate.CarApi;

/**
 * CarPlay Ultra-Lite's compatibility check (mod id cp-climate): run on a head unit before installing it and the
 * E-GMP CarPlay EV Routing mod (bundle 2026-11-09). It only reads: system properties, checksums of the libraries the two mods patch,
 * the car framework's calls and listeners (by name, as cp-climate itself looks them up), the climate flag
 * numbers, and a few read-only climate values. It writes a report (Markdown and JSON) to the folder given.
 *
 * Run on the car (adb shell, as shell or root):
 *   CLASSPATH=/data/local/tmp/cp-climate-check.jar app_process /system/bin ioniq6.cpclimate.check.Main /data/local/tmp/cp-climate-check
 *
 * Everything is compared with the car it was written and tested on: Ioniq 6 (CEEV) USA firmware 260507.
 */
public final class Main {
    static final String VERSION = "1.0 (CarPlay Ultra-Lite 1.0.0, egmp-carplay-ev-routing 0.4.3-ioniq6.15)";
    static final String REFERENCE = "Ioniq 6 (CEEV) USA 260507";

    static final class Check {
        final String area, name, result, found, expected, meaning;

        Check(String area, String name, String result, String found, String expected, String meaning) {
            this.area = area;
            this.name = name;
            this.result = result;
            this.found = found;
            this.expected = expected;
            this.meaning = meaning;
        }
    }

    final List<Check> checks = new ArrayList<Check>();
    final Map<String, String> facts = new LinkedHashMap<String, String>();

    void add(String area, String name, String result, String found, String expected, String meaning) {
        checks.add(new Check(area, name, result, found == null ? "" : found, expected == null ? "" : expected, meaning == null ? "" : meaning));
    }

    String result(String area, String name) {
        for (Check c : checks) if (c.area.equals(area) && c.name.equals(name)) return c.result;
        return "MISSING";
    }

    boolean pass(String area, String name) {
        return "PASS".equals(result(area, name));
    }

    // ---- reading the unit ----

    static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    static String md5(String path) {
        InputStream in = null;
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            in = new FileInputStream(path);
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte b : d.digest()) s.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return s.toString();
        } catch (Throwable t) {
            return new File(path).exists() ? "unreadable" : "missing";
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static Object service(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** a read-only binder call: interface token, int arguments, the reply read as "int" or "float"; null on failure */
    static String call(Object binder, String iface, int code, String reply, int... args) {
        if (binder == null || code < 0) return null;
        try {
            Class<?> parcel = Class.forName("android.os.Parcel");
            Method obtain = parcel.getMethod("obtain");
            Object data = obtain.invoke(null), rep = obtain.invoke(null);
            try {
                parcel.getMethod("writeInterfaceToken", String.class).invoke(data, iface);
                for (int a : args) parcel.getMethod("writeInt", int.class).invoke(data, a);
                Class<?> ib = Class.forName("android.os.IBinder");
                ib.getMethod("transact", int.class, parcel, parcel, int.class).invoke(binder, code, data, rep, 0);
                parcel.getMethod("readException").invoke(rep);
                Object v = parcel.getMethod("float".equals(reply) ? "readFloat" : "readInt").invoke(rep);
                return String.valueOf(v);
            } finally {
                parcel.getMethod("recycle").invoke(data);
                parcel.getMethod("recycle").invoke(rep);
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "error: " + c.getClass().getSimpleName() + (c.getMessage() != null ? " " + c.getMessage() : "");
        }
    }

    static Integer constant(String cls, String field) {
        try {
            return Class.forName(cls).getField(field).getInt(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- the checks ----

    void system() {
        String product = prop("ro.build.product");
        facts.put("ro.build.product", product);
        facts.put("ro.board.platform", prop("ro.board.platform"));
        facts.put("ro.mobis.onebindir", prop("ro.mobis.onebindir"));
        facts.put("ro.build.version.release", prop("ro.build.version.release"));
        facts.put("uid", String.valueOf(android_uid()));
        String[] p = product.split("\\.");
        String model = p.length > 0 ? p[0] : "", region = p.length > 1 ? p[1] : "", fw = p.length > 0 ? p[p.length - 1] : "";
        boolean same = "CEEV".equals(model) && "USA".equals(region) && "260507".equals(fw);
        // E-GMP units HMG-API has data for (its maps and wireless-carplay-aa stock lists): the same CarPlay library
        // (libCarPlayCore.so d3a47296…) as the Ioniq 6; their car framework (automotive.odex) differs.
        boolean known = ("CVEV".equals(model) || "NEEV".equals(model) || "NEEV23".equals(model))
                && (("USA".equals(region) && ("260507".equals(fw) || "260903".equals(fw))) || ("CAN".equals(region) && "260206".equals(fw)));
        String names = "CVEV".equals(model) ? "Kia EV6" : "NEEV".equals(model) || "NEEV23".equals(model) ? "Hyundai Ioniq 5" : "CEEV".equals(model) ? "Hyundai Ioniq 6" : "unknown model";
        facts.put("model", names);
        add("System", "Head unit model, region and firmware", same ? "PASS" : "WARN", product + " (" + names + ")", "CEEV.USA.….260507",
                same ? "The car these mods were built and tested on."
                        : known ? "Not tested on this car, but HMG-API's data shows the same CarPlay library as the Ioniq 6's; its car framework differs, so the call, listener and flag checks below decide. cp-climate's mod.json doesn't list it: HMG-API asks before installing ('Install anyway')."
                        : "Not tested, and unknown to this check. cp-climate and the routing mod don't list it, so HMG-API asks before installing ('Install anyway'); the checks below decide whether it can work.");
        boolean egmp = "NEEV23".equals(model) || "CVEV".equals(model) || "NEEV".equals(model) || "CEEV".equals(model);
        add("System", "E-GMP model (routing mod)", egmp ? "PASS" : "WARN", model, "NEEV23, CVEV, NEEV or CEEV",
                egmp ? "The routing mod (EV routing and the CarPlay theme identification) has a profile for this model." : "The routing mod is for E-GMP EVs; on this model its EV parts don't apply and the theme identification would have to be split out.");
        String android = prop("ro.build.version.release");
        add("System", "Android", "4.4.2".equals(android) ? "PASS" : "WARN", android, "4.4.2", null);
        boolean hmgapi = new File("/system/bin/hmgapid").exists() || new File("/data/hmgapi").exists();
        add("System", "HMG-API installed", hmgapi ? "PASS" : "FAIL", hmgapi ? "yes" : "no", "yes",
                hmgapi ? null : "Both mods are HMG-API mods; install HMG-API (with this firmware's hook map) first.");
        File wireless = new File("/data/hmgapi/mods/wireless-carplay-aa");
        File mods = new File("/data/hmgapi/mods");
        String w = wireless.exists() ? "installed" : mods.canRead() ? "not installed" : "unknown (run as root to see)";
        add("System", "wireless-carplay-aa mod (wireless CarPlay)", wireless.exists() ? "PASS" : mods.canRead() ? "FAIL" : "INFO", w, "installed",
                "cp-climate's climate data comes over the car's Wi-Fi hotspot (wireless CarPlay), and the routing mod requires this mod. "
                        + "EV6 and Ioniq 5: HMG-API's official wireless-carplay-aa supports them. Ioniq 6: use the bundle's 2.1.0-ioniq6.1 build (the official one has no Ioniq 6 support).");
    }

    /** the author key every mod in the bundle is signed with (HMG-API derives a mod's uuid from it) */
    static final String BUNDLE_UUID = "4bec7579-9d68-8e3c-b667-2804d05a20b9";

    static String read(String path) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static String jsonString(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String exec(String... cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            p.waitFor();
            return b.toString("UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** the three mods the bundle installs: what is on the unit now, and whether the bundle can update it in place */
    void mods() {
        String model = prop("ro.build.product").split("\\.")[0];
        boolean root = android_uid() == 0;
        String[][] mods = {
                { "cp-climate", "CarPlay Ultra-Lite", "1.0.0" },
                { "egmp-carplay-ev-routing", "EV routing in Apple Maps + the CarPlay theme identification", "0.4.3-ioniq6.15" },
                { "wireless-carplay-aa", "wireless CarPlay / Android Auto", "2.1.0-ioniq6.1 (Ioniq 6) or HMG-API's official 2.1.0 (EV6, Ioniq 5)" },
        };
        for (String[] m : mods) {
            String dir = "/data/hmgapi/mods/" + m[0];
            String mj = read(dir + "/mod.json");
            String version = jsonString(mj, "version");
            String uuid = root ? jsonString(read(dir + "/META-HMGAPI/manifest.json"), "uuid") : null;
            boolean listsCar = mj != null && mj.contains("\"" + model + "\"");
            facts.put("installed " + m[0], mj == null ? "no" : version + (uuid != null ? " (author key " + uuid + ")" : ""));
            String name = "Installed " + m[0] + " (" + m[1] + ")";
            if (mj == null) {
                add("Mods", name, "INFO", "not installed", m[2], "The bundle installs it.");
                continue;
            }
            boolean ours = BUNDLE_UUID.equalsIgnoreCase(uuid);
            boolean other = uuid != null && !ours;
            if ("wireless-carplay-aa".equals(m[0])) {
                add("Mods", name, listsCar ? "PASS" : "FAIL", version + (uuid != null ? ", author key " + uuid : ""), m[2],
                        listsCar ? "This build supports this car; keep it (on an EV6 or Ioniq 5 the official one is fine)."
                                : "This build doesn't list this car (" + model + "): remove it and install the bundle's 2.1.0-ioniq6.1.");
            } else if (other) {
                add("Mods", name, "WARN", version + ", author key " + uuid, m[2] + ", author key " + BUNDLE_UUID,
                        "Signed by another author (for example the base " + m[0] + " release): HMG-API won't replace it with the bundle's. Remove it first (hmgapid engine remove " + m[0] + ", then apply); its settings go with it.");
            } else {
                add("Mods", name, ours ? "PASS" : "INFO", version + (uuid != null ? ", author key " + uuid : ""), m[2],
                        ours ? "Same author key as the bundle: installing the bundle updates it in place and keeps its settings."
                                : "Run the check as root to see who signed it: the bundle can only update it if it's signed with the bundle's key, otherwise remove it first.");
            }
        }
        if (root) {
            String routing = exec("hmgapid", "setting-get", "egmp-carplay-ev-routing");
            String engine = jsonString(routing, "carplay_engine"), theme = jsonString(routing, "carplay_theme");
            facts.put("routing settings", String.valueOf(routing));
            if (routing != null && routing.startsWith("{")) {
                add("Mods", "Routing mod engine type (Apple Maps EV routing)", engine == null || "electric".equals(engine) ? "PASS" : "WARN",
                        String.valueOf(engine), "electric", "electric".equals(engine) || engine == null ? "Apple Maps gets the car as an EV (charging stops, range)."
                                : "Apple Maps EV routing needs carplay_engine electric.");
                add("Mods", "Routing mod CarPlay theme", "INFO", String.valueOf(theme), "44C9FA31EC:985:19.0 (the bundle's default)",
                        "The theme only works together with cp-climate's Ultra settings; with cp-climate missing or not applying, set it off or CarPlay can go black.");
            }
        }
    }

    static int android_uid() {
        try {
            return (Integer) Class.forName("android.os.Process").getMethod("myUid").invoke(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    void files() {
        file("CarPlay (DiPO)", "/system/lib/libCarPlayCore.so", "d3a47296437df56fb1ffc7ac3dfca4cb", "FAIL",
                "cp-climate's native patch (the climate data streams, the virtual cluster, the theme's display names) is checked against these exact bytes. With another build it doesn't apply and CarPlay shows no climate: send this file so a recipe can be made for it.");
        file("CarPlay (DiPO)", "/system/app/HKMC_DiPO.apk", "ae4ca93c8ecc0a371d1481ed7f607d63", "WARN",
                "cp-climate's Java part hooks DiPO through HMG-API's hook map (dipo.carplay.*); another DiPO build is fine if the map covers it, but untested."
                        + " Known builds: EV6 USA 260507 01446fb7…, Ioniq 5 USA 260507 1b080aa1….");
        file("Car framework", "/system/framework/automotive.odex", "173334b2461f4dc5afe1545b9be0f390", "INFO",
                "Identical: every car call and listener is exactly the tested one. Different (the EV6 USA 260507 has efbfceed…): the call, listener and flag checks below decide.");
        file("Themes (routing mod)", "/system/lib/libiap2session.so", "fc7cb675b46a44bd893a1edc054c5e71", "FAIL",
                "The routing mod's native recipe (identification extension, incl. the CarPlay theme parameters) is checked against these bytes. Another build: no theme, and the routing mod's own EV features need the same port. Send this file.");
        file("Themes (routing mod)", "/system/lib/libiap2client.so", "153a97fd6d2c62dc58286aa47c35feb3", "INFO",
                "Only the routing mod's carplay_link_ft_v2 experiment (off by default) patches it.");
    }

    void file(String area, String path, String want, String otherwise, String meaning) {
        String got = md5(path);
        facts.put("md5 " + path, got);
        add(area, path, want.equals(got) ? "PASS" : otherwise, got, want, meaning);
    }

    static final String[][] CALLS = {
            { CarApi.DATC, "registerDATCListener", "(Lcom/hkmc/datc/IDATCListener;Landroid/os/IBinder;)V", "1", "temperature callback (exact °C/°F)" },
            { CarApi.DATC, "getTemperatureUnit", "()I", "3", "the car's temperature unit" },
            { CarApi.DATC, "getDriverOnlyValue", "()I", "27", "Driver Only state" },
            { CarApi.DATC, "getVRClimateStatus", "(I)F", "29", "reading the climate (everything)" },
            { CarApi.DATC, "setVRClimateStatus", "(III)V", "28", "climate commands (fan, A/C, AUTO, temp, modes, defrost, recirculation, SYNC)" },
            { CarApi.DATC, "setFrontClimateControl", "(II)V", "41", "Driver Only and the passenger temperature" },
            { CarApi.USM, "getFrontSeatHeat_DrvState", "()I", "247", "driver seat heat state" },
            { CarApi.USM, "getFrontSeatHeat_AstState", "()I", "248", "passenger seat heat state" },
            { CarApi.USM, "setFrontSeatHeatDrvStep", "(I)V", "d5", "driver seat heat command" },
            { CarApi.USM, "setFrontSeatHeatAstStep", "(I)V", "d6", "passenger seat heat command" },
            { CarApi.VR, "set_RrDefogger_Cmd_AVN", "(I)V", "f", "rear defrost command" },
            { CarApi.BCM, "registerBCMListener", "(Lcom/hkmc/bcm/IBCMListener;Landroid/os/IBinder;)V", "1", "rear defrost state (listener)" },
    };

    void framework() {
        for (String[] c : CALLS) {
            int code = CarApi.code(c[0], c[1], c[2]);
            String iface = c[0].substring(c[0].lastIndexOf('.') + 1);
            String found = code < 0 ? "missing" : Integer.toHexString(code);
            add("Car framework", iface + "." + c[1] + c[2], code < 0 ? "FAIL" : "PASS", found, c[3],
                    code < 0 ? "Not on this firmware (or another signature): cp-climate leaves out " + c[4] + "."
                            : found.equals(c[3]) ? "Same number as the Ioniq 6." : "Another number than the Ioniq 6; cp-climate looks it up by name, so it uses the right one.");
        }
        String datc = CarApi.fingerprint(CarApi.DATC_LISTENER), bcm = CarApi.fingerprint(CarApi.BCM_LISTENER);
        add("Car framework", "IDATCListener fingerprint", CarApi.DATC_LISTENER_PRINT.equals(datc) ? "PASS" : "WARN", String.valueOf(datc), CarApi.DATC_LISTENER_PRINT,
                CarApi.DATC_LISTENER_PRINT.equals(datc) ? "cp-climate registers its temperature listener." : "Differs: cp-climate won't register the temperature listener (temperatures still come from the status flags, in whole degrees).");
        add("Car framework", "IBCMListener fingerprint", CarApi.BCM_LISTENER_PRINT.equals(bcm) ? "PASS" : "WARN", String.valueOf(bcm), CarApi.BCM_LISTENER_PRINT,
                CarApi.BCM_LISTENER_PRINT.equals(bcm) ? "cp-climate registers its body listener for the rear defroster's state." : "Differs: cp-climate won't register it (a listener answering a call wrongly can crash system_server). The rear window button still works, without showing the car's state.");
        // the climate flag numbers cp-climate sends and reads (DATCFlag for getVRClimateStatus/setVRClimateStatus, DATCControl for setFrontClimateControl)
        String[][] flags = {
                { "com.hkmc.datc.DATCFlag", "TEMP", "2" }, { "com.hkmc.datc.DATCFlag", "BLOWER_LEVEL", "5" },
                { "com.hkmc.datc.DATCFlag", "BLOWER_MODE", "6" }, { "com.hkmc.datc.DATCFlag", "AUTO", "8" },
                { "com.hkmc.datc.DATCFlag", "AC", "9" }, { "com.hkmc.datc.DATCFlag", "FRONT_DEFOG", "10" },
                { "com.hkmc.datc.DATCFlag", "INTAKE", "11" }, { "com.hkmc.datc.DATCFlag", "OP_STATE", "12" },
                { "com.hkmc.datc.DATCFlag", "SYNC", "22" }, { "com.hkmc.datc.DATCFlag", "TEMP_PS_C", "29" },
                { "com.hkmc.datc.DATCFlag$DATCControl", "DRIVER_ONLY", "24" }, { "com.hkmc.datc.DATCFlag$DATCControl", "TEMP_DR_C", "28" },
                { "com.hkmc.datc.DATCFlag$DATCControl", "TEMP_PS_C", "29" }, { "com.hkmc.datc.DATCFlag$DATCControl", "TEMP_PS_F", "31" },
        };
        StringBuilder bad = new StringBuilder();
        int same = 0;
        for (String[] f : flags) {
            Integer v = constant(f[0], f[1]);
            if (v != null && v == Integer.parseInt(f[2])) same++;
            else bad.append(bad.length() > 0 ? ", " : "").append(f[1]).append('=').append(v == null ? "missing" : String.valueOf(v));
        }
        add("Car framework", "Climate flag numbers (DATCFlag, DATCControl)", same == flags.length ? "PASS" : "FAIL",
                same + "/" + flags.length + " as the Ioniq 6" + (bad.length() > 0 ? "; differ: " + bad : ""), flags.length + "/" + flags.length,
                same == flags.length ? null : "cp-climate uses these numbers as on the Ioniq 6; different ones would read and set the wrong things. Needs a code change before installing.");
    }

    void live() {
        Object datc = service("com.hkmc.micom.datc"), usm = service("com.hkmc.micom.usm");
        facts.put("service com.hkmc.micom.datc", datc != null ? "present" : "missing");
        facts.put("service com.hkmc.micom.usm", usm != null ? "present" : "missing");
        facts.put("service com.hkmc.micom.bcm", service("com.hkmc.micom.bcm") != null ? "present" : "missing");
        facts.put("service com.hkmc.vr", service("com.hkmc.vr") != null ? "present" : "missing");
        int get = CarApi.code(CarApi.DATC, "getVRClimateStatus", "(I)F");
        int[][] flags = { { 12, 0 }, { 8, 0 }, { 9, 0 }, { 10, 0 }, { 11, 0 }, { 5, 0 }, { 6, 0 }, { 2, 0 }, { 29, 0 }, { 22, 0 } };
        String[] names = { "on (1 on, 2 off)", "AUTO", "A/C", "front defrost", "intake (1 recirc, 2 fresh)", "fan raw (0 off, 2-9)", "vent mode", "driver set temp", "passenger raw ((°C-14)*2)", "SYNC" };
        StringBuilder s = new StringBuilder();
        boolean ok = datc != null && get >= 0;
        for (int i = 0; i < flags.length; i++) {
            String v = call(datc, CarApi.DATC, get, "float", flags[i][0]);
            if (v == null || v.startsWith("error")) ok = false;
            s.append(names[i]).append(' ').append(v).append("; ");
        }
        String unit = call(datc, CarApi.DATC, CarApi.code(CarApi.DATC, "getTemperatureUnit", "()I"), "int");
        String only = call(datc, CarApi.DATC, CarApi.code(CarApi.DATC, "getDriverOnlyValue", "()I"), "int");
        String type = call(datc, CarApi.DATC, CarApi.code(CarApi.DATC, "getClimateType", "()I"), "int");
        s.append("unit ").append(unit).append(" (1 °C, 2 °F); driver only ").append(only).append("; climate type ").append(type);
        facts.put("climate readings", s.toString());
        add("Live climate", "Read-only climate readings", ok ? "PASS" : "WARN", s.toString(), "values (Ioniq 6 °F car: unit 2)",
                ok ? "The car answers cp-climate's reads. Check the values look like the car's panel." : "Some reads failed (as this user, or the service differs); see the values. Running the check as root rules out permissions.");
        if ("1".equals(unit)) add("Live climate", "Temperature unit °C", "WARN", "°C", "°F (tested)", "cp-climate has a °C path, but it was never tried on a car: temperatures need checking on the first drive.");
        String ds = call(usm, CarApi.USM, CarApi.code(CarApi.USM, "getFrontSeatHeat_DrvState", "()I"), "int");
        String ps = call(usm, CarApi.USM, CarApi.code(CarApi.USM, "getFrontSeatHeat_AstState", "()I"), "int");
        boolean seats = ds != null && ps != null && !ds.startsWith("error") && !ps.startsWith("error");
        add("Live climate", "Seat heat states", seats ? "PASS" : "WARN", "driver " + ds + ", passenger " + ps, "2 off, 6/7/8 low/mid/high",
                seats ? "If they show 2 with the seats off, the levels match the Ioniq 6's." : "Seat heat couldn't be read here.");
    }

    // ---- verdicts and the report ----

    List<String[]> verdicts() {
        List<String[]> v = new ArrayList<String[]>();
        boolean nativeOk = pass("CarPlay (DiPO)", "/system/lib/libCarPlayCore.so");
        boolean flags = pass("Car framework", "Climate flag numbers (DATCFlag, DATCControl)");
        boolean read = pass("Car framework", "IDATCService.getVRClimateStatus(I)F");
        boolean set = pass("Car framework", "IDATCService.setVRClimateStatus(III)V");
        boolean front = pass("Car framework", "IDATCService.setFrontClimateControl(II)V");
        v.add(verdict("CarPlay climate (dock temperatures, Climate app)", nativeOk && read && set && flags,
                !nativeOk ? "cp-climate's native patch doesn't fit this CarPlay library" : !flags ? "the climate flag numbers differ" : "climate calls missing"));
        v.add(verdict("Driver Only (passenger power button) and passenger temperature", nativeOk && front && flags && pass("Car framework", "IDATCService.getDriverOnlyValue()I"), "needs setFrontClimateControl and getDriverOnlyValue"));
        boolean seat = pass("Car framework", "IUSMService.setFrontSeatHeatDrvStep(I)V") && pass("Car framework", "IUSMService.setFrontSeatHeatAstStep(I)V")
                && pass("Car framework", "IUSMService.getFrontSeatHeat_DrvState()I") && pass("Car framework", "IUSMService.getFrontSeatHeat_AstState()I");
        v.add(verdict("Seat heat", nativeOk && seat, "seat heat calls missing or CarPlay climate not possible"));
        v.add(verdict("Front defrost", nativeOk && set && flags, "needs setVRClimateStatus and the FRONT_DEFOG flag"));
        boolean rear = pass("Car framework", "IVRService.set_RrDefogger_Cmd_AVN(I)V");
        boolean rearState = rear && pass("Car framework", "IBCMListener fingerprint") && pass("Car framework", "IBCMService.registerBCMListener(Lcom/hkmc/bcm/IBCMListener;Landroid/os/IBinder;)V");
        v.add(new String[] { "Rear defrost (READY only)", !(nativeOk && rear) ? "NO" : rearState ? "YES" : "PARTLY",
                !(nativeOk && rear) ? "no rear defrost command on this firmware" : rearState ? "" : "works, but without showing the car's state (listener differs)" });
        boolean theme = pass("Themes (routing mod)", "/system/lib/libiap2session.so") && pass("System", "E-GMP model (routing mod)");
        v.add(verdict("CarPlay theme, wallpapers and the per-seat climate panels", nativeOk && theme,
                !theme ? "the routing mod's identification recipe doesn't fit this unit's iAP2 library (or not an E-GMP model)" : "CarPlay climate not possible"));
        return v;
    }

    static String[] verdict(String feature, boolean yes, String why) {
        return new String[] { feature, yes ? "YES" : "NO", yes ? "" : why };
    }

    String markdown() {
        StringBuilder m = new StringBuilder();
        m.append("# CarPlay Ultra-Lite compatibility report\n\n");
        m.append("Made ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())).append(" on ").append(facts.get("ro.build.product"))
                .append(" by cp-climate-check ").append(VERSION).append(". Reference car: ").append(REFERENCE)
                .append(". The check only reads; it changes nothing on the unit.\n\n");
        m.append("## Will it work on this car?\n\n| Feature | Works | Why not |\n|---|---|---|\n");
        for (String[] v : verdicts()) m.append("| ").append(v[0]).append(" | ").append(v[1]).append(" | ").append(v[2]).append(" |\n");
        int fails = 0, warns = 0;
        for (Check c : checks) {
            if ("FAIL".equals(c.result)) fails++;
            if ("WARN".equals(c.result)) warns++;
        }
        m.append("\n").append(fails == 0 && warns == 0 ? "Everything matches the reference car: the bundle should work as it is.\n"
                : fails + " FAIL, " + warns + " WARN. See what each means below. Don't install until the FAIL items are dealt with.\n");
        m.append("\n## Checks\n\n| Result | Area | Check | Found | Reference car | What it means |\n|---|---|---|---|---|---|\n");
        for (Check c : checks)
            m.append("| ").append(c.result).append(" | ").append(c.area).append(" | ").append(esc(c.name)).append(" | ").append(esc(c.found))
                    .append(" | ").append(esc(c.expected)).append(" | ").append(esc(c.meaning)).append(" |\n");
        m.append("\n## Facts\n\n");
        for (Map.Entry<String, String> e : facts.entrySet()) m.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        m.append("\n## What to send back, or give to your own Claude\n\n");
        m.append("Send this report (cp-climate-check.md and cp-climate-check.json). For a FAIL on a library, also send that file (`adb pull <path>`); ")
                .append("a FAIL on libCarPlayCore.so or libiap2session.so needs a new recipe built against your file. For a call or flag FAIL, also send ")
                .append("/system/framework/automotive.odex.\n\n");
        m.append("For Claude: cp-climate (GPL-3.0, source in the bundle's source/ folder) patches DiPO's libCarPlayCore.so with one HMG-API ")
                .append("recipe (tools/build.py builds it from the stock library and checks its placement with tests/RecipePlaceTest.java; the hook ")
                .append("sites are documented in native/hooks.c and HANDOFF.md), and its Java part looks the car's binder calls up by name ")
                .append("(CarApi.java). Listener fingerprints come from tools/listener_fingerprint.py on baksmali output of automotive.odex. The ")
                .append("theme identification is in the routing mod's src/native/identity.c with recipes/native-fc7.recipe for libiap2session.so. ")
                .append("Never register a listener whose fingerprint differs without checking every call that writes a reply.\n");
        return m.toString();
    }

    static String esc(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    String json() {
        StringBuilder j = new StringBuilder("{\n  \"tool\": \"cp-climate-check ").append(VERSION).append("\",\n  \"reference\": \"").append(REFERENCE).append("\",\n  \"facts\": {");
        int i = 0;
        for (Map.Entry<String, String> e : facts.entrySet())
            j.append(i++ > 0 ? "," : "").append("\n    \"").append(q(e.getKey())).append("\": \"").append(q(e.getValue())).append('"');
        j.append("\n  },\n  \"verdicts\": [");
        i = 0;
        for (String[] v : verdicts())
            j.append(i++ > 0 ? "," : "").append("\n    {\"feature\": \"").append(q(v[0])).append("\", \"works\": \"").append(v[1]).append("\", \"why\": \"").append(q(v[2])).append("\"}");
        j.append("\n  ],\n  \"checks\": [");
        i = 0;
        for (Check c : checks)
            j.append(i++ > 0 ? "," : "").append("\n    {\"result\": \"").append(c.result).append("\", \"area\": \"").append(q(c.area)).append("\", \"check\": \"").append(q(c.name))
                    .append("\", \"found\": \"").append(q(c.found)).append("\", \"expected\": \"").append(q(c.expected)).append("\"}");
        return j.append("\n  ]\n}\n").toString();
    }

    static String q(String s) {
        StringBuilder o = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') o.append('\\').append(c);
            else if (c < 0x20) o.append(' ');
            else o.append(c);
        }
        return o.toString();
    }

    static void write(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "/data/local/tmp/cp-climate-check");
        out.mkdirs();
        Main m = new Main();
        m.system();
        m.mods();
        m.files();
        m.framework();
        m.live();
        String md = m.markdown();
        write(new File(out, "cp-climate-check.md"), md);
        write(new File(out, "cp-climate-check.json"), m.json());
        for (String[] v : m.verdicts()) System.out.println(v[1] + "\t" + v[0] + (v[2].length() > 0 ? " (" + v[2] + ")" : ""));
        System.out.println("report: " + new File(out, "cp-climate-check.md"));
    }
}
