package ioniq6.cpclimate.video;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.tls.AbstractTlsClient;
import org.bouncycastle.tls.BasicTlsPSKExternal;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.PRFAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/**
 * The iPhone's proxy for CarPlay video (setProxyParameters): the head unit has no internet, so the phone
 * offers an HTTPS proxy on the CarPlay Wi-Fi. Its URL is https://[link-local]:port, reached with TLS 1.3 and an
 * external pre-shared key (proxyPsk, proxyPskIdentity); then HTTP CONNECT host:port with proxyAuthorization
 * opens a tunnel to the video's server (mib2q-carplay-rgi docs: "TLS1.3-PSK CONNECT proxy").
 *
 * Android 4.4 has no TLS 1.3, so this uses BouncyCastle's own TLS (lightweight API, pure-Java crypto).
 * {@link #open} returns the tunnel as a Socket; HTTPS servers are then reached with a second TLS over it.
 */
public final class PhoneProxy {
    static final Charset ASCII = Charset.forName("US-ASCII");
    static final int CONNECT_TIMEOUT_MS = 8000, READ_TIMEOUT_MS = 20000;

    final InetAddress address;
    final int port;
    final byte[] psk, identity;
    final String authorization;
    final TlsCrypto crypto = new BcTlsCrypto(new SecureRandom());

    public PhoneProxy(InetAddress address, int port, byte[] psk, byte[] identity, String authorization) {
        this.address = address;
        this.port = port;
        this.psk = psk.clone();
        this.identity = identity.clone();
        this.authorization = authorization;
    }

    /**
     * The ways the proxy's PSK handshake is tried, in order, until one works (then that one is used first). The
     * phone answered handshake_failure to TLS 1.3 with the key bound to SHA-256 (2026-11-10); a TLS 1.3 external
     * PSK only matches when the server binds it to the same hash, and Apple's servers here pick SHA-384 suites.
     */
    static final int TRY_13_SHA256_AND_12 = 0, TRY_13_SHA384 = 1, TRY_12 = 2, TRIES = 3;
    static volatile int working = -1;

    static String name(int variant) {
        return variant == TRY_13_SHA256_AND_12 ? "TLS 1.3 (SHA-256 PSK) or 1.2 PSK"
                : variant == TRY_13_SHA384 ? "TLS 1.3 (SHA-384 PSK)" : "TLS 1.2 PSK only";
    }

    /** a tunnel to host:port through the phone (TLS PSK to the proxy, then CONNECT) */
    public Socket open(String host, int targetPort) throws IOException {
        int first = working;
        IOException last = null;
        for (int k = 0; k < TRIES + (first >= 0 ? 1 : 0); k++) {
            int variant = first >= 0 ? (k == 0 ? first : k - 1) : k;
            if (first >= 0 && k > 0 && variant == first) continue;
            try {
                Socket s = open(host, targetPort, variant);
                if (working != variant) {
                    working = variant;
                    Video.log("video: phone proxy handshake works with " + name(variant) + ": " + lastHandshake);
                }
                return s;
            } catch (TlsFatalAlert e) {
                last = e;
                Video.log("video: phone proxy refused " + name(variant) + ": " + e.getMessage() + " / " + lastHandshake);
            } catch (org.bouncycastle.tls.TlsFatalAlertReceived e) {
                last = e;
                Video.log("video: phone proxy refused " + name(variant) + ": " + e.getMessage() + " / " + lastHandshake);
            }
        }
        throw last != null ? last : new IOException("no handshake tried");
    }

