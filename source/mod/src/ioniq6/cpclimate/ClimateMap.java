package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Ioniq 6's climate as CarPlay's vehicle state values (M4, read side). The inputs are what
 * CarClimate reads: DATCService's voice-control status flags, its temperature callback and
 * USMService's seat heat states. Values that weren't read (NaN, -1) are left out, so the phone
 * keeps what it had. Decoded from the car on 2026-11-07 (physical buttons pressed while the
 * flags were logged):
 *
 *   flag 12 on/off 1/2, 8 AUTO 0/1, 9 A/C 0/1, 10 front defrost 0/1, 11 intake 1 recirculate
 *   2 fresh, 5 fan raw 2-9 = the panel's 1-8 (0 off), 6 vent mode 1 face, 2 face+feet, 3 feet,
 *   4 feet+windshield, 0 under AUTO, 10 under defrost; 2 driver set temperature in the car's
 *   unit (getTemperatureUnit 1 °C, 2 °F; only °F seen so far); 22 SYNC
 *   1 on; 29 passenger raw code ((°C - 14) * 2; 0 while driver only is on).
 *   getDriverOnlyValue: 1 driver only (the passenger zone off), 0 off. CarPlay's passenger zone
 *   on/off is the car's Driver Only, written with setFrontClimateControl(24, 1 on / 0 off).
 *   The car keeps °F and °C setpoints on their own scales (73 °F is its 22.5 °C, which the
 *   phone would show as 72 °F), so the °F values are used and rounded to the half degree the
 *   phone turns back into the same °F. Seat heat state 2 off, 6/7/8 low/mid/high (as the Ioniq 5 logged; 3 levels here too).
 */
final class ClimateMap {
    static final long HVAC_ON = 514, COMPRESSOR = 515, MAX_DEFROST = 517, FAN_ON = 524, FAN_LEVEL = 525;
    static final long FAN_AUTO = 526, RECIRCULATION = 528, AUTO_CLIMATE = 530, ZONES_SYNCED = 538;
    static final long DRIVER_ON = 519, DRIVER_TARGET = 520, PASSENGER_ON = 533, PASSENGER_TARGET = 534;
    /** Apple's per-seat power (CAFZoneOn, added in data/vsdump-src climate.rs): the Climate app's seat power buttons */
    static final long DRIVER_ZONE_ON = 564, PASSENGER_ZONE_ON = 567;
    /** CarPlay's window heating levels (0/1); the Ioniq 6 has no heated windshield, so the front one is its defrost */
    static final long FRONT_WINDOW_HEATING = 541, REAR_WINDOW_HEATING = 545;
    static final long UPPER_ON = 549, UPPER_AUTO = 550, LOWER_ON = 559, LOWER_AUTO = 560;
    static final long DRIVER_SEAT_LEVEL = 1026, DRIVER_SEAT_ON = 1027, PASSENGER_SEAT_LEVEL = 1030, PASSENGER_SEAT_ON = 1031;
    /** CarPlay's range: its ends show as LO and HI, as the car's do below 62 °F and above 90 °F */
    static final double LO = 16.0, HI = 32.5;
    static final int TEMP_NORMAL = 0xffff;

    /** one reading of the car */
    static final class Reading {
        float on = Float.NaN, auto = Float.NaN, ac = Float.NaN, defrost = Float.NaN, intake = Float.NaN;
        float fan = Float.NaN, mode = Float.NaN, driverF = Float.NaN, passengerRaw = Float.NaN, sync = Float.NaN;
        /** from the temperature callback: °C, °F and the display state, or NaN before the first one */
        float driverC = Float.NaN, passengerC = Float.NaN, passengerF = Float.NaN;
        int driverState = -1, passengerState = -1;
        int driverSeat = -1, passengerSeat = -1;
        /** the rear seats' heat states (the same numbers) and the steering wheel heater's indicator (1 on, 0 off); -1 not read */
        int rearLeftSeat = -1, rearRightSeat = -1, wheel = -1;
        /** getDriverOnlyValue: 1 driver only, 0 off, -1 not read */
        int driverOnly = -1;
        /** the car's temperature unit: 1 °C, 2 °F, -1 not read */
        int unit = -1;
        /** BCMService's rear defroster (mDefoggerState): 1 on, 0 off, -1 not known (or the setting is off) */
        int rearDefog = -1;
    }

