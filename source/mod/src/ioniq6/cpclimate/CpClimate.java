package ioniq6.cpclimate;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CarPlay climate, milestone 1: DiPO tells the iPhone it speaks the vehicle state protocol
 * (the recipe adds vehicleStateProtocolInfo and displayPanels to /info and echoes the
 * feature in the SETUP reply) and logs the phone's SETUP requests, to see whether it then
 * asks for the vehicle state data streams. M2 accepts those streams (VsChannel); M3 answers
 * them as LIVI does (VehicleState). M4 puts the car's own climate on CarPlay both ways
 * (CarClimate): the car's state mirrored to the phone, the phone's changes sent to the car.
 *
 * Crash guard: each DiPO start that patches is recorded; after GUARD_STARTS starts within
 * GUARD_MS the patch is left out until a CarPlay session has run HEALTHY_MS.
 *
 * Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
 */
public final class CpClimate extends ModEntry {
    static final String DIPO = "com.daudio.app.dipo";
    static final String GUARD_FILE = "cp-climate-guard";
    static final int GUARD_STARTS = 2;
    static final long GUARD_MS = 10 * 60 * 1000L;
    static final long HEALTHY_MS = 60 * 1000L;
    /** the session's stream SETUPs come in after it has started */
    static final long COPY_DELAY_MS = 5000;
    static final int MAX_LINES = 600;
    /** log entries copied into HMG-API's saved log: the patched SETUP log, ours, DiPO's stream refusals */
    static final String[] MARKS = { "Setting up session", "cp-climate", "Unsupported stream type",
            "Command '", "Request accessory UI", "ChangeModeBorrow", "ChangeModeUnborrow", "Modes changed" };

    static ModContext mod;
    static File guard;
    static final Set<String> copied = new HashSet<String>();
    static boolean busy;

