package ioniq6.cpclimate.video;

import java.security.KeyStore;
import java.security.cert.*;
import javax.net.ssl.*;

/** ExtraRoots alone validate real chains (letsencrypt.org, real-debrid.com, Apple's HLS CDN). usage: ExtraRootsTest */
public class ExtraRootsTest {
    static int fails;
    static void check(String w, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + w); }
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null, null);
        int i = 0;
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ExtraRoots.PEM.getBytes("US-ASCII")))) ks.setCertificateEntry("x" + i++, c);
        check("ExtraRoots parse: " + i + " certificates", i == ExtraRoots.COUNT && i > 20);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(ks);
        X509TrustManager extras = TunnelHttp.first(tmf.getTrustManagers());
        for (String host : new String[] { "letsencrypt.org", "real-debrid.com", "api.real-debrid.com", "devstreaming-cdn.apple.com" }) {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, 443);
            s.startHandshake();
            X509Certificate[] chain = (X509Certificate[]) s.getSession().getPeerCertificates();
            String auth = chain[0].getPublicKey().getAlgorithm().equals("EC") ? "ECDHE_ECDSA" : "ECDHE_RSA";
            s.close();
            boolean ok;
            try { extras.checkServerTrusted(chain, auth); ok = true; } catch (CertificateException e) { ok = false; System.out.println("     " + e.getMessage()); }
            check(host + " (issuer " + chain[chain.length - 1].getIssuerX500Principal().getName().replaceAll(",.*", "") + ") trusted by ExtraRoots alone", ok);
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
    }
}
