package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The car's side of CarPlay's vehicle state protocol, as M3: answers the phone's configRequest,
 * registerRequest, readRequest, writeRequest and controlRequest from LIVI's description (VsData)
 * and keeps what the phone writes, with LIVI's rules for how one value moves another (auto
 * climate, max A/C, synced zones, levels and their on switches, mute). Nothing reaches the car
 * yet: a write only changes what the phone is told. A port of LIVI vehicle_state.rs handle()
 * and rules.rs; tests/VehicleStateTest.java checks it answers byte for byte as LIVI does.
 * Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
 */
final class VehicleState {
    static final int NORMAL = 0, HIGH = 1;     // lanes, numbered as VsChannel numbers them

    static final long PLUGIN_ID = 0;
    static final long NO_VALUE = -6727, WRITTEN = 0, NO_SUCH_COMMAND = -6735;

    static final long HVAC_ON = 514, COMPRESSOR = 515, MAX_AC = 516, FAN_AUTO = 526, RECIRCULATION = 528;
    static final long AUTO_CLIMATE = 530, DRIVER_TARGET = 520, PASSENGER_TARGET = 534, ZONES_SYNCED = 538;
    static final long[] VENTS = { 548, 558 };   // upper and lower: the Ioniq 6 has no body-only vent
    static final long FAN_LEVEL = 525, FAN_ON = 524;
    /** level, then its on switch: the two seat heaters and the fan */
    static final long[][] LEVELLED = { { 1026, 1027 }, { 1030, 1031 }, { FAN_LEVEL, FAN_ON } };
    static final double TEMPERATURE_LO = 16.0;
    static final long VOLUME = 2306, MUTE = 2309;
    static final long HISTORY = 3584, TEMPORARY_ZONE = 3085, VERSION_LINE = 1281;
    static final int HISTORY_SLOTS = 10;

    /** one message for the phone, and the lane it goes on */
    static final class Out {
        final int lane;
        final byte[] message;

        Out(int lane, byte[] message) {
            this.lane = lane;
            this.message = message;
        }
    }

    /** the real car, when one is attached (CarClimate): it gets what the phone writes */
    interface Car {
        void phoneWrote(Map<Long, Object> written, Map<Long, Object> now);
    }

    /** how long a value the phone wrote wins over the car's old one, while the car catches up */
    static final long HOLD_MS = 2500;

    final VsChannel.Log out;
    volatile Car car;
    /** when the session that made this state started (the second lane joins within VsChannel.SESSION_PAIRING_MS) */
    volatile long startedAt;
    private final Map<Long, Long> heldSince = new HashMap<Long, Long>();
    /**
     * seat cooling writes not echoed at once but confirmed when the car reports them (about a second later). Test of
     * 2026-11-10: with the instant echo the Climate app drew cooling one step behind (heating was right); its
     * progress view delays animations, and the echo may land inside that delay.
     */
    private final java.util.Set<Long> echoLater = new java.util.HashSet<Long>();

    /** a write of the front seats' level going to or from cooling (layout B/C/D) */
    static boolean coolingWrite(long iid, Object before, Object v) {
        if (!ClimateMap.cooling() || (iid != ClimateMap.DRIVER_SEAT_LEVEL && iid != ClimateMap.PASSENGER_SEAT_LEVEL)) return false;
        Long b = OpackCodec.asLong(before), n = OpackCodec.asLong(v);
        return (b != null && b < 0) || (n != null && n < 0);
    }
    final TreeMap<Long, Object> values = new TreeMap<Long, Object>();
    final TreeSet<Long> followed = new TreeSet<Long>();
    private final Map<Long, Object> resume = new HashMap<Long, Object>();
    private List<Object[]> beforeMaxAc;
    private final Set<Long> writable = new HashSet<Long>(), shared = new HashSet<Long>();
    private final Map<Long, Long> notifiers = new HashMap<Long, Long>();
    private final Map<Long, String> names = new HashMap<Long, String>();
    private final Map<Long, String> controls = new HashMap<Long, String>();
    private final OpackCodec.Raw accessories;

    /** the vehicle variant the phone resolves a CarPlay theme's assets by (setting vehicle_variant), "" for LIVI's stock description */
    static volatile String variant = "";
    static final long VARIANT_SERVICE = 769, INTERIOR_STYLE = 770, EXTERIOR_STYLE = 771, VEHICLE_LAYOUT = 772;

