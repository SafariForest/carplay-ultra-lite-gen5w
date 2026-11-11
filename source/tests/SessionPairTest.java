package ioniq6.cpclimate;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * Host test: the iPhone opens both vehicle state lanes within milliseconds; both must answer from
 * one VehicleState (two fresh ones left the phone's subscriptions on a state the car no longer fed:
 * CarPlay froze at its initial 70 °F and default vents, 2026-11-08).
 * usage: SessionPairTest <dir>
 */
public class SessionPairTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        VsChannel.Log quiet = new VsChannel.Log() {
            @Override
            public void log(String msg) {
            }
        };
        int fails = 0;
        for (int round = 0; round < 500; round++) {
            final VehicleState[] state = new VehicleState[1];
            VsChannel[] lanes = new VsChannel[2];
            for (int lane = 0; lane < 2; lane++) lanes[lane] = new VsChannel(lane, dir, quiet, state, lanes);
            final CountDownLatch go = new CountDownLatch(1);
            final VehicleState[] seen = new VehicleState[2];
            Thread[] t = new Thread[2];
            for (int lane = 0; lane < 2; lane++) {
                final VsChannel ch = lanes[lane];
                final int l = lane;
                t[lane] = new Thread() {
                    @Override
                    public void run() {
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        ch.startSession();
                        seen[l] = state[0];
                    }
                };
                t[lane].start();
            }
            go.countDown();
            for (Thread x : t) x.join();
            if (seen[0] == null || seen[0] != seen[1] || state[0] != seen[0]) fails++;
        }
        System.out.println((fails == 0 ? "ok   " : "FAIL ") + "both lanes share one vehicle state in 500 simultaneous starts (" + fails + " split)");
        final VehicleState[] state = new VehicleState[1];
        VsChannel[] lanes = new VsChannel[2];
        for (int lane = 0; lane < 2; lane++) lanes[lane] = new VsChannel(lane, dir, quiet, state, lanes);
        lanes[0].startSession();
        VehicleState first = state[0];
        first.startedAt -= VsChannel.SESSION_PAIRING_MS + 1;
        lanes[1].startSession();
        boolean fresh = state[0] != first;
        System.out.println((fresh ? "ok   " : "FAIL ") + "a lane connecting after the pairing window starts a fresh session");
        if (!fresh) fails++;
        System.out.println(fails == 0 ? "ALL OK" : "FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
