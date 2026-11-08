package tv.galleryhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Wallpaper engine. Kinds (any combination, taking turns):
 *  - space: NASA Image and Video Library (public domain, no key)
 *  - city:  night cities & architecture — Wikimedia Commons featured/quality photos
 *  - dark:  moody landscapes (aurora, stars, mist, blue hour) — Wikimedia Commons featured/quality photos
 *  - art:   public-domain paintings (Art Institute of Chicago, Cleveland Museum of Art)
 * Only landscape, fairly dark pictures are kept so they sit well behind the HUD.
 * Every download is cached on disk, so rotation continues offline. All methods block.
 */
final class Art {
    private static final String TAG = "GalleryHome";
    private static final String UA = "GalleryHome/3.1 (personal Android TV launcher)";
    private static final int MAX_CACHE_FILES = 60;
    // Skip pictures that are too bright to sit behind the dark HUD (space can be a bit brighter).
    private static float maxLuma(String kind) { return SPACE.equals(kind) ? 0.5f : ART.equals(kind) ? 0.55f : 0.36f; }

    static final String SPACE = "space", CITY = "city", DARK = "dark", ART = "art";
    static final String[] KINDS = {SPACE, CITY, DARK, ART};

    private static final String[] NASA_QUERIES = {
            "nebula hubble", "galaxy hubble", "webb nebula", "webb galaxy", "carina nebula",
            "orion nebula", "pillars of creation", "spiral galaxy", "star cluster hubble",
            "earth at night space station", "aurora space station", "city lights night iss",
            "jupiter juno", "saturn cassini", "milky way", "supernova remnant",
    };
    private static final String NASA_REJECT =
            "(?i).*\\b(briefing|conference|portrait|ceremony|meeting|press|logo|patch|award|visit|students?|"
            + "team|launch pad|rollout|interview|training|spacesuit|engineers?|graph|chart|diagram|illustration|"
            + "artist.s concept|animation|map|poster|test|hardware|mockup|infographic)\\b.*";
    // Quoted phrases so e.g. "aurora" doesn't match a ship or a dragonfly called Aurora.
    private static final String[] CITY_QUERIES = {
            "\"skyline at night\"", "\"at night\" skyline", "\"city at night\"", "\"by night\" city",
            "\"Hong Kong\" \"at night\"", "\"Victoria Harbour\" night", "\"Tokyo\" \"at night\"",
            "\"Shanghai\" \"at night\"", "\"Singapore\" \"at night\"", "\"blue hour\" skyline",
            "\"night view\" city", "neon \"at night\"", "\"illuminated at night\"",
    };
    private static final String[] DARK_QUERIES = {
            "\"aurora borealis\"", "\"northern lights\"", "\"Milky Way\" landscape", "\"Milky Way\" over",
            "\"night sky\" mountains", "\"starry sky\"", "\"star trails\"", "\"blue hour\" mountains",
            "\"volcano\" eruption night", "\"moonlight\" landscape", "\"night\" lake stars",
    };
    private static final String[] ART_QUERIES = {
            "nocturne", "night", "moonlight", "landscape", "seascape", "evening", "storm", "winter",
    };

    static final class Item {
        Bitmap bitmap;
        String title;
        String credit;
    }

    private static final class Candidate {
        String url, alt, title, credit, kind;
    }

    private final File dir;
    private final Random rnd = new Random();
    private final Map<String, List<Candidate>> pools = new HashMap<>();
    private final Map<String, Long> fetchedAt = new HashMap<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private int turn;

