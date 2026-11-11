package ioniq6.cpclimate.video;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** ExoPlayer's network reads (playlists, segments, keys) through the phone: TunnelHttp over PhoneProxy tunnels */
final class TunnelDataSource extends BaseDataSource {
    final TunnelHttp http;
    TunnelHttp.Response response;
    InputStream body;
    Uri uri;
    long remaining;
    boolean opened;

    TunnelDataSource(TunnelHttp http) {
        super(true);
        this.http = http;
    }

    static final class Factory implements DataSource.Factory {
        final TunnelHttp http;

        Factory(TunnelHttp http) {
            this.http = http;
        }

        @Override
        public DataSource createDataSource() {
            return new TunnelDataSource(http);
        }
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        String url = spec.uri.toString();
        long position = spec.position, length = spec.length == C.LENGTH_UNSET ? -1 : spec.length;
        // media (not playlists): ask for the first range only; if the server answers 206 with the total size, the
        // rest comes over several connections at once (ParallelStream)
        String path = spec.uri.getPath() == null ? "" : spec.uri.getPath().toLowerCase(java.util.Locale.US);
        boolean media = !(path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".key"));
        long firstLen = media && (length < 0 || length > 2L * ParallelStream.CHUNK) ? ParallelStream.CHUNK : length;
        try {
            response = http.get(url, position, firstLen, spec.httpRequestHeaders);
        } catch (IOException e) {
            Video.trace("open failed: " + TunnelHttp.stack(e));
            throw new HttpDataSource.HttpDataSourceException("tunnel: " + e.getMessage(), e, spec,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        } catch (Throwable e) {
            // an Error here would end ExoPlayer's loader thread, and with it the process: one failed load instead
            Video.trace("open failed (error): " + TunnelHttp.stack(e));
            throw new HttpDataSource.HttpDataSourceException("tunnel: " + e, new IOException(e), spec,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
        int status = response.status;
        if (status == 416 && position > 0) {
            // asked from the very end: nothing left to read
            response.close();
            response = null;
            opened = true;
            transferStarted(spec);
            return 0;
        }
        if (status < 200 || status > 299) {
            Map<String, List<String>> headers = response.headers;
            response.close();
            response = null;
            throw new HttpDataSource.InvalidResponseCodeException(status, null, null, headers, spec, new byte[0]);
        }
        body = response.body;
        if (status == 206 && firstLen != length) {
            long total = totalSize(response.header("content-range"));
            long end = length >= 0 ? position + length : total;
            if (total < 0 && length < 0) {
                // no total size: can't plan the ranges; read this one and ask again from there
                Video.trace("parallel: the server gave no total size; reading in one piece");
                response.close();
                response = http.get(url, position, length, spec.httpRequestHeaders);
                body = response.body;
                status = response.status;
            } else {
                long firstEnd = Math.min(end, position + response.length);
                if (end > firstEnd) {
                    body = new ParallelStream(http, url, spec.httpRequestHeaders, response, firstEnd, end);
                    Video.trace("parallel: " + (end - position) + " B as " + ParallelStream.CHUNK / 1024 + " KB ranges over "
                            + ParallelStream.WORKERS + " more connections " + Video.tail(spec.uri.getPath()));
                }
                remaining = end - position;
                opened = true;
                transferStarted(spec);
                return remaining;
            }
        }
        // a server that ignored the Range: skip to the position ourselves
        if (status == 200 && position > 0) {
            long skip = position;
            while (skip > 0) {
                long n = body.skip(skip);
                if (n <= 0) {
                    if (body.read() < 0) throw new IOException("body ended while skipping to " + position);
                    n = 1;
                }
                skip -= n;
            }
        }
        long available = response.length < 0 ? C.LENGTH_UNSET : status == 200 ? response.length - position : response.length;
        remaining = length >= 0 ? length : available;
        opened = true;
        transferStarted(spec);
        return remaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (body == null) return C.RESULT_END_OF_INPUT;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int want = remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining);
        int n;
        try {
            n = body.read(buffer, offset, want);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            Video.trace("read failed (error): " + TunnelHttp.stack(e));
            throw new IOException("tunnel read: " + e, e);
        }
        if (n < 0) return C.RESULT_END_OF_INPUT;
        if (remaining != C.LENGTH_UNSET) remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response == null ? Collections.<String, List<String>>emptyMap() : response.headers;
    }

    /** the total size from "bytes a-b/total", -1 if unknown */
    static long totalSize(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (body instanceof ParallelStream) ((ParallelStream) body).close();
        if (response != null) response.close();
        response = null;
        body = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
