package ioniq6.cpclimate;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CarPlay video, stage 2: the phone's playback messages. The native /command hook (hooks.c
 * cpc_command_hook) answers them 200 instead of DiPO's 451 and passes each one here as a datagram
 * on 127.0.0.1:PORT: "CPV1", the X-Apple-StreamID (8 bytes, big-endian), the request body. The body
 * is a plist {params: {data: bplist(message)}}; the message is what DiPlay's VideoInCar reads
 * (type insertPlayQueueItem, setRate, seek, stop, getProperty, ...). Content URLs are cut to their
 * host in the log: they can carry tokens.
 *
 * Stage 3: requests (kind=request: playbackInfo, seek, property) are answered the way DiPlay's
 * VideoInCar answers them (from Apple's AirPlay web app), with no player yet: playbackInfo says
 * "loading". Each answer goes to the native sender (hooks.c video_sender) as a datagram on
 * 127.0.0.1:OUT_PORT: "CPV2", the stream id (8 bytes, big-endian), the body {params: {data: bplist}}.
 */
final class VideoMessages extends Thread {
    static final int PORT = 7136, OUT_PORT = 7137;
    static final int MAX_SHOWN = 600;

    final VsChannel.Log out;
    int count, answered;
    /** the uuid of the item the phone queued last (insertPlayQueueItem) */
    Object itemUuid;
    DatagramSocket answers;

    VideoMessages(VsChannel.Log out) {
        super("cp-climate-video-messages");
        this.out = out;
        setDaemon(true);
        instance = this;
    }

    // ---- the video engine (tools/video, merged into hooks.jar by build.sh; absent in a plain build) ----

    static volatile VideoMessages instance;
    static volatile Method engineMessage, engineShow;

    /** loads the engine if this build has it: Video.init(context); false if not */
    static boolean startEngine(Object context) {
        try {
            Class<?> v = Class.forName("ioniq6.cpclimate.video.Video", true, VideoMessages.class.getClassLoader());
            v.getMethod("init", android.content.Context.class).invoke(null, context);
            engineMessage = v.getMethod("onMessage", long.class, Map.class);
            engineShow = v.getMethod("onShowRequested");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            VsChannel.Log o = instance != null ? instance.out : null;
            if (o != null) o.log("video: engine not started: " + t);
            return false;
        }
    }

    /** the engine's answer or notification to the phone */
    static void engineSend(long sid, Map<String, Object> message) {
        VideoMessages v = instance;
        if (v != null) v.sendAnswer(sid, message);
    }

    static void engineLog(String m) {
        VideoMessages v = instance;
        if (v != null) v.out.log(m);
    }

    @Override
    public void run() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            out.log("video: playback messages listening on 127.0.0.1:" + PORT + " (experiment)");
            byte[] buf = new byte[65536];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                try {
                    int n = p.getLength();
                    ++count;
                    Map<?, ?> m = message(buf, n);
                    // playbackInfo comes every second: logged for the first few, then every 30th
                    boolean info = m != null && "playbackInfo".equals(m.get("type"));
                    if (!info || count < 10 || count % 30 == 0) out.log(describe(buf, n, count));
                    if (m != null) {
                        Method e = engineMessage;
                        if (e != null) e.invoke(null, sid(buf), m);
                        else answer(sid(buf), m);
                    }
                } catch (Exception e) {
                    out.log("video: message " + count + " not read: " + e);
                }
            }
        } catch (Throwable t) {
            out.log("video: playback messages listener stopped: " + t);
        }
    }

    static long sid(byte[] b) {
        long sid = 0;
        for (int i = 4; i < 12; i++) sid = sid << 8 | (b[i] & 0xff);
        return sid;
    }

    /** the phone's message in a datagram from the native hook, or null */
    static Map<?, ?> message(byte[] b, int n) {
        if (n < 12 || b[0] != 'C' || b[1] != 'P' || b[2] != 'V' || b[3] != '1') return null;
        byte[] body = new byte[n - 12];
        System.arraycopy(b, 12, body, 0, body.length);
        Object outer = Bplist.decode(body);
        Object data = outer instanceof Map ? ((Map<?, ?>) outer).get("params") : null;
        data = data instanceof Map ? ((Map<?, ?>) data).get("data") : null;
        if (!(data instanceof byte[])) return null;
        Object m = Bplist.decode((byte[]) data);
        return m instanceof Map ? (Map<?, ?>) m : null;
    }

    /** answers the phone's requests (and remembers its item) */
    void answer(long sid, Map<?, ?> m) {
        Object type = m.get("type");
        if ("insertPlayQueueItem".equals(type) && m.get("item") instanceof Map) itemUuid = ((Map<?, ?>) m.get("item")).get("uuid");
        Map<String, Object> reply = reply(m, itemUuid);
        if (reply == null) return;
        sendAnswer(sid, reply);
    }

    /**
     * One message to the phone through the native sender. Queued and sent from a thread of its own: the engine
     * answers from the main thread (ExoPlayer's looper), where Android refuses sockets
     * (NetworkOnMainThreadException, 2026-11-10).
     */
    void sendAnswer(long sid, Map<String, Object> reply) {
        synchronized (outbox) {
            if (sender == null) {
                sender = new Thread("cp-climate-video-answers") {
                    @Override
                    public void run() {
                        while (true) {
                            Object[] next;
                            try {
                                next = outbox.take();
                            } catch (InterruptedException e) {
                                return;
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) next[1];
                            sendNow((Long) next[0], m);
                        }
                    }
                };
                sender.setDaemon(true);
                sender.start();
            }
        }
        if (!outbox.offer(new Object[] { sid, reply })) out.log("video: answer dropped, queue full");
    }

    final java.util.concurrent.LinkedBlockingQueue<Object[]> outbox = new java.util.concurrent.LinkedBlockingQueue<Object[]>(256);
    Thread sender;

    private void sendNow(long sid, Map<String, Object> reply) {
        try {
            if (answers == null) answers = new DatagramSocket();
            byte[] d = datagram(sid, reply);
            answers.send(new DatagramPacket(d, d.length, InetAddress.getByName("127.0.0.1"), OUT_PORT));
            if (++answered <= 10 || answered % 30 == 0) out.log("video: answer " + answered + " (" + reply.get("type") + ", messageID " + reply.get("messageID") + ") to stream " + sid);
        } catch (Exception e) {
            out.log("video: answer not sent: " + e);
        }
    }

    /** "CPV2", the stream id, the event-channel body {params: {data: bplist(answer)}} */
    static byte[] datagram(long sid, Map<String, Object> answer) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("data", Bplist.encode(answer));
        Map<String, Object> outer = new LinkedHashMap<String, Object>();
        outer.put("params", params);
        byte[] body = Bplist.encode(outer);
        byte[] d = new byte[12 + body.length];
        d[0] = 'C'; d[1] = 'P'; d[2] = 'V'; d[3] = '2';
        for (int i = 0; i < 8; i++) d[4 + i] = (byte) (sid >>> (56 - 8 * i));
        System.arraycopy(body, 0, d, 12, body.length);
        return d;
    }

    /**
     * The answer to a request (DiPlay VideoInCar playbackInfoResponse / seekResponse / propertyResponse),
     * with no player: loading, nothing seekable or loaded. Null for messages that aren't requests.
     */
    static Map<String, Object> reply(Map<?, ?> m, Object itemUuid) {
        if (!"request".equals(m.get("kind"))) return null;
        Object type = m.get("type"), id = m.get("messageID");
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        if ("playbackInfo".equals(type)) {
            Map<String, Object> info = new LinkedHashMap<String, Object>();
            if (itemUuid != null) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("uuid", itemUuid);
                info.put("item", item);
            }
            info.put("rate", 0.0);
            info.put("readyToPlay", Boolean.FALSE);
            info.put("playbackLikelyToKeepUp", Boolean.FALSE);
            info.put("droppedVideoFrames", 0L);
            info.put("totalVideoFrames", 0L);
            info.put("decodedFrameCount", 0L);
            info.put("playbackState", "loading");
            info.put("interstitialInfo", new LinkedHashMap<String, Object>());
            r.put("info", info);
            r.put("kind", "response");
            r.put("type", "playbackInfo");
        } else if ("seek".equals(type)) {
            r.put("type", "seek");
            r.put("kind", "response");
        } else if ("property".equals(type) || "getProperty".equals(type)) {
            Object key = m.get("key");
            if (key == null) key = m.get("property");
            if (key != null) r.put("key", key);
            if ("hasEnabledAudio".equals(key)) r.put("value", Boolean.TRUE);
            else if ("muted".equals(key)) r.put("value", Boolean.FALSE);
            r.put("kind", "response");
            r.put("type", "property");
        } else {
            // a request this player doesn't know: an empty answer, so the phone doesn't wait on it
            r.put("type", type);
            r.put("kind", "response");
        }
        if (id != null) r.put("messageID", id);
        return r;
    }

    /** one log line for a datagram from the native hook */
    static String describe(byte[] b, int n, int index) {
        if (n < 12 || b[0] != 'C' || b[1] != 'P' || b[2] != 'V' || b[3] != '1') return "video: message " + index + ": not ours (" + n + " bytes)";
        long sid = 0;
        for (int i = 4; i < 12; i++) sid = sid << 8 | (b[i] & 0xff);
        byte[] body = new byte[n - 12];
        System.arraycopy(b, 12, body, 0, body.length);
        StringBuilder sb = new StringBuilder("video: message " + index + " on stream " + sid + " (" + body.length + " B): ");
        Object outer = Bplist.decode(body);
        Object data = outer instanceof Map ? ((Map<?, ?>) outer).get("params") : null;
        data = data instanceof Map ? ((Map<?, ?>) data).get("data") : null;
        if (!(data instanceof byte[])) {
            sb.append("no params.data; ").append(show(outer, 0));
        } else {
            sb.append(show(Bplist.decode((byte[]) data), 0));
        }
        String s = sb.toString();
        return s.length() > MAX_SHOWN ? s.substring(0, MAX_SHOWN) + "..." : s;
    }

    /** a short, sorted, one-line view of a decoded plist value */
    static String show(Object v, int depth) {
        if (v instanceof Map) {
            if (depth > 3) return "{...}";
            List<String> keys = new ArrayList<String>();
            for (Object k : ((Map<?, ?>) v).keySet()) keys.add(String.valueOf(k));
            Collections.sort(keys);
            // the type first: it says what the message is
            if (keys.remove("type")) keys.add(0, "type");
            StringBuilder sb = new StringBuilder("{");
            for (String k : keys) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(k).append('=').append(showValue(k, ((Map<?, ?>) v).get(k), depth));
            }
            return sb.append('}').toString();
        }
        if (v instanceof List) {
            if (depth > 3) return "[...]";
            StringBuilder sb = new StringBuilder("[");
            for (Object o : (List<?>) v) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(show(o, depth + 1));
            }
            return sb.append(']').toString();
        }
        if (v instanceof byte[]) return "<" + ((byte[]) v).length + " bytes>";
        return String.valueOf(v);
    }

    static String showValue(String key, Object v, int depth) {
        if (v instanceof String && isUrl((String) v)) return urlHost((String) v);
        return show(v, depth + 1);
    }

    static boolean isUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** scheme://host/... : enough to see where the video comes from, without paths or tokens */
    static String urlHost(String u) {
        int start = u.indexOf("://") + 3;
        int end = start;
        while (end < u.length() && u.charAt(end) != '/' && u.charAt(end) != '?' && u.charAt(end) != '#') end++;
        return u.substring(0, end) + (end < u.length() ? "/..." : "");
    }
}