    Art(Context ctx) {
        dir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    static List<String> kinds(String source) {
        List<String> out = new ArrayList<>();
        if (source != null) {
            for (String k : source.split(",")) {
                k = k.trim();
                for (String known : KINDS) if (known.equals(k) && !out.contains(k)) out.add(k);
            }
        }
        if (out.isEmpty()) { out.add(SPACE); out.add(CITY); out.add(DARK); }
        return out;
    }

    /** Returns the next wallpaper, or null if nothing (online or cached) is available. */
    synchronized Item next(String source, int targetW, int targetH) {
        List<String> ks = kinds(source);
        for (int attempt = 0; attempt < ks.size() * 2; attempt++) {
            String kind = ks.get(turn++ % ks.size());
            try {
                Candidate c = pick(kind);
                if (c == null) continue;
                File f = download(c);
                Item it = decode(f, targetW, targetH, maxLuma(kind));
                remember(c.url);
                if (it == null) { discard(f); continue; }     // wrong shape or too bright
                it.title = c.title;
                it.credit = c.credit;
                return it;
            } catch (Exception e) {
                Log.w(TAG, kind + " wallpaper failed: " + e);
            }
        }
        return fromCache(source, targetW, targetH);
    }

    /** A random picture from the on-disk cache only (fast, no network). */
    synchronized Item cached(String source, int targetW, int targetH) {
        return fromCache(source, targetW, targetH);
    }

    // ---------------------------------------------------------------- pools

    private Candidate pick(String kind) throws Exception {
        List<Candidate> pool = pools.get(kind);
        if (pool == null) { pool = new ArrayList<>(); pools.put(kind, pool); }
        Long at = fetchedAt.get(kind);
        int fresh = 0;
        for (Candidate c : pool) if (!recent.contains(c.url)) fresh++;
        if (fresh < 3 || at == null || System.currentTimeMillis() - at > 12L * 3600 * 1000) {
            List<Candidate> got = fetch(kind);
            Log.i(TAG, kind + " candidates: " + got.size());
            if (!got.isEmpty()) {
                pool.clear();
                pool.addAll(got);
                fetchedAt.put(kind, System.currentTimeMillis());
            }
        }
        List<Candidate> choices = new ArrayList<>();
        for (Candidate c : pool) if (!recent.contains(c.url)) choices.add(c);
        if (choices.isEmpty()) choices = pool;
        if (choices.isEmpty()) return null;
        Candidate c = choices.get(rnd.nextInt(choices.size()));
        pool.remove(c);
        return c;
    }

    private List<Candidate> fetch(String kind) {
        List<Candidate> out = new ArrayList<>();
        try {
            if (SPACE.equals(kind)) out.addAll(fetchNasa());
            else if (CITY.equals(kind)) out.addAll(fetchCommons(CITY_QUERIES, CITY));
            else if (DARK.equals(kind)) out.addAll(fetchCommons(DARK_QUERIES, DARK));
            else if (ART.equals(kind)) {
                try { out.addAll(fetchAic()); } catch (Exception e) { Log.w(TAG, "AIC failed: " + e); }
                out.addAll(fetchCleveland());
            }
        } catch (Exception e) {
            Log.w(TAG, kind + " fetch failed: " + e);
        }
        return out;
    }

    // ---------------------------------------------------------------- NASA

    private List<Candidate> fetchNasa() throws Exception {
        List<Candidate> out = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            String q = NASA_QUERIES[rnd.nextInt(NASA_QUERIES.length)];
            String url = "https://images-api.nasa.gov/search?media_type=image&page_size=60&page="
                    + (1 + rnd.nextInt(2)) + "&q=" + enc(q);
            JSONObject coll = new JSONObject(httpString(url)).optJSONObject("collection");
            JSONArray items = coll == null ? null : coll.optJSONArray("items");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONArray data = item.optJSONArray("data"), links = item.optJSONArray("links");
                if (data == null || links == null || data.length() == 0 || links.length() == 0) continue;
                JSONObject d = data.optJSONObject(0);
                String thumb = links.optJSONObject(0) == null ? "" : links.optJSONObject(0).optString("href", "");
                if (d == null || !thumb.contains("~thumb.")) continue;
                String title = d.optString("title", "");
                if (title.matches(NASA_REJECT)) continue;
                String nasaId = d.optString("nasa_id", "");
                if (nasaId.isEmpty()) continue;
                Candidate c = new Candidate();
                c.url = "nasa:" + nasaId;            // resolved to a real file via the asset manifest on download
                c.title = clip(title);
                String center = d.optString("center", "");
                String year = d.optString("date_created", "");
                year = year.length() >= 4 ? year.substring(0, 4) : "";
                StringBuilder sb = new StringBuilder("NASA");
                if (!center.isEmpty() && !"null".equals(center)) sb.append(" ").append(center);
                if (!year.isEmpty()) sb.append(", ").append(year);
                sb.append("  ·  NASA Image Library");
                c.credit = clip(sb.toString());
                c.kind = SPACE;
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Wikimedia Commons (search)

    private List<Candidate> fetchCommons(String[] queries, String kind) throws Exception {
        List<Candidate> out = new ArrayList<>();
        String[] cats = {"Featured_pictures_on_Wikimedia_Commons", "Quality_images"};
        for (int round = 0; round < 3 && out.size() < 12; round++) {
            String q = queries[rnd.nextInt(queries.length)];
            String cat = cats[round == 2 ? 1 : 0];
            String search = "incategory:" + cat + " " + q;
            String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                    + "&generator=search&gsrnamespace=6&gsrlimit=40&gsrsearch=" + enc(search)
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920"
                    + "&iiextmetadatafilter=ObjectName%7CArtist%7CLicenseShortName";
            JSONObject root = new JSONObject(httpString(url));
            JSONObject query = root.optJSONObject("query");
            JSONObject pages = query == null ? null : query.optJSONObject("pages");
            if (pages == null) continue;
            Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                JSONObject p = pages.optJSONObject(keys.next());
                if (p == null) continue;
                JSONArray ii = p.optJSONArray("imageinfo");
                if (ii == null || ii.length() == 0) continue;
                JSONObject info = ii.optJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                if (w < 1600 || !goodShape(w, h)) continue;
                String thumb = info.optString("thumburl", "");
                String lower = thumb.toLowerCase(Locale.US);
                if (!thumb.startsWith("https://") || lower.endsWith(".tif") || lower.endsWith(".tiff")) continue;
                JSONObject meta = info.optJSONObject("extmetadata");
                Candidate c = new Candidate();
                c.url = thumb;
                String name = meta == null ? null : metaValue(meta, "ObjectName");
                if (name == null || name.isEmpty()) {
                    name = p.optString("title", "").replaceFirst("^File:", "").replaceAll("\\.[A-Za-z]+$", "");
                }
                c.title = clip(name);
                String artist = meta == null ? null : metaValue(meta, "Artist");
                String lic = meta == null ? null : metaValue(meta, "LicenseShortName");
                StringBuilder sb = new StringBuilder();
                if (artist != null && !artist.isEmpty()) sb.append("Photo ").append(artist);
                if (lic != null && !lic.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(lic);
                sb.append(sb.length() > 0 ? "  ·  " : "").append("Wikimedia Commons");
                c.credit = clip(sb.toString());
                c.kind = kind;
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- museums (optional "art" kind)

    private List<Candidate> fetchAic() throws Exception {
        List<Candidate> out = new ArrayList<>();
        String q = ART_QUERIES[rnd.nextInt(ART_QUERIES.length)];
        String url = "https://api.artic.edu/api/v1/artworks/search?q=" + enc(q)
                + "&" + enc("query[term][is_public_domain]") + "=true"
                + "&fields=id,title,artist_title,date_display,image_id,thumbnail&limit=40&page=" + (1 + rnd.nextInt(3));
        JSONArray data = new JSONObject(httpString(url)).optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i);
            if (a == null) continue;
            String imageId = a.optString("image_id", "");
            JSONObject th = a.optJSONObject("thumbnail");
            if (imageId.isEmpty() || "null".equals(imageId) || th == null) continue;
            if (!goodShape(th.optInt("width"), th.optInt("height"))) continue;
            Candidate c = new Candidate();
            c.url = "https://www.artic.edu/iiif/2/" + imageId + "/full/1686,/0/default.jpg";
            c.title = clip(a.optString("title", ""));
            String artist = a.optString("artist_title", ""), date = a.optString("date_display", "");
            if ("null".equals(artist)) artist = "";
            if ("null".equals(date)) date = "";
            c.credit = clip((artist + (date.isEmpty() ? "" : (artist.isEmpty() ? "" : ", ") + date)
                    + "  ·  Art Institute of Chicago").replaceFirst("^  ·  ", ""));
            c.kind = ART;
            out.add(c);
        }
        return out;
    }

    private List<Candidate> fetchCleveland() throws Exception {
        List<Candidate> out = new ArrayList<>();
        String q = ART_QUERIES[rnd.nextInt(ART_QUERIES.length)];
        String url = "https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&type=Painting"
                + "&limit=100&skip=" + rnd.nextInt(2) * 100 + "&q=" + enc(q) + "&fields=title,creation_date,creators,images";
        JSONArray data = new JSONObject(httpString(url)).optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i);
            JSONObject imgs = a == null ? null : a.optJSONObject("images");
            JSONObject img = imgs == null ? null : (imgs.optJSONObject("print") != null ? imgs.optJSONObject("print") : imgs.optJSONObject("web"));
            if (img == null) continue;
            String u = img.optString("url", "");
            if (!u.startsWith("https://") || !goodShape(img.optInt("width"), img.optInt("height"))) continue;
            Candidate c = new Candidate();
            c.url = u;
            c.title = clip(a.optString("title", ""));
            String artist = "";
            JSONArray cr = a.optJSONArray("creators");
            if (cr != null && cr.length() > 0 && cr.optJSONObject(0) != null) {
                artist = cr.optJSONObject(0).optString("description", "").replaceAll("\\s*\\(.*$", "");
            }
            String date = a.optString("creation_date", "");
            if ("null".equals(date)) date = "";
            c.credit = clip((artist + (date.isEmpty() ? "" : (artist.isEmpty() ? "" : ", ") + date)
                    + "  ·  Cleveland Museum of Art").replaceFirst("^  ·  ", ""));
            c.kind = ART;
            out.add(c);
        }
        return out;
    }

    // ---------------------------------------------------------------- cache

    private File download(Candidate c) throws Exception {
        String key = md5(c.url);
        File img = new File(dir, key + ".jpg");
        File meta = new File(dir, key + ".txt");
        if (!img.exists() || img.length() == 0) {
            File tmp = new File(dir, key + ".part");
            if (c.url.startsWith("nasa:")) {
                fetchTo(nasaFile(c.url.substring(5)), tmp);
            } else try {
                fetchTo(c.url, tmp);
            } catch (Exception first) {
                if (c.alt == null) throw first;
                fetchTo(c.alt, tmp);
            }
            if (!tmp.renameTo(img)) throw new RuntimeException("rename failed");
            OutputStream mo = new FileOutputStream(meta);
            try { mo.write((c.title + "\n" + c.credit + "\n" + c.kind).getBytes("UTF-8")); } finally { mo.close(); }
            trimCache();
        } else {
            //noinspection ResultOfMethodCallIgnored
            img.setLastModified(System.currentTimeMillis());
        }
        return img;
    }

    /** NASA file names vary (~large.jpg, ~orig.jpg, …): pick the best JPEG listed in the asset manifest. */
    private static String nasaFile(String nasaId) throws Exception {
        JSONObject coll = new JSONObject(httpString("https://images-api.nasa.gov/asset/" + enc(nasaId).replace("+", "%20")))
                .optJSONObject("collection");
        JSONArray items = coll == null ? null : coll.optJSONArray("items");
        if (items == null) throw new RuntimeException("no NASA asset list for " + nasaId);
        String large = null, orig = null, medium = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            String h = o == null ? "" : o.optString("href", "");
            String l = h.toLowerCase(Locale.US);
            if (!(l.endsWith(".jpg") || l.endsWith(".jpeg"))) continue;
            if (l.contains("~large")) large = h;
            else if (l.contains("~orig")) orig = h;
            else if (l.contains("~medium")) medium = h;
        }
        String pick = large != null ? large : orig != null ? orig : medium;
        if (pick == null) throw new RuntimeException("no JPEG for " + nasaId);
        return pick.replace("http://", "https://").replace(" ", "%20");
    }