    static final int CELSIUS = 1;

    static Map<Long, Object> toCarPlay(Reading r) {
        Map<Long, Object> v = new LinkedHashMap<Long, Object>();
        if (known(r.on)) {
            boolean on = r.on == 1;
            v.put(HVAC_ON, on);
            v.put(DRIVER_ON, on);
            v.put(DRIVER_ZONE_ON, on);
            if (known(r.fan)) {
                long level = on ? fanLevel(r.fan) : 0;
                v.put(FAN_LEVEL, level);
                v.put(FAN_ON, level > 0);
            }
        }
        if (known(r.auto)) {
            v.put(AUTO_CLIMATE, r.auto == 1);
            v.put(FAN_AUTO, r.auto == 1);
        }
        if (known(r.ac)) v.put(COMPRESSOR, r.ac == 1);
        if (known(r.defrost)) {
            v.put(MAX_DEFROST, r.defrost == 1);
            v.put(FRONT_WINDOW_HEATING, r.defrost == 1 ? 1L : 0L);
        }
        if (r.rearDefog >= 0) v.put(REAR_WINDOW_HEATING, r.rearDefog == 1 ? 1L : 0L);
        if (known(r.intake)) v.put(RECIRCULATION, r.intake == 1);
        if (known(r.mode)) vents(v, (int) r.mode);
        boolean celsius = r.unit == CELSIUS;
        // with the climate off the car reports 17.0 (its LO) and display state 0xff instead of its setpoint
        // (2026-11-10: 21.5 while on, 17.0/ff once off), so the last setpoint seen while on is shown instead
        boolean off = known(r.on) && r.on != 1;
        Double driver = off ? lastDriver
                : known(r.driverF) ? Double.valueOf(celsius ? temperature(r.driverF) : fahrenheit(r.driverF))
                : r.driverState == TEMP_NORMAL && known(r.driverC) ? Double.valueOf(temperature(r.driverC)) : null;
        if (!off && driver != null) remember(driver, lastPassenger);
        if (driver != null) v.put(DRIVER_TARGET, driver);
        if (rearZone()) {
            // the rear zone (read-only): the car has no rear temperature, so it shows the driver's
            if (driver != null) v.put(REAR_TARGET, driver);
            if (known(r.on)) v.put(REAR_ON, r.on == 1);
            if (VehicleState.twoRear) {
                if (driver != null) v.put(REAR2_TARGET, driver);
                if (known(r.on)) v.put(REAR2_ON, r.on == 1);
            }
        }
        if (known(r.sync) && known(r.passengerRaw)) {
            boolean synced = r.sync == 1;
            boolean zone = r.driverOnly >= 0 ? r.driverOnly == 0 : synced || r.passengerRaw != 0;
            v.put(ZONES_SYNCED, synced);
            if (known(r.on)) {
                v.put(PASSENGER_ON, r.on == 1 && zone);
                v.put(PASSENGER_ZONE_ON, r.on == 1 && zone);
            }
            Double passenger = synced ? driver
                    : off ? (lastPassenger != null ? lastPassenger : driver)
                    : !zone ? null
                    : r.passengerState == TEMP_NORMAL && celsius && known(r.passengerC) ? Double.valueOf(temperature(r.passengerC))
                    : r.passengerState == TEMP_NORMAL && !celsius && known(r.passengerF) ? Double.valueOf(fahrenheit(r.passengerF))
                    : Double.valueOf(temperature(r.passengerRaw / 2.0 + 14));
            if (!off && zone && !synced && passenger != null) remember(lastDriver, passenger);
            if (passenger != null) v.put(PASSENGER_TARGET, passenger);
        }
        if (off && blankWhenOff) blankTargets(v);
        seat(v, r.driverSeat, DRIVER_SEAT_LEVEL, DRIVER_SEAT_ON);
        seat(v, r.passengerSeat, PASSENGER_SEAT_LEVEL, PASSENGER_SEAT_ON);
        if (extras) {
            if (!VehicleState.coolingLayout) {
                ventSeat(v, r.driverSeat, DRIVER_VENT_LEVEL, DRIVER_VENT_ON);
                ventSeat(v, r.passengerSeat, PASSENGER_VENT_LEVEL, PASSENGER_VENT_ON);
            }
            if (rowRear()) {
                // layout C: one control for the row; it shows the warmer of the two seats
                rearSeat(v, warmer(r.rearLeftSeat, r.rearRightSeat), REAR_LEFT_LEVEL, REAR_LEFT_ON);
            } else {
                rearSeat(v, r.rearLeftSeat, REAR_LEFT_LEVEL, REAR_LEFT_ON);
                rearSeat(v, r.rearRightSeat, REAR_RIGHT_LEVEL, REAR_RIGHT_ON);
            }
            if (r.wheel >= 0) v.put(WHEEL_LEVEL, r.wheel > 0 ? 1L : 0L);
        }
        return v;
    }

