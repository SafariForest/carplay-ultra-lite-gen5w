package ioniq6.cpclimate;

import hmgapi.hook.Points;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * iAP over CarPlay, the DiPO half (setting iap_channel = bridge). The phone's iAP bytes (iAPChannel
 * 'comm' packages) go over a loopback socket to the EV Routing mod in iap2server (127.0.0.1:7140),
 * which runs them through iap2server's stock CarplayClient link; what that link sends back goes to the
 * phone on the same stream as async 'cmnd' packages, as LIVI's iap_tunnel duplex does.
 * Framing on the socket: u32 big-endian length, then the bytes.
 */
final class IapBridge {
    static final int PORT = 7140;
    static final int MAX_FRAME = 1 << 20;
    static final int MAX_LOGGED = 200;

    /** DiPO's CarPlayService, from the session hooks (the native send needs it) */
    static volatile Object carPlay;
    static volatile ClassLoader loader;
    static VsChannel.Log out;

    /** the iAPChannel stream's writer while it's open: the car's iAP2 goes back on it (LIVI iap_tunnel duplex) */
    static volatile VsChannel.Writer stream;
    private static int legacyDropped;

    private static Socket sock;
    private static DataOutputStream toCar;
    private static int up, down;

    /** the phone's iAP channel opened: link to iap2server for this session */
    static synchronized void open() {
        close();
        up = down = 0;
        try {
            final Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
            sock = s;
            toCar = new DataOutputStream(s.getOutputStream());
            out.log("IAPB linked to iap2server on 127.0.0.1:" + PORT);
            new Thread("cp-climate-iapb") {
                @Override
                public void run() {
                    fromCar(s);
                }
            }.start();
        } catch (Throwable t) {
            out.log("IAPB no link to iap2server (is the EV Routing mod's iAP bridge on?): " + t);
            sock = null;
            toCar = null;
        }
    }

    /** bytes the phone sent, to iap2server */
    static synchronized void toCar(byte[] b, int off, int len) {
        if (toCar == null) return;
        try {
            toCar.writeInt(len);
            toCar.write(b, off, len);
            toCar.flush();
            if (up++ < MAX_LOGGED) out.log("IAPB phone->car " + len + " B " + IapChannel.hex(b, off, Math.min(len, IapChannel.HEX_BYTES)));
        } catch (Throwable t) {
            out.log("IAPB phone->car failed: " + t);
            close();
        }
    }

    static synchronized void close() {
        if (sock != null) {
            out.log("IAPB link closed (" + up + " up, " + down + " down)");
            VsChannel.close(sock);
        }
        sock = null;
        toCar = null;
    }

    /** a DiPO iAPSendMessage call dropped while the stream carries the link */
    static void droppedLegacy(int len) {
        int n = ++legacyDropped;
        if (n <= 5 || n % 200 == 0) out.log("IAPB dropped DiPO's iAPSendMessage copy (" + len + " B, #" + n + "): the stream carries the link");
    }

    /** iap2server's link output, to the phone over the iAPChannel stream */
    static void fromCar(Socket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (true) {
                int n = in.readInt();
                if (n <= 0 || n > MAX_FRAME) throw new java.io.IOException("frame size " + n);
                byte[] b = new byte[n];
                in.readFully(b);
                VsChannel.Writer w = stream;
                if (w == null) {
                    if (down++ < MAX_LOGGED) out.log("IAPB car->phone " + n + " B dropped: no iAPChannel stream");
                    continue;
                }
                w.send(VsChannel.pack(VsChannel.ASYNC, VsChannel.COMMAND, 0, b));
                if (down++ < MAX_LOGGED) out.log("IAPB car->phone " + n + " B (stream) " + IapChannel.hex(b, 0, Math.min(n, IapChannel.HEX_BYTES)));
            }
        } catch (Throwable t) {
            out.log("IAPB iap2server side ended: " + t);
        } finally {
            synchronized (IapBridge.class) {
                if (sock == s) close();
            }
        }
    }
}
