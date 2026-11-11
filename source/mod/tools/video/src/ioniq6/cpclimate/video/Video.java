package ioniq6.cpclimate.video;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.video.VideoSize;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CarPlay video in the car (stage 4+): the phone's playback messages drive an ExoPlayer that fetches the
 * media through the phone's proxy (PhoneProxy, TunnelHttp) and shows it full screen over the head unit.
 * Answers and notifications follow DiPlay's VideoInCar (from Apple's AirPlay web app).
 *
 * Called by cp-climate's core by reflection (it is built without this module): init, onMessage, onShowRequested.
 * Calls back the same way: VideoMessages.engineSend / engineLog, VideoUi.engineClosed. Everything that
 * touches the player or the window runs on the main thread.
 */
public final class Video {
    static final int ERROR_NETWORK = -17221, ERROR_DECODER = -12911, ERROR_INCOMPATIBLE_ASSET = -12927;
    static final String FILES = "/data/data/com.daudio.app.dipo/files/";

    static Context context;
    static Handler main;
    static ExoPlayer player;
    static FrameLayout window;
    static SurfaceView surface;
    static TextView status;
    static FrameLayout controls;
    static Object itemUuid;
    static long streamId = -1;
    static boolean shown;
    static String currentUrl;

    // ---- entry points (reflection from the core) ----

    public static void init(Context ctx) {
        context = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        main = new Handler(Looper.getMainLooper());
        log("video engine ready (ExoPlayer 2.19.1, proxy through the phone)");
    }

