import java.io.*;
import java.net.*;
import ioniq6.cpclimate.video.TunnelHttp;

/** TunnelHttp against real servers, the tunnel replaced by a direct connection. usage: TunnelHttpTest */
public class TunnelHttpTest {
    static int fails;
    static void check(String w, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + w); }
    static byte[] all(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] x = new byte[16384]; int n; while ((n = in.read(x)) > 0) b.write(x, 0, n); return b.toByteArray(); }
    public static void main(String[] a) throws Exception {
        TunnelHttp http = new TunnelHttp(new TunnelHttp.Tunnels() { public Socket open(String h, int p) throws IOException { Socket s = new Socket(); s.connect(new InetSocketAddress(h, p), 8000); return s; } });
        String master = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8";
        TunnelHttp.Response r = http.get(master, 0, -1, null);
        String body = new String(all(r.body), "UTF-8"); r.close();
        check("HLS master playlist: status " + r.status + ", " + body.length() + " chars", r.status == 200 && body.startsWith("#EXTM3U"));
        String first = null; for (String l : body.split("\n")) if (!l.startsWith("#") && l.trim().length() > 0) { first = l.trim(); break; }
        String media = URI.create(master).resolve(first).toString();
        r = http.get(media, 0, -1, null); String pl = new String(all(r.body), "UTF-8"); r.close();
        check("media playlist " + first + ": " + r.status, r.status == 200 && pl.contains("#EXTINF"));
        String seg = null; for (String l : pl.split("\n")) if (!l.startsWith("#") && l.trim().length() > 0) { seg = l.trim(); break; }
        String segUrl = URI.create(media).resolve(seg).toString();
        r = http.get(segUrl, 100, 1000, null); byte[] part = all(r.body); r.close();
        check("segment byte range 100+1000: status " + r.status + ", " + part.length + " bytes", r.status == 206 && part.length == 1000);
        r = http.get("http://apple.com/", 0, -1, null); all(r.body); r.close();
        check("redirect followed: http://apple.com/ ends at " + r.url + " (" + r.status + ")", r.status == 200 && r.url.startsWith("https://"));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
    }
}
