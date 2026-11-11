package ioniq6.cpclimate.video;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * One large response read as byte ranges over several connections at once, handed out in order.
 * 2026-11-11: one connection through the phone gave 1.8-3.4 Mbit/s for a 3.5 Mbit/s stream (loading pauses);
 * several connections share the phone's link better and spread the two TLS layers over the head unit's 4 cores.
 *
 * The first range is the response the caller already has (its body, read as it arrives); workers fetch the
 * ranges after it, at most WINDOW bytes ahead of the reader. A range that fails or stalls is fetched again
 * (up to TRIES times) on a fresh connection.
 */
final class ParallelStream extends InputStream {
    static final int CHUNK = 512 * 1024, WORKERS = 3, TRIES = 3;
    static final long WINDOW = 6L * CHUNK;

    final TunnelHttp http;
    final String url;
    final Map<String, String> headers;
    final long end;
    final TunnelHttp.Response first;
    final Thread[] workers = new Thread[WORKERS];

    // guarded by this
    final TreeMap<Long, byte[]> ready = new TreeMap<Long, byte[]>();
    long nextToFetch, readPos;
    IOException failure;
    boolean closed;

    // the reader's own
    InputStream firstBody;
    byte[] cur;
    int curAt;

    /** first: the open response for [start, firstEnd); the rest, [firstEnd, end), is fetched in parallel */
    ParallelStream(TunnelHttp http, String url, Map<String, String> headers, TunnelHttp.Response first, long firstEnd, long end) {
        this.http = http;
        this.url = url;
        this.headers = headers;
        this.first = first;
        this.firstBody = first.body;
        this.end = end;
        this.nextToFetch = firstEnd;
        this.readPos = firstEnd;
        for (int i = 0; i < WORKERS; i++) {
            final int n = i;
            workers[i] = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    work();
                }
            }, "cp-climate-fetch-" + n, 1L << 20);
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    void work() {
        while (true) {
            long off, len;
            synchronized (this) {
                while (!closed && failure == null && nextToFetch < end && nextToFetch - readPos >= WINDOW) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed || failure != null || nextToFetch >= end) return;
                off = nextToFetch;
                len = Math.min(CHUNK, end - off);
                nextToFetch += len;
            }
            byte[] data = null;
            IOException last = null;
            for (int t = 0; t < TRIES && data == null; t++) {
                if (isClosed()) return;
                try {
                    data = fetch(off, (int) len);
                } catch (IOException e) {
                    last = e;
                    Video.trace("range " + off + "+" + len + " failed (try " + (t + 1) + "): " + e.getMessage());
                } catch (Throwable e) {
                    last = new IOException("range " + off + ": " + e, e);
                    Video.trace("range " + off + " failed (error): " + TunnelHttp.stack(e));
                }
            }
            synchronized (this) {
                if (data == null) {
                    if (failure == null) failure = last != null ? last : new IOException("range " + off + " failed");
                } else {
                    ready.put(off, data);
                }
                notifyAll();
            }
        }
    }

    byte[] fetch(long off, int len) throws IOException {
        TunnelHttp.Response r = http.get(url, off, len, headers);
        try {
            if (r.status != 206) throw new IOException("range " + off + "+" + len + ": status " + r.status);
            byte[] b = new byte[len];
            int at = 0;
            while (at < len) {
                if (isClosed()) throw new IOException("closed");
                int n = r.body.read(b, at, len - at);
                if (n < 0) throw new IOException("range " + off + " ended after " + at + " of " + len + " bytes");
                at += n;
            }
            return b;
        } finally {
            r.close();
        }
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (firstBody != null) {
            int n = firstBody.read(b, off, len);
            if (n >= 0) return n;
            firstBody = null;
            first.close();
        }
        if (cur == null || curAt >= cur.length) {
            synchronized (this) {
                cur = null;
                while (true) {
                    if (closed) throw new IOException("closed");
                    byte[] next = ready.remove(readPos);
                    if (next != null) {
                        cur = next;
                        curAt = 0;
                        readPos += next.length;
                        notifyAll();
                        break;
                    }
                    if (failure != null) throw failure;
                    if (readPos >= end) return -1;
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("interrupted");
                    }
                }
            }
        }
        int n = Math.min(len, cur.length - curAt);
        System.arraycopy(cur, curAt, b, off, n);
        curAt += n;
        return n;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            ready.clear();
            notifyAll();
        }
        first.close();
    }
}
