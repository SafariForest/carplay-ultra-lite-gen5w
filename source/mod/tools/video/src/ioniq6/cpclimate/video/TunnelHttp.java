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
 * HTTPS is a second TLS inside the tunnel: BouncyCastle's (TLS 1.2/1.3, SNI set, chain and host name checked).
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
        final Conn conn;
        final TunnelHttp owner;
        boolean closed;

        Response(int status, Map<String, List<String>> headers, String url, InputStream body, long length, Socket socket) {
            this(status, headers, url, body, length, null, null, socket);
        }

        Response(int status, Map<String, List<String>> headers, String url, InputStream body, long length, Conn conn, TunnelHttp owner, Socket socket) {
            this.status = status;
            this.headers = headers;
            this.url = url;
            this.body = body;
            this.length = length;
            this.conn = conn;
            this.owner = owner;
            this.socket = socket;
        }

        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.US));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        /** the body was read to its end and the server keeps the connection: it can carry the next request */
        boolean reusable() {
            String c = header("connection");
            if (c != null && c.toLowerCase(Locale.US).contains("close")) return false;
            if (body instanceof Limited) return ((Limited) body).left == 0;
            if (body instanceof Chunked) return ((Chunked) body).done;
            return false;
        }

        public void close() {
            if (closed) return;
            closed = true;
            if (conn != null && owner != null && reusable()) {
                owner.give(conn);
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** a connection (tunnel, TLS inside it for https) and its buffered input, kept between requests */
    static final class Conn {
        final String key;
        final Socket socket;
        final InputStream in;
        long idleSince;
        int uses;

        Conn(String key, Socket socket, InputStream in) {
            this.key = key;
            this.socket = socket;
            this.in = in;
        }
    }

    /** idle connections by scheme://host:port; each tunnel costs two handshakes (~0.5 s in the car), so reuse them */
    final Map<String, java.util.ArrayDeque<Conn>> idle = new java.util.HashMap<String, java.util.ArrayDeque<Conn>>();
    static final long IDLE_MS = 20000;
    /** idle connections kept per server: the parallel ranges use up to 4 at once (ParallelStream) */
    static final int IDLE_PER_KEY = ParallelStream.WORKERS + 1;

    Conn take(String key) {
        while (true) {
            Conn c;
            synchronized (idle) {
                java.util.ArrayDeque<Conn> q = idle.get(key);
                c = q == null ? null : q.pollLast();
            }
            if (c == null) return null;
            if (System.currentTimeMillis() - c.idleSince < IDLE_MS && !c.socket.isClosed()) return c;
            closeQuietly(c.socket);
        }
    }

    void give(Conn c) {
        c.idleSince = System.currentTimeMillis();
        Conn drop = null;
        synchronized (idle) {
            java.util.ArrayDeque<Conn> q = idle.get(c.key);
            if (q == null) idle.put(c.key, q = new java.util.ArrayDeque<Conn>());
            q.addLast(c);
            if (q.size() > IDLE_PER_KEY) drop = q.pollFirst();
        }
        if (drop != null) closeQuietly(drop.socket);
    }

    static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
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
        String key = scheme + "://" + host + ":" + port;
        Conn reused = take(key);
        if (reused != null) {
            try {
                Video.trace("http: reusing the connection to " + host + " (use " + (reused.uses + 1) + ")");
                return exchange(reused, u, url, host, position, length, extra);
            } catch (IOException e) {
                // the server closed it while idle: once more on a new connection
                Video.trace("http: kept connection failed (" + e.getMessage() + "), opening a new one");
                closeQuietly(reused.socket);
            }
        }
        Video.trace("http: tunnel to " + host + ":" + port);
        Socket s = tunnels.open(host, port);
        Video.trace("http: tunnel open");
        boolean ok = false;
        try {
            if (tls) s = secure(s, host, port);
            Response r = exchange(new Conn(key, s, new java.io.BufferedInputStream(s.getInputStream(), 65536)), u, url, host, position, length, extra);
            ok = true;
            return r;
        } finally {
            if (!ok) closeQuietly(s);
        }
    }

    /** one request and its response head on c (the body is left to the caller); c is closed if this fails */
    Response exchange(Conn c, URI u, String url, String host, long position, long length, Map<String, String> extra) throws IOException {
        Socket s = c.socket;
        c.uses++;
        boolean ok = false;
        try {
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\n");
            req.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            req.append("Accept: */*\r\nAccept-Encoding: identity\r\nConnection: keep-alive\r\n");
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
            InputStream in = c.in;
            String statusLine = line(in);
            String[] sp = statusLine.split(" ", 3);
            if (sp.length < 2 || !sp[0].startsWith("HTTP/")) throw new IOException("bad status line from " + host + ": " + statusLine);
            int status = Integer.parseInt(sp[1]);
            Video.trace("http: " + status + " from " + host);
            Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
            for (String h = line(in); h.length() > 0; h = line(in)) {
                int colon = h.indexOf(':');
                if (colon <= 0) continue;
                String k = h.substring(0, colon).trim().toLowerCase(Locale.US);
                List<String> v = headers.get(k);
                if (v == null) headers.put(k, v = new ArrayList<String>());
                v.add(h.substring(colon + 1).trim());
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
            if (len == 0 && body instanceof Limited) Video.trace("http: empty body");
            ok = true;
            return new Response(status, headers, url, body, len, c, this, s);
        } finally {
            if (!ok) closeQuietly(s);
        }
    }

    static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /**
     * TLS to host inside the tunnel, with BouncyCastle's TLS (1.2 and 1.3) on the tunnel's streams: SNI set, the
     * server's chain checked against the system's roots or ExtraRoots, its name checked against host.
     * (2026-11-11: Android 4.4's own TLS can't run over a tunnel: OpenSSLSocketImpl uses the wrapped socket's file
     * descriptor, which a tunnel doesn't have, and failed with "Socket closed". The JDK's worked in host tests.)
     */
    static Socket secure(Socket tunnel, final String host, int port) throws IOException {
        final X509TrustManager tm;
        try {
            tm = trust();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("TLS setup: " + e);
        }
        final org.bouncycastle.tls.TlsClientProtocol tls = new org.bouncycastle.tls.TlsClientProtocol(tunnel.getInputStream(), tunnel.getOutputStream());
        final boolean literal = isAddress(host);
        final org.bouncycastle.tls.DefaultTlsClient client = new org.bouncycastle.tls.DefaultTlsClient(
                new org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto(RANDOM)) {
            @Override
            protected java.util.Vector getSNIServerNames() {
                if (literal) return null;
                java.util.Vector<org.bouncycastle.tls.ServerName> v = new java.util.Vector<org.bouncycastle.tls.ServerName>();
                v.add(new org.bouncycastle.tls.ServerName(org.bouncycastle.tls.NameType.host_name, host.getBytes(ASCII)));
                return v;
            }

            @Override
            public org.bouncycastle.tls.TlsAuthentication getAuthentication() {
                return new org.bouncycastle.tls.TlsAuthentication() {
                    @Override
                    public void notifyServerCertificate(org.bouncycastle.tls.TlsServerCertificate sc) throws IOException {
                        X509Certificate[] chain = chain(sc.getCertificate());
                        try {
                            String key = chain[0].getPublicKey().getAlgorithm();
                            tm.checkServerTrusted(chain, "EC".equals(key) || "ECDSA".equals(key) ? "ECDHE_ECDSA" : "ECDHE_RSA");
                        } catch (CertificateException e) {
                            throw new IOException("certificate of " + host + " not trusted: " + e.getMessage());
                        }
                        if (!nameMatches(host, chain[0])) throw new IOException("certificate isn't for " + host);
                    }

                    @Override
                    public org.bouncycastle.tls.TlsCredentials getClientCredentials(org.bouncycastle.tls.CertificateRequest r) {
                        return null;
                    }
                };
            }
        };
        Video.trace("tls: handshake with " + host);
        onBigStack("cp-climate-tls", new IoTask() {
            @Override
            public void run() throws IOException {
                tls.connect(client);
            }
        }, tls);
        Video.trace("tls: " + host + " ok");
        return new PhoneProxy.TunnelSocket(tunnel, tls, tls.getInputStream(), tls.getOutputStream());
    }

    interface IoTask {
        void run() throws IOException;
    }

    /**
     * runs task on a thread with a 4 MB stack and waits for it: BouncyCastle's handshakes (pure Java big-number and
     * curve math) run deeper than the small stacks of ExoPlayer's loader threads on Android 4.4. Any error, even an
     * Error, comes back as an IOException, so it fails one request instead of the process.
     */
    static void onBigStack(String name, final IoTask task, final Object toClose) throws IOException {
        final Throwable[] failed = new Throwable[1];
        Thread t = new Thread(null, new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable e) {
                    failed[0] = e;
                }
            }
        }, name, 4L << 20);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            // ExoPlayer cancelled the load: close the connection so the handshake ends too
            try {
                if (toClose instanceof org.bouncycastle.tls.TlsProtocol) ((org.bouncycastle.tls.TlsProtocol) toClose).close();
            } catch (Throwable ignored) {
            }
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("cancelled during the TLS handshake");
        }
        Throwable e = failed[0];
        if (e == null) return;
        if (e instanceof IOException) throw (IOException) e;
        Video.trace(name + " failed: " + stack(e));
        throw new IOException(name + ": " + e, e);
    }

    static String stack(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    static X509Certificate[] chain(org.bouncycastle.tls.Certificate c) throws IOException {
        if (c == null || c.isEmpty()) throw new IOException("server sent no certificate");
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate[] out = new X509Certificate[c.getLength()];
            for (int i = 0; i < out.length; i++)
                out[i] = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(c.getCertificateAt(i).getEncoded()));
            return out;
        } catch (CertificateException e) {
            throw new IOException("bad server certificate: " + e.getMessage());
        }
    }

    static boolean isAddress(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    /** RFC 6125: the host against the certificate's DNS names (a wildcard covers one left-most label), or IP addresses; CN if no names */
    static boolean nameMatches(String host, X509Certificate cert) {
        String h = host.toLowerCase(Locale.US);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        boolean address = isAddress(h), anyDns = false;
        try {
            java.util.Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) for (List<?> san : sans) {
                int type = ((Number) san.get(0)).intValue();
                Object v = san.get(1);
                if (!(v instanceof String)) continue;
                if (type == 2) {
                    anyDns = true;
                    if (!address && dnsMatches(h, ((String) v).toLowerCase(Locale.US))) return true;
                } else if (type == 7 && address) {
                    try {
                        if (java.net.InetAddress.getByName((String) v).equals(java.net.InetAddress.getByName(h))) return true;
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (java.security.cert.CertificateParsingException e) {
            return false;
        }
        if (anyDns || address) return false;
        String dn = cert.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return dnsMatches(h, p.substring(3).toLowerCase(Locale.US));
        }
        return false;
    }

    static boolean dnsMatches(String host, String pattern) {
        if (pattern.endsWith(".")) pattern = pattern.substring(0, pattern.length() - 1);
        if (!pattern.startsWith("*.")) return host.equals(pattern);
        int dot = host.indexOf('.');
        return dot > 0 && host.substring(dot + 1).equals(pattern.substring(2)) && pattern.indexOf('.', 2) > 0;
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