    /** a front seat's ventilation: the car's state 3 / 4 / 5 are levels 1 / 2 / 3 (heating states and off are 0) */
    static void ventSeat(Map<Long, Object> v, int state, long levelIid, long onIid) {
        long level;
        switch (state) {
            case 2: case 6: case 7: case 8: level = 0; break;
            case 3: level = 1; break;
            case 4: level = 2; break;
            case 5: level = 3; break;
            default: return;
        }
        v.put(levelIid, level);
        v.put(onIid, level > 0);
    }

    /** a rear seat's heat: the car's state 2 off, 6 low, 8 high are levels 0 / 1 / 2 */
    static void rearSeat(Map<Long, Object> v, int state, long levelIid, long onIid) {
        long level;
        switch (state) {
            case 2: level = 0; break;
            case 6: level = 1; break;
            case 7: case 8: level = 2; break;
            default: return;
        }
        v.put(levelIid, level);
        v.put(onIid, level > 0);
    }

    /** the panel's fan step for a raw blower value (raw 1 shows as 1, as under AUTO) */
    static long fanLevel(float raw) {
        return raw <= 0 ? 0 : Math.max(1, (long) raw - 1);
    }

    /** the car's vent mode as CarPlay's two vents; under AUTO they follow it */
    static void vents(Map<Long, Object> v, int mode) {
        boolean upper, lower;
        switch (mode) {
            case 0:
                v.put(UPPER_AUTO, true);
                v.put(LOWER_AUTO, true);
                return;
            case 1: upper = true; lower = false; break;
            case 2: upper = true; lower = true; break;
            case 3:
            case 4: upper = false; lower = true; break;
            case 10: upper = false; lower = false; break;
            default: return;
        }
        v.put(UPPER_ON, upper);
        v.put(LOWER_ON, lower);
        v.put(UPPER_AUTO, false);
        v.put(LOWER_AUTO, false);
    }

    static void seat(Map<Long, Object> v, int state, long levelIid, long onIid) {
        long level;
        switch (state) {
            case 2: level = 0; break;
            case 6: level = 1; break;
            case 7: level = 2; break;
            case 8: level = 3; break;
            // the seat is ventilating: layout B shows it as cooling (-1..-3), otherwise as heat off
            case 3: case 4: case 5: level = cooling() ? coolingLevel(state) : 0; break;
            default: return;
        }
        v.put(levelIid, level);
        v.put(onIid, level != 0);
    }

    /**
     * The car's three ventilation steps (USMService states 3 / 4 / 5 = low / medium / high) and CarPlay's cooling
     * levels. With the range published from -3 (setting cooling_min -3) they are -1 / -2 / -3, but Climate lit one
     * dot too many. From -4 Climate draws a four-step bar: high is -4 (full), medium -2, low -1. The phone steps a
     * tap at a time from -4 up, so its -3 is medium too (shown as -2 once the car confirms); every tap then changes
     * the car (2026-11-11: with high reported as -3 the bar dropped to 3/4 after a tap to full).
     */
    static long coolingLevel(int state) {
        if (VehicleState.coolingMin <= -4) return state == 5 ? -4 : state == 4 ? -2 : -1;
        return -(state - 2);
    }

    /** a CarPlay cooling level (negative) as the car's ventilation state */
    static int coolingState(long level) {
        if (VehicleState.coolingMin <= -4) return level <= -4 ? 5 : level <= -2 ? 4 : 3;
        return (int) Math.min(3, -level) + 2;
    }

