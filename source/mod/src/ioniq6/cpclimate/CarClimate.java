package ioniq6.cpclimate;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * M4: the car's climate on CarPlay, both ways. Once a second (while the phone is on a vehicle
 * state stream) it reads DATCService's voice-control status flags and USMService's seat heat
 * states, adds the temperatures DATCService's callback reports, and hands the result
 * (ClimateMap) to the session's VehicleState, which tells the phone what changed. What the phone
 * writes comes back as phoneWrote and goes to the car, in order, on a thread of its own, as
 * the commands ClimateMap.commands chooses (the ones tested on the car on 2026-11-07).
 */
final class CarClimate extends Thread implements VehicleState.Car {
    static final String DATC = "com.hkmc.micom.datc", DATC_IFACE = "com.hkmc.datc.IDATCService";
    static final String LISTENER_IFACE = "com.hkmc.datc.IDATCListener";
    static final String USM = "com.hkmc.micom.usm", USM_IFACE = "com.hkmc.usm.IUSMService";
    /** DATCService listener call (valid because its fingerprint is checked before registering) */
    static final int ON_TEMPERATURES = 0x1;
    static final int F_FAN = 5, F_MODE = 6, F_AUTO = 8, F_AC = 9, F_DEFROST = 10, F_INTAKE = 11, F_ON = 12;
    static final int F_DRIVER_F = 2, F_PASSENGER_RAW = 29, F_SYNC = 22;
    static final long PERIOD_MS = 1000;
    /** the rear defroster: its state from BCMService's listener, set through VRService (1 on, 0 off; READY only) */
    static final String BCM = "com.hkmc.micom.bcm", BCM_IFACE = "com.hkmc.bcm.IBCMService";
    static final String BCM_LISTENER_IFACE = "com.hkmc.bcm.IBCMListener";
    static final String VR_SERVICE = "com.hkmc.vr", VR_IFACE = "com.hkmc.vr.IVRService";
    /** BCMService listener calls (valid because its fingerprint is checked before registering) */
    static final int BCM_LAST_CALL = 0x37;
    static final int ON_TELE_HEATING = 0x14, ON_DIGITAL_KEY_DATA = 0x25, ON_MODEM_INFO = 0x27;
    /** the rear_defrost setting (CpClimate) */
    static volatile boolean rearDefrost;

    /** the car's calls, looked up by name and signature in its framework (CarApi); -1: not on this firmware */
    static final int txRegisterDatc = CarApi.code(CarApi.DATC, "registerDATCListener", "(Lcom/hkmc/datc/IDATCListener;Landroid/os/IBinder;)V");
    static final int txUnit = CarApi.code(CarApi.DATC, "getTemperatureUnit", "()I");
    static final int txDriverOnly = CarApi.code(CarApi.DATC, "getDriverOnlyValue", "()I");
    static final int txGetStatus = CarApi.code(CarApi.DATC, "getVRClimateStatus", "(I)F");
    static final int txSetStatus = CarApi.code(CarApi.DATC, "setVRClimateStatus", "(III)V");
    static final int txSetFront = CarApi.code(CarApi.DATC, "setFrontClimateControl", "(II)V");
    static final int txDriverSeat = CarApi.code(CarApi.USM, "getFrontSeatHeat_DrvState", "()I");
    static final int txPassengerSeat = CarApi.code(CarApi.USM, "getFrontSeatHeat_AstState", "()I");
    static final int txSetDriverSeat = CarApi.code(CarApi.USM, "setFrontSeatHeatDrvStep", "(I)V");
    static final int txSetPassengerSeat = CarApi.code(CarApi.USM, "setFrontSeatHeatAstStep", "(I)V");
    static final int txRear = CarApi.code(CarApi.VR, "set_RrDefogger_Cmd_AVN", "(I)V");
    static final int txRegisterBcm = CarApi.code(CarApi.BCM, "registerBCMListener", "(Lcom/hkmc/bcm/IBCMListener;Landroid/os/IBinder;)V");
    /** the ambient light test: the mood light's red, green and blue (0-255) on USMService, set and read */
    static final int txAmbRed = CarApi.code(CarApi.USM, "setDualLight_BottomRed", "(I)V");
    static final int txAmbGreen = CarApi.code(CarApi.USM, "setDualLight_BottomGreen", "(I)V");
    static final int txAmbBlue = CarApi.code(CarApi.USM, "setDualLight_BottomBlue", "(I)V");
    static final int txGetAmbRed = CarApi.code(CarApi.USM, "getDualLight_BottomRed", "()I");
    static final int txGetAmbGreen = CarApi.code(CarApi.USM, "getDualLight_BottomGreen", "()I");
    static final int txGetAmbBlue = CarApi.code(CarApi.USM, "getDualLight_BottomBlue", "()I");
    /** the ambient_test setting (CpClimate) */
    static volatile boolean ambientTest;
    /**
     * the extra seat and wheel controls (setting extra_seats): the rear seats' heat state and setters (2 off, 6 low,
     * 8 high; the front seats' ventilation is states 3-5 of the front seats' own calls) and the steering wheel
     * heater: its two indicator steps (both 1 while on, both 0 off) and a setter that TOGGLES it (tested on the car
     * 2026-11-09: 1 turned it on, 0 did nothing, 1 again turned it off)
     */
    static final int txRearLeftSeat = CarApi.code(CarApi.USM, "getRearSeatHeat_LeftState", "()I");
    static final int txRearRightSeat = CarApi.code(CarApi.USM, "getRearSeatHeat_RightState", "()I");
    static final int txSetRearLeft = CarApi.code(CarApi.USM, "setRearSeatHeatLeftStep", "(I)V");
    static final int txSetRearRight = CarApi.code(CarApi.USM, "setRearSeatHeatRightStep", "(I)V");
    static final int txWheelStep1 = CarApi.code(CarApi.USM, "getSWRC_SteeringwheelHeatIndStep1", "()I");
    static final int txWheelStep2 = CarApi.code(CarApi.USM, "getSWRC_SteeringwheelHeatIndStep2", "()I");
    static final int txSetWheel = CarApi.code(CarApi.USM, "setSWRC_SteeringwheelWarmerSet", "(I)V");
    /** the listeners are answered by hand, so only when they are exactly the ones this was written for */
    static final boolean datcListenerOk = CarApi.DATC_LISTENER_PRINT.equals(CarApi.fingerprint(CarApi.DATC_LISTENER));
    static final boolean bcmListenerOk = CarApi.BCM_LISTENER_PRINT.equals(CarApi.fingerprint(CarApi.BCM_LISTENER));

