package ioniq6.cpclimate;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test: IapChannel decrypts the phone's iAP stream (cpc-vs-2.key, the vehicle state lanes'
 * framing) and logs each 'comm' package with its first bytes; a 'sync' package gets its 'rply'.
 * usage: IapChannelTest <dir>
 */
public class IapChannelTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        byte[] rd = new byte[32], wr = new byte[32], keys = new byte[72];
        for (int i = 0; i < 32; i++) { rd[i] = (byte) (0x80 + i); wr[i] = (byte) (0x10 + i); }
        System.arraycopy(rd, 0, keys, 8, 32);
        System.arraycopy(wr, 0, keys, 40, 32);
        FileOutputStream f = new FileOutputStream(new File(dir, IapChannel.KEYS));
        f.write(keys);
        f.close();
        final List<String> logs = new ArrayList<String>();
        IapChannel ch = new IapChannel(dir, new VsChannel.Log() {
            @Override
            public synchronized void log(String msg) {
                logs.add(msg);
            }
        }, false);
        ch.start();
        Thread.sleep(300);
        Socket s = new Socket("127.0.0.1", IapChannel.PORT);
        // the phone writes with the car's read key and reads with its write key
        VsChannel.Writer phone = new VsChannel.Writer(s, s.getOutputStream(), new ChaChaPoly(wr, rd));
        byte[] syn = { (byte) 0xff, 0x5a, 0x00, 0x1a, (byte) 0x80, 0x2b, 0x00, 0x00 };
        phone.send(VsChannel.pack(VsChannel.ASYNC, IapChannel.COMM, 0, syn));
        phone.send(VsChannel.pack(VsChannel.SYNC, IapChannel.COMM, 7, syn));
        Thread.sleep(500);
        s.close();
        Thread.sleep(300);
        int fails = 0;
        String all;
        synchronized (logs) { all = logs.toString(); }
        boolean got = all.contains("IAP asyn/comm 8 B iAP2 ff5a001a802b0000");
        System.out.println((got ? "ok   " : "FAIL ") + "the comm package is decrypted and logged with its iAP2 bytes");
        if (!got) fails++;
        boolean sync = all.contains("IAP sync/comm 8 B");
        System.out.println((sync ? "ok   " : "FAIL ") + "a sync package is read (and answered)");
        if (!sync) fails++;
        boolean closed = all.contains("closed by the phone after 2 package(s), 16 B");
        System.out.println((closed ? "ok   " : "FAIL ") + "the close is logged with the totals");
        if (!closed) fails++;
        if (fails > 0) System.out.println(all);
        System.out.println(fails == 0 ? "ALL OK" : "FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
