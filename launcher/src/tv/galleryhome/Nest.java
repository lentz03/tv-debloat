package tv.galleryhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * What the Nest Mini is playing, read straight from the speaker over Google Cast (no Google account, no cloud).
 * One instance per process, shared by the home screen and the screensaver. It only runs while one of them is on screen.
 *
 *  find:    mDNS "_googlecast._tcp" via NsdManager; skips this TV's own Cast receiver; remembers the speaker's address
 *  connect: CONNECT to receiver-0 → RECEIVER_STATUS (volume, running app) → CONNECT to the app → MEDIA_STATUS
 *  control: PLAY / PAUSE / QUEUE_UPDATE jump ±1 on the media channel; SET_VOLUME on the receiver
 */
final class Nest {
    private static final String TAG = "GalleryHome";
    private static final String SERVICE = "_googlecast._tcp";

    interface Listener { void onNest(Status s); }

    /** Immutable snapshot for the UI. */
    static final class Status {
        static final Status NONE = new Status();
        boolean active;                      // something is playing or paused on the speaker
        String title = "", artist = "", app = "", device = "Nest Mini", imageUrl;
        Bitmap art;
        double position, duration;           // seconds; duration 0 = live / unknown
        long stamp;                          // elapsedRealtime when position was read
        boolean playing;
        float rate = 1f;
        float volume = -1f;                  // 0..1, -1 unknown
        boolean muted, canNext = true, canPrev = true;

        double positionNow() {
            double p = position;
            if (playing) p += (SystemClock.elapsedRealtime() - stamp) / 1000.0 * rate;
            return duration > 0 ? Math.min(duration, Math.max(0, p)) : Math.max(0, p);
        }

        Status copy() {
            Status s = new Status();
            s.active = active; s.title = title; s.artist = artist; s.app = app; s.device = device; s.imageUrl = imageUrl;
            s.art = art; s.position = position; s.duration = duration; s.stamp = stamp; s.playing = playing; s.rate = rate;
            s.volume = volume; s.muted = muted; s.canNext = canNext; s.canPrev = canPrev;
            return s;
        }
    }

    private static Nest instance;

