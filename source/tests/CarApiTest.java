package ioniq6.cpclimate;

/**
 * Host test of CarApi against stand-ins of the Ioniq 6's framework (tests/gen_car_framework.py writes them
 * from baksmali output): every call CarClimate uses resolves to the Ioniq 6's number with the descriptor
 * CarClimate asks for, the two listeners give the fingerprints CarApi checks (the same that
 * tools/listener_fingerprint.py computes), and a wrong descriptor or a missing method gives -1.
 * usage: CarApiTest [altered]   (altered: the stand-in BCM listener has one more call, so it must not match)
 */
public class CarApiTest {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    public static void main(String[] a) {
        boolean altered = a.length > 0 && "altered".equals(a[0]);
        if (altered) {
            check("a BCM listener with one more call does not match, so it would not be registered",
                    !CarApi.BCM_LISTENER_PRINT.equals(CarApi.fingerprint(CarApi.BCM_LISTENER)));
            System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
            System.exit(fails == 0 ? 0 : 1);
        }
        int[] got = { CarClimate.txRegisterDatc, CarClimate.txUnit, CarClimate.txDriverOnly, CarClimate.txGetStatus, CarClimate.txSetStatus, CarClimate.txSetFront,
                CarClimate.txDriverSeat, CarClimate.txPassengerSeat, CarClimate.txSetDriverSeat, CarClimate.txSetPassengerSeat, CarClimate.txRear, CarClimate.txRegisterBcm };
        int[] ioniq6 = { 0x1, 0x3, 0x27, 0x29, 0x28, 0x41, 0x247, 0x248, 0xd5, 0xd6, 0xf, 0x1 };
        StringBuilder s = new StringBuilder();
        boolean same = true;
        for (int i = 0; i < got.length; i++) {
            s.append(Integer.toHexString(got[i])).append(' ');
            same &= got[i] == ioniq6[i];
        }
        check("every car call resolves by name and signature to the Ioniq 6's number: " + s, same);
        check("the DATC listener's fingerprint matches (" + CarApi.fingerprint(CarApi.DATC_LISTENER) + ")", CarClimate.datcListenerOk);
        check("the BCM listener's fingerprint matches (" + CarApi.fingerprint(CarApi.BCM_LISTENER) + ")", CarClimate.bcmListenerOk);
        check("a wrong signature gives -1", CarApi.code(CarApi.DATC, "setVRClimateStatus", "(II)V") == -1
                && CarApi.code(CarApi.VR, "set_RrDefogger_Cmd_AVN", "(Z)V") == -1);
        check("a missing method or interface gives -1", CarApi.code(CarApi.DATC, "setSomethingElse", "()V") == -1
                && CarApi.code("com.hkmc.nothing.INothing", "x", "()V") == -1);
        check("calls() says they are the Ioniq 6's numbers and both listeners are verified: " + CarClimate.calls(),
                CarClimate.calls().contains("(as the Ioniq 6's)") && CarClimate.calls().contains("DATC listener verified") && CarClimate.calls().contains("BCM listener verified"));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