    /** layout B of the extra seat controls: the front seats' level also carries ventilation, as negative steps */
    static boolean cooling() {
        return extras && VehicleState.coolingLayout;
    }

    /** the rear Temperature zone (setting rear_zone), with layout B or C: a zone card for the rear seats' heat */
    static final long REAR_TEMP = 576, REAR_ON = 577, REAR_TARGET = 578, REAR_CURRENT = 579, REAR_TEMP_KEY = 580;
    /** layout D's second rear zone (rear right; the first, 576, is rear left) */
    static final long REAR2_TEMP = 581, REAR2_ON = 582, REAR2_TARGET = 583;

    static boolean rearZone() {
        return cooling() && VehicleState.rearZone;
    }

    /** layout C of the extra seat controls: the rear seats' heat as one control for the row */
    static boolean rowRear() {
        return cooling() && VehicleState.rowRear;
    }

    /** of two rear seat heat states (2 off, 6 low, 7/8 high, -1 not read), the warmer one */
    static int warmer(int a, int b) {
        int ra = a == 6 ? 1 : a == 7 || a == 8 ? 2 : a == 2 ? 0 : -1;
        int rb = b == 6 ? 1 : b == 7 || b == 8 ? 2 : b == 2 ? 0 : -1;
        return ra >= rb ? a : b;
    }

    /** a °F setpoint as the half degree °C the phone shows as the same °F */
    static double fahrenheit(double f) {
        return temperature((f - 32) / 1.8);
    }

    /** °C on CarPlay's half-degree steps, LO and HI at the ends */
    static double temperature(double c) {
        double half = Math.round(c * 2) / 2.0;
        return half <= LO ? LO : half >= HI ? HI : half;
    }

    /**
     * setting blank_when_off: with the climate off the zones' temperatures are reported as BLANK, which VehicleState
     * turns into Apple's "no value" (-6727) for the phone, instead of the last setpoint
     */
    static volatile boolean blankWhenOff;
    static final Object BLANK = new Object() {
        @Override
        public String toString() {
            return "blank";
        }
    };

    /** the zones' target temperatures as BLANK (climate off, setting blank_when_off) */
    static void blankTargets(Map<Long, Object> v) {
        v.put(DRIVER_TARGET, BLANK);
        v.put(PASSENGER_TARGET, BLANK);
        if (rearZone()) {
            v.put(REAR_TARGET, BLANK);
            if (VehicleState.twoRear) v.put(REAR2_TARGET, BLANK);
        }
    }

    /** the last setpoints seen with the climate on (°C as shown), kept in setpointFile across restarts */
    static volatile Double lastDriver, lastPassenger;
    /** set by CpClimate: files/cpc-setpoints ("driver passenger"); null keeps them in memory only */
    static volatile java.io.File setpointFile;

    static synchronized void remember(Double driver, Double passenger) {
        if (eq(driver, lastDriver) && eq(passenger, lastPassenger)) return;
        lastDriver = driver;
        lastPassenger = passenger;
        java.io.File f = setpointFile;
        if (f == null) return;
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            try {
                o.write((driver + " " + passenger).getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable t) {
            // only a convenience: the next start shows nothing until the climate is on
        }
    }

    /** reads setpointFile (once, at start) */
    static synchronized void loadSetpoints(java.io.File f) {
        setpointFile = f;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[64];
            int n;
            try {
                n = in.read(b);
            } finally {
                in.close();
            }
            String[] p = new String(b, 0, Math.max(0, n), "UTF-8").trim().split(" ");
            lastDriver = parse(p[0]);
            lastPassenger = p.length > 1 ? parse(p[1]) : null;
        } catch (Throwable t) {
            // none yet
        }
    }