    private static void fetchTo(String url, File out) throws Exception {
        HttpURLConnection con = open(url);
        try {
            if (con.getResponseCode() != 200) throw new RuntimeException("HTTP " + con.getResponseCode() + " " + url);
            InputStream in = con.getInputStream();
            OutputStream o = new FileOutputStream(out);
            try { copy(in, o); } finally { o.close(); in.close(); }
        } finally {
            con.disconnect();
        }
    }

    private void discard(File img) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, img.getName().replace(".jpg", ".txt")).delete();
        //noinspection ResultOfMethodCallIgnored
        img.delete();
    }

    private Item fromCache(String source, int tw, int th) {
        List<String> ks = kinds(source);
        File[] files = dir.listFiles();
        if (files == null) return null;
        List<File> jpgs = new ArrayList<>();
        for (File f : files) if (f.getName().endsWith(".jpg")) jpgs.add(f);
        Collections.shuffle(jpgs, rnd);
        for (File f : jpgs) {
            if (recent.contains(f.getName()) && jpgs.size() > 3) continue;
            String[] lines;
            try { lines = readString(new File(dir, f.getName().replace(".jpg", ".txt"))).split("\n"); }
            catch (Exception e) { continue; }
            String kind = lines.length > 2 ? lines[2].trim() : "nature";
            if (!ks.contains(kind)) continue;
            Item it = decode(f, tw, th, maxLuma(kind));
            if (it == null) continue;
            remember(f.getName());
            it.title = lines[0];
            it.credit = lines.length > 1 ? lines[1] : "";
            return it;
        }
        return null;
    }

    private void trimCache() {
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> jpgs = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".jpg")) jpgs.add(f);
            else if (f.getName().endsWith(".part")) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        if (jpgs.size() <= MAX_CACHE_FILES) return;
        Collections.sort(jpgs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (int i = 0; i < jpgs.size() - MAX_CACHE_FILES; i++) discard(jpgs.get(i));
    }

    private static Item decode(File f, int tw, int th, float maxLuma) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        if (!goodShape(o.outWidth, o.outHeight)) return null;
        // Full-screen but dimmed behind the HUD: ~1600px+ is plenty and keeps memory low.
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try {
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        } catch (OutOfMemoryError e) {
            d.inSampleSize = sample * 2;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        }
        if (b == null) return null;
        if (luma(b) > maxLuma) return null;
        Item it = new Item();
        it.bitmap = b;
        return it;
    }

    /** Average brightness 0..1 from a tiny copy of the picture. */
    private static float luma(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 32, 18, true);
        long sum = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int p = s.getPixel(x, y);
                sum += (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            }
        }
        if (s != b) s.recycle();
        return sum / (32f * 18f * 255f);
    }

    /** Landscape pictures that fill a 16:9 screen without heavy cropping. */
    private static boolean goodShape(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        float r = w / (float) h;
        return r >= 1.3f && r <= 2.3f;
    }

    private void remember(String key) {
        recent.remove(key);
        recent.addLast(key);
        while (recent.size() > 40) recent.removeFirst();
    }

    // ---------------------------------------------------------------- http helpers

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("AIC-User-Agent", UA);
        con.setInstanceFollowRedirects(true);
        return con;
    }

    private static String httpString(String url) throws Exception {
        HttpURLConnection con = open(url);
        try {
            if (con.getResponseCode() != 200) throw new RuntimeException("HTTP " + con.getResponseCode() + " " + url);
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try { copy(in, bo); } finally { in.close(); }
            return bo.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static String readString(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try { copy(in, bo); } finally { in.close(); }
        return bo.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String metaValue(JSONObject meta, String key) {
        JSONObject o = meta.optJSONObject(key);
        if (o == null) return null;
        String v = o.optString("value", "");
        return Html.fromHtml(v).toString().replaceAll("\\s+", " ").trim();
    }

    private static String clip(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 110 ? s.substring(0, 107) + "…" : s;
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