    /** a playback message from the phone (insertPlayQueueItem, setRate, seek, playbackInfo, ...) */
    public static void onMessage(final long sid, final Map<?, ?> m) {
        if (main == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(sid, m);
                } catch (Throwable t) {
                    log("video: message " + m.get("type") + " failed: " + t);
                }
            }
        });
    }

    /** the phone asked the car to show the video (requestUI videoplayback:) */
    public static void onShowRequested() {
        if (main == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                show();
            }
        });
    }

    // ---- the phone's messages ----

    static void handle(long sid, Map<?, ?> m) {
        streamId = sid;
        Object type = m.get("type");
        if ("insertPlayQueueItem".equals(type)) {
            insert(m);
        } else if ("setRate".equals(type)) {
            double rate = number(m.get("rate"), 0);
            if (player != null) player.setPlayWhenReady(rate > 0);
        } else if ("seek".equals(type)) {
            Long ms = cmMillis(m.get("time"));
            if (player != null && ms != null) player.seekTo(ms);
        } else if ("stop".equals(type) || "removePlayQueueItem".equals(type)) {
            stop("phone: " + type);
        }
        if ("request".equals(m.get("kind"))) send(answer(m));
    }

    static void insert(Map<?, ?> m) {
        Object item = m.get("item");
        if (!(item instanceof Map)) return;
        Map<?, ?> it = (Map<?, ?>) item;
        itemUuid = it.get("uuid");
        Object loc = it.get("Content-Location");
        String url = loc instanceof String ? (String) loc : null;
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            log("video: item " + itemUuid + " isn't http(s) (" + (url == null ? "no URL" : url.substring(0, Math.min(12, url.length()))) + "), can't play it");
            send(error(ERROR_INCOMPATIBLE_ASSET));
            return;
        }
        PhoneProxy proxy = proxy();
        if (proxy == null) {
            send(error(ERROR_NETWORK));
            return;
        }
        long startMs = 0;
        Object sps = it.get("Start-Position-Seconds");
        if (sps instanceof Number) startMs = (long) (((Number) sps).doubleValue() * 1000);
        else if (cmMillis(it.get("Start-Position")) != null) startMs = cmMillis(it.get("Start-Position"));
        final PhoneProxy p = proxy;
        TunnelHttp http = new TunnelHttp(new TunnelHttp.Tunnels() {
            @Override
            public Socket open(String host, int port) throws IOException {
                return p.open(host, port);
            }
        });
        TunnelDataSource.Factory data = new TunnelDataSource.Factory(http);
        // HLS only for playlists; everything else (MP4, MKV, WebM, TS, ...: Real-Debrid's files) is read as a file
        String lower = url.toLowerCase(java.util.Locale.US);
        String path = Uri.parse(url).getPath() == null ? "" : Uri.parse(url).getPath().toLowerCase(java.util.Locale.US);
        boolean progressive = !(path.endsWith(".m3u8") || path.endsWith(".m3u") || lower.contains(".m3u8?") || lower.contains("format=m3u8"));
        MediaItem media = MediaItem.fromUri(url);
        MediaSource source = progressive ? new ProgressiveMediaSource.Factory(data).createMediaSource(media)
                : new HlsMediaSource.Factory(data).setAllowChunklessPreparation(true).createMediaSource(media);
        release();
        player = new ExoPlayer.Builder(context, new com.google.android.exoplayer2.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setLooper(Looper.getMainLooper()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        player.addListener(listener);
        player.setMediaSource(source, startMs);
        player.setPlayWhenReady(false);
        player.prepare();
        currentUrl = url;
        if (surface != null) player.setVideoSurfaceView(surface);
        log("video: playing " + host(url) + " (" + (progressive ? "progressive" : "HLS") + ", from " + startMs / 1000 + " s) through the phone");
    }

    static final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("type", "playbackState");
            n.put("name", isPlaying ? "playing" : "paused");
            if (isPlaying && itemUuid != null) n.put("item", dict("uuid", itemUuid));
            send(n);
            updateStatus();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            log("video: player " + (state == Player.STATE_BUFFERING ? "buffering" : state == Player.STATE_READY ? "ready"
                    : state == Player.STATE_ENDED ? "ended" : "idle"));
            updateStatus();
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            Throwable c = e.getCause();
            log("video: player error " + e.getErrorCodeName() + ": " + e.getMessage() + (c != null ? " / " + c : ""));
            int code = e.errorCode >= 2000 && e.errorCode < 3000 ? ERROR_NETWORK
                    : e.errorCode >= 4000 && e.errorCode < 5000 ? ERROR_DECODER : ERROR_INCOMPATIBLE_ASSET;
            if (e.errorCode >= 6000 && e.errorCode < 7000) code = ERROR_INCOMPATIBLE_ASSET;   // DRM (FairPlay)
            send(error(code));
            updateStatus();
        }

        @Override
        public void onTracksChanged(com.google.android.exoplayer2.Tracks tracks) {
            // what the file has and what this head unit can decode (it has H.264 in hardware, no HEVC, no AC3/DTS)
            StringBuilder b = new StringBuilder("video: tracks:");
            for (com.google.android.exoplayer2.Tracks.Group g : tracks.getGroups()) {
                for (int i = 0; i < g.length; i++) {
                    com.google.android.exoplayer2.Format f = g.getTrackFormat(i);
                    b.append(' ').append(f.sampleMimeType).append(f.width > 0 ? " " + f.width + "x" + f.height : "")
                            .append(f.channelCount > 0 ? " " + f.channelCount + "ch" : "")
                            .append(g.isTrackSupported(i) ? "" : " (can't decode)").append(g.isTrackSelected(i) ? " [playing]" : "").append(';');
                }
            }
            log(b.toString());
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            layoutSurface(size.width, size.height, size.pixelWidthHeightRatio);
        }
    };

    // ---- answers ----

    static Map<String, Object> answer(Map<?, ?> m) {
        Object type = m.get("type"), id = m.get("messageID");
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        if ("playbackInfo".equals(type)) {
            r.put("info", info());
            r.put("kind", "response");
            r.put("type", "playbackInfo");
        } else if ("seek".equals(type)) {
            r.put("type", "seek");
            r.put("kind", "response");
        } else if ("property".equals(type) || "getProperty".equals(type)) {
            Object key = m.get("key") != null ? m.get("key") : m.get("property");
            if (key != null) r.put("key", key);
            boolean ready = player != null && player.getPlaybackState() == Player.STATE_READY;
            if ("hasEnabledAudio".equals(key)) r.put("value", Boolean.TRUE);
            else if ("muted".equals(key)) r.put("value", Boolean.FALSE);
            else if ("seekableTimeRanges".equals(key) && ready) r.put("value", Collections.singletonList(range(0, durationMs())));
            else if ("loadedTimeRanges".equals(key) && ready) r.put("value", Collections.singletonList(range(0, player.getBufferedPosition())));
            r.put("kind", "response");
            r.put("type", "property");
        } else {
            r.put("type", type);
            r.put("kind", "response");
        }
        if (id != null) r.put("messageID", id);
        return r;
    }

    static Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        if (itemUuid != null) info.put("item", dict("uuid", itemUuid));
        boolean ready = player != null && (player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_ENDED);
        boolean playing = player != null && player.isPlaying();
        info.put("rate", playing ? 1.0 : 0.0);
        info.put("readyToPlay", ready);
        info.put("playbackLikelyToKeepUp", ready && player.getPlaybackState() == Player.STATE_READY);
        if (ready) {
            long d = durationMs();
            info.put("position", cmTime(player.getCurrentPosition()));
            info.put("duration", cmTime(d));
            info.put("seekableTimeRanges", Collections.singletonList(range(0, d)));
            info.put("loadedTimeRanges", Collections.singletonList(range(0, player.getBufferedPosition())));
        }
        info.put("droppedVideoFrames", 0L);
        info.put("totalVideoFrames", 0L);
        info.put("decodedFrameCount", 0L);
        info.put("playbackState", !ready ? "loading" : playing ? "playing" : "paused");
        info.put("interstitialInfo", new LinkedHashMap<String, Object>());
        return info;
    }

    static long durationMs() {
        long d = player == null ? C.TIME_UNSET : player.getDuration();
        return d == C.TIME_UNSET ? 0 : d;
    }

    static Map<String, Object> error(int code) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("type", "error");
        e.put("error", dict("domain", "", "code", (long) code));
        if (itemUuid != null) e.put("uuid", itemUuid);
        return e;
    }

    static Map<String, Object> cmTime(long ms) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("value", ms);
        t.put("timescale", 1000L);
        t.put("flags", 1L);
        t.put("epoch", 0L);
        return t;
    }

    static Map<String, Object> range(long startMs, long durMs) {
        return dict("start", cmTime(startMs), "duration", cmTime(durMs));
    }

    static Map<String, Object> dict(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static Long cmMillis(Object t) {
        if (!(t instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) t;
        double value = number(m.get("value"), Double.NaN), scale = number(m.get("timescale"), 0);
        if (Double.isNaN(value) || scale <= 0) return null;
        return (long) (value * 1000 / scale);
    }

    static double number(Object o, double otherwise) {
        return o instanceof Number ? ((Number) o).doubleValue() : otherwise;
    }

    // ---- the screen ----

    static void show() {
        if (context == null) return;
        if (window == null) {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            window = new FrameLayout(context);
            window.setBackgroundColor(Color.BLACK);
            surface = new SurfaceView(context);
            window.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            controls = new FrameLayout(context);
            TextView close = button("✕  Back to CarPlay");
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide("back button");
                }
            });
            controls.addView(close, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
            TextView playPause = button("⏯  Play / Pause");
            playPause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
                }
            });
            controls.addView(playPause, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            status = new TextView(context);
            status.setTextColor(Color.WHITE);
            status.setTextSize(20);
            status.setPadding(24, 24, 24, 24);
            controls.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT));
            window.addView(controls, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            window.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    return true;
                }
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setTitle("cp-climate video");
            wm.addView(window, lp);
            if (player != null) player.setVideoSurfaceView(surface);
        }
        window.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        shown = true;
        if (player != null) {
            player.setPlayWhenReady(true);
            VideoSize s = player.getVideoSize();
            if (s.width > 0) layoutSurface(s.width, s.height, s.pixelWidthHeightRatio);
        }
        updateStatus();
        log("video: shown full screen");
    }

    static TextView button(String text) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(24);
        b.setBackgroundColor(0x99000000);
        b.setPadding(32, 24, 32, 24);
        return b;
    }

    static void layoutSurface(int w, int h, float par) {
        if (surface == null || window == null || w <= 0 || h <= 0) return;
        int W = window.getWidth(), H = window.getHeight();
        if (W <= 0 || H <= 0) {
            final int fw = w, fh = h;
            final float fp = par;
            window.post(new Runnable() {
                @Override
                public void run() {
                    if (window.getWidth() > 0) layoutSurface(fw, fh, fp);
                }
            });
            return;
        }
        double aspect = w * (double) par / h;
        int vw = W, vh = (int) Math.round(W / aspect);
        if (vh > H) {
            vh = H;
            vw = (int) Math.round(H * aspect);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    static void updateStatus() {
        if (status == null) return;
        String s = player == null ? "No video" : player.getPlayerError() != null ? "Can't play this video"
                : player.getPlaybackState() == Player.STATE_BUFFERING ? "Loading…" : player.isPlaying() ? "" : "Paused";
        status.setText(s);
    }

    /** back to CarPlay: video paused, window hidden, the screen given back */
    static void hide(String why) {
        if (player != null) player.setPlayWhenReady(false);
        if (window != null) window.setVisibility(View.GONE);
        shown = false;
        log("video: hidden (" + why + ")");
        callCore("ioniq6.cpclimate.VideoUi", "engineClosed", new Class<?>[0]);
    }

    static void stop(String why) {
        release();
        if (shown) hide(why);
        itemUuid = null;
        currentUrl = null;
    }

    static void release() {
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
    }

    // ---- the phone's proxy ----

    /** the proxy from setProxyParameters (saved by the native /command hook), or null */
    static PhoneProxy proxy() {
        try {
            File f = new File(FILES + "cpc-video-proxy.bplist");
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int at = 0;
                while (at < b.length) {
                    int n = in.read(b, at, b.length - at);
                    if (n < 0) break;
                    at += n;
                }
            } finally {
                in.close();
            }
            Object outer = callCore("ioniq6.cpclimate.Bplist", "decode", new Class<?>[] { byte[].class }, b);
            Map<?, ?> top = outer instanceof Map ? (Map<?, ?>) outer : null;
            Map<?, ?> params = top != null && top.get("params") instanceof Map ? (Map<?, ?>) top.get("params") : top;
            if (params == null) throw new IOException("no parameters");
            String url = String.valueOf(params.get("proxyUrl"));
            int port = (int) number(params.get("proxyPort"), 0);
            byte[] psk = (byte[]) params.get("proxyPsk"), id = (byte[]) params.get("proxyPskIdentity");
            String auth = params.get("proxyAuthorization") instanceof String ? (String) params.get("proxyAuthorization") : null;
            String host = URI.create(url).getHost();
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            InetAddress addr = address(host);
            if (port <= 0 || psk == null || id == null) throw new IOException("incomplete parameters");
            log("video: the phone's proxy is " + addr + " port " + port);
            return new PhoneProxy(addr, port, psk, id, auth);
        } catch (Throwable t) {
            log("video: no proxy from the phone: " + t);
            return null;
        }
    }

    /** a link-local IPv6 address needs its interface: the one the phone is on (wlan0 for wireless CarPlay) */
    static InetAddress address(String host) throws IOException {
        InetAddress a = InetAddress.getByName(host);
        if (!(a instanceof Inet6Address) || !a.isLinkLocalAddress()) return a;
        NetworkInterface ni = NetworkInterface.getByName("wlan0");
        if (ni == null || !ni.isUp()) {
            ni = null;
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress x : Collections.list(n.getInetAddresses())) if (x instanceof Inet6Address && x.isLinkLocalAddress()) ni = n;
                if (ni != null) break;
            }
        }
        if (ni == null) throw new IOException("no interface for the phone's link-local address " + host);
        return Inet6Address.getByAddress(null, a.getAddress(), ni);
    }

    // ---- back to the core ----

    static void send(Map<String, Object> m) {
        if (m == null || streamId < 0) return;
        callCore("ioniq6.cpclimate.VideoMessages", "engineSend", new Class<?>[] { long.class, Map.class }, streamId, m);
    }

    static void log(String s) {
        callCore("ioniq6.cpclimate.VideoMessages", "engineLog", new Class<?>[] { String.class }, s);
    }

    static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    static Object callCore(String cls, String method, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(cls, true, Video.class.getClassLoader()).getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
