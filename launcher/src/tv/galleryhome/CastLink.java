package tv.galleryhome;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One Google Cast (CASTV2) connection: TLS to port 8009, frames of a 4-byte length + a protobuf CastMessage.
 * Only the fields we need are encoded by hand (no protobuf library):
 *   1 protocol_version (0) · 2 source_id · 3 destination_id · 4 namespace · 5 payload_type (0 = string) · 6 payload_utf8
 * Cast devices use a self-signed certificate, so the link accepts it; it only ever talks to the speaker on the home network.
 */
final class CastLink implements Closeable {
    static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    static final String RECEIVER = "receiver-0";
    private static final String SENDER = "sender-gallery";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Msg {
        String src = "", dst = "", ns = "", payload = "";
    }

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;

    CastLink(String host, int port) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), 5000);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{TRUST_SPEAKER}, null);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setSoTimeout(25_000);          // the heartbeat brings traffic every 5 s; silence means the speaker is gone
            s.startHandshake();
            sock = s;
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();
        } catch (IOException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e;
        } catch (Exception e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw new IOException(e);
        }
    }

    synchronized void send(String dst, String ns, String json) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64 + json.length());
        varint(b, (1 << 3)); varint(b, 0);
        str(b, 2, SENDER);
        str(b, 3, dst);
        str(b, 4, ns);
        varint(b, (5 << 3)); varint(b, 0);
        str(b, 6, json);
        byte[] body = b.toByteArray();
        int n = body.length;
        out.write(new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
        out.write(body);
        out.flush();
    }

    /** Blocks until the next message (or throws when the link drops / goes silent). */
    Msg read() throws IOException {
        int len = in.readInt();
        if (len < 0 || len > (1 << 20)) throw new IOException("bad cast frame length " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return parse(buf);
    }

    static Msg parse(byte[] buf) throws IOException {
        Msg m = new Msg();
        int[] p = {0};
        while (p[0] < buf.length) {
            long key = readVarint(buf, p);
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 0) { readVarint(buf, p); continue; }
            if (wire == 1) { p[0] += 8; continue; }
            if (wire == 5) { p[0] += 4; continue; }
            if (wire != 2) throw new IOException("bad wire type " + wire);
            int n = (int) readVarint(buf, p);
            if (n < 0 || p[0] + n > buf.length) throw new IOException("bad field length");
            String s = (field >= 2 && field <= 4) || field == 6 ? new String(buf, p[0], n, UTF8) : null;
            p[0] += n;
            if (field == 2) m.src = s;
            else if (field == 3) m.dst = s;
            else if (field == 4) m.ns = s;
            else if (field == 6) m.payload = s;
        }
        return m;
    }

    private static long readVarint(byte[] b, int[] p) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (p[0] >= b.length) throw new IOException("truncated varint");
            int x = b[p[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
        }
        throw new IOException("varint too long");
    }

    private static void varint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7FL) != 0) { b.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        b.write((int) v);
    }

    private static void str(ByteArrayOutputStream b, int field, String s) {
        byte[] d = s.getBytes(UTF8);
        varint(b, (field << 3) | 2);
        varint(b, d.length);
        b.write(d, 0, d.length);
    }

    /**
     * Closing a TLS socket sends a goodbye message to the speaker, which is network I/O: on the main thread
     * Android kills the whole app for that (NetworkOnMainThreadException). So from the UI thread, close in the background.
     */
    @Override public void close() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Thread t = new Thread(new Runnable() { public void run() { closeNow(); } }, "cast-close");
            t.setDaemon(true);
            t.start();
        } else {
            closeNow();
        }
    }

    private void closeNow() {
        try { sock.close(); } catch (Exception ignored) { }
    }

    /** Cast speakers present a self-signed device certificate; accept it (local network only). */
    private static final X509TrustManager TRUST_SPEAKER = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