    /**
     * LIVI vehicle_state.rs with_variant: the service holding interiorStyle gets the exterior style, interior style
     * and vehicle layout, each a fixed string naming the model (a theme's variant, e.g. DB12Coupe); null if the
     * accessory list has no such service.
     */
    @SuppressWarnings("unchecked")
    static byte[] withVariant(byte[] accessories, String model, Map<Long, Object> values) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            for (Object s : (List<Object>) services) {
                if (!(s instanceof Map) || !(((Map<Object, Object>) s).get("characteristics") instanceof List)) continue;
                List<Object> chars = (List<Object>) ((Map<Object, Object>) s).get("characteristics");
                Map<Object, Object> style = null;
                for (Object c : chars)
                    if (c instanceof Map && Long.valueOf(INTERIOR_STYLE).equals(OpackCodec.asLong(((Map<Object, Object>) c).get("iid"))))
                        style = (Map<Object, Object>) c;
                if (style == null) continue;
                List<Object> three = new ArrayList<Object>();
                long[][] kinds = {{EXTERIOR_STYLE, 0x48000003L}, {INTERIOR_STYLE, 0x48000004L}, {VEHICLE_LAYOUT, 0x48000005L}};
                for (long[] k : kinds) {
                    Map<Object, Object> c = new java.util.LinkedHashMap<Object, Object>(style);
                    c.put("iid", k[0]);
                    c.put("type", k[1]);
                    c.put("mutable", Boolean.FALSE);
                    c.put("initialValue", model);
                    three.add(c);
                    values.put(k[0], model);
                }
                ((Map<Object, Object>) s).put("characteristics", three);
                return OpackCodec.encode(list);
            }
        }
        return null;
    }

    /** nowPlaying's currentMediaSource: LIVI's description says "DAB" (its radio), which makes Ultra open on a radio this car doesn't have */
    static final long NOW_PLAYING_SOURCE = 4354;

    /**
     * The description with currentMediaSource's initial value emptied (no current source: the phone keeps its
     * own last app); the bytes unchanged if the accessory list has no such characteristic.
     */
    @SuppressWarnings("unchecked")
    static byte[] withoutCurrentSource(byte[] accessories) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return accessories;
        boolean changed = false;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            for (Object s : (List<Object>) services) {
                if (!(s instanceof Map) || !(((Map<Object, Object>) s).get("characteristics") instanceof List)) continue;
                for (Object c : (List<Object>) ((Map<Object, Object>) s).get("characteristics")) {
                    if (c instanceof Map && Long.valueOf(NOW_PLAYING_SOURCE).equals(OpackCodec.asLong(((Map<Object, Object>) c).get("iid")))) {
                        ((Map<Object, Object>) c).put("initialValue", "");
                        changed = true;
                    }
                }
            }
        }
        return changed ? OpackCodec.encode(list) : accessories;
    }

    /** the ambient_test setting (CpClimate): one more line in CarPlay's car settings, writable by the phone */
    static volatile boolean ambientTest;
    /** the test line's service and its settingValue; LIVI's version line is service VERSION_LINE (its values 1282-1288), the build date 1289, the section 1297 */
    static final long AMBIENT_LINE = 1305, AMBIENT_VALUE = 1308;

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object o) {
        if (o instanceof Map) {
            Map<Object, Object> m = new LinkedHashMap<Object, Object>();
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) m.put(e.getKey(), deepCopy(e.getValue()));
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object x : (List<Object>) o) l.add(deepCopy(x));
            return l;
        }
        return o;
    }

    /**
     * The description with one more settings line, "Ambient color", next to LIVI's version line (same section): a
     * copy of that line whose settingValue is shared and writable, holding a #RRGGBB. Its values (the copy's seven)
     * go into values so the phone can read them; null if there is no version line to copy.
     */
    @SuppressWarnings("unchecked")
    static byte[] withAmbientTest(byte[] accessories, String hex, Map<Long, Object> values) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        final long shift = AMBIENT_LINE - VERSION_LINE;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            for (Object s : (List<Object>) services) {
                if (!(s instanceof Map) || !Long.valueOf(VERSION_LINE).equals(OpackCodec.asLong(((Map<Object, Object>) s).get("iid")))) continue;
                Map<Object, Object> line = (Map<Object, Object>) deepCopy(s);
                line.put("iid", AMBIENT_LINE);
                for (Object o : (List<Object>) line.get("characteristics")) {
                    Map<Object, Object> c = (Map<Object, Object>) o;
                    long from = OpackCodec.asLong(c.get("iid"));
                    long iid = from + shift;
                    c.put("iid", iid);
                    Object value;
                    switch ((int) (from - VERSION_LINE)) {
                        case 1: value = "AmbientColor"; break;          // settingIdentifier
                        case 2: value = "Ambient color (test)"; break;  // settingLabel
                        case 3: value = hex;                            // settingValue: shared, writable
                            c.put("mutable", Boolean.TRUE);
                            c.put("writable", Boolean.TRUE);
                            c.put("oemWritable", Boolean.TRUE);
                            break;
                        case 5: value = "lightbulb"; break;             // settingSymbol
                        case 6: value = 2L; break;                      // settingSortOrder: after version and build date
                        default: value = c.get("initialValue");         // category, section: as the version line
                    }
                    if (value != null) {
                        c.put("initialValue", value);
                        values.put(iid, value);
                    }
                }
                ((List<Object>) services).add(line);
                return OpackCodec.encode(list);
            }
        }
        return null;
    }

    /** the extra_seats setting (CpClimate): rear seat heat, front seat ventilation and the steering wheel heater */
    static volatile boolean extras;

    /**
     * A copy of the front seat heating service (kind 0x2200_0001: level, on, layout key) moved to iid base: the rear
     * seats take the second row's layout keys; ventilation is the same service as kind 0x2200_0002 (SeatFan) whose level is
     * a uint8 FanLevel (0x3100_0012). Apple's type numbers are from a decompiled iOS table, not yet tried on a phone.
     */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> seatCopy(Map<Object, Object> template, long base, String layoutKey, long maxLevel, boolean fan, Map<Long, Object> values) {
        Map<Object, Object> svc = (Map<Object, Object>) deepCopy(template);
        long shift = base - OpackCodec.asLong(svc.get("iid"));
        svc.put("iid", base);
        if (fan) svc.put("type", 0x22000002L);
        for (Object o : (List<Object>) svc.get("characteristics")) {
            Map<Object, Object> c = (Map<Object, Object>) o;
            long iid = OpackCodec.asLong(c.get("iid")) + shift;
            c.put("iid", iid);
            Object value = null;
            switch ((int) (iid - base)) {
                case 1:                                        // the level
                    c.put("maximumValue", maxLevel);
                    if (fan) {
                        c.put("type", 0x31000012L);
                        c.put("format", 1L);                   // uint8
                    }
                    value = 0L;
                    break;
                case 2: value = Boolean.FALSE; break;          // on
                case 3: value = layoutKey; break;              // vehicleLayoutKey
                default: break;
            }
            if (value != null) {
                c.put("initialValue", value);
                values.put(iid, value);
            }
        }
        return svc;
    }

    /** the steering wheel heater: one service (kind 0x1100_0003) on the climate accessory with one level, 0 off and 1 on */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> wheelService(Map<Object, Object> template, Map<Long, Object> values) {
        Map<Object, Object> svc = (Map<Object, Object>) deepCopy(template);
        long from = OpackCodec.asLong(svc.get("iid"));
        svc.put("iid", 571L);
        svc.put("type", 0x11000003L);
        List<Object> only = new ArrayList<Object>();
        for (Object o : (List<Object>) svc.get("characteristics")) {
            Map<Object, Object> c = (Map<Object, Object>) o;
            if (OpackCodec.asLong(c.get("iid")) - from != 1) continue;
            c.put("iid", ClimateMap.WHEEL_LEVEL);
            c.put("maximumValue", 1L);
            c.put("initialValue", 0L);
            values.put(ClimateMap.WHEEL_LEVEL, 0L);
            only.add(c);
        }
        svc.put("characteristics", only);
        return svc;
    }

    /**
     * the extras_layout setting (CpClimate): false is layout A (ventilation as separate SeatFan services), true is
     * layout B (ventilation as the cooling half of the front seats' own HeatingCoolingLevel, -1..-3, the way iOS 27's
     * CAFSeatHeatingCooling models it, plus a rear climate zone so the rear seats' heat has a panel)
     */
    static volatile boolean coolingLayout;
    /**
     * layout C (extras_layout "c"): layout B, but the rear seats' heat is one control for the whole row, keyed
     * seat_2ndRow. The theme has a selected-seat image for seat_2ndRow but none for seat_2ndRow_left/right, and the
     * Climate app shows its placeholder (no car picture) when an image it asks for is missing (the suspected cause).
     */
    static volatile boolean rowRear;
    /**
     * layout D (extras_layout "d"): layout B's front cooling, the rear seats' heat on their own seats
     * (seat_2ndRow_left / _right) and a read-only rear Temperature zone for each (no row zone, no middle). The
     * theme has no picture for those seats, so the Climate app shows its zone buttons instead of the car.
     */
    static volatile boolean twoRear;
    /** test switch (setting wheel_heater): false leaves the steering wheel heater out (Climate drew it over the vent position button) */
    static volatile boolean wheelHeater = true;
    /**
     * setting rear_zone (layout B/C): a rear Temperature service (seat_2ndRow, iids 576-580). The Climate app makes one
     * zone card per Temperature service, so without it the rear seats' heat has nowhere to show. Read-only: the car
     * has no rear temperature, so it mirrors the driver's.
     */
    static volatile boolean rearZone = true;

    /** a read-only copy of the driver's Temperature service for the rear row */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> rearTemperature(Map<Object, Object> driver, Map<Long, Object> values) {
        return rearTemperature(driver, ClimateMap.REAR_TEMP, "seat_2ndRow", values);
    }

    /** a read-only copy of the driver's Temperature service at base (base+1 on, +2 target, +3 current, +4 layout key) */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> rearTemperature(Map<Object, Object> driver, long base, String key, Map<Long, Object> values) {
        Map<Object, Object> svc = (Map<Object, Object>) deepCopy(driver);
        long shift = base - OpackCodec.asLong(svc.get("iid"));
        svc.put("iid", base);
        for (Object o : (List<Object>) svc.get("characteristics")) {
            Map<Object, Object> c = (Map<Object, Object>) o;
            long iid = OpackCodec.asLong(c.get("iid")) + shift;
            c.put("iid", iid);
            c.put("writable", Boolean.FALSE);
            if (iid == base + 4) {
                c.put("initialValue", key);
                values.put(iid, key);
            } else if (c.containsKey("initialValue")) {
                values.put(iid, c.get("initialValue"));
            }
        }
        return svc;
    }
    /** test switch (setting zone_power): false leaves the per-seat power services (ZoneOn) out (a power button was drawn over AUTO) */
    static volatile boolean zonePower = true;
    /** the per-seat power services: LIVI's driver and passenger, layout B's rear */
    static final long[] ZONE_ON_SERVICES = { 563, 566, 573 };

    /**
     * the cabin service (513) with Apple's HVACOn (0x31000002) as iid 514: LIVI's has none, so the phone never knew
     * the climate system was off (zones kept showing 17.0 / LO). ClimateMap already fills and writes HVAC_ON.
     * Null if there is no cabin service or it already has 514.
     */
    @SuppressWarnings("unchecked")
    static byte[] withHvacOn(byte[] accessories, Map<Long, Object> values) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            for (Object s : (List<Object>) services) {
                if (!(s instanceof Map) || !Long.valueOf(513).equals(OpackCodec.asLong(((Map<Object, Object>) s).get("iid")))) continue;
                List<Object> chars = (List<Object>) ((Map<Object, Object>) s).get("characteristics");
                Map<Object, Object> template = null;
                for (Object c : chars) {
                    Long iid = OpackCodec.asLong(((Map<Object, Object>) c).get("iid"));
                    if (Long.valueOf(ClimateMap.HVAC_ON).equals(iid)) return null;
                    if (Long.valueOf(ClimateMap.COMPRESSOR).equals(iid)) template = (Map<Object, Object>) c;
                }
                if (template == null) return null;
                Map<Object, Object> c = new java.util.LinkedHashMap<Object, Object>(template);
                c.put("iid", ClimateMap.HVAC_ON);
                c.put("type", 0x31000002L);
                c.put("initialValue", Boolean.FALSE);
                chars.add(0, c);
                values.put(ClimateMap.HVAC_ON, Boolean.FALSE);
                return OpackCodec.encode(list);
            }
        }
        return null;
    }

    /** the description without the per-seat power services (ZoneOn); null if there are none */
    @SuppressWarnings("unchecked")
    static byte[] withoutZonePower(byte[] accessories) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        int removed = 0;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            java.util.Iterator<Object> it = ((List<Object>) services).iterator();
            while (it.hasNext()) {
                Object s = it.next();
                Long iid = s instanceof Map ? OpackCodec.asLong(((Map<Object, Object>) s).get("iid")) : null;
                for (long z : ZONE_ON_SERVICES) {
                    if (iid != null && iid == z) {
                        it.remove();
                        removed++;
                        break;
                    }
                }
            }
        }
        return removed == 0 ? null : OpackCodec.encode(list);
    }
    /** layout B's rear zone: a CAFZoneOn (0x1100_0014) for seat_2ndRow; on (+1) always true, layout key (+2) */
    static final long REAR_ZONE = 573, REAR_ZONE_ON = 574;

    /** a copy of the per-seat power service (CAFZoneOn) for the rear row, its on fixed at true (the car has no rear zone to switch) */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> rearZone(Map<Object, Object> template, Map<Long, Object> values) {
        Map<Object, Object> svc = (Map<Object, Object>) deepCopy(template);
        long shift = REAR_ZONE - OpackCodec.asLong(svc.get("iid"));
        svc.put("iid", REAR_ZONE);
        for (Object o : (List<Object>) svc.get("characteristics")) {
            Map<Object, Object> c = (Map<Object, Object>) o;
            long iid = OpackCodec.asLong(c.get("iid")) + shift;
            c.put("iid", iid);
            Object value = null;
            if (iid == REAR_ZONE_ON) {
                value = Boolean.TRUE;
                c.put("writable", Boolean.FALSE);
                c.put("mutable", Boolean.FALSE);
            } else if (iid == REAR_ZONE + 2) {
                value = "seat_2ndRow";
            }
            if (value != null) {
                c.put("initialValue", value);
                values.put(iid, value);
            }
        }
        return svc;
    }

    /** the front seats' HeatingCoolingLevel (iid 1026 / 1030) opened to -3, the cooling (ventilation) steps */
    @SuppressWarnings("unchecked")
    static void withCooling(Map<Object, Object> seat) {
        long base = OpackCodec.asLong(seat.get("iid"));
        for (Object o : (List<Object>) seat.get("characteristics")) {
            Map<Object, Object> c = (Map<Object, Object>) o;
            if (OpackCodec.asLong(c.get("iid")) == base + 1) c.put("minimumValue", -3L);
        }
    }

    /** the description with the extra seat and wheel services (null if there is no seat heating or climate service to copy) */
    @SuppressWarnings("unchecked")
    static byte[] withExtras(byte[] accessories, Map<Long, Object> values) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        List<Object> seatServices = null, climateServices = null;
        Map<Object, Object> seatTemplate = null, passengerSeat = null, zoneTemplate = null, driverTemp = null;
        for (Object a : (List<Object>) list) {
            Object services = a instanceof Map ? ((Map<Object, Object>) a).get("services") : null;
            if (!(services instanceof List)) continue;
            for (Object s : (List<Object>) services) {
                if (!(s instanceof Map)) continue;
                Long iid = OpackCodec.asLong(((Map<Object, Object>) s).get("iid"));
                if (Long.valueOf(1025).equals(iid)) {
                    seatServices = (List<Object>) services;
                    seatTemplate = (Map<Object, Object>) s;
                }
                if (Long.valueOf(1029).equals(iid)) passengerSeat = (Map<Object, Object>) s;
                if (Long.valueOf(563).equals(iid)) zoneTemplate = (Map<Object, Object>) s;
                if (Long.valueOf(518).equals(iid)) driverTemp = (Map<Object, Object>) s;
                if (Long.valueOf(513).equals(iid)) climateServices = (List<Object>) services;
            }
        }
        if (seatTemplate == null || climateServices == null) return null;
        if (coolingLayout && (passengerSeat == null || zoneTemplate == null)) return null;
        // the rear copies are made before the front seats are opened to cooling, so they stay heat only
        if (coolingLayout && rowRear && !twoRear) {
            seatServices.add(seatCopy(seatTemplate, 1033, "seat_2ndRow", 2, false, values));
        } else {
            seatServices.add(seatCopy(seatTemplate, 1033, "seat_2ndRow_left", 2, false, values));
            seatServices.add(seatCopy(seatTemplate, 1037, "seat_2ndRow_right", 2, false, values));
        }
        if (coolingLayout) {
            withCooling(seatTemplate);
            withCooling(passengerSeat);
            if (!twoRear) climateServices.add(rearZone(zoneTemplate, values));
            if (rearZone && driverTemp != null) {
                if (twoRear) {
                    climateServices.add(rearTemperature(driverTemp, ClimateMap.REAR_TEMP, "seat_2ndRow_left", values));
                    climateServices.add(rearTemperature(driverTemp, ClimateMap.REAR2_TEMP, "seat_2ndRow_right", values));
                } else {
                    climateServices.add(rearTemperature(driverTemp, values));
                }
            }
        } else {
            seatServices.add(seatCopy(seatTemplate, 1041, "seat_front_left", 3, true, values));
            seatServices.add(seatCopy(seatTemplate, 1045, "seat_front_right", 3, true, values));
        }
        if (wheelHeater) climateServices.add(wheelService(seatTemplate, values));
        return OpackCodec.encode(list);
    }

    @SuppressWarnings("unchecked")
    VehicleState(VsChannel.Log out) {
        this.out = out;
        Map<Object, Object> initial = (Map<Object, Object>) OpackCodec.decode(VsData.bytes(VsData.VALUES));
        for (Map.Entry<Object, Object> e : initial.entrySet()) values.put(OpackCodec.asLong(e.getKey()), e.getValue());
        for (int iid : VsData.WRITABLE) writable.add((long) iid);
        for (int iid : VsData.SHARED) shared.add((long) iid);
        for (int i = 0; i < VsData.NOTIFIERS.length; i += 2) notifiers.put((long) VsData.NOTIFIERS[i], (long) VsData.NOTIFIERS[i + 1]);
        for (int i = 0; i < VsData.NAME_IIDS.length; i++) names.put((long) VsData.NAME_IIDS[i], VsData.NAMES[i]);
        for (int i = 0; i < VsData.CONTROL_IIDS.length; i++) controls.put((long) VsData.CONTROL_IIDS[i], VsData.CONTROL_NAMES[i]);
        byte[] acc = VsData.bytes(VsData.ACCESSORIES);
        // no radio on this car: no current media source, so Ultra doesn't open on LIVI's DAB radio
        acc = withoutCurrentSource(acc);
        values.put(NOW_PLAYING_SOURCE, "");
        if (ambientTest) {
            // the ambient light test: starts at the car's own colour (white if it can't be read)
            String hex = CarClimate.ambientHex();
            if (hex == null) hex = "#FFFFFF";
            byte[] with = withAmbientTest(acc, hex, values);
            if (with != null) {
                acc = with;
                writable.add(AMBIENT_VALUE);
                shared.add(AMBIENT_VALUE);
                names.put(AMBIENT_VALUE, "ambientColor");
                out.log("VS ambient light test setting added (value " + AMBIENT_VALUE + ", " + hex + ")");
            } else {
                out.log("VS ambient light test not added: no version line to copy");
            }
        }
        if (extras) {
            byte[] with = withExtras(acc, values);
            if (with != null) {
                acc = with;
                boolean row = coolingLayout && rowRear && !twoRear;
                long[] ids = row
                    ? new long[] { ClimateMap.REAR_LEFT_LEVEL, ClimateMap.REAR_LEFT_ON, ClimateMap.WHEEL_LEVEL }
                    : coolingLayout
                    ? new long[] { ClimateMap.REAR_LEFT_LEVEL, ClimateMap.REAR_LEFT_ON, ClimateMap.REAR_RIGHT_LEVEL, ClimateMap.REAR_RIGHT_ON,
                        ClimateMap.WHEEL_LEVEL }
                    : new long[] { ClimateMap.REAR_LEFT_LEVEL, ClimateMap.REAR_LEFT_ON, ClimateMap.REAR_RIGHT_LEVEL, ClimateMap.REAR_RIGHT_ON,
                        ClimateMap.DRIVER_VENT_LEVEL, ClimateMap.DRIVER_VENT_ON, ClimateMap.PASSENGER_VENT_LEVEL, ClimateMap.PASSENGER_VENT_ON,
                        ClimateMap.WHEEL_LEVEL };
                String[] label = row
                    ? new String[] { "rearSeatsHeatingLevel", "rearSeatsHeatingOn", "steeringWheelHeatingLevel" }
                    : coolingLayout
                    ? new String[] { "rearLeftSeatHeatingLevel", "rearLeftSeatHeatingOn", "rearRightSeatHeatingLevel", "rearRightSeatHeatingOn",
                        "steeringWheelHeatingLevel" }
                    : new String[] { "rearLeftSeatHeatingLevel", "rearLeftSeatHeatingOn", "rearRightSeatHeatingLevel", "rearRightSeatHeatingOn",
                        "driverSeatFanLevel", "driverSeatFanOn", "passengerSeatFanLevel", "passengerSeatFanOn", "steeringWheelHeatingLevel" };
                for (int i = 0; i < ids.length; i++) {
                    if (!wheelHeater && ids[i] == ClimateMap.WHEEL_LEVEL) continue;
                    writable.add(ids[i]);
                    shared.add(ids[i]);
                    names.put(ids[i], label[i]);
                }
                if (coolingLayout) names.put(REAR_ZONE_ON, "rearZoneOn");
                if (coolingLayout && rearZone) {
                    names.put(ClimateMap.REAR_ON, twoRear ? "rearLeftTemperatureOn" : "rearTemperatureOn");
                    names.put(ClimateMap.REAR_TARGET, twoRear ? "rearLeftTargetTemperature" : "rearTargetTemperature");
                    if (twoRear) {
                        names.put(ClimateMap.REAR2_ON, "rearRightTemperatureOn");
                        names.put(ClimateMap.REAR2_TARGET, "rearRightTargetTemperature");
                    }
                }
                out.log(twoRear
                    ? "VS extra controls added (layout D): rear left and right seat heat, each with its own read-only rear zone, front seat cooling levels -1..-3"
                    : row
                    ? "VS extra controls added (layout C): rear seats' heat as one seat_2ndRow control + rear zone, front seat cooling levels -1..-3, steering wheel heater"
                    : coolingLayout
                    ? "VS extra controls added (layout B): rear seat heat + rear zone, front seat cooling levels -1..-3, steering wheel heater"
                    : "VS extra controls added (layout A): rear seat heat, front seat ventilation (SeatFan), steering wheel heater");
            } else {
                out.log("VS extra controls not added: no seat heating or climate service to copy");
            }
        }
        String model = variant;
        if (model != null && !model.isEmpty()) {
            Map<Long, Object> styles = new HashMap<Long, Object>();
            byte[] varied = withVariant(acc, model, styles);
            if (varied != null) {
                acc = varied;
                values.putAll(styles);
                names.put(EXTERIOR_STYLE, "exteriorStyle");
                names.put(VEHICLE_LAYOUT, "vehicleLayout");
                out.log("VS vehicle variant " + model + " (exteriorStyle, interiorStyle, vehicleLayout)");
            } else {
                out.log("VS vehicle variant " + model + " not set: no interiorStyle service");
            }
        }
        byte[] hvac = withHvacOn(acc, values);
        if (hvac != null) {
            acc = hvac;
            writable.add(ClimateMap.HVAC_ON);
            shared.add(ClimateMap.HVAC_ON);
            names.put(ClimateMap.HVAC_ON, "hvacOn");
            out.log("VS climate system power (HVACOn, 514) added to the cabin");
        }
        if (!zonePower) {
            byte[] without = withoutZonePower(acc);
            if (without != null) {
                acc = without;
                out.log("VS per-seat power services (ZoneOn) left out (test switch)");
            }
        }
        if (extras && !wheelHeater) out.log("VS steering wheel heater left out (test switch)");
        byte[] versioned = withVersions(VsData.bytes(VsData.ACCESSORIES), acc);
        if (versioned != null) {
            acc = versioned;
            out.log("VS accessory versions: " + changedVersions);
        }
        accessories = new OpackCodec.Raw(acc);
    }

    /**
     * /info's vehicleStateProtocolInfo for this description, as LIVI's (data/vspi.bplist) is laid out:
     * {protocolVersion, pluginCount, pluginMapping {LIVI: 0}, pluginConfigs [{name, pluginID, accessories}]}.
     * The phone builds its car from these accessories and takes only values from the vehicle state
     * channel, so CpClimate writes this to files/cpc-vspi.bplist for the native /info hook.
     */
    byte[] vspi() {
        Map<String, Object> plugin = new LinkedHashMap<String, Object>();
        plugin.put("name", "LIVI");
        plugin.put("pluginID", 0L);
        plugin.put("accessories", OpackCodec.decode(accessories.b));
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("LIVI", 0L);
        Map<String, Object> top = new LinkedHashMap<String, Object>();
        top.put("protocolVersion", "1.0");
        top.put("pluginCount", 1L);
        top.put("pluginMapping", mapping);
        List<Object> configs = new ArrayList<Object>();
        configs.add(plugin);
        top.put("pluginConfigs", configs);
        return Bplist.encode(top);
    }

    /** what withVersions changed, for the log ("none" if nothing differs from LIVI's description) */
    static volatile String changedVersions = "none";

    /**
     * Every accessory cp-climate changes gets its own version. The phone keeps a car's accessories and
     * apparently replaces one only when its version changes: with LIVI's "1.0" everywhere it never took
     * the vehicle variant's exterior style and layout, the rear seats or the wheel (2026-11-10 logs: its
     * VehicleVariant held InteriorStyle only, and the Climate app failed with
     * variantIdentifierMissing(vehicleLayout), its car picture replaced by a placeholder). The version is
     * "1." plus a number from the accessory's services, so it changes whenever they do; unchanged
     * accessories keep LIVI's version. Null if nothing changed or the description can't be read.
     */
    @SuppressWarnings("unchecked")
    static byte[] withVersions(byte[] stock, byte[] now) {
        Object before = OpackCodec.decode(stock), after = OpackCodec.decode(now);
        if (!(before instanceof List) || !(after instanceof List)) return null;
        Map<Long, byte[]> stockServices = new HashMap<Long, byte[]>();
        for (Object a : (List<Object>) before) {
            if (!(a instanceof Map)) continue;
            Map<Object, Object> m = (Map<Object, Object>) a;
            stockServices.put(OpackCodec.asLong(m.get("iid")), OpackCodec.encode(m.get("services")));
        }
        StringBuilder changed = new StringBuilder();
        for (Object a : (List<Object>) after) {
            if (!(a instanceof Map)) continue;
            Map<Object, Object> m = (Map<Object, Object>) a;
            Long iid = OpackCodec.asLong(m.get("iid"));
            byte[] services = OpackCodec.encode(m.get("services"));
            byte[] was = stockServices.get(iid);
            if (was != null && java.util.Arrays.equals(was, services)) continue;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(services);
            String version = "1." + (1 + crc.getValue() % 9998);
            m.put("version", version);
            if (changed.length() > 0) changed.append(", ");
            changed.append(iid).append('=').append(version);
        }
        if (changed.length() == 0) return null;
        changedVersions = changed.toString();
        return OpackCodec.encode(after);
    }

    synchronized List<Out> handle(int lane, byte[] message) {
        List<Out> answers = new ArrayList<Out>();
        Object outer;
        try {
            outer = OpackCodec.decode(message);
        } catch (IllegalArgumentException e) {
            out.log("VS a " + message.length + " B message is not OPACK");
            return answers;
        }
        Long plugin = OpackCodec.asLong(get(outer, "pluginID"));
        Object request = null;
        if (get(outer, "pluginData") instanceof byte[]) {
            try {
                request = OpackCodec.decode((byte[]) get(outer, "pluginData"));
            } catch (IllegalArgumentException e) {
                // dropped below
            }
        }
        if (request == null || plugin == null || plugin != PLUGIN_ID) {
            out.log("VS message for plugin " + plugin + " without readable data, dropped");
            return answers;
        }
        Object transaction = get(request, "transactionID");
        Object given = get(request, "values");
        Object command = get(request, "command");
        if ("configRequest".equals(command)) {
            out.log("VS phone reads the vehicle description");
            answers.add(new Out(lane, wrap(dict("command", "configResponse", "transactionID", transaction,
                    "values", dict("accessories", accessories)))));
        } else if ("registerRequest".equals(command)) {
            List<Object> iids = list(given);
            answers.add(new Out(lane, wrap(follow(iids, transaction))));
            // the version line's logo (VERSION_LINE + 5) needs an image archive, which M3 has none of
            if (hasIid(iids, historySlot(0) + 1)) answers.addAll(historyUpdate());
            if (hasIid(iids, TEMPORARY_ZONE + 1)) out.log("VS phone is ready for notifications (not shown by M3)");
        } else if ("readRequest".equals(command)) {
            answers.add(new Out(lane, wrap(read(list(given), transaction))));
        } else if ("writeRequest".equals(command)) {
            answers.addAll(write(lane, map(given), transaction));
        } else if ("controlRequest".equals(command)) {
            answers.add(new Out(lane, wrap(control(map(given), transaction))));
        } else {
            out.log("VS phone sent " + (command == null ? "no command" : "\"" + command + "\"") + ", not handled");
        }
        return answers;
    }

    /**
     * Values the car reports (CarClimate), told to the phone. The car is right about its own
     * state, so LIVI's rules don't run: each value is simply set, as LIVI's car_sets does.
     */
    synchronized List<Out> fromCar(Map<Long, Object> reported) {
        Map<Object, Object> changed = new LinkedHashMap<Object, Object>();
        Map<Object, Object> blanked = new LinkedHashMap<Object, Object>();
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Object> e : reported.entrySet()) {
            Long since = heldSince.get(e.getKey());
            if (since != null) {
                if (now - since < HOLD_MS && !same(values.get(e.getKey()), e.getValue())) continue;
                heldSince.remove(e.getKey());
                // a cooling write the car now confirms: tell the phone (its echo was held back)
                if (echoLater.remove(e.getKey()) && same(values.get(e.getKey()), e.getValue()) && followed.contains(e.getKey()))
                    changed.put(e.getKey(), e.getValue());
            }
            if (e.getValue() == ClimateMap.BLANK) {
                carBlanks(e.getKey(), blanked);
                continue;
            }
            carSets(e.getKey(), e.getValue(), changed);
        }
        List<Out> answers = new ArrayList<Out>();
        if (!blanked.isEmpty()) {
            // the phone's "no value" for each, as a registerResponse gives it for a value the car doesn't have
            Map<Object, Object> m = dict("command", "updateNotify", "errors", blanked);
            if (!changed.isEmpty()) m.put("values", changed);
            answers.add(new Out(HIGH, wrap(m)));
            return answers;
        }
        Out notify = updateNotify(changed);
        if (notify != null) answers.add(notify);
        return answers;
    }

    /** the car has no value for iid (setting blank_when_off): it is dropped, and the phone told if it follows it */
    private void carBlanks(long iid, Map<Object, Object> blanked) {
        if (!values.containsKey(iid)) return;
        values.remove(iid);
        if (names.containsKey(iid)) out.log("VS car has no " + names.get(iid) + " (climate off)");
        if (followed.contains(iid)) blanked.put(iid, NO_VALUE);
    }

    private Map<Object, Object> follow(List<Object> iids, Object transaction) {
        Map<Object, Object> held = new LinkedHashMap<Object, Object>();
        Map<Object, Object> missing = new LinkedHashMap<Object, Object>();
        List<String> named = new ArrayList<String>();
        for (Object o : iids) {
            Long iid = OpackCodec.asLong(o);
            if (iid == null) continue;
            followed.add(iid);
            if (values.containsKey(iid)) held.put(iid, values.get(iid));
            else missing.put(iid, NO_VALUE);
            if (names.containsKey(iid)) named.add(names.get(iid));
        }
        out.log("VS phone follows " + iids.size() + " values (" + join(named) + "), " + held.size() + " of them set");
        Map<Object, Object> answer = dict("command", "registerResponse", "transactionID", transaction, "values", held);
        if (!missing.isEmpty()) answer.put("errors", missing);
        return answer;
    }

    private Map<Object, Object> read(List<Object> iids, Object transaction) {
        Map<Object, Object> held = new LinkedHashMap<Object, Object>();
        Map<Object, Object> missing = new LinkedHashMap<Object, Object>();
        List<String> named = new ArrayList<String>();
        for (Object o : iids) {
            Long iid = OpackCodec.asLong(o);
            if (iid == null) continue;
            if (names.containsKey(iid)) named.add(names.get(iid));
            if (values.containsKey(iid)) held.put(iid, values.get(iid));
            else missing.put(iid, NO_VALUE);
        }
        out.log("VS phone reads " + join(named));
        Map<Object, Object> answer = dict("command", "readResponse", "transactionID", transaction);
        if (!held.isEmpty()) answer.put("values", held);
        if (!missing.isEmpty()) answer.put("errors", missing);
        return answer;
    }

    private List<Out> write(int lane, Map<Object, Object> written, Object transaction) {
        Map<Object, Object> results = new LinkedHashMap<Object, Object>();
        Map<Object, Object> changed = new LinkedHashMap<Object, Object>();
        for (Map.Entry<Object, Object> e : written.entrySet()) {
            Long iid = OpackCodec.asLong(e.getKey());
            if (iid == null) continue;
            Object v = e.getValue();
            if (!writable.contains(iid)) {
                out.log("VS phone may not write value " + iid);
                results.put(iid, NO_VALUE);
                continue;
            }
            if (shared.contains(iid)) out.log("VS phone set " + names.get(iid) + " to " + shown(v));
            else out.log("VS phone wrote " + names.get(iid));
            results.put(iid, WRITTEN);
            // LIVI hides a notification history entry here; M3 keeps no history, so nothing to hide
            Object before = values.put(iid, v);
            if (car != null && coolingWrite(iid, before, v)) {
                echoLater.add(iid);
                continue;
            }
            if (!same(before, v) && followed.contains(iid)) changed.put(iid, v);
        }
        Car c = car;
        if (c == null) {
            settle(written, changed);
        } else {
            // a real car: it does what LIVI's rules do itself, and the reader shows the result
            Map<Long, Object> sent = new LinkedHashMap<Long, Object>();
            long now = System.currentTimeMillis();
            for (Map.Entry<Object, Object> e : written.entrySet()) {
                Long iid = OpackCodec.asLong(e.getKey());
                if (iid == null || !writable.contains(iid)) continue;
                sent.put(iid, e.getValue());
                heldSince.put(iid, now);
            }
            c.phoneWrote(sent, new TreeMap<Long, Object>(values));
        }
        List<Out> answers = new ArrayList<Out>();
        answers.add(new Out(lane, wrap(dict("command", "writeResponse", "transactionID", transaction, "errors", results))));
        Out notify = updateNotify(changed);
        if (notify != null) answers.add(notify);
        return answers;
    }

    private Map<Object, Object> control(Map<Object, Object> commands, Object transaction) {
        Map<Object, Object> answered = new LinkedHashMap<Object, Object>();
        Map<Object, Object> refused = new LinkedHashMap<Object, Object>();
        for (Map.Entry<Object, Object> e : commands.entrySet()) {
            Long iid = OpackCodec.asLong(e.getKey());
            String name = iid == null ? null : controls.get(iid);
            if (name == null) {
                out.log("VS phone sent command " + shown(e.getKey()) + ", the car has none such");
                refused.put(e.getKey(), NO_SUCH_COMMAND);
                continue;
            }
            // LIVI's radio controls; the car has no radio hooked up, so they are only logged
            out.log("VS phone pressed " + name + " " + shown(e.getValue()));
            answered.put(e.getKey(), null);
        }
        Map<Object, Object> answer = dict("command", "controlResponse", "transactionID", transaction);
        if (!answered.isEmpty()) answer.put("values", answered);
        if (!refused.isEmpty()) answer.put("errors", refused);
        return answer;
    }

    /** the empty notification history LIVI sends when the phone follows it */
    private List<Out> historyUpdate() {
        List<Out> answers = new ArrayList<Out>();
        for (int n = 0; n < HISTORY_SLOTS; n++) {
            long iid = historySlot(n);
            Object[] group = { iid + 1, "", iid + 2, "", iid + 3, 0L, iid + 4, "", iid + 5, 0L, iid + 6, true, iid + 7, true };
            Map<Object, Object> pairs = new LinkedHashMap<Object, Object>();
            for (int i = 0; i < group.length; i += 2) {
                values.put((Long) group[i], group[i + 1]);
                pairs.put(group[i], group[i + 1]);
            }
            answers.add(updateNotify(pairs));
        }
        return answers;
    }

    static long historySlot(int n) {
        return HISTORY + 1 + 8 * n;
    }

    // ---- LIVI rules.rs ----

    private void settle(Map<Object, Object> written, Map<Object, Object> changed) {
        keepZonesSynced(written, changed);
        switchByLevel(written, changed);
        followAuto(written, changed);
        runMaxAc(written, changed);
        muteByVolume(written, changed);
    }

    /** the value written for iid, or NONE */
    private static Object find(Map<Object, Object> written, long iid) {
        for (Map.Entry<Object, Object> e : written.entrySet()) {
            Long k = OpackCodec.asLong(e.getKey());
            if (k != null && k == iid) return e.getValue();
        }
        return NONE;
    }

    private static final Object NONE = new Object();

    private void muteByVolume(Map<Object, Object> written, Map<Object, Object> changed) {
        Long volume = OpackCodec.asLong(values.get(VOLUME));
        long level = volume == null ? 0 : volume;
        Object mute = find(written, MUTE), loud = find(written, VOLUME);
        if (Boolean.TRUE.equals(mute) && level > 0) {
            resume.put(VOLUME, level);
            carSets(VOLUME, 0L, changed);
        } else if (Boolean.FALSE.equals(mute) && level == 0) {
            Object back = resume.containsKey(VOLUME) ? resume.get(VOLUME) : (Object) 50L;
            carSets(VOLUME, back, changed);
        } else if (mute == NONE && loud instanceof Long && (Long) loud > 0) {
            carSets(MUTE, false, changed);
        }
    }

    private void keepZonesSynced(Map<Object, Object> written, Map<Object, Object> changed) {
        if (!Boolean.TRUE.equals(values.get(ZONES_SYNCED))) return;
        if (find(written, PASSENGER_TARGET) != NONE && find(written, ZONES_SYNCED) == NONE) {
            carSets(ZONES_SYNCED, false, changed);
        } else if (values.containsKey(DRIVER_TARGET)) {
            carSets(PASSENGER_TARGET, values.get(DRIVER_TARGET), changed);
        }
    }

    private void switchByLevel(Map<Object, Object> written, Map<Object, Object> changed) {
        for (long[] pair : LEVELLED) {
            long levelIid = pair[0], onIid = pair[1];
            Object level = find(written, levelIid), on = find(written, onIid);
            if (level instanceof Long) {
                carSets(onIid, (Long) level > 0, changed);
            } else if (on instanceof Boolean) {
                boolean isOn = (Boolean) on;
                boolean held = values.containsKey(levelIid);
                Object now = values.get(levelIid);
                boolean zero = held && Long.valueOf(0).equals(now);
                if (!isOn && !zero) {
                    if (held) resume.put(levelIid, now);
                    carSets(levelIid, 0L, changed);
                } else if (isOn && zero) {
                    Object back = resume.containsKey(levelIid) ? resume.get(levelIid) : (Object) 1L;
                    carSets(levelIid, back, changed);
                }
            }
        }
    }

    private void followAuto(Map<Object, Object> written, Map<Object, Object> changed) {
        Object auto = find(written, AUTO_CLIMATE);
        if (auto instanceof Boolean) {
            carSets(FAN_AUTO, auto, changed);
            for (long vent : VENTS) carSets(vent + 2, auto, changed);
            return;
        }
        boolean fanByHand = find(written, FAN_LEVEL) != NONE || find(written, FAN_ON) != NONE;
        boolean ventsByHand = false;
        for (long vent : VENTS) ventsByHand |= find(written, vent + 1) != NONE;
        if (fanByHand) carSets(FAN_AUTO, false, changed);
        if (ventsByHand) for (long vent : VENTS) carSets(vent + 2, false, changed);
        if (fanByHand || ventsByHand) carSets(AUTO_CLIMATE, false, changed);
    }

    private void runMaxAc(Map<Object, Object> written, Map<Object, Object> changed) {
        Object[][] takes = { { DRIVER_TARGET, TEMPERATURE_LO }, { PASSENGER_TARGET, TEMPERATURE_LO },
                { COMPRESSOR, true }, { RECIRCULATION, true }, { FAN_LEVEL, 7L }, { FAN_ON, true },
                { FAN_AUTO, false }, { AUTO_CLIMATE, false } };
        Object max = find(written, MAX_AC);
        boolean tookWritten = false;
        for (Object[] t : takes) tookWritten |= find(written, (Long) t[0]) != NONE;
        if (Boolean.TRUE.equals(max) && beforeMaxAc == null) {
            beforeMaxAc = new ArrayList<Object[]>();
            for (Object[] t : takes) if (values.containsKey(t[0])) beforeMaxAc.add(new Object[] { t[0], values.get(t[0]) });
            for (Object[] t : takes) carSets((Long) t[0], t[1], changed);
        } else if (Boolean.FALSE.equals(max)) {
            List<Object[]> before = beforeMaxAc;
            beforeMaxAc = null;
            if (before != null) for (Object[] t : before) carSets((Long) t[0], t[1], changed);
        } else if (beforeMaxAc != null && tookWritten) {
            beforeMaxAc = null;
            carSets(MAX_AC, false, changed);
        }
    }

    private void carSets(long iid, Object v, Map<Object, Object> changed) {
        if (values.containsKey(iid) && same(values.get(iid), v)) return;
        if (names.containsKey(iid)) out.log("VS car set " + names.get(iid) + " to " + shown(v));
        values.put(iid, v);
        if (followed.contains(iid)) changed.put(iid, v);
        Long notifier = notifiers.get(iid);
        if (notifier != null && names.containsKey(iid)) {
            Long count = OpackCodec.asLong(values.get(notifier));
            long next = ((count == null ? 0 : count) + 1) % 256;
            values.put(notifier, next);
            if (followed.contains(notifier)) changed.put(notifier, next);
        }
    }

    // ---- LIVI wire.rs ----

    private static Out updateNotify(Map<Object, Object> changed) {
        if (changed.isEmpty()) return null;
        return new Out(HIGH, wrap(dict("command", "updateNotify", "values", changed)));
    }

    static byte[] wrap(Object inner) {
        return OpackCodec.encode(dict("pluginID", PLUGIN_ID, "pluginData", OpackCodec.encode(inner)));
    }

    static String shown(Object v) {
        if (v instanceof byte[]) return ((byte[]) v).length + " B";
        if (v instanceof List && ((List<?>) v).size() > 4) return ((List<?>) v).size() + " items";
        if (v == null) return "null";
        if (v instanceof Map || v instanceof List) return Opack.show(OpackCodec.encode(v));
        return String.valueOf(v);
    }

    // ---- small helpers ----

    static Map<Object, Object> dict(Object... kv) {
        Map<Object, Object> m = new LinkedHashMap<Object, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static Object get(Object dict, String key) {
        return dict instanceof Map ? ((Map<?, ?>) dict).get(key) : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> map(Object v) {
        return v instanceof Map ? (Map<Object, Object>) v : new LinkedHashMap<Object, Object>();
    }

    static boolean hasIid(List<Object> iids, long iid) {
        for (Object o : iids) {
            Long n = OpackCodec.asLong(o);
            if (n != null && n == iid) return true;
        }
        return false;
    }

    /** OPACK value equality, as LIVI's Value compares (data by content) */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            List<?> x = new ArrayList<Object>(((Map<?, ?>) a).entrySet()), y = new ArrayList<Object>(((Map<?, ?>) b).entrySet());
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                Map.Entry<?, ?> p = (Map.Entry<?, ?>) x.get(i), q = (Map.Entry<?, ?>) y.get(i);
                if (!same(p.getKey(), q.getKey()) || !same(p.getValue(), q.getValue())) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    static String join(List<String> parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) s.append(s.length() == 0 ? "" : " ").append(p);
        return s.toString();
    }
}
