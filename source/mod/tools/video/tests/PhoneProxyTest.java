import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Vector;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import ioniq6.cpclimate.video.PhoneProxy;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/**
 * Host test of PhoneProxy against a stand-in for the iPhone's proxy: a BouncyCastle TLS 1.3 server with an
 * external PSK that answers HTTP CONNECT (Proxy-Authorization checked) by relaying to the target.
 * usage: PhoneProxyTest [--internet]   (--internet also fetches https://www.apple.com through the tunnel)
 */
public class PhoneProxyTest {
    static int fails;
    static final byte[] PSK = new byte[32], ID = new byte[16];
    static final String AUTH = "Basic dGVzdDp0ZXN0";

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    public static void main(String[] a) throws Exception {
        new SecureRandom().nextBytes(PSK);
        new SecureRandom().nextBytes(ID);
        final ServerSocket proxy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread pt = new Thread() { public void run() { serveProxy(proxy); } };
        pt.setDaemon(true); pt.start();
        final ServerSocket web = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread wt = new Thread() { public void run() { serveWeb(web); } };
        wt.setDaemon(true); wt.start();

        PhoneProxy p = new PhoneProxy(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort(), PSK, ID, AUTH);
        Socket t = p.open("127.0.0.1", web.getLocalPort());
        OutputStream o = t.getOutputStream();
        o.write("GET /hello HTTP/1.0\r\nHost: x\r\n\r\n".getBytes("US-ASCII"));
        o.flush();
        String body = new String(readAll(t.getInputStream()), "US-ASCII");
        t.close();
        check("plain HTTP through the tunnel: " + body.replace("\r\n", "|"), body.endsWith("hello from web"));

        PhoneProxy wrong = new PhoneProxy(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort(), new byte[32], ID, AUTH);
        boolean refused = false;
        try { wrong.open("127.0.0.1", web.getLocalPort()).close(); } catch (IOException e) { refused = true; System.out.println("     wrong key: " + e); }
        check("a wrong key is refused", refused);

        PhoneProxy noAuth = new PhoneProxy(InetAddress.getByName("127.0.0.1"), proxy.getLocalPort(), PSK, ID, "Basic bad");
        String err = null;
        try { noAuth.open("127.0.0.1", web.getLocalPort()).close(); } catch (IOException e) { err = e.getMessage(); }
        check("bad Proxy-Authorization: CONNECT refused with its status (" + err + ")", err != null && err.contains("407"));

        if (a.length > 0 && a[0].equals("--internet")) {
            Socket tun = p.open("www.apple.com", 443);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tun, "www.apple.com", 443, true);
            s.startHandshake();
            s.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: www.apple.com\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
            s.getOutputStream().flush();
            String r = new String(readAll(s.getInputStream()), "US-ASCII");
            s.close();
            check("HTTPS to www.apple.com through the tunnel: " + r.substring(0, Math.min(r.length(), 40)).replace("\r\n", "|"), r.startsWith("HTTP/1.1 "));
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try { while ((n = in.read(buf)) > 0) b.write(buf, 0, n); } catch (TlsNoCloseNotifyException ignored) { }
        return b.toByteArray();
    }

    static void serveWeb(ServerSocket ss) {
        while (true) {
            try {
                Socket s = ss.accept();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
                String line;
                while ((line = r.readLine()) != null && line.length() > 0) { }
                s.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: 14\r\n\r\nhello from web".getBytes("US-ASCII"));
                s.close();
            } catch (IOException e) { return; }
        }
    }

    /** the stand-in phone proxy: TLS 1.3 external PSK, then CONNECT with Proxy-Authorization, then a relay */
    static void serveProxy(ServerSocket ss) {
        final TlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
        while (true) {
            final Socket s;
            try { s = ss.accept(); } catch (IOException e) { return; }
            new Thread() { public void run() {
                try {
                    TlsServerProtocol tls = new TlsServerProtocol(s.getInputStream(), s.getOutputStream());
                    tls.accept(new AbstractTlsServer(crypto) {
                        protected ProtocolVersion[] getSupportedVersions() { return ProtocolVersion.TLSv13.only(); }
                        public TlsCredentials getCredentials() { return null; }
                        protected int[] getSupportedCipherSuites() { return new int[] { CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_CHACHA20_POLY1305_SHA256 }; }
                        public TlsPSKExternal getExternalPSK(Vector ids) {
                            for (int i = 0; i < ids.size(); i++) {
                                PskIdentity id = (PskIdentity) ids.elementAt(i);
                                if (java.util.Arrays.equals(id.getIdentity(), ID))
                                    return new BasicTlsPSKExternal(ID, getCrypto().createSecret(PSK), PRFAlgorithm.tls13_hkdf_sha256);
                            }
                            return null;
                        }
                    });
                    InputStream in = tls.getInputStream(); OutputStream out = tls.getOutputStream();
                    String head = readLineHead(in);
                    String first = head.split("\r\n")[0];
                    if (!head.contains("Proxy-Authorization: " + AUTH + "\r\n")) {
                        out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".getBytes("US-ASCII")); out.flush(); tls.close(); return;
                    }
                    String target = first.split(" ")[1];
                    int c = target.lastIndexOf(':');
                    String host = target.substring(0, c).replace("[", "").replace("]", "");
                    final Socket up = new Socket(host, Integer.parseInt(target.substring(c + 1)));
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII")); out.flush();
                    final OutputStream o2 = out; final InputStream upIn = up.getInputStream();
                    Thread back = new Thread() { public void run() { pump(upIn, o2); } };
                    back.start();
                    pump(in, up.getOutputStream());
                    back.join(10000);
                    up.close(); tls.close();
                } catch (Exception e) {
                    try { s.close(); } catch (IOException ignored) { }
                }
            } }.start();
        }
    }

    static String readLineHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) break;
            b.write(c);
            byte[] x = b.toByteArray();
            int n = x.length;
            if (n >= 4 && x[n - 4] == '\r' && x[n - 3] == '\n' && x[n - 2] == '\r' && x[n - 1] == '\n') break;
        }
        return new String(b.toByteArray(), "US-ASCII");
    }

    static void pump(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
            try { out.close(); } catch (IOException ignored) { }
        } catch (IOException ignored) { }
    }
}
