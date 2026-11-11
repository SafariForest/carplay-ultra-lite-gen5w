package ioniq6.cpclimate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

/**
 * The uiSync experiment: the native SETUP handler sends the phone's uiSync data stream (type 130,
 * clientTypeUUID 07D9F906-…) here, with its keys in files/cpc-vs-3.key. Decrypted like the iAP
 * stream (a duplex tunnel, LIVI iap_tunnel.rs): every package body that isn't a reply is a uiSync
 * message for UiSync, whose answers go back as async 'cmnd' packages.
 */
final class UiSyncChannel extends Thread {
    static final int PORT = 7135;
    static final String KEYS = "cpc-vs-3.key";
    static final int MAX_LOGGED = 300;

    final File dir;
    final VsChannel.Log out;
    final UiSync sync = new UiSync();

    UiSyncChannel(File dir, VsChannel.Log out, UiSync.Layout layout) {
        super("cp-climate-uisync");
        this.dir = dir;
        this.out = out;
        sync.wanted = layout;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            out.log("SYNC listening on port " + PORT + " (uiSync experiment, cluster layout "
                    + (sync.wanted == null ? "not requested" : sync.wanted.name + " " + sync.wanted.palette) + ")");
            while (true) {
                final Socket sock = s.accept();
                new Thread("cp-climate-uisync-conn") {
                    @Override
                    public void run() {
                        serve(sock);
                    }
                }.start();
            }
        } catch (Throwable t) {
            out.log("SYNC listener stopped: " + t);
        }
    }

    void serve(Socket sock) {
        out.log("SYNC connection from " + sock.getRemoteSocketAddress());
        int logged = 0, packages = 0;
        try {
            byte[] keys = IapChannel.readKeys(dir, KEYS);
            if (keys == null) {
                out.log("SYNC no keys from the SETUP: closed");
                return;
            }
            byte[] rd = new byte[32], wr = new byte[32];
            System.arraycopy(keys, 8, rd, 0, 32);
            System.arraycopy(keys, 40, wr, 0, 32);
            ChaChaPoly cipher = new ChaChaPoly(rd, wr);
            InputStream in = sock.getInputStream();
            VsChannel.Writer w = new VsChannel.Writer(sock, sock.getOutputStream(), cipher);
            synchronized (sync) {
                sync.reopen();
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
                    out.log("SYNC a frame didn't authenticate: closed");
                    return;
                }
                System.arraycopy(sealed, used, sealed, 0, have - used);
                have -= used;
                byte[] p = plain.toByteArray();
                int at = 0;
                while (p.length - at >= VsChannel.HEADER) {
                    int size = VsChannel.be32(p, at);
                    if (size < VsChannel.HEADER || size > VsChannel.MAX_PACKAGE) {
                        out.log("SYNC implausible package size " + size + ": closed");
                        return;
                    }
                    if (p.length - at < size) break;
                    int kind = VsChannel.be32(p, at + 4);
                    long token = (long) VsChannel.be32(p, at + 20) << 32 | (VsChannel.be32(p, at + 24) & 0xffffffffL);
                    int len = size - VsChannel.HEADER;
                    packages++;
                    if (kind == VsChannel.SYNC) w.send(VsChannel.pack(VsChannel.REPLY, 0, token, new byte[0]));
                    if (kind != VsChannel.REPLY && len > 0) {
                        byte[] body = java.util.Arrays.copyOfRange(p, at + VsChannel.HEADER, at + size);
                        List<byte[]> answers;
                        List<String> picked;
                        synchronized (sync) {
                            try {
                                answers = sync.handle(body);
                            } catch (IllegalArgumentException e) {
                                answers = java.util.Collections.emptyList();
                                if (logged++ < MAX_LOGGED) out.log("SYNC unreadable message (" + e.getMessage() + "): " + UiSync.describe(body));
                            }
                            picked = new java.util.ArrayList<String>(sync.choices);
                            sync.choices.clear();
                        }
                        if (logged++ < MAX_LOGGED) out.log("SYNC phone: " + UiSync.describe(body));
                        for (byte[] a : answers) {
                            w.send(VsChannel.pack(VsChannel.ASYNC, VsChannel.COMMAND, 0, a));
                            if (logged++ < MAX_LOGGED) out.log("SYNC car: " + UiSync.describe(a));
                        }
                        for (String c : picked) out.log("SYNC the phone picked " + c);
                    }
                    at += size;
                }
                plain.reset();
                plain.write(p, at, p.length - at);
            }
            out.log("SYNC connection closed by the phone after " + packages + " package(s)");
        } catch (Throwable t) {
            out.log("SYNC connection ended after " + packages + " package(s): " + t);
        } finally {
            VsChannel.close(sock);
        }
    }
}