    static Double parse(String s) {
        try {
            double d = Double.parseDouble(s);
            return d >= LO && d <= HI ? Double.valueOf(d) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean eq(Double a, Double b) {
        return a == null ? b == null : a.equals(b);
    }

    static boolean known(float f) {
        return !Float.isNaN(f) && f >= 0;
    }

    // ---- write side: what the phone wrote, as the car's own commands ----

    /**
     * a command: DATCService setVRClimateStatus(a, b, c), setFrontClimateControl(a, b), USMService a(b), or
     * the rear defroster on (a 1) or off (a 0), which CarClimate sends through VRService
     */
    static final int VR = 0, FRONT = 1, SEAT = 2, REAR = 3;
    /** the ambient light test: a red, green and blue (0-255) for USMService's setDualLight_Bottom* calls */
    static final int AMBIENT = 4;
    /** the steering wheel heater: a is 1 to have it on, 0 off; the car's call is a toggle, so CarClimate presses it only if the indicator differs */
    static final int WHEEL = 5;
    /** rear seat heat setters (like SET_DRIVER_SEAT: ids for CarClimate to turn into USMService's calls) */
    static final int SET_REAR_LEFT = 0xd7, SET_REAR_RIGHT = 0xd8;

    /** the extra seat and wheel controls (setting extra_seats): VehicleState.withExtras adds these values to the description */
    static volatile boolean extras;
    static final long REAR_LEFT_LEVEL = 1034, REAR_LEFT_ON = 1035, REAR_RIGHT_LEVEL = 1038, REAR_RIGHT_ON = 1039;
    static final long DRIVER_VENT_LEVEL = 1042, DRIVER_VENT_ON = 1043, PASSENGER_VENT_LEVEL = 1046, PASSENGER_VENT_ON = 1047;
    static final long WHEEL_LEVEL = 572;
    static final int SET_DRIVER_SEAT = 0xd5, SET_PASSENGER_SEAT = 0xd6;
    static final int FRONT_DRIVER_ONLY = 24;
    static final int FAHRENHEIT = 2, FRONT_DRIVER_C = 28, FRONT_PASSENGER_C = 29, FRONT_PASSENGER_F = 31;
    static final int MIN_F = 62, MAX_F = 90;

    static final class Command {
        final int kind, a, b, c;

        Command(int kind, int a, int b, int c) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.c = c;
        }

        @Override
        public String toString() {
            if (kind == REAR) return "rear(" + a + ")";
            if (kind == AMBIENT) return "ambient(" + a + ", " + b + ", " + c + ")";
            if (kind == WHEEL) return "wheel(" + a + ")";
            return (kind == VR ? "vr" : kind == FRONT ? "front" : "seat") + "(" + a + ", " + b + (kind == VR ? ", " + c : "") + ")";
        }
    }

    /**
     * The commands for what the phone wrote (iid -> value), given the values after the write
     * (now), in the car's temperature unit (unit: 1 °C, otherwise °F). Rear and front window heating, MAX A/C off and anything else the car has no command
     * for are left out: the reader puts the car's own state back on the phone.
     */
    static List<Command> commands(Map<Long, Object> written, Map<Long, Object> now, int unit) {
        boolean celsius = unit == CELSIUS;
        List<Command> out = new ArrayList<Command>();
        boolean vents = false;
        for (Map.Entry<Long, Object> e : written.entrySet()) {
            long iid = e.getKey();
            Object v = e.getValue();
            if ((iid == HVAC_ON || iid == DRIVER_ZONE_ON) && v instanceof Boolean) {
                // the driver's seat power is the whole system's: the car can't run the passenger zone alone
                out.add(fan((Boolean) v ? Math.max(1, number(now.get(FAN_LEVEL), 1)) : 0));
            } else if (iid == FAN_ON && v instanceof Boolean) {
                out.add(fan((Boolean) v ? Math.max(1, number(now.get(FAN_LEVEL), 1)) : 0));
            } else if (iid == FAN_LEVEL && v instanceof Long) {
                out.add(fan((Long) v));
            } else if ((iid == AUTO_CLIMATE || iid == FAN_AUTO) && v instanceof Boolean) {
                out.add(new Command(VR, 8, (Boolean) v ? 1 : 0, 0));
            } else if (iid == COMPRESSOR && v instanceof Boolean) {
                out.add(new Command(VR, 9, (Boolean) v ? 1 : 0, 0));
            } else if (iid == MAX_DEFROST && v instanceof Boolean) {
                out.add(new Command(VR, 10, (Boolean) v ? 1 : 0, 0));
            } else if (iid == FRONT_WINDOW_HEATING && v instanceof Long) {
                // no heated windshield on this car: the front window button is its front defrost
                out.add(new Command(VR, 10, (Long) v > 0 ? 1 : 0, 0));
            } else if (iid == REAR_WINDOW_HEATING && v instanceof Long) {
                out.add(new Command(REAR, (Long) v > 0 ? 1 : 0, 0, 0));
            } else if (iid == RECIRCULATION && v instanceof Boolean) {
                out.add(new Command(VR, 11, (Boolean) v ? 1 : 2, 0));
            } else if ((iid == PASSENGER_ON || iid == PASSENGER_ZONE_ON) && v instanceof Boolean) {
                // the passenger zone off is the car's Driver Only; turning it on while the system is off
                // starts the system first (unless the phone turned hvacOn on in the same write)
                if ((Boolean) v && !Boolean.TRUE.equals(now.get(HVAC_ON)) && !Boolean.TRUE.equals(now.get(DRIVER_ZONE_ON)))
                    out.add(fan(Math.max(1, number(now.get(FAN_LEVEL), 1))));
                out.add(new Command(FRONT, FRONT_DRIVER_ONLY, (Boolean) v ? 0 : 1, 0));
            } else if (iid == ZONES_SYNCED && v instanceof Boolean) {
                out.add(new Command(VR, 22, (Boolean) v ? 2 : 1, 0));
            } else if (iid == MAX_AC && Boolean.TRUE.equals(v)) {
                // the car has no MAX A/C command: A/C, recirculation and the top fan step, as LIVI's rule does
                out.add(new Command(VR, 9, 1, 0));
                out.add(new Command(VR, 11, 1, 0));
                out.add(fan(8));
            } else if (iid == DRIVER_TARGET && v instanceof Double) {
                // °C: the stock screen's call (setVRClimateStatus only takes whole degrees); not yet tried on a car
                out.add(celsius ? new Command(FRONT, FRONT_DRIVER_C, celsiusRaw((Double) v), 0)
                        : new Command(VR, 2, toFahrenheit((Double) v), FAHRENHEIT));
            } else if (iid == PASSENGER_TARGET && v instanceof Double) {
                // setVRClimateStatus's passenger path names a signal the car doesn't have; the stock screen's call
                out.add(celsius ? new Command(FRONT, FRONT_PASSENGER_C, celsiusRaw((Double) v), 0)
                        : new Command(FRONT, FRONT_PASSENGER_F, toFahrenheit((Double) v) - 56, 0));
            } else if (iid == UPPER_ON || iid == LOWER_ON) {
                vents = true;
            } else if (iid == DRIVER_SEAT_LEVEL || iid == DRIVER_SEAT_ON) {
                Command c = seat(SET_DRIVER_SEAT, iid == DRIVER_SEAT_ON, v, now.get(DRIVER_SEAT_LEVEL));
                if (c != null) out.add(c);
            } else if (iid == PASSENGER_SEAT_LEVEL || iid == PASSENGER_SEAT_ON) {
                Command c = seat(SET_PASSENGER_SEAT, iid == PASSENGER_SEAT_ON, v, now.get(PASSENGER_SEAT_LEVEL));
                if (c != null) out.add(c);
            } else if (extras && (iid == DRIVER_VENT_LEVEL || iid == DRIVER_VENT_ON)) {
                Command c = ventSeat(SET_DRIVER_SEAT, iid == DRIVER_VENT_ON, v, now.get(DRIVER_VENT_LEVEL), now.get(DRIVER_SEAT_LEVEL));
                if (c != null) out.add(c);
            } else if (extras && (iid == PASSENGER_VENT_LEVEL || iid == PASSENGER_VENT_ON)) {
                Command c = ventSeat(SET_PASSENGER_SEAT, iid == PASSENGER_VENT_ON, v, now.get(PASSENGER_VENT_LEVEL), now.get(PASSENGER_SEAT_LEVEL));
                if (c != null) out.add(c);
            } else if (extras && (iid == REAR_LEFT_LEVEL || iid == REAR_LEFT_ON)) {
                Command c = rearSeat(SET_REAR_LEFT, iid == REAR_LEFT_ON, v, now.get(REAR_LEFT_LEVEL));
                if (c != null) out.add(c);
                // layout C: the row's control sets both rear seats
                if (c != null && rowRear()) out.add(new Command(SEAT, SET_REAR_RIGHT, c.b, 0));
            } else if (extras && (iid == REAR_RIGHT_LEVEL || iid == REAR_RIGHT_ON)) {
                Command c = rearSeat(SET_REAR_RIGHT, iid == REAR_RIGHT_ON, v, now.get(REAR_RIGHT_LEVEL));
                if (c != null) out.add(c);
            } else if (extras && iid == WHEEL_LEVEL && v instanceof Long) {
                out.add(new Command(WHEEL, (Long) v > 0 ? 1 : 0, 0, 0));
            }
        }
        if (vents) {
            boolean upper = Boolean.TRUE.equals(now.get(UPPER_ON)), lower = Boolean.TRUE.equals(now.get(LOWER_ON));
            if (upper || lower) out.add(new Command(VR, 6, upper && lower ? 2 : upper ? 1 : 3, 0));
        }
        return out;
    }

    static final long MAX_AC = 516;

    /** the panel's fan step 1-8 as the raw blower value (step + 1); 0 turns the climate off */
    static Command fan(long step) {
        long s = Math.max(0, Math.min(8, step));
        return new Command(VR, 5, s == 0 ? 0 : (int) s + 1, 0);
    }

    /**
     * seat heat level 0-3 (or on/off, which turns on at the last level or low) as USMService's state; in layout B
     * also -1..-3, the ventilation steps (USMService 3 / 4 / 5)
     */
    static Command seat(int code, boolean onOff, Object v, Object level) {
        long l;
        boolean cooling = cooling();
        if (onOff) {
            if (!(v instanceof Boolean)) return null;
            long last = number(level, 1);
            l = (Boolean) v ? (last != 0 && (cooling || last > 0) ? last : 1) : 0;
        } else {
            if (!(v instanceof Long)) return null;
            l = (Long) v;
        }
        l = Math.max(cooling ? -4 : 0, Math.min(3, l));
        if (l < 0) return new Command(SEAT, code, coolingState(l), 0);
        return new Command(SEAT, code, l == 0 ? 2 : (int) l + 5, 0);
    }

    /** rear seat heat level 0-2 (or on/off: on = low) as USMService's state: 2 off, 6 low, 8 high */
    static Command rearSeat(int code, boolean onOff, Object v, Object level) {
        long l;
        if (onOff) {
            if (!(v instanceof Boolean)) return null;
            l = (Boolean) v ? Math.max(1, number(level, 1)) : 0;
        } else {
            if (!(v instanceof Long)) return null;
            l = Math.max(0, Math.min(2, (Long) v));
        }
        return new Command(SEAT, code, l == 0 ? 2 : l == 1 ? 6 : 8, 0);
    }

    /**
     * front seat ventilation level 0-3 (or on/off: on = low) as USMService's state: 3 low, 4 medium, 5 high. Level 0 is
     * the car's off (2) only if the seat isn't heating, so the phone's idle zero doesn't switch heating off.
     */
    static Command ventSeat(int code, boolean onOff, Object v, Object level, Object heating) {
        long l;
        if (onOff) {
            if (!(v instanceof Boolean)) return null;
            l = (Boolean) v ? Math.max(1, number(level, 1)) : 0;
        } else {
            if (!(v instanceof Long)) return null;
            l = Math.max(0, Math.min(3, (Long) v));
        }
        if (l == 0) return number(heating, 0) > 0 ? null : new Command(SEAT, code, 2, 0);
        return new Command(SEAT, code, (int) l + 2, 0);
    }

    /** a CarPlay °C setpoint as the car's whole °F, inside its 62-90 range */
    static int toFahrenheit(double c) {
        return (int) Math.max(MIN_F, Math.min(MAX_F, Math.round(c * 1.8 + 32)));
    }

    /** a CarPlay °C setpoint as DATCService's raw °C ((°C - 14) * 2), inside 17-32 °C */
    static int celsiusRaw(double c) {
        return (int) Math.max(6, Math.min(36, Math.round(c * 2) - 28));
    }

    static long number(Object v, long otherwise) {
        return v instanceof Long ? (Long) v : otherwise;
    }
}
