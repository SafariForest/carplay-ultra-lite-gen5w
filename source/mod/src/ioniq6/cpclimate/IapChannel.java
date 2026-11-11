package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Stage A of iAP over CarPlay (the iAPChannel experiment): the native SETUP handler sends the
 * phone's iAP data stream (type 130, clientTypeUUID E9459FD0-…) here, with its keys in
 * files/cpc-vs-2.key. This decrypts it like the vehicle state lanes and only logs what the phone
 * sends (LIVI's iAP tunnel is receive-only too): nothing is answered or handed to iap2server yet.
 */
final class IapChannel extends Thread {
    static final int PORT = 7133;
    static final String KEYS = "cpc-vs-2.key";
    /** iAP payloads: 'comm' packages (LIVI iap_tunnel.rs MSG_TYPE_COMM) */
    static final int COMM = 0x636f6d6d;
    static final int MAX_LOGGED = 300;
    static final int HEX_BYTES = 48;

    final File dir;
    final VsChannel.Log out;
    /** stage B: hand the phone's iAP bytes to iap2server (IapBridge) instead of only logging them */
    final boolean bridge;

    IapChannel(File dir, VsChannel.Log out, boolean bridge) {
        super("cp-climate-iap");
        this.dir = dir;
        this.out = out;
        this.bridge = bridge;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            out.log("IAP listening on port " + PORT + " (iAPChannel experiment, " + (bridge ? "bridged to iap2server" : "log only") + ")");
            while (true) {
                final Socket sock = s.accept();
                new Thread("cp-climate-iap-conn") {
                    @Override
                    public void run() {
                        serve(sock);
                    }
                }.start();
            }
        } catch (Throwable t) {
            out.log("IAP listener stopped: " + t);
        }
    }

    void serve(Socket sock) {
        out.log("IAP connection from " + sock.getRemoteSocketAddress());
        int logged = 0;
        long total = 0;
        try {
            byte[] keys = readKeys();
            if (keys == null) {
                out.log("IAP no keys from the SETUP: closed");
                return;
            }
            byte[] rd = new byte[32], wr = new byte[32];
            System.arraycopy(keys, 8, rd, 0, 32);
            System.arraycopy(keys, 40, wr, 0, 32);
            ChaChaPoly cipher = new ChaChaPoly(rd, wr);
            InputStream in = sock.getInputStream();
            VsChannel.Writer w = new VsChannel.Writer(sock, sock.getOutputStream(), cipher);
            if (bridge) {
                IapBridge.stream = w;
                IapBridge.open();
            }
            byte[] sealed = new byte[64 * 1024];
            int have = 0;
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            while (true) {
                if (have == sealed.length) sealed = java.util.Arrays.copyOf(sealed, sealed.length * 2);
                int n = in.read(sealed, have, sealed.length - have);
                if (n < 0) break;
                have += n;
                int used = cipher.open(sealed, have, plain);
                if (used < 0) {
                    out.log("IAP a frame didn't authenticate: closed");
                    return;
                }
                System.arraycopy(sealed, used, sealed, 0, have - used);
                have -= used;
                byte[] p = plain.toByteArray();
                int at = 0;
                while (p.length - at >= VsChannel.HEADER) {
                    int size = VsChannel.be32(p, at);
                    if (size < VsChannel.HEADER || size > VsChannel.MAX_PACKAGE) {
                        out.log("IAP implausible package size " + size + ": closed");
                        return;
                    }
                    if (p.length - at < size) break;
                    int kind = VsChannel.be32(p, at + 4), type = VsChannel.be32(p, at + 16);
                    long token = (long) VsChannel.be32(p, at + 20) << 32 | (VsChannel.be32(p, at + 24) & 0xffffffffL);
                    int len = size - VsChannel.HEADER;
                    total += len;
                    if (kind == VsChannel.SYNC) w.send(VsChannel.pack(VsChannel.REPLY, 0, token, new byte[0]));
                    if (bridge && type == COMM && len > 0) IapBridge.toCar(p, at + VsChannel.HEADER, len);
                    if (logged++ < MAX_LOGGED)
                        out.log("IAP " + VsChannel.tag(kind) + "/" + VsChannel.tag(type) + " " + len + " B"
                                + (type == COMM ? " iAP2 " : " ") + hex(p, at + VsChannel.HEADER, Math.min(len, HEX_BYTES)));
                    at += size;
                }
                plain.reset();
                plain.write(p, at, p.length - at);
            }
            out.log("IAP connection closed by the phone after " + logged + " package(s), " + total + " B");
        } catch (Throwable t) {
            out.log("IAP connection ended after " + logged + " package(s): " + t);
        } finally {
            if (bridge) {
                IapBridge.close();
                IapBridge.stream = null;
            }
            VsChannel.close(sock);
        }
    }

    byte[] readKeys() {
        return readKeys(dir, KEYS);
    }

    /** a lane's keys file from the native SETUP handler: seed (8), read key (32), write key (32) */
    static byte[] readKeys(File dir, String name) {
        byte[] b = new byte[72];
        try {
            InputStream in = new FileInputStream(new File(dir, name));
            try {
                int got = 0;
                while (got < b.length) {
                    int n = in.read(b, got, b.length - got);
                    if (n < 0) return null;
                    got += n;
                }
                return b;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String hex(byte[] b, int off, int n) {
        StringBuilder s = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) s.append(Character.forDigit((b[off + i] >> 4) & 15, 16)).append(Character.forDigit(b[off + i] & 15, 16));
        return s.toString();
    }
}