    Socket open(String host, int targetPort, int variant) throws IOException {
        Socket raw = new Socket();
        boolean ok = false;
        lastHandshake = "";
        try {
            raw.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(READ_TIMEOUT_MS);
            raw.setTcpNoDelay(true);
            TlsClientProtocol tls = new TlsClientProtocol(raw.getInputStream(), raw.getOutputStream());
            tls.connect(new PskClient(crypto, identity, psk, variant));
            OutputStream out = tls.getOutputStream();
            InputStream in = tls.getInputStream();
            String target = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + targetPort;
            StringBuilder req = new StringBuilder();
            req.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(target).append("\r\n");
            if (authorization != null && authorization.length() > 0) req.append("Proxy-Authorization: ").append(authorization).append("\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes(ASCII));
            out.flush();
            String head = readHead(in);
            String status = head.substring(0, head.indexOf("\r\n") < 0 ? head.length() : head.indexOf("\r\n"));
            String[] parts = status.split(" ");
            if (parts.length < 2 || !parts[1].equals("200")) throw new IOException("proxy refused CONNECT " + target + ": " + status);
            ok = true;
            return new TunnelSocket(raw, tls, in, out);
        } finally {
            if (!ok) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** the response head (status line and headers, through the blank line) */
    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new IOException("proxy closed during the CONNECT reply");
            b.write(c);
            if (b.size() > 16384) throw new IOException("CONNECT reply too long");
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : (c == '\r' ? 1 : 0);
        }
        return new String(b.toByteArray(), ASCII);
    }

    /** what the last handshake chose (version and cipher suite), for the log */
    public static volatile String lastHandshake = "";

    /**
     * The phone's pre-shared key, offered every way it may expect it in one ClientHello: TLS 1.3 with it as an
     * external PSK, and TLS 1.2's PSK cipher suites (Apple's Network.framework examples use those). The phone
     * picks; no certificates are exchanged either way. (2026-11-10: TLS 1.3 alone got handshake_failure.)
     */
    static final class PskClient extends AbstractTlsClient {
        final byte[] identity, key;
        final int variant;

        PskClient(TlsCrypto crypto, byte[] identity, byte[] key, int variant) {
            super(crypto);
            this.identity = identity;
            this.key = key;
            this.variant = variant;
        }

        @Override
        protected ProtocolVersion[] getSupportedVersions() {
            return variant == TRY_13_SHA384 ? ProtocolVersion.TLSv13.only()
                    : variant == TRY_12 ? ProtocolVersion.TLSv12.only()
                    : ProtocolVersion.TLSv13.downTo(ProtocolVersion.TLSv12);
        }

        @Override
        protected int[] getSupportedCipherSuites() {
            // TLS 1.3 suites must match the PSK's hash, or BouncyCastle leaves the PSK out
            if (variant == TRY_13_SHA384) return new int[] { CipherSuite.TLS_AES_256_GCM_SHA384 };
            if (variant == TRY_12) return new int[] {
                CipherSuite.TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_PSK_WITH_AES_256_GCM_SHA384, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256,
                CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256, CipherSuite.TLS_PSK_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_PSK_WITH_CHACHA20_POLY1305_SHA256, CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256,
                CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA,
            };
            return new int[] {
                CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_CHACHA20_POLY1305_SHA256, CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_PSK_WITH_AES_256_GCM_SHA384, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256,
                CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256, CipherSuite.TLS_PSK_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_PSK_WITH_CHACHA20_POLY1305_SHA256, CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256,
                CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA,
            };
        }

        /** TLS 1.2: the PSK cipher suites' identity and key */
        @Override
        public org.bouncycastle.tls.TlsPSKIdentity getPSKIdentity() {
            return new org.bouncycastle.tls.BasicTlsPSKIdentity(identity, key);
        }

        ProtocolVersion version;
        int suite;

        @Override
        public void notifyServerVersion(ProtocolVersion v) throws IOException {
            super.notifyServerVersion(v);
            version = v;
        }

        @Override
        public void notifySelectedCipherSuite(int s) {
            super.notifySelectedCipherSuite(s);
            suite = s;
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            lastHandshake = version + " cipher 0x" + Integer.toHexString(suite);
        }

        @Override
        public void notifyAlertReceived(short level, short description) {
            lastHandshake = "alert from the phone: " + org.bouncycastle.tls.AlertDescription.getText(description) + " (offered " + version + ")";
        }

        @Override
        public Vector getExternalPSKs() {
            if (variant == TRY_12) return null;
            Vector v = new Vector();
            v.addElement(new BasicTlsPSKExternal(identity, getCrypto().createSecret(key),
                    variant == TRY_13_SHA384 ? PRFAlgorithm.tls13_hkdf_sha384 : PRFAlgorithm.tls13_hkdf_sha256));
            return v;
        }

        @Override
        public TlsAuthentication getAuthentication() throws IOException {
            // with an external PSK the server isn't authenticated by certificate; anything else is unexpected
            throw new TlsFatalAlert(AlertDescription.internal_error, "phone proxy asked for certificate authentication");
        }

    }

    /** a Socket whose streams are the tunnel (inside the proxy TLS); closing it closes the proxy connection */
    static final class TunnelSocket extends Socket {
        final Socket raw;
        final TlsClientProtocol tls;
        final InputStream in;
        final OutputStream out;

        TunnelSocket(Socket raw, TlsClientProtocol tls, InputStream in, OutputStream out) {
            this.raw = raw;
            this.tls = tls;
            this.in = in;
            this.out = out;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public synchronized void close() throws IOException {
            try {
                tls.close();
            } catch (IOException ignored) {
            }
            raw.close();
        }

        @Override
        public boolean isConnected() {
            return raw.isConnected();
        }

        @Override
        public boolean isClosed() {
            return raw.isClosed();
        }

        @Override
        public InetAddress getInetAddress() {
            return raw.getInetAddress();
        }

        @Override
        public int getPort() {
            return raw.getPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return raw.getRemoteSocketAddress();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws java.net.SocketException {
            raw.setSoTimeout(timeout);
        }

        @Override
        public synchronized int getSoTimeout() throws java.net.SocketException {
            return raw.getSoTimeout();
        }

        @Override
        public void shutdownInput() throws IOException {
        }

        @Override
        public void shutdownOutput() throws IOException {
        }
    }
}