    static synchronized Nest get(Context c) {
        if (instance == null) instance = new Nest(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final ExecutorService artExec = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService net;              // pings + commands for the current session
    private volatile boolean running;
    private Thread loop;
    private final Object wake = new Object();

    // ---- session state (guarded by "this")
    private Status st = Status.NONE.copy();
    private Status published = Status.NONE;
    private CastLink link;
    private String mediaDest;                          // transportId of the app that plays media
    private int mediaSession = -1;
    private int reqId = 1;
    private boolean demo;

    // ---- discovery
    private NsdManager nsd;
    private boolean discovering;
    private final LinkedList<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean resolving;
    /** A speaker we could connect to. IPv4 addresses are preferred; IPv6 link-local ones carry their interface. */
    private static final class Cand {
        String host, name = "", model = "";
        int port, score;
        boolean v4;
        long badUntil;
        int rank() { return score * 10 + (v4 ? 1 : 0); }
    }
    private final List<Cand> cands = new ArrayList<>();

    private Nest(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("nest", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ listeners / lifecycle

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        ui.removeCallbacks(stopLater);
        l.onNest(published);
        if (!running && !demo && new Prefs(ctx).player()) start();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) {          // short grace period: home → screensaver hands over without reconnecting
            ui.removeCallbacks(stopLater);
            ui.postDelayed(stopLater, 8_000);
        }
    }

    private final Runnable stopLater = new Runnable() {
        @Override public void run() {
            if (!listeners.isEmpty()) return;
            stop();
            ui.removeCallbacks(hide);
            pendingHide = null;
            published = Status.NONE;        // don't show a stale song when the home screen comes back
        }
    };

    /** Called when the "mini player" setting changes. */
    void setEnabled(boolean on) {
        if (on && !listeners.isEmpty() && !demo) start();
        if (!on) { stop(); publishNow(Status.NONE.copy()); }
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        loop = new Thread(new Runnable() { public void run() { connectLoop(); } }, "nest");
        loop.setDaemon(true);
        loop.start();
    }

    private void stop() {
        running = false;
        stopDiscovery();
        CastLink l;
        synchronized (this) { l = link; }
        if (l != null) l.close();
        synchronized (wake) { wake.notifyAll(); }
    }

    // ------------------------------------------------------------------ demo (test hook)

    /** Shows a sample track so the layout can be checked without the speaker playing. */
    void setDemo(boolean on) {
        demo = on;
        if (on) {
            stop();
            Status s = new Status();
            s.active = true; s.title = "Harbour Lights"; s.artist = "Neon Atlas"; s.app = "Spotify";
            s.position = 102; s.duration = 238; s.stamp = SystemClock.elapsedRealtime(); s.playing = true;
            s.volume = 0.4f; s.art = demoArt();
            synchronized (this) { st = s; }
            publishNow(s.copy());
        } else {
            synchronized (this) { st = Status.NONE.copy(); }
            publishNow(Status.NONE.copy());
            if (!listeners.isEmpty() && new Prefs(ctx).player()) start();
        }
    }

    private static Bitmap demoArt() {
        int n = 160;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, n, 0xFF2B2160, 0xFFC4573A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(new RadialGradient(n * 0.5f, n * 0.48f, n * 0.3f, 0xFFF5B54A, 0x00F5B54A, Shader.TileMode.CLAMP));
        c.drawCircle(n * 0.5f, n * 0.48f, n * 0.3f, p);
        p.setShader(null);
        p.setColor(0xFFC4573A);
        for (int i = 0; i < 5; i++) c.drawRect(0, n * 0.72f + i * 7, n, n * 0.72f + i * 7 + 3, p);
        return b;
    }

    // ------------------------------------------------------------------ commands (from the UI thread)

    void playPause() {
        final boolean pause;
        synchronized (this) {
            if (!st.active) return;
            pause = st.playing;
            st.position = st.positionNow();
            st.stamp = SystemClock.elapsedRealtime();
            st.playing = !pause;
            publish();
        }
        if (demo) return;
        media(pause ? "{\"type\":\"PAUSE\"}" : "{\"type\":\"PLAY\"}");
    }

    void skip(int dir) {
        if (demo) { synchronized (this) { st.position = 0; st.stamp = SystemClock.elapsedRealtime(); publish(); } return; }
        media("{\"type\":\"QUEUE_UPDATE\",\"jump\":" + (dir < 0 ? -1 : 1) + "}");
    }

    /** Volume 0..1 on the speaker itself. */
    void setVolume(float v) {
        final float level = Math.max(0f, Math.min(1f, Math.round(v * 100) / 100f));
        synchronized (this) { st.volume = level; publish(); }
        if (demo) return;
        sendLater(CastLink.RECEIVER, CastLink.NS_RECEIVER,
                "{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":" + level + "}}", true);
    }

    private void media(String json) {
        final String dest; final int session;
        synchronized (this) { dest = mediaDest; session = mediaSession; }
        if (dest == null || session < 0) return;
        String body = json.substring(0, json.length() - 1) + ",\"mediaSessionId\":" + session + "}";
        sendLater(dest, CastLink.NS_MEDIA, body, true);
    }

    private void sendLater(final String dst, final String ns, final String json, final boolean withId) {
        final ScheduledExecutorService ex;
        synchronized (this) { ex = net; }
        if (ex == null) return;
        try {
            ex.execute(new Runnable() { public void run() { send(dst, ns, json, withId); } });
        } catch (RuntimeException ignored) { }
    }

    private void send(String dst, String ns, String json, boolean withId) {
        CastLink l;
        String body = json;
        synchronized (this) {
            l = link;
            if (withId) body = json.substring(0, json.length() - 1) + ",\"requestId\":" + (reqId++) + "}";
        }
        if (l == null) return;
        try { l.send(dst, ns, body); }
        catch (IOException e) { Log.w(TAG, "nest: send failed: " + e); l.close(); }
    }

    // ------------------------------------------------------------------ connection loop (thread "nest")

    private void connectLoop() {
        String saved = sp.getString("host", null);
        if (saved != null) addCand(saved, sp.getInt("port", 8009), sp.getString("model", ""), sp.getString("name", ""), 5);
        while (running) {
            Cand c = best();
            if (c == null) {                 // nothing known (or everything failed lately): search, give answers time to arrive
                startDiscovery();
                sleep(3_500);
                c = best();
                if (c == null) { sleep(6_000); continue; }
            }
            boolean connected = false;
            try {
                session(c);
                connected = true;
            } catch (Exception e) {
                Log.w(TAG, "nest: " + c.host + ":" + c.port + " " + e);
            } finally {
                connected = sessionStarted;  // reached the speaker at all (a later drop isn't the address's fault)
                endSession();
            }
            if (!running) break;
            if (!connected) {                // dead or stale address (DHCP): try the next one, and look again
                markBad(c.host);
                startDiscovery();
                sleep(1_000);
            } else {
                sleep(3_000);                // the speaker dropped us (rebooted, Wi-Fi blip): reconnect shortly
            }
        }
        stopDiscovery();
    }

    private volatile boolean sessionStarted;

    private synchronized Cand best() {
        long now = SystemClock.elapsedRealtime();
        Cand b = null;
        for (Cand c : cands) if (c.badUntil <= now && (b == null || c.rank() > b.rank())) b = c;
        return b;
    }

    private synchronized void markBad(String host) {
        for (Cand c : cands) if (c.host.equals(host)) c.badUntil = SystemClock.elapsedRealtime() + 60_000;
    }

    private synchronized void addCand(String host, int port, String model, String name, int score) {
        for (Cand c : cands) {
            if (c.host.equals(host) && c.port == port) {
                c.score = Math.max(c.score, score); c.badUntil = 0;
                if (!model.isEmpty()) c.model = model;
                if (!name.isEmpty()) c.name = name;
                return;
            }
        }
        Cand c = new Cand();
        c.host = host; c.port = port; c.model = model; c.name = name; c.score = score; c.v4 = !host.contains(":");
        cands.add(c);
    }

    private void sleep(long ms) {
        synchronized (wake) {
            try { wake.wait(ms); } catch (InterruptedException ignored) { }
        }
    }

    private void session(Cand c) throws IOException {
        String host = c.host;
        int port = c.port;
        sessionStarted = false;
        CastLink l = new CastLink(host, port);
        sessionStarted = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
        synchronized (this) { link = l; net = ex; mediaDest = null; mediaSession = -1; }
        if (!running) throw new IOException("stopped");
        Log.i(TAG, "nest: connected to " + host + ":" + port);
        sp.edit().putString("host", host).putInt("port", port).putString("model", c.model).putString("name", c.name).apply();
        stopDiscovery();
        l.send(CastLink.RECEIVER, CastLink.NS_CONN, connectJson());
        send(CastLink.RECEIVER, CastLink.NS_RECEIVER, "{\"type\":\"GET_STATUS\"}", true);
        final int[] tick = {0};
        ScheduledFuture<?> beat = ex.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                send(CastLink.RECEIVER, CastLink.NS_HEARTBEAT, "{\"type\":\"PING\"}", false);
                tick[0]++;
                if (tick[0] % 6 == 0) send(CastLink.RECEIVER, CastLink.NS_RECEIVER, "{\"type\":\"GET_STATUS\"}", true);
                String d;
                synchronized (Nest.this) { d = mediaDest; }
                if (d != null && tick[0] % 3 == 0) send(d, CastLink.NS_MEDIA, "{\"type\":\"GET_STATUS\"}", true);
            }
        }, 5, 5, TimeUnit.SECONDS);
        try {
            while (running) handle(l.read());
        } finally {
            beat.cancel(true);
        }
    }

    private void endSession() {
        CastLink l; ScheduledExecutorService ex;
        synchronized (this) {
            l = link; ex = net; link = null; net = null; mediaDest = null; mediaSession = -1;
            if (!demo) { st = Status.NONE.copy(); publish(); }
        }
        if (ex != null) ex.shutdownNow();
        if (l != null) l.close();
    }

    private static String connectJson() {
        return "{\"type\":\"CONNECT\",\"origin\":{},\"userAgent\":\"GalleryHome\",\"senderInfo\":{\"sdkType\":2,"
                + "\"version\":\"15.605.1.3\",\"browserVersion\":\"44.0.2403.30\",\"platform\":4,"
                + "\"systemVersion\":\"Android TV\",\"connectionType\":1}}";
    }

    private void handle(CastLink.Msg m) {
        if (m.payload == null || m.payload.isEmpty()) return;
        JSONObject j;
        try { j = new JSONObject(m.payload); } catch (Exception e) { return; }
        String type = j.optString("type");
        if (CastLink.NS_HEARTBEAT.equals(m.ns)) {
            if ("PING".equals(type)) send(m.src, CastLink.NS_HEARTBEAT, "{\"type\":\"PONG\"}", false);
            return;
        }
        if ("RECEIVER_STATUS".equals(type)) receiverStatus(j.optJSONObject("status"));
        else if ("MEDIA_STATUS".equals(type)) mediaStatus(j.optJSONArray("status"));
        else if (CastLink.NS_CONN.equals(m.ns) && "CLOSE".equals(type)) {
            synchronized (this) {
                if (m.src.equals(mediaDest)) { mediaDest = null; mediaSession = -1; st.active = false; publish(); }
            }
        }
    }

    private void receiverStatus(JSONObject s) {
        if (s == null) return;
        String connectTo = null;
        synchronized (this) {
            JSONObject vol = s.optJSONObject("volume");
            if (vol != null) {
                if (vol.has("level")) st.volume = (float) vol.optDouble("level", st.volume);
                st.muted = vol.optBoolean("muted", st.muted);
            }
            JSONArray apps = s.optJSONArray("applications");
            String dest = null, name = "";
            for (int i = 0; apps != null && i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a == null || a.optBoolean("isIdleScreen", false)) continue;
                JSONArray ns = a.optJSONArray("namespaces");
                boolean hasMedia = false;
                for (int k = 0; ns != null && k < ns.length(); k++) {
                    JSONObject n = ns.optJSONObject(k);
                    if (n != null && CastLink.NS_MEDIA.equals(n.optString("name"))) hasMedia = true;
                }
                if (hasMedia && a.has("transportId")) { dest = a.optString("transportId"); name = a.optString("displayName"); break; }
            }
            if (dest == null) {
                if (mediaDest != null) Log.i(TAG, "nest: nothing playing");
                mediaDest = null; mediaSession = -1; st.active = false;
            } else if (!dest.equals(mediaDest)) {
                mediaDest = dest; mediaSession = -1; connectTo = dest;
                st.app = name;
                Log.i(TAG, "nest: app " + name);
            }
            publish();
        }
        if (connectTo != null) {
            send(connectTo, CastLink.NS_CONN, connectJson(), false);
            send(connectTo, CastLink.NS_MEDIA, "{\"type\":\"GET_STATUS\"}", true);
        }
    }

    private void mediaStatus(JSONArray arr) {
        String fetch = null;
        synchronized (this) {
            if (arr == null || arr.length() == 0) { st.active = false; publish(); return; }
            JSONObject s = arr.optJSONObject(0);
            if (s == null) return;
            int sid = s.optInt("mediaSessionId", mediaSession);
            if (sid != mediaSession) { st.title = ""; st.artist = ""; st.imageUrl = null; st.art = null; st.duration = 0; }
            mediaSession = sid;
            String state = s.optString("playerState", st.playing ? "PLAYING" : "PAUSED");
            if (s.has("currentTime")) { st.position = s.optDouble("currentTime", 0); st.stamp = SystemClock.elapsedRealtime(); }
            st.rate = (float) s.optDouble("playbackRate", 1);
            st.playing = "PLAYING".equals(state) || "BUFFERING".equals(state);
            st.active = !"IDLE".equals(state);
            long cmds = s.optLong("supportedMediaCommands", -1);
            if (cmds >= 0) { st.canNext = (cmds & 64) != 0; st.canPrev = (cmds & 128) != 0; }
            JSONObject vol = s.optJSONObject("volume");
            JSONObject media = s.optJSONObject("media");
            if (media == null && s.optJSONObject("extendedStatus") != null) media = s.optJSONObject("extendedStatus").optJSONObject("media");
            if (media != null) {
                if (media.has("duration")) st.duration = Math.max(0, media.optDouble("duration", 0));
                JSONObject md = media.optJSONObject("metadata");
                if (md != null) {
                    st.title = md.optString("title", st.title);
                    String artist = md.optString("artist", "");
                    if (artist.isEmpty()) artist = md.optString("albumArtist", "");
                    if (artist.isEmpty()) artist = md.optString("subtitle", "");
                    if (!artist.isEmpty() || md.has("title")) st.artist = artist;
                    JSONArray imgs = md.optJSONArray("images");
                    if (imgs != null && imgs.length() > 0) {
                        String url = pickImage(imgs);
                        if (url != null && !url.equals(st.imageUrl)) { st.imageUrl = url; st.art = null; fetch = url; }
                    }
                }
            }
            if (vol != null && vol.has("level") && st.volume < 0) st.volume = (float) vol.optDouble("level", st.volume);
            if (st.active && st.title.isEmpty()) st.title = st.app.isEmpty() ? "Playing" : st.app;
            Log.i(TAG, "nest: " + state + " \"" + st.title + "\" · " + st.artist + " (" + st.app + ")");
            publish();
        }
        if (fetch != null) fetchArt(fetch);
    }

    /** The smallest image that is still at least 120 px, otherwise the first. */
    private static String pickImage(JSONArray imgs) {
        String best = null; int bestW = Integer.MAX_VALUE;
        for (int i = 0; i < imgs.length(); i++) {
            JSONObject o = imgs.optJSONObject(i);
            if (o == null || o.optString("url").isEmpty()) continue;
            int w = o.optInt("width", 0);
            if (best == null) best = o.optString("url");
            if (w >= 120 && w < bestW) { bestW = w; best = o.optString("url"); }
        }
        return best;
    }

    private void fetchArt(final String url) {
        artExec.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = download(url);
                synchronized (Nest.this) {
                    if (url.equals(st.imageUrl)) { st.art = b; publish(); }
                }
            }
        });
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "GalleryHome/5 (Android TV)");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); total += n; if (total > 6_000_000) break; }
            byte[] d = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(d, 0, d.length, o);
        } catch (Exception e) {
            Log.w(TAG, "nest: cover art failed: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ publishing

    /** Call with "this" locked. Hiding is delayed a few seconds so the gap between two songs doesn't flash. */
    private void publish() {
        final Status s = st.copy();
        ui.post(new Runnable() { public void run() { deliver(s); } });
    }

    private void publishNow(final Status s) {
        ui.post(new Runnable() { public void run() { ui.removeCallbacks(hide); published = s; for (Listener l : new ArrayList<>(listeners)) l.onNest(s); } });
    }

    private Status pendingHide;
    private final Runnable hide = new Runnable() {
        @Override public void run() {
            if (pendingHide == null) return;
            published = pendingHide;
            pendingHide = null;
            for (Listener l : new ArrayList<>(listeners)) l.onNest(published);
        }
    };

    private void deliver(Status s) {
        if (!s.active && published.active) {
            pendingHide = s;
            ui.removeCallbacks(hide);
            ui.postDelayed(hide, running ? 4_000 : 0);
            return;
        }
        ui.removeCallbacks(hide);
        pendingHide = null;
        published = s;
        for (Listener l : new ArrayList<>(listeners)) l.onNest(s);
    }

    // ------------------------------------------------------------------ discovery (mDNS)

    private void startDiscovery() {
        synchronized (this) {
            if (discovering || !running) return;
            discovering = true;
        }
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    if (nsd == null) nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
                    discovery = newDiscovery();
                    nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
                    Log.i(TAG, "nest: looking for Cast speakers");
                } catch (RuntimeException e) {
                    Log.w(TAG, "nest: Android discovery unavailable: " + e);
                }
                new Thread(new Runnable() { public void run() { unicastQuery(); } }, "mdns").start();
                ui.removeCallbacks(endSearch);
                ui.postDelayed(endSearch, 20_000);           // don't leave the search running
            }
        });
    }

    private final Runnable endSearch = new Runnable() { public void run() { stopDiscovery(); } };

    private void stopDiscovery() {
        synchronized (this) {
            if (!discovering) return;
            discovering = false;
            resolveQueue.clear();
        }
        ui.post(new Runnable() {
            @Override public void run() {
                try { if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
                discovery = null;
            }
        });
    }

    private NsdManager.DiscoveryListener discovery;         // a fresh listener per search (NsdManager refuses to reuse one)

    private NsdManager.DiscoveryListener newDiscovery() { return new NsdManager.DiscoveryListener() {
        @Override public void onStartDiscoveryFailed(String t, int code) {
            Log.w(TAG, "nest: discovery failed " + code);
            synchronized (Nest.this) { discovering = false; }
        }
        @Override public void onStopDiscoveryFailed(String t, int code) { }
        @Override public void onDiscoveryStarted(String t) { }
        @Override public void onDiscoveryStopped(String t) { }
        @Override public void onServiceLost(NsdServiceInfo s) { }
        @Override public void onServiceFound(NsdServiceInfo s) {
            synchronized (Nest.this) { resolveQueue.add(s); }
            resolveNext();
        }
    }; }

    /** NsdManager resolves one service at a time. */
    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (this) {
            if (resolving || resolveQueue.isEmpty() || nsd == null) return;
            resolving = true;
            next = resolveQueue.removeFirst();
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int code) {
                    synchronized (Nest.this) { resolving = false; }
                    resolveNext();
                }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    consider(s);
                    synchronized (Nest.this) { resolving = false; }
                    resolveNext();
                }
            });
        } catch (RuntimeException e) {
            synchronized (this) { resolving = false; }
        }
    }

    /** Our own mDNS question: gets each speaker's IPv4 address, which is what we'd rather dial. */
    private void unicastQuery() {
        try {
            Set<String> own = ownAddresses();
            for (MdnsQuery.Service sv : MdnsQuery.query(SERVICE, 2_500)) {
                boolean self = own.contains(sv.ipv4);
                int score = score(sv.model, self);
                Log.i(TAG, "nest: found " + sv + (self ? " (this TV)" : "") + " score " + score);
                if (score > 0) addCand(sv.ipv4, sv.port, sv.model, sv.name, score);
            }
        } catch (Exception e) {
            Log.w(TAG, "nest: mDNS query failed: " + e);
        }
    }

    private static int score(String model, boolean self) {
        String m = model.toLowerCase(java.util.Locale.US);
        return self || m.contains("group") ? 0
                : m.contains("mini") || m.contains("nest audio") || m.contains("google home") ? 3
                : m.contains("nest") ? 2 : 1;
    }

    private void consider(NsdServiceInfo s) {
        InetAddress a = s.getHost();
        if (a == null) return;
        String model = attr(s, "md"), name = attr(s, "fn");
        boolean self = ownAddresses().contains(key(a));
        int score = score(model, self);
        Log.i(TAG, "nest: found " + name + " [" + model + "] " + a.getHostAddress() + ":" + s.getPort() + (self ? " (this TV)" : "") + " score " + score);
        if (score == 0) return;
        for (String host : dialable(a)) addCand(host, s.getPort(), model, name, score);
    }

    /**
     * NsdManager on this TV returns IPv6 link-local addresses without the interface ("fe80::…" instead of
     * "fe80::…%wlan0"), which the socket rejects with EINVAL. Attach each usable interface in turn, LAN one first.
     */
    private static List<String> dialable(InetAddress a) {
        List<String> out = new ArrayList<>();
        if (!(a instanceof Inet6Address) || !a.isLinkLocalAddress() || ((Inet6Address) a).getScopeId() != 0) {
            out.add(a.getHostAddress());
            return out;
        }
        try {
            List<String> later = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean v6ll = false, v4 = false;
                for (InetAddress x : Collections.list(ni.getInetAddresses())) {
                    if (x instanceof Inet6Address && x.isLinkLocalAddress()) v6ll = true;
                    if (x instanceof Inet4Address && x.isSiteLocalAddress()) v4 = true;
                }
                if (!v6ll) continue;
                String h = Inet6Address.getByAddress(null, a.getAddress(), ni).getHostAddress();
                (v4 ? out : later).add(h);
            }
            out.addAll(later);
        } catch (Exception e) {
            Log.w(TAG, "nest: can't scope " + a + ": " + e);
        }
        return out;
    }

    /** Address bytes only, so "fe80::1%wlan0" and "fe80::1" count as the same machine. */
    private static String key(InetAddress a) {
        return java.util.Arrays.toString(a.getAddress());
    }

    private static String attr(NsdServiceInfo s, String key) {
        try {
            Map<String, byte[]> a = s.getAttributes();
            byte[] v = a == null ? null : a.get(key);
            return v == null ? "" : new String(v, "UTF-8");
        } catch (Exception e) { return ""; }
    }

    private static Set<String> ownAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) { out.add(key(a)); out.add(a.getHostAddress()); }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
