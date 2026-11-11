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
        noPicture = new File(FILES + "cpc-video-nopicture").exists();
        noSound = new File(FILES + "cpc-video-nosound").exists();
        // 2026-11-11: CarPlay's process died 1.7 s into a video with nothing in the log; record any uncaught error first
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter w = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(w));
                    trace("UNCAUGHT in thread " + t.getName() + ": " + w);
                } catch (Throwable ignored) {
                }
                // the video's own threads (ExoPlayer's, the TLS ones): the video stops, CarPlay keeps running
                // (2026-11-11: an Error in ExoPlayer:Playback took the whole CarPlay process down)
                String name = t.getName();
                if (name != null && (name.startsWith("ExoPlayer") || name.startsWith("cp-climate-")) && t != Looper.getMainLooper().getThread()) {
                    try {
                        log("video: stopped after an error in " + name + ": " + e);
                        if (main != null) main.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    send(error(ERROR_INCOMPATIBLE_ASSET));
                                    stop("error in the player");
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                if (before != null) before.uncaughtException(t, e);
            }
        });
        String mode = noPicture ? ", sound only (test)" : noSound ? ", picture only" : "";
        trace("---- engine start (process " + android.os.Process.myPid() + ")" + mode);
        log("video engine ready (ExoPlayer 2.19.1, proxy through the phone)" + mode);
    }

    static boolean noPicture, noSound;

    /**
     * settings video_no_picture (no picture decoder) and video_sound off (no sound renderer). Without sound the
     * player keeps time by the wall clock: 2026-11-11 the head unit's audio output from CarPlay's process had no
     * device ("DiPO available device not found", out_get_presentation_position errors), its position ran ahead,
     * and the picture raced to catch up (50 frames dropped at a time), with no sound.
     */
    static final class Renderers extends com.google.android.exoplayer2.DefaultRenderersFactory {
        Renderers(Context c) {
            super(c);
        }

        @Override
        protected void buildVideoRenderers(Context c, int mode, com.google.android.exoplayer2.mediacodec.MediaCodecSelector s, boolean fallback,
                Handler h, com.google.android.exoplayer2.video.VideoRendererEventListener l, long joining,
                ArrayList<com.google.android.exoplayer2.Renderer> out) {
            if (!noPicture) super.buildVideoRenderers(c, mode, s, fallback, h, l, joining, out);
        }

        @Override
        protected void buildAudioRenderers(Context c, int mode, com.google.android.exoplayer2.mediacodec.MediaCodecSelector s, boolean fallback,
                com.google.android.exoplayer2.audio.AudioSink sink, Handler h, com.google.android.exoplayer2.audio.AudioRendererEventListener l,
                ArrayList<com.google.android.exoplayer2.Renderer> out) {
            if (!noSound) super.buildAudioRenderers(c, mode, s, fallback, sink, h, l, out);
        }
    }

    static final java.text.SimpleDateFormat STAMP = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US);

    /**
     * a line in files/cpc-video-trace.txt, written and synced before returning, so the last step before a crash
     * survives it (the HMG-API log loses what a dying process hadn't passed on yet). Kept under 512 KB.
     */
    static synchronized void trace(String s) {
        try {
            File f = new File(FILES + "cpc-video-trace.txt");
            if (f.length() > 512 * 1024) {
                File old = new File(FILES + "cpc-video-trace.1.txt");
                old.delete();
                f.renameTo(old);
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            try {
                String line;
                synchronized (STAMP) {
                    line = STAMP.format(new java.util.Date()) + " [" + Thread.currentThread().getName() + "] " + s + "\n";
                }
                out.write(line.getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
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
        com.google.android.exoplayer2.DefaultRenderersFactory renderers = new Renderers(context);
        // start after 4 s of video (quick start), but after a pause wait for 10 s so pauses come less often;
        // keep loading up to 90 s ahead (2026-11-11: the default 2.5 s after a pause gave a pause every few seconds)
        com.google.android.exoplayer2.DefaultLoadControl load = new com.google.android.exoplayer2.DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 90000, 4000, 10000)
                .setTargetBufferBytes(64 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context, renderers.setEnableDecoderFallback(true))
                .setLoadControl(load)
                .setLooper(Looper.getMainLooper()).build();
        startCpuTrace();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false);
        player.addListener(listener);
        player.addAnalyticsListener(steps);
        player.setMediaSource(source, startMs);
        player.setPlayWhenReady(false);
        player.prepare();
        currentUrl = url;
        if (surface != null) player.setVideoSurfaceView(surface);
        log("video: playing " + host(url) + " (" + (progressive ? "progressive" : "HLS") + ", from " + startMs / 1000 + " s) through the phone");
    }

    /** the player's steps that touch the head unit's hardware or the network, for the trace */
    static final com.google.android.exoplayer2.analytics.AnalyticsListener steps = new com.google.android.exoplayer2.analytics.AnalyticsListener() {
        @Override
        public void onVideoInputFormatChanged(EventTime t, com.google.android.exoplayer2.Format f,
                com.google.android.exoplayer2.decoder.DecoderReuseEvaluation r) {
            log("video: picture format " + f.sampleMimeType + " " + f.width + "x" + f.height + " " + f.codecs + " (opening the decoder)");
        }

        @Override
        public void onVideoDecoderInitialized(EventTime t, String name, long initializedTimestampMs, long initializationDurationMs) {
            log("video: picture decoder " + name + " opened in " + initializationDurationMs + " ms");
        }

        @Override
        public void onAudioInputFormatChanged(EventTime t, com.google.android.exoplayer2.Format f,
                com.google.android.exoplayer2.decoder.DecoderReuseEvaluation r) {
            log("video: sound format " + f.sampleMimeType + " " + f.channelCount + "ch " + f.sampleRate + " Hz (opening the decoder)");
        }

        @Override
        public void onAudioDecoderInitialized(EventTime t, String name, long initializedTimestampMs, long initializationDurationMs) {
            log("video: sound decoder " + name + " opened in " + initializationDurationMs + " ms");
        }

        @Override
        public void onRenderedFirstFrame(EventTime t, Object output, long renderTimeMs) {
            log("video: first picture shown");
        }

        @Override
        public void onLoadStarted(EventTime t, com.google.android.exoplayer2.source.LoadEventInfo info, com.google.android.exoplayer2.source.MediaLoadData data) {
            trace("load " + data.dataType + " " + host(info.uri.toString()) + " " + tail(info.uri.getPath()));
        }

        @Override
        public void onLoadCompleted(EventTime t, com.google.android.exoplayer2.source.LoadEventInfo info, com.google.android.exoplayer2.source.MediaLoadData data) {
            long media = data.mediaStartTimeMs != C.TIME_UNSET && data.mediaEndTimeMs != C.TIME_UNSET ? data.mediaEndTimeMs - data.mediaStartTimeMs : -1;
            long kbps = info.loadDurationMs > 0 ? info.bytesLoaded * 8 / info.loadDurationMs : -1;
            trace("loaded " + info.bytesLoaded + " B in " + info.loadDurationMs + " ms (" + kbps + " kbit/s)"
                    + (media > 0 ? " for " + media + " ms of video, " + (info.loadDurationMs * 100 / media) + "% of real time" : "")
                    + " " + tail(info.uri.getPath()) + (player != null ? ", buffered to " + (player.getBufferedPosition() - player.getCurrentPosition()) + " ms ahead" : ""));
        }

        @Override
        public void onAudioUnderrun(EventTime t, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
            trace("audio underrun (buffer " + bufferSizeMs + " ms, " + elapsedSinceLastFeedMs + " ms since fed)");
        }

        @Override
        public void onAudioSinkError(EventTime t, Exception e) {
            trace("audio sink error: " + e);
        }

        @Override
        public void onLoadError(EventTime t, com.google.android.exoplayer2.source.LoadEventInfo info, com.google.android.exoplayer2.source.MediaLoadData data,
                IOException e, boolean cancelled) {
            log("video: load failed " + tail(info.uri.getPath()) + ": " + e);
        }

        @Override
        public void onDroppedVideoFrames(EventTime t, int dropped, long elapsedMs) {
            trace("dropped " + dropped + " frames in " + elapsedMs + " ms");
        }
    };

    // ---- CPU use in the trace: whether the download is held back by the network or by the head unit's CPU ----

    static long lastProcTicks = -1, lastAllTicks = -1, lastBusyTicks = -1;
    static boolean cpuTracing;

    static void startCpuTrace() {
        if (cpuTracing || main == null) return;
        cpuTracing = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) {
                    cpuTracing = false;
                    lastProcTicks = -1;
                    return;
                }
                try {
                    traceCpu();
                } catch (Throwable ignored) {
                }
                main.postDelayed(this, 5000);
            }
        }, 5000);
    }

    /** this process's and the whole system's CPU over the last 5 s, in % of all cores (4 on this head unit) */
    static void traceCpu() throws IOException {
        String self = readSmall("/proc/self/stat");
        String[] f = self.substring(self.lastIndexOf(')') + 2).split(" ");
        long proc = Long.parseLong(f[11]) + Long.parseLong(f[12]);   // utime + stime
        String[] c = readSmall("/proc/stat").split("\n")[0].trim().split("\\s+");
        long all = 0, idle = Long.parseLong(c[4]) + (c.length > 5 ? Long.parseLong(c[5]) : 0);
        for (int i = 1; i < c.length; i++) all += Long.parseLong(c[i]);
        long busy = all - idle;
        if (lastProcTicks >= 0 && all > lastAllTicks) {
            long d = all - lastAllTicks;
            trace("cpu: video process " + (proc - lastProcTicks) * 100 / d + "%, whole system " + (busy - lastBusyTicks) * 100 / d
                    + "% of all cores; " + (player != null ? (player.getBufferedPosition() - player.getCurrentPosition()) + " ms buffered" : ""));
        }
        lastProcTicks = proc;
        lastAllTicks = all;
        lastBusyTicks = busy;
    }

    static String readSmall(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        try {
            byte[] b = new byte[8192];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "US-ASCII");
        } finally {
            in.close();
        }
    }

    static String tail(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    static final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            // stopped only to buffer: still playing as far as the phone is concerned (a "paused" made it pause, 2026-11-11)
            if (!isPlaying && player != null && player.getPlayWhenReady() && player.getPlayerError() == null
                    && player.getPlaybackState() != Player.STATE_ENDED) {
                updateStatus();
                return;
            }
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
        // the rate the phone asked for, also while loading or buffering (rate 0 there made the phone send setRate 0)
        boolean wanted = player != null && player.getPlayWhenReady() && player.getPlayerError() == null;
        info.put("rate", wanted ? 1.0 : 0.0);
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
        trace(s);
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