    final VehicleState[] state;
    final VsChannel[] lanes;
    final VsChannel.Log out;
    private IBinder datc;
    private final Temperatures temps = new Temperatures();
    private int failures;
    private volatile int unit = -1;
    private final LinkedBlockingQueue<ClimateMap.Command> commands = new LinkedBlockingQueue<ClimateMap.Command>();
    private final Body body = new Body();
    private final Binder bodyToken = new Binder();
    private IBinder bcm;
    private boolean bodyTried;

    /**
     * BCMService's listener, answered exactly as the stock com.hkmc.bcm.IBCMListener.Stub (automotive.jar,
     * CEEV 260507) does: 55 calls (1-0x37), all plain reads and writeNoException, except onDigitalKeyDataChanged
     * (0x25) and onModemInfoChanged (0x27), whose byte[] is an out parameter: the length is read and an array of
     * that length must go back, or system_server's proxy throws "bad array lengths" and the unit reboots (what
     * HMG-API's vehicle listener did, see the boot loop of 2026-11-07). Nothing here may throw back into
     * system_server. onRemoteTeleHeatingStatusChanged (0x14) is (mStWheelHeatState, mDefoggerState), sent on
     * every change and once at registration.
     */
    static final class Body extends Binder {
        volatile int defogger = -1, wheel = -1;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(BCM_LISTENER_IFACE);
                return true;
            }
            if (code < FIRST_CALL_TRANSACTION || code > BCM_LAST_CALL) return false;
            boolean array = code == ON_DIGITAL_KEY_DATA || code == ON_MODEM_INFO;
            byte[] back = null;
            try {
                data.enforceInterface(BCM_LISTENER_IFACE);
                if (array) {
                    int n = data.readInt();
                    back = n < 0 ? null : new byte[n];
                } else if (code == ON_TELE_HEATING) {
                    int w = data.readInt();
                    int d = data.readInt();
                    wheel = w;
                    defogger = d;
                }
            } catch (Throwable t) {
                // a call we can't read: answer it all the same
            }
            if (reply != null) {
                reply.writeNoException();
                if (array) reply.writeByteArray(back);
            }
            return true;
        }
    }

    /** DATCService's temperature callback (synchronous: every call is answered at once) */
    static final class Temperatures extends Binder {
        volatile float driverC = Float.NaN, passengerC = Float.NaN, passengerF = Float.NaN;
        volatile int driverState = -1, passengerState = -1;

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER_IFACE);
                return true;
            }
            try {
                if (code == ON_TEMPERATURES) {
                    data.enforceInterface(LISTENER_IFACE);
                    float dc = data.readFloat();
                    int ds = data.readInt();
                    data.readFloat();
                    data.readInt();
                    float pc = data.readFloat();
                    int ps = data.readInt();
                    float pf = data.readFloat();
                    driverC = dc;
                    passengerF = pf;
                    driverState = ds;
                    passengerC = pc;
                    passengerState = ps;
                }
            } catch (Throwable t) {
                // a callback we can't read: keep the last values
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    CarClimate(VehicleState[] state, VsChannel[] lanes, VsChannel.Log out) {
        super("cp-climate-car");
        this.state = state;
        this.lanes = lanes;
        this.out = out;
        setDaemon(true);
    }

    @Override
    public void phoneWrote(Map<Long, Object> written, Map<Long, Object> now) {
        List<ClimateMap.Command> list = ClimateMap.commands(written, now, unit);
        if (!rearDefrost) {
            for (java.util.Iterator<ClimateMap.Command> i = list.iterator(); i.hasNext(); )
                if (i.next().kind == ClimateMap.REAR) i.remove();
        }
        // the ambient light test setting: a #RRGGBB, sent to the car only while the ambient_test setting is on
        Object ambient = written.get(VehicleState.AMBIENT_VALUE);
        if (ambient != null) {
            int[] rgb = parseColor(ambient);
            if (rgb == null) out.log("AMBIENT ignored: " + ambient + " is not #RRGGBB");
            else if (!ambientTest) out.log("AMBIENT test is off, not sending " + ambient);
            else list.add(new ClimateMap.Command(ClimateMap.AMBIENT, rgb[0], rgb[1], rgb[2]));
        }
        if (list.isEmpty() && ambient == null) out.log("CAR nothing to send for " + written.keySet());
        commands.addAll(list);
    }

    /** sends the queued commands, one at a time */
    void startSender() {
        Thread t = new Thread("cp-climate-car-send") {
            @Override
            public void run() {
                while (true) {
                    ClimateMap.Command c;
                    try {
                        c = commands.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (send(c)) out.log("CAR sent " + c);
                    } catch (Throwable t) {
                        out.log("CAR " + c + " failed: " + t);
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** sends a command; false if nothing needed sending */
    boolean send(ClimateMap.Command c) throws Exception {
        if (c.kind == ClimateMap.REAR) return pressRear(c.a);
        if (c.kind == ClimateMap.AMBIENT) return writeAmbient(c.a, c.b, c.c);
        if (c.kind == ClimateMap.WHEEL) return setWheel(c.a == 1);
        int code = c.kind == ClimateMap.SEAT
                ? (c.a == ClimateMap.SET_DRIVER_SEAT ? txSetDriverSeat : c.a == ClimateMap.SET_PASSENGER_SEAT ? txSetPassengerSeat
                : c.a == ClimateMap.SET_REAR_LEFT ? txSetRearLeft : c.a == ClimateMap.SET_REAR_RIGHT ? txSetRearRight : -1)
                : c.kind == ClimateMap.VR ? txSetStatus : txSetFront;
        if (code < 0) throw new IllegalStateException("this firmware has no such call");
        IBinder b = service(c.kind == ClimateMap.SEAT ? USM : DATC);
        if (b == null) throw new IllegalStateException("no service");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(c.kind == ClimateMap.SEAT ? USM_IFACE : DATC_IFACE);
            if (c.kind == ClimateMap.SEAT) {
                data.writeInt(c.b);
            } else {
                data.writeInt(c.a);
                data.writeInt(c.b);
                if (c.kind == ClimateMap.VR) data.writeInt(c.c);
            }
            b.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
        return true;
    }

    /**
     * the car's rear defroster: VRService set_RrDefogger_Cmd_AVN puts its value straight on the CAN signal
     * C_RrDefoggerCmdAVN, 1 on and 0 off (what the stock voice handler sends; tested 2026-11-08: 0 turned it off in
     * 90 ms). Not a toggle, so it is safe to send whatever the car's state; READY only.
     */
    boolean pressRear(int want) throws Exception {
        if (txRear < 0) throw new IllegalStateException("this firmware has no set_RrDefogger_Cmd_AVN(int)");
        IBinder vr = service(VR_SERVICE);
        if (vr == null) throw new IllegalStateException("no " + VR_SERVICE);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VR_IFACE);
            data.writeInt(want == 1 ? 1 : 0);
            vr.transact(txRear, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
        return true;
    }

    /** registers the BCMService listener once (only with the rear_defrost setting on) */
    void watchBody() {
        if (bodyTried) return;
        bodyTried = true;
        if (!bcmListenerOk || txRegisterBcm < 0) {
            out.log("CAR body listener left out: " + (bcmListenerOk ? "no registerBCMListener" : "IBCMListener differs from the one cp-climate answers")
                    + "; the rear window button still works, without the car's state");
            return;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder b = service(BCM);
            if (b == null) throw new IllegalStateException("no " + BCM);
            data.writeInterfaceToken(BCM_IFACE);
            data.writeStrongBinder(body);
            data.writeStrongBinder(bodyToken);
            b.transact(txRegisterBcm, data, reply, 0);
            reply.readException();
            bcm = b;
            out.log("CAR body listener registered, rear defroster " + body.defogger);
        } catch (Throwable t) {
            out.log("CAR body listener not registered: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public void run() {
        out.log("CAR reader and sender started; " + calls());
        startSender();
        String last = null;
        while (true) {
            try {
                Thread.sleep(PERIOD_MS);
                VehicleState vs = state[0];
                if (vs == null || !(lanes[0].isOpen() || lanes[1].isOpen())) continue;
                ClimateMap.Reading r = read();
                if (r == null) continue;
                Map<Long, Object> values = ClimateMap.toCarPlay(r);
                String seen = values.toString();
                if (!seen.equals(last)) {
                    out.log("CAR " + describe(r));
                    last = seen;
                }
                lanes[1].deliver(vs.fromCar(values));
                failures = 0;
            } catch (Throwable t) {
                datc = null;
                if (failures++ < 5) out.log("CAR read failed: " + t);
            }
        }
    }

    /** which of the car's calls this firmware has, and whether they are the Ioniq 6's (CEEV 260507) numbers */
    static String calls() {
        int[] got = { txRegisterDatc, txUnit, txDriverOnly, txGetStatus, txSetStatus, txSetFront,
                txDriverSeat, txPassengerSeat, txSetDriverSeat, txSetPassengerSeat, txRear, txRegisterBcm };
        int[] ioniq6 = { 0x1, 0x3, 0x27, 0x29, 0x28, 0x41, 0x247, 0x248, 0xd5, 0xd6, 0xf, 0x1 };
        StringBuilder s = new StringBuilder("car calls");
        boolean same = true;
        for (int i = 0; i < got.length; i++) {
            s.append(i == 0 ? " " : ",").append(got[i] < 0 ? "missing" : Integer.toHexString(got[i]));
            same &= got[i] == ioniq6[i];
        }
        return s.append(same ? " (as the Ioniq 6's)" : " (NOT the Ioniq 6's numbers)")
                .append("; DATC listener ").append(datcListenerOk ? "verified" : "differs, not registered")
                .append(", BCM listener ").append(bcmListenerOk ? "verified" : "differs, not registered").toString();
    }

    ClimateMap.Reading read() throws Exception {
        if (txGetStatus < 0) return null;   // no climate status call on this firmware: nothing to show
        if (datc == null) {
            datc = service(DATC);
            if (datc == null) return null;
            if (datcListenerOk && txRegisterDatc >= 0) register(datc);
        }
        ClimateMap.Reading r = new ClimateMap.Reading();
        r.on = status(F_ON);
        r.auto = status(F_AUTO);
        r.ac = status(F_AC);
        r.defrost = status(F_DEFROST);
        r.intake = status(F_INTAKE);
        r.fan = status(F_FAN);
        r.mode = status(F_MODE);
        r.driverF = status(F_DRIVER_F);
        r.passengerRaw = status(F_PASSENGER_RAW);
        r.sync = status(F_SYNC);
        r.unit = unit = temperatureUnit();
        r.driverOnly = driverOnly();
        r.driverC = temps.driverC;
        r.driverState = temps.driverState;
        r.passengerC = temps.passengerC;
        r.passengerF = temps.passengerF;
        r.passengerState = temps.passengerState;
        IBinder usm = service(USM);
        if (usm != null) {
            r.driverSeat = seat(usm, txDriverSeat);
            r.passengerSeat = seat(usm, txPassengerSeat);
            if (ClimateMap.extras) {
                r.rearLeftSeat = seat(usm, txRearLeftSeat);
                r.rearRightSeat = seat(usm, txRearRightSeat);
                r.wheel = wheelOn(usm);
            }
        }
        if (rearDefrost) {
            watchBody();
            r.rearDefog = body.defogger;
        }
        return r;
    }

    void register(IBinder b) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            data.writeStrongBinder(temps);
            data.writeStrongBinder(new Binder());
            b.transact(txRegisterDatc, data, reply, 0);
            reply.readException();
            out.log("CAR temperature listener registered");
        } catch (Throwable t) {
            out.log("CAR temperature listener not registered: " + t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    int driverOnly() {
        if (txDriverOnly < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            datc.transact(txDriverOnly, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    int temperatureUnit() throws Exception {
        if (txUnit < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            datc.transact(txUnit, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    float status(int flag) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DATC_IFACE);
            data.writeInt(flag);
            datc.transact(txGetStatus, data, reply, 0);
            reply.readException();
            return reply.readFloat();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * The ambient light test: the mood light's red, green and blue (USMService setDualLight_BottomRed, Green and
     * Blue, 0-255 as the car reports them: 2026-11-09 it read 255, 3, 10), then the car's own values read back
     * into the log, so a write the light ignored shows. Only the colour; mode and speed are left as they are.
     */
    boolean writeAmbient(int r, int g, int b) throws Exception {
        if (txAmbRed < 0 || txAmbGreen < 0 || txAmbBlue < 0)
            throw new IllegalStateException("this firmware has no setDualLight_Bottom* calls");
        IBinder usm = service(USM);
        if (usm == null) throw new IllegalStateException("no " + USM);
        int[] values = { clamp(r), clamp(g), clamp(b) };
        int[] codes = { txAmbRed, txAmbGreen, txAmbBlue };
        for (int k = 0; k < 3; k++) {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(USM_IFACE);
                data.writeInt(values[k]);
                usm.transact(codes[k], data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        out.log("AMBIENT wrote " + String.format("#%02X%02X%02X", values[0], values[1], values[2])
                + "; the car now reports " + ambientHex());
        return true;
    }

    /** the steering wheel heater's state: 1 if either indicator step is lit, 0 if both are dark, -1 if neither could be read */
    static int wheelOn(IBinder usm) {
        int s1 = seat(usm, txWheelStep1), s2 = seat(usm, txWheelStep2);
        if (s1 < 0 && s2 < 0) return -1;
        return s1 > 0 || s2 > 0 ? 1 : 0;
    }

    /**
     * The steering wheel heater on or off. The car's call is a toggle (a press), so it is sent only when the
     * indicator says the heater isn't already as wanted; false if there was nothing to do.
     */
    boolean setWheel(boolean want) throws Exception {
        if (txSetWheel < 0 || txWheelStep1 < 0) throw new IllegalStateException("this firmware has no steering wheel heater calls");
        IBinder usm = service(USM);
        if (usm == null) throw new IllegalStateException("no " + USM);
        int now = wheelOn(usm);
        if (now < 0) throw new IllegalStateException("the steering wheel heater's state can't be read");
        if ((now == 1) == want) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(USM_IFACE);
            data.writeInt(1);
            usm.transact(txSetWheel, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
        return true;
    }

    static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** the car's mood light colour as #RRGGBB, or null if it can't be read */
    static String ambientHex() {
        try {
            IBinder usm = service(USM);
            if (usm == null) return null;
            int r = seat(usm, txGetAmbRed), g = seat(usm, txGetAmbGreen), b = seat(usm, txGetAmbBlue);
            if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return null;
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Throwable t) {
            return null;
        }
    }

    /** "#RRGGBB" (or "RRGGBB") as {r, g, b}, or null */
    static int[] parseColor(Object o) {
        if (!(o instanceof String)) return null;
        String s = ((String) o).trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            int v = Integer.parseInt(s, 16);
            return new int[] { (v >> 16) & 255, (v >> 8) & 255, v & 255 };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int seat(IBinder usm, int code) {
        if (code < 0) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(USM_IFACE);
            usm.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
    }

    static String describe(ClimateMap.Reading r) {
        return "on " + (int) r.on + " auto " + (int) r.auto + " ac " + (int) r.ac + " defrost " + (int) r.defrost
                + " intake " + (int) r.intake + " sync " + (int) r.sync + " driverOnly " + r.driverOnly + " unit " + r.unit + " fan " + (int) r.fan + " mode " + (int) r.mode + " driver " + r.driverF
                + "F/" + r.driverC + "C (" + Integer.toHexString(r.driverState) + ") passenger raw " + r.passengerRaw + " "
                + r.passengerC + "C/" + r.passengerF + "F (" + Integer.toHexString(r.passengerState) + ") seats " + r.driverSeat + "/" + r.passengerSeat
                + " rear defroster " + r.rearDefog;
    }
}