    /** files/cpc-vspi.bplist: VehicleState.vspi for the native /info hook (removed if it can't be built) */
    static void writeVspi(File dir, VsChannel.Log log) {
        File to = new File(dir, "cpc-vspi.bplist"), tmp = new File(dir, "cpc-vspi.bplist.tmp");
        try {
            byte[] b = new VehicleState(new VsChannel.Log() {
                @Override
                public void log(String m) {
                }
            }).vspi();
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write(b);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(to)) throw new java.io.IOException("rename failed");
            log.log("vehicleStateProtocolInfo for /info written (" + b.length + " bytes, accessory versions " + VehicleState.changedVersions + ")");
        } catch (Throwable t) {
            to.delete();
            tmp.delete();
            log.log("vehicleStateProtocolInfo for /info not written, LIVI's is used: " + t);
        }
    }

    @Override
    public void appLoaded(ModContext m, LoadedApp app) {
        if (!DIPO.equals(app.packageName)) return;
        mod = m;
        File dir = app.context != null ? app.context.getFilesDir() : new File("/data/data/" + DIPO + "/files");
        guard = new File(dir, GUARD_FILE);
        ClimateMap.loadSetpoints(new File(dir, "cpc-setpoints"));
        List<Long> starts = recentStarts();
        if (starts.size() >= GUARD_STARTS) {
            m.error("crash guard: DiPO started " + starts.size() + " times in " + GUARD_MS / 60000
                    + " min with the patch on, so it stays off this time (delete " + guard + " to retry)", null);
        } else {
            starts.add(System.currentTimeMillis());
            save(starts);
            // CarPlay Ultra-Lite 1.0: two settings. Everything the development builds made switchable is fixed
            // at what was tested on the Ioniq 6 (CEEV USA 260507, 2026-11-08); the code for the other values stays.
            boolean ultra = m.settingBoolean("carplay_ultra", true);
            writeSize(dir, "100");
            writeDock(dir, "1");                                  // the bottom dock with the climate bar
            writeVersion(dir, "950");                             // receiver version 950.7.1 in /info
            writeFlag(dir, "cpc-extras", true, "LIVI display extras");
            writeCluster(dir, "full");                            // the instrument panel with its plugin (climate overlay)
            writeWide(dir, "0");
            writeUltra(dir, ultra ? 2 : 0);                       // virtual cluster + the theme's display names
            boolean iapBridge = false, iap = false;               // iAP over CarPlay: an experiment, off
            writeFlag(dir, "cpc-iap", false, "iAPChannel");
            // the theme's vehicle: the Aston Martin DBX unless the vehicle_variant test setting picks another
            // (the Climate app shows a placeholder instead of its car picture with DBX; which model it needs is a test)
            String model = m.settingString("vehicle_variant", "DBX");
            VehicleState.variant = ultra && !"none".equals(model) ? model : "";
            m.log("vehicle variant " + (VehicleState.variant.isEmpty() ? "none" : VehicleState.variant));
            boolean uiSync = ultra;                               // the theme's layouts, palettes and wallpapers
            writeFlag(dir, "cpc-uisync", uiSync, "uiSync (CarPlay theme layouts and palettes)");
            writeFeatures(dir, ultra ? "livi_all" : "off");
            writeFlag(dir, "cpc-xfeat", ultra, "/info enhancedRequestCarUI");
            // bit 1 = videoPlayback (the phone's AirPlay video while parked), bit 2 = hevc; both only when the phone offers them
            int extraBits = m.settingBoolean("video_playback", true) ? 1 : 0;
            writeNumber(dir, "cpc-xenable", extraBits, extraBits != 0 ? "SETUP extra features: videoPlayback" : "SETUP extra features off");
            writeNumber(dir, "cpc-hid", 0, "LIVI input devices in /info");   // on, iOS drew a knob-focus box
            m.nativePatch(app, "libCarPlayCore.so");
            // M2: the vehicle state data streams the native SETUP handler sends the phone to;
            // M3: answered from one VehicleState the two lanes share
            VsChannel.Log log = new VsChannel.Log() {
                @Override
                public void log(String msg) {
                    mod.log(msg);
                }
            };
            VehicleState[] state = new VehicleState[1];
            VsChannel[] lanes = new VsChannel[2];
            for (int lane = 0; lane < 2; lane++) lanes[lane] = new VsChannel(lane, dir, log, state, lanes);
            for (VsChannel ch : lanes) ch.start();
            // M4: the car's climate on CarPlay both ways (CarClimate reads it and sends the phone's changes)
            CarClimate.rearDefrost = m.settingBoolean("rear_defrost", true);
            // the ambient light test (off): one shared, writable setting in CarPlay's car settings whose value is a #RRGGBB
            // that is sent to the car's mood light; see VehicleState.withAmbientTest and CarClimate.writeAmbient
            VehicleState.ambientTest = CarClimate.ambientTest = m.settingBoolean("ambient_test", false);
            // the extra seat and wheel controls (off): rear seat heat, front seat ventilation, steering wheel heater
            VehicleState.extras = ClimateMap.extras = m.settingBoolean("extra_seats", false);
            // A: ventilation as separate SeatFan services; B: as the seats' own cooling levels, plus a rear zone
            // the driver card has room for three buttons: AUTO, vent position, seat (2026-11-10: more were drawn on top
            // of each other). The wheel heater and the per-seat power are off unless turned on.
            VehicleState.wheelHeater = m.settingBoolean("wheel_heater", false);
            VehicleState.zonePower = m.settingBoolean("zone_power", false);
            VehicleState.rearZone = m.settingBoolean("rear_zone", true);
            ClimateMap.blankWhenOff = m.settingBoolean("blank_when_off", true);
            VehicleState.ventCard = "passenger".equals(m.settingString("vent_card", "driver")) ? "passenger" : "driver";
            VehicleState.coolingMin = "-4".equals(m.settingString("cooling_min", "-4")) ? -4 : -3;
            String layout = m.settingString("extras_layout", "b");
            VehicleState.coolingLayout = "b".equals(layout) || "c".equals(layout) || "d".equals(layout);
            VehicleState.rowRear = "c".equals(layout);
            VehicleState.twoRear = "d".equals(layout);
            // /info's copy of the car (the phone builds its car from it): cp-climate's description, not LIVI's
            writeVspi(dir, log);
            CarClimate car = new CarClimate(state, lanes, log);
            VsChannel.car = car;
            car.start();
            // the Ultra experiment: the virtual cluster's video goes here and is thrown away
            if (ultra) new ClusterSink(log).start();
            if ((extraBits & 1) != 0) new ClusterSink(log, 7134, "videoPlayback").start();
            // video stage 2: the playback messages the native /command hook answers and passes on
            if ((extraBits & 1) != 0) {
                // test: the video engine plays sound only (no picture decoder), to tell a decoder crash from the rest
                writeNumber(dir, "cpc-video-nopicture", m.settingBoolean("video_no_picture", false) ? 1 : 0, "video: sound only (test)");
                // the head unit doesn't play sound from CarPlay's process yet, and trying makes the picture race
                writeNumber(dir, "cpc-video-nosound", m.settingBoolean("video_sound", false) ? 0 : 1, "video: picture only");
                new VideoMessages(log).start();
                // the video engine (ExoPlayer through the phone's proxy), when this build has it
                boolean engine = app.context != null && VideoMessages.startEngine(app.context);
                log.log(engine ? "video: engine loaded" : "video: no engine in this build (answers only)");
            }
            // video stage 1: requestUI("videoplayback:") kept from DiPO (no jump to the launcher), screen borrowed as LIVI does
            VideoUi.out = log;
            VideoUi.enabled = (extraBits & 1) != 0;
            // the iAPChannel experiment, stage A: the phone's iAP stream is decrypted and logged only
            if (iap) {
                IapBridge.out = log;
                IapBridge.loader = app.classLoader;
                new IapChannel(dir, log, iapBridge).start();
            }
            // the uiSync experiment: the theme's layout/palette channel, answered by UiSync
            // the car asks for no layout, so the phone keeps what was picked on its Themes page
            if (uiSync) new UiSyncChannel(dir, log, UiSync.layout("off")).start();
        }
        MethodHook copy = new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                copyLater(COPY_DELAY_MS, false);
            }
        };
        m.hook(app.classLoader, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                IapBridge.carPlay = p.thisObject;
                VideoUi.install(p.thisObject, app.classLoader);
                copyLater(COPY_DELAY_MS, false);
                copyLater(HEALTHY_MS, true);
            }
        });
        m.hook(app.classLoader, "dipo.carplay.session_finalized", copy);
        // iAP the phone sends as iAPSendMessage commands: the wireless-carplay-aa mod hands those to iap2server
        // itself (feeding them here too doubled them); only DiPO's CarPlayService is taken from it
        try {
            m.hook(app.classLoader, "dipo.carplay.iap_message", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    IapBridge.carPlay = p.thisObject;
                }
            });
        } catch (Throwable t) {
            m.log("iAP message hook not installed: " + t);
        }
        // while the iAPChannel stream carries the link (LIVI's duplex tunnel), DiPO's iAPSendMessage copies of the
        // same packets (the wireless-carplay-aa mod's CarPlay channel) are dropped: the phone gets each packet once
        try {
            m.hook(app.classLoader, "dipo.carplay.native_iap_send", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (IapBridge.stream != null) {
                        p.setResult(0);
                        IapBridge.droppedLegacy((Integer) p.args[1]);
                    }
                }
            });
        } catch (Throwable t) {
            m.log("iAP send hook not installed: " + t);
        }
        m.log("M4 in " + app.processName + " (guard " + guard + ", " + starts.size() + " recent start(s))");
    }

    /** the CarPlay size setting, for the native /info hook (files/cpc-size, 50-100 %) */
    static void writeSize(File dir, String size) {
        File f = new File(dir, "cpc-size");
        try {
            int pct = Integer.parseInt(size.trim());
            if (pct < 50 || pct > 100) pct = 100;
            FileWriter w = new FileWriter(f);
            try {
                w.write(pct + "\n");
            } finally {
                w.close();
            }
            mod.log("CarPlay size " + pct + " %");
        } catch (Exception e) {
            f.delete();
            mod.log("CarPlay size: real size (" + e + ")");
        }
    }

    /** the dock position setting, for the native /info hook (files/cpc-dock: 0 auto, 1 bottom, 2 side) */
    static void writeDock(File dir, String dock) {
        File f = new File(dir, "cpc-dock");
        try {
            int edge = Integer.parseInt(dock.trim());
            if (edge < 0 || edge > 4) edge = 1;
            FileWriter w = new FileWriter(f);
            try {
                w.write(edge + "\n");
            } finally {
                w.close();
            }
            mod.log("CarPlay dock edge " + edge);
        } catch (Exception e) {
            f.delete();
            mod.log("CarPlay dock: bottom (" + e + ")");
        }
    }

    /** the receiver version experiment, for the native /info hook (files/cpc-version: 1 for 950.7.1) */
    static void writeVersion(File dir, String version) {
        File f = new File(dir, "cpc-version");
        if (!"950".equals(version.trim())) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write("1\n");
            } finally {
                w.close();
            }
            mod.log("CarPlay receiver version 950.7.1 in /info (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    /** an on/off experiment for the native /info hook: the file holds 1 while on */
    static void writeFlag(File dir, String name, boolean on, String what) {
        File f = new File(dir, name);
        if (!on) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write("1\n");
            } finally {
                w.close();
            }
            mod.log(what + " on (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    /** LIVI's /info feature bits that DiPO's lacks, without its audio ones (CARPLAY_AUDIO_FEATURES: bits 9, 11, 18, 20, 22, 26, 40) */
    static final long LIVI_NON_AUDIO = 0x205203a462L;
    /** all of LIVI's bits that DiPO's lacks, audio ones too */
    static final long LIVI_ALL = 0x615653aee2L & ~0x4104040280L;

    /** the /info feature bits experiment (files/cpc-feat, hex): livi_nonaudio, livi_all or bit_N; off deletes it */
    static void writeFeatures(File dir, String value) {
        File f = new File(dir, "cpc-feat");
        String v = value.trim();
        long bits = 0;
        if ("livi_nonaudio".equals(v)) bits = LIVI_NON_AUDIO;
        else if ("livi_all".equals(v)) bits = LIVI_ALL;
        else if (v.startsWith("bit_")) {
            try {
                int bit = Integer.parseInt(v.substring(4));
                if (bit >= 0 && bit < 63) bits = 1L << bit;
            } catch (NumberFormatException e) {
                bits = 0;
            }
        }
        if (bits == 0) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write(Long.toHexString(bits) + "\n");
            } finally {
                w.close();
            }
            mod.log("/info feature bits 0x" + Long.toHexString(bits) + " (" + v + ") on (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    /** a small number for the native hooks; 0 deletes the file */
    static void writeNumber(File dir, String name, int value, String what) {
        File f = new File(dir, name);
        if (value == 0) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write(value + "\n");
            } finally {
                w.close();
            }
            mod.log(what + " on (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    /** the Ultra experiment for the native hooks (files/cpc-ultra: 1 virtual cluster, 2 with the theme's display ids) */
    static void writeUltra(File dir, int mode) {
        File f = new File(dir, "cpc-ultra");
        if (mode == 0) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write(mode + "\n");
            } finally {
                w.close();
            }
            mod.log("CarPlay Ultra virtual cluster on" + (mode == 2 ? ", with the theme's display ids" : "") + " (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    /** the Apple widescreen view areas experiment (files/cpc-wide: 1 start whole, 2 start right) */
    static void writeWide(File dir, String value) {
        File f = new File(dir, "cpc-wide");
        String v = value.trim();
        if (!"1".equals(v) && !"2".equals(v)) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write(v + "\n");
            } finally {
                w.close();
            }
            mod.log("Apple widescreen view areas, start " + ("2".equals(v) ? "right" : "whole") + " (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    /** the instrument panel experiment (files/cpc-nocluster: 1 off, 2 no showsInstruments, 3 no plugin) */
    static void writeCluster(File dir, Object value) {
        File f = new File(dir, "cpc-nocluster");
        String v = value == null ? "full" : String.valueOf(value);
        // an older version saved this setting as a toggle: false meant off
        int code = "off".equals(v) || "false".equals(v) ? 1 : "no_instruments".equals(v) ? 2 : "no_plugin".equals(v) ? 3
                : "plugin_main".equals(v) ? 4 : "plugin_stream".equals(v) ? 5 : 0;
        if (code == 0) {
            f.delete();
            return;
        }
        try {
            FileWriter w = new FileWriter(f);
            try {
                w.write(code + "\n");
            } finally {
                w.close();
            }
            mod.log("instrument panel: " + v + " (experiment)");
        } catch (Exception e) {
            f.delete();
        }
    }

    static List<Long> recentStarts() {
        List<Long> out = new ArrayList<Long>();
        long now = System.currentTimeMillis();
        try {
            BufferedReader in = new BufferedReader(new FileReader(guard));
            try {
                String l;
                while ((l = in.readLine()) != null) {
                    try {
                        long t = Long.parseLong(l.trim());
                        if (now - t < GUARD_MS && t <= now) out.add(t);
                    } catch (NumberFormatException e) {
                        // a damaged line: skipped
                    }
                }
            } finally {
                in.close();
            }
        } catch (Exception e) {
            // no file yet
        }
        return out;
    }

    static void save(List<Long> starts) {
        try {
            FileWriter w = new FileWriter(guard);
            try {
                for (Long t : starts) w.write(t + "\n");
            } finally {
                w.close();
            }
        } catch (Exception e) {
            mod.error("crash guard: can't write " + guard, e);
        }
    }

    static synchronized void copyLater(final long delay, final boolean healthy) {
        if (busy && !healthy) return;
        if (!healthy) busy = true;
        new Thread("cp-climate") {
            @Override
            public void run() {
                try {
                    Thread.sleep(delay);
                    copy();
                    if (healthy && guard.delete()) mod.log("crash guard: CarPlay ran " + delay / 1000 + " s, starts cleared");
                } catch (Throwable t) {
                    mod.error("copying the CarPlay lines", t);
                } finally {
                    if (!healthy) {
                        synchronized (CpClimate.class) {
                            busy = false;
                        }
                    }
                }
            }
        }.start();
    }

    /** DiPO's own TccCarPlay lines: an entry spans several lines that share one header */
    static synchronized void copy() throws Exception {
        Process p = Runtime.getRuntime().exec(new String[] { "logcat", "-d", "-v", "threadtime", "-s", "TccCarPlay:*" });
        List<String> lines = new ArrayList<String>();
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String l;
            while ((l = in.readLine()) != null) lines.add(l);
        } finally {
            in.close();
            p.destroy();
        }
        int sent = 0, entries = 0;
        boolean streams130 = false;
        for (int i = 0; i < lines.size() && sent < MAX_LINES; i++) {
            String l = lines.get(i);
            if (!marked(l)) continue;
            String head = header(l);
            if (head == null || !copied.add(head)) continue;
            entries++;
            for (int j = i; j < lines.size() && sent < MAX_LINES && head.equals(header(lines.get(j))); j++) {
                String body = lines.get(j).substring(head.length());
                if (body.contains("\"type\" : 130") || body.contains("stream type: 130")) streams130 = true;
                mod.log("CP " + body);
                sent++;
            }
        }
        if (entries > 0) {
            mod.log("CP copied " + entries + " entr" + (entries == 1 ? "y" : "ies") + ", " + sent + " line(s); type 130 streams "
                    + (streams130 ? "ASKED FOR" : "not seen"));
        }
    }

    static boolean marked(String l) {
        for (String m : MARKS) if (l.contains(m)) return true;
        return false;
    }

    /** "11-07 12:44:01.930  1363  1363 " from a threadtime line, or null */
    static String header(String l) {
        if (l.length() < 31 || l.charAt(2) != '-' || l.charAt(5) != ' ') return null;
        int k = 18;
        for (int f = 0; f < 2; f++) {
            while (k < l.length() && l.charAt(k) == ' ') k++;
            while (k < l.length() && Character.isDigit(l.charAt(k))) k++;
        }
        return k < l.length() ? l.substring(0, k + 1) : null;
    }
}
