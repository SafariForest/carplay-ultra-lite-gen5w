package ioniq6.cpclimate;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test: with the bridge on, the phone's iAPChannel bytes reach "iap2server" (a fake on
 * 127.0.0.1:7140) as length-prefixed frames, and what it sends back comes to the phone on the same
 * stream as an async 'cmnd' package (LIVI iap_tunnel duplex).
 * usage: IapBridgeTest <dir>
 */
public class IapBridgeTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        byte[] rd = new byte[32], wr = new byte[32], keys = new byte[72];
        for (int i = 0; i < 32; i++) { rd[i] = (byte) (0x40 + i); wr[i] = (byte) (0x20 + i); }
        System.arraycopy(rd, 0, keys, 8, 32);
        System.arraycopy(wr, 0, keys, 40, 32);
        FileOutputStream f = new FileOutputStream(new File(dir, IapChannel.KEYS));
        f.write(keys);
        f.close();
        final List<String> logs = new ArrayList<String>();
        VsChannel.Log log = new VsChannel.Log() {
            @Override
            public synchronized void log(String msg) {
                logs.add(msg);
            }
        };
        IapBridge.out = log;
        final ServerSocket car = new ServerSocket();
        car.setReuseAddress(true);
        car.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), IapBridge.PORT));
        final byte[][] got = new byte[1][];
        Thread fake = new Thread() {
            @Override
            public void run() {
                try {
                    Socket s = car.accept();
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    int n = in.readInt();
                    byte[] b = new byte[n];
                    in.readFully(b);
                    got[0] = b;
                    DataOutputStream o = new DataOutputStream(s.getOutputStream());
                    byte[] reply = { (byte) 0xff, 0x55, 0x02, 0x00, (byte) 0xee, 0x10 };
                    o.writeInt(reply.length);
                    o.write(reply);
                    o.flush();
                    Thread.sleep(500);
                    s.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        fake.start();
        new IapChannel(dir, log, true).start();
        Thread.sleep(300);
        Socket phone = new Socket("127.0.0.1", IapChannel.PORT);
        ChaChaPoly phoneCipher = new ChaChaPoly(wr, rd);
        VsChannel.Writer w = new VsChannel.Writer(phone, phone.getOutputStream(), phoneCipher);
        byte[] detect = { (byte) 0xff, 0x55, 0x02, 0x00, (byte) 0xee, 0x10 };
        w.send(VsChannel.pack(VsChannel.ASYNC, IapChannel.COMM, 0, detect));
        // what the car sends back on the stream: one package after the 32-byte header
        phone.setSoTimeout(2000);
        java.io.InputStream pin = phone.getInputStream();
        byte[] sealed = new byte[4096];
        int have = 0;
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        try {
            while (plain.size() < VsChannel.HEADER + 6) {
                int n = pin.read(sealed, have, sealed.length - have);
                if (n < 0) break;
                have += n;
                int used = phoneCipher.open(sealed, have, plain);
                if (used < 0) break;
                System.arraycopy(sealed, used, sealed, 0, have - used);
                have -= used;
            }
        } catch (java.net.SocketTimeoutException e) {
            // checked below
        }
        byte[] back = plain.toByteArray();
        Thread.sleep(300);
        phone.close();
        Thread.sleep(300);
        car.close();
        String all;
        synchronized (log) { all = logs.toString(); }
        int fails = 0;
        boolean through = got[0] != null && java.util.Arrays.equals(got[0], detect);
        System.out.println((through ? "ok   " : "FAIL ") + "the phone's iAP2 detect reaches iap2server as one frame");
        if (!through) fails++;
        boolean linked = all.contains("IAPB linked to iap2server");
        System.out.println((linked ? "ok   " : "FAIL ") + "the bridge links when the phone's iAP channel opens");
        if (!linked) fails++;
        byte[] reply = { (byte) 0xff, 0x55, 0x02, 0x00, (byte) 0xee, 0x10 };
        boolean onStream = back.length == VsChannel.HEADER + 6 && VsChannel.be32(back, 4) == VsChannel.ASYNC
                && VsChannel.be32(back, 16) == VsChannel.COMMAND
                && java.util.Arrays.equals(java.util.Arrays.copyOfRange(back, VsChannel.HEADER, back.length), reply);
        System.out.println((onStream ? "ok   " : "FAIL ") + "iap2server's answer comes back on the phone's stream as one async 'cmnd' package");
        if (!onStream) fails++;
        boolean noted = all.contains("IAPB car->phone 6 B (stream)");
        System.out.println((noted ? "ok   " : "FAIL ") + "the answer is logged as sent on the stream");
        if (!noted) fails++;
        boolean cleared = IapBridge.stream == null;
        System.out.println((cleared ? "ok   " : "FAIL ") + "the stream writer is cleared when the phone's channel closes");
        if (!cleared) fails++;
        boolean closed = all.contains("IAPB link closed");
        System.out.println((closed ? "ok   " : "FAIL ") + "the link closes with the phone's channel");
        if (!closed) fails++;
        if (fails > 0) System.out.println(all);
        System.out.println(fails == 0 ? "ALL OK" : "FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
