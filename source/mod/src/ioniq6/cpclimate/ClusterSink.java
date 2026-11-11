package ioniq6.cpclimate;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The CarPlay Ultra experiment's virtual cluster: the native SETUP handler sends the phone's
 * cluster screen stream (type 111) here, and this takes the video and throws it away, so iOS
 * sees a cluster display that works. Nothing is decrypted or shown; it logs how much came in.
 */
final class ClusterSink extends Thread {
    static final int PORT = 7132;
    /** a progress line every this many bytes */
    static final long REPORT_BYTES = 4L * 1024 * 1024;

    final VsChannel.Log out;
    final int port;
    final String what;

    ClusterSink(VsChannel.Log out) {
        this(out, PORT, "cluster");
    }

    /** a sink for another stream the phone opens (the videoPlayback experiment's data streams) */
    ClusterSink(VsChannel.Log out, int port, String what) {
        super("cp-climate-" + what);
        this.out = out;
        this.port = port;
        this.what = what;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            out.log(what + " listening on port " + port + " (experiment)");
            while (true) {
                final Socket sock = s.accept();
                new Thread("cp-climate-cluster-conn") {
                    @Override
                    public void run() {
                        drain(sock);
                    }
                }.start();
            }
        } catch (Throwable t) {
            out.log(what + " listener stopped: " + t);
        }
    }

    void drain(Socket sock) {
        String who = String.valueOf(sock.getRemoteSocketAddress());
        out.log(what + " stream from " + who);
        long total = 0, next = REPORT_BYTES;
        long start = System.currentTimeMillis();
        try {
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total >= next) {
                    out.log(what + " stream: " + total / 1024 + " KB in " + (System.currentTimeMillis() - start) / 1000 + " s");
                    next += REPORT_BYTES;
                }
            }
        } catch (Throwable t) {
            out.log(what + " stream error: " + t);
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
            out.log(what + " stream closed after " + total / 1024 + " KB, " + (System.currentTimeMillis() - start) / 1000 + " s");
        }
    }
}
