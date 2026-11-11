package ioniq6.cpclimate.video;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A small HTTP/1.1 client whose connections go through the phone (PhoneProxy tunnels): GET with an optional
 * byte range, redirects followed, Content-Length / chunked / until-close bodies, one connection per request.
 * HTTPS is a second TLS inside the tunnel: Android's own (TLS 1.2 turned on, SNI set, host name checked).
 */
public final class TunnelHttp {
    static final Charset ASCII = Charset.forName("US-ASCII");
    static final int MAX_REDIRECTS = 5;
    static final String USER_AGENT = "AppleCoreMedia/1.0.0.24B119 (iPhone; U; CPU OS 27_2 like Mac OS X; en_us)";

    /** how a tunnel is opened (PhoneProxy in the car, a plain socket in tests) */
    public interface Tunnels {
        Socket open(String host, int port) throws IOException;
    }

    final Tunnels tunnels;

    public TunnelHttp(Tunnels tunnels) {
        this.tunnels = tunnels;
    }

    /** an open response: status, headers (lower-case names), the final URL and the body stream */
    public static final class Response {
        public final int status;
        public final Map<String, List<String>> headers;
        public final String url;
        public final InputStream body;
        /** the body's length, or -1 if unknown */
        public final long length;
        final Socket socket;

        Response(int status, Map<String, List<String>> headers, String url, InputStream body, long length, Socket socket) {
            this.status = status;
            this.headers = headers;
            this.url = url;
            this.body = body;
            this.length = length;
            this.socket = socket;
        }

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.US));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** GET url; position/length a byte range (length -1: to the end; position 0 and -1: no Range header) */
    public Response get(String url, long position, long length, Map<String, String> extra) throws IOException {
        String at = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Response r = once(at, position, length, extra);
            if (r.status >= 300 && r.status < 400 && r.status != 304 && r.header("location") != null) {
                String next = URI.create(at).resolve(r.header("location").trim()).toString();
                r.close();
                at = next;
                continue;
            }
            return r;
        }
        throw new IOException("too many redirects for " + url);
    }

    Response once(String url, long position, long length, Map<String, String> extra) throws IOException {
        URI u = URI.create(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IOException("not http(s): " + scheme);
        boolean tls = scheme.equals("https");
        String host = u.getHost();
        if (host == null) throw new IOException("no host in " + url);
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s = tunnels.open(host, port);
        boolean ok = false;
        try {
            if (tls) s = secure(s, host, port);
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\n");
            req.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            req.append("Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
            if (position > 0 || length >= 0) {
                req.append("Range: bytes=").append(position).append('-');
                if (length >= 0) req.append(position + length - 1);
                req.append("\r\n");
            }
            if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("host") || k.equalsIgnoreCase("range") || k.equalsIgnoreCase("connection")) continue;
                req.append(k).append(": ").append(e.getValue()).append("\r\n");
            }
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(ASCII));
            out.flush();
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 65536);
            String statusLine = line(in);
            String[] sp = statusLine.split(" ", 3);
            if (sp.length < 2 || !sp[0].startsWith("HTTP/")) throw new IOException("bad status line from " + host + ": " + statusLine);
            int status = Integer.parseInt(sp[1]);
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            for (String h = line(in); h.length() > 0; h = line(in)) {
                int c = h.indexOf(':');
                if (c <= 0) continue;
                String k = h.substring(0, c).trim().toLowerCase(Locale.US);
                List<String> v = headers.get(k);
                if (v == null) headers.put(k, v = new ArrayList<String>());
                v.add(h.substring(c + 1).trim());
            }
            List<String> te = headers.get("transfer-encoding");
            List<String> cl = headers.get("content-length");
            long len = -1;
            InputStream body;
            if (te != null && te.get(0).toLowerCase(Locale.US).contains("chunked")) {
                body = new Chunked(in);
            } else if (cl != null) {
                len = Long.parseLong(cl.get(0).trim());
                body = new Limited(in, len);
            } else {
                body = in;
            }
            ok = true;
            return new Response(status, headers, url, body, len, s);
        } finally {
            if (!ok) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** TLS to host inside the tunnel: the platform's TLS (1.2 turned on), SNI set, trusted by the system's roots or ExtraRoots */
    static Socket secure(Socket tunnel, String host, int port) throws IOException {
        SSLSocket ssl;
        boolean android = isAndroid();
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { trust() }, null);
            ssl = (SSLSocket) ctx.getSocketFactory().createSocket(tunnel, host, port, true);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("TLS setup: " + e);
        }
        // Android 4.4 supports TLS 1.1/1.2 but leaves them off for client sockets
        List<String> want = new ArrayList<String>();
        for (String p : ssl.getSupportedProtocols()) if (p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1.3")) want.add(p);
        if (!want.isEmpty()) ssl.setEnabledProtocols(want.toArray(new String[0]));
        try {
            if (android) {
                // SNI: Android's OpenSSLSocketImpl.setHostname
                ssl.getClass().getMethod("setHostname", String.class).invoke(ssl, host);
            } else {
                // host tests: the JDK sends SNI and checks the host name itself (by reflection: neither exists on Android 4.4)
                javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
                p.getClass().getMethod("setEndpointIdentificationAlgorithm", String.class).invoke(p, "HTTPS");
                ssl.setSSLParameters(p);
            }
        } catch (Exception e) {
            if (!android) throw new IOException("host name checking unavailable: " + e);
        }
        ssl.startHandshake();
        // Android's default verifier checks the certificate's names (the JDK's always says no; it checked above)
        HostnameVerifier v = HttpsURLConnection.getDefaultHostnameVerifier();
        if (android && !v.verify(host, ssl.getSession())) {
            ssl.close();
            throw new IOException("certificate isn't for " + host);
        }
        return ssl;
    }

    static boolean isAndroid() {
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static volatile X509TrustManager trust;

    /** the system's trust, and ExtraRoots for servers whose root is newer than the system's list */
    static synchronized X509TrustManager trust() throws java.security.GeneralSecurityException, IOException {
        if (trust != null) return trust;
        TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sys.init((KeyStore) null);
        final X509TrustManager system = first(sys.getTrustManagers());
        KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
        extra.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int i = 0;
        for (java.security.cert.Certificate c : cf.generateCertificates(new java.io.ByteArrayInputStream(ExtraRoots.PEM.getBytes(ASCII))))
            extra.setCertificateEntry("extra" + i++, c);
        TrustManagerFactory ex = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        ex.init(extra);
        final X509TrustManager extras = first(ex.getTrustManagers());
        trust = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("no client certificates here");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, auth);
                } catch (CertificateException e) {
                    extras.checkServerTrusted(chain, auth);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        };
        return trust;
    }

    static X509TrustManager first(TrustManager[] tms) {
        for (TrustManager t : tms) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (b.size() == 0) throw new IOException("connection closed");
                break;
            }
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("header line too long");
        }
        return new String(b.toByteArray(), ASCII);
    }

    /** a body of a known length */
    static final class Limited extends InputStream {
        final InputStream in;
        long left;

        Limited(InputStream in, long left) {
            this.in = in;
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("connection closed with " + left + " bytes to go");
            left -= n;
            return n;
        }
    }

    /** a chunked body */
    static final class Chunked extends InputStream {
        final InputStream in;
        long left;
        boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (left == 0) {
                String size = line(in);
                int semi = size.indexOf(';');
                left = Long.parseLong((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
                if (left == 0) {
                    while (line(in).length() > 0) {
                    }
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("connection closed inside a chunk");
            left -= n;
            if (left == 0) line(in);
            return n;
        }
    }
}
