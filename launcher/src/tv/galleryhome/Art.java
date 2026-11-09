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
 *  - av-*:  Artvee (artvee.com, public domain): landscapes, Japanese prints, vintage posters, illustrations,
 *           and "amber nights" — warm, dark night scenes picked to match the room's orange lights.
 *           Artvee has no API, so its category pages are read like a browser would (one page per refill).
 * Only landscape, fairly dark pictures are kept so they sit well behind the HUD.
 * Every download is cached on disk, so rotation continues offline. All methods block.
 */
final class Art {
    private static final String TAG = "GalleryHome";
    private static final String UA = "GalleryHome/3.1 (personal Android TV launcher)";
    private static final int MAX_CACHE_FILES = 60;
    // Skip pictures that are too bright to sit behind the dark HUD (space can be a bit brighter).
    // Prints, posters and engravings sit on pale paper: they're allowed brighter and get dimmed more instead (see Item.dim).
    private static float maxLuma(String kind) {
        if (SPACE.equals(kind)) return 0.5f;
        if (ART.equals(kind)) return 0.55f;
        if (AV_LANDSCAPE.equals(kind)) return 0.6f;
        if (AV_AMBER.equals(kind)) return 0.5f;
        if (AV_ASIAN.equals(kind) || AV_POSTERS.equals(kind) || AV_ILLUS.equals(kind)) return 0.85f;
        return 0.36f;
    }

    static final String SPACE = "space", CITY = "city", DARK = "dark", ART = "art";
    static final String AV_LANDSCAPE = "av-landscape", AV_ASIAN = "av-asian", AV_POSTERS = "av-posters",
            AV_ILLUS = "av-illustration", AV_AMBER = "av-amber";
    static final String[] KINDS = {SPACE, CITY, DARK, ART, AV_LANDSCAPE, AV_ASIAN, AV_POSTERS, AV_ILLUS, AV_AMBER};

    /** Artvee category paths (artvee.com/c/<path>/) for each kind. */
    private static final String[] AV_AMBER_CATS = {"landscape", "asian-art", "illustration", "still-life"};
    private static final java.util.regex.Pattern AV_AMBER_WORDS = java.util.regex.Pattern.compile(
            "(?i).*\\b(night|nights|nocturne|moon|moonlight|moonlit|evening|sunset|sundown|dusk|twilight|lantern|lanterns|"
            + "fire|firelight|fireworks|candle|candlelight|lamp|lamplight|torch|torchlight|glow|ember|embers|autumn|"
            + "harbour|harbor|bonfire|furnace|forge|lights)\\b.*");
    /** Not wallpaper material: type specimens, charts and diagrams. */
    private static final java.util.regex.Pattern AV_REJECT = java.util.regex.Pattern.compile(
            "(?i).*\\b(alphabet|alphabets|lettering|letters|font|typeface|specimen|monogram|monograms|diagram|chart|map|"
            + "plan|anatomy|anatomical|pattern|patterns|ornament|ornaments|border|borders|initials|calligraphy|"
            + "title page|frontispiece|cover|sheet music|score)\\b.*");
    /** Kept off a living-room screen: nudes and the usual titles they hide behind. */
    private static final java.util.regex.Pattern AV_NUDE = java.util.regex.Pattern.compile(
            "(?i).*\\b(nude|nudes|naked|bather|bathers|bathing|bath|venus|odalisque|leda|danae|dana\u00eb|susanna|toilette|"
            + "nymph|nymphs|satyr|bacchante|bacchanal|eve|reclining|torso|model|undress|undressing|academy|figure study|"
            + "erotic|harem|bathsheba|diana|psyche|andromeda|galatea|temptation|saint anthony)\\b.*");

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
    /** Japanese woodblock prints from museum open-access collections, used when Artvee's Asian Art listing is empty. */
    private static final String[] JP_QUERIES = {
            "Hiroshige", "Hokusai", "Kawase Hasui", "Ohara Koson", "Kobayashi Kiyochika", "Utagawa Kuniyoshi",
            "Hiroshige night", "Hiroshige snow", "Japanese woodblock moon", "Japanese woodblock evening",
    };

    private static final String[] ART_QUERIES = {
            "nocturne", "night", "moonlight", "landscape", "seascape", "evening", "storm", "winter",
    };

    static final class Item {
        Bitmap bitmap;
        String title;
        String credit;
        /** Extra dimming for bright pictures (pale prints and posters): multiply the wallpaper brightness by this. */
        float dim = 1f;
    }

    private static final class Candidate {
        String url, alt, title, credit, kind, thumb;
        float busy;
        int year;
    }

    private final File dir, logFile;
    private final Random rnd = new Random();
    private final Map<String, List<Candidate>> pools = new HashMap<>();
    private final Map<String, Long> fetchedAt = new HashMap<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private int turn;

    Art(Context ctx) {
        dir = new File(ctx.getCacheDir(), "art");
        File ext = null;
        try { ext = ctx.getExternalFilesDir(null); } catch (Exception ignored) { }
        logFile = new File(ext != null ? ext : ctx.getCacheDir(), "artlog.txt");   // adb-readable: /sdcard/Android/data/tv.galleryhome/files
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
                Item it = decode(f, targetW, targetH, maxLuma(kind), AV_AMBER.equals(kind));
                remember(c.url);
                if (it == null) { discard(f); continue; }     // wrong shape or too bright
                it.title = c.title;
                it.credit = c.credit;
                return it;
            } catch (Exception e) {
                note(kind + " wallpaper failed: " + e);
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
            note(kind + " candidates: " + got.size());
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
            else if (kind.startsWith("av-")) {
                try { out.addAll(fetchArtvee(kind)); } catch (Exception e) { note(kind + " Artvee failed: " + e); }
                if (AV_ASIAN.equals(kind) && out.isEmpty()) out.addAll(fetchJapanesePrints());
            }
        } catch (Exception e) {
            note(kind + " fetch failed: " + e);
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

    private List<Candidate> fetchAic() throws Exception { return fetchAic(ART_QUERIES, ART); }

    private List<Candidate> fetchAic(String[] queries, String kind) throws Exception {
        List<Candidate> out = new ArrayList<>();
        String q = queries[rnd.nextInt(queries.length)];
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
            c.kind = kind;
            out.add(c);
        }
        return out;
    }

    private List<Candidate> fetchCleveland() throws Exception { return fetchCleveland(ART_QUERIES, ART, "Painting"); }

    private List<Candidate> fetchCleveland(String[] queries, String kind, String type) throws Exception {
        List<Candidate> out = new ArrayList<>();
        String q = queries[rnd.nextInt(queries.length)];
        String url = "https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&type=" + type
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
            c.kind = kind;
            out.add(c);
        }
        return out;
    }

    /** Artvee serves the TV an empty Asian Art listing, so Japanese prints also come from two museums' public-domain collections. */
    private List<Candidate> fetchJapanesePrints() {
        List<Candidate> out = new ArrayList<>();
        try { out.addAll(fetchAic(JP_QUERIES, AV_ASIAN)); } catch (Exception e) { note("japanese prints AIC failed: " + e); }
        try { out.addAll(fetchCleveland(JP_QUERIES, AV_ASIAN, "Print")); } catch (Exception e) { note("japanese prints Cleveland failed: " + e); }
        for (Iterator<Candidate> it = out.iterator(); it.hasNext(); ) {
            Candidate c = it.next();
            if (AV_REJECT.matcher(c.title).matches() || AV_NUDE.matcher(c.title).matches()) it.remove();
        }
        note("japanese prints from museums: " + out.size());
        return out;
    }

    // ---------------------------------------------------------------- Artvee (public domain; no API, so pages are read)

    private final Map<String, Integer> avPages = new HashMap<>();

    private List<Candidate> fetchArtvee(String kind) throws Exception {
        boolean amber = AV_AMBER.equals(kind);
        List<String> cats = new ArrayList<>();
        if (amber) {
            List<String> all = new ArrayList<>(java.util.Arrays.asList(AV_AMBER_CATS));
            Collections.shuffle(all, rnd);
            cats.addAll(all.subList(0, 2));
        } else {
            cats.add(AV_LANDSCAPE.equals(kind) ? "landscape" : AV_ASIAN.equals(kind) ? "asian-art"
                    : AV_POSTERS.equals(kind) ? "posters" : "illustration");
        }
        List<Candidate> out = new ArrayList<>();
        for (String cat : cats) {
            List<Candidate> kept = new ArrayList<>();
            for (int round = 0; round < 3 && kept.size() < 4; round++) kept.addAll(artveePage(cat, kind, amber));
            if (busy(kind) && kept.size() > 1) {                         // keep the calmer half: the clock sits bottom-left
                Collections.sort(kept, new java.util.Comparator<Candidate>() {
                    @Override public int compare(Candidate x, Candidate y) { return Float.compare(x.busy, y.busy); }
                });
                kept = new ArrayList<>(kept.subList(0, (kept.size() + 1) / 2));
            }
            out.addAll(kept);
        }
        return out;
    }

    /** One random listing page of a category: the pictures whose previews suit a dark wallpaper. */
    private List<Candidate> artveePage(String cat, String kind, boolean amber) throws Exception {
        String html;
        Integer pages = avPages.get(cat);
        int page = pages == null ? 1 : 1 + rnd.nextInt(pages);           // first visit: page 1, to learn the real page count
        try {
            html = httpString(artveeUrl(cat, page));
        } catch (RuntimeException e) {
            if (page == 1) throw e;
            avPages.remove(cat);                                         // past the last page (or a bad page): page 1
            page = 1;
            html = httpString(artveeUrl(cat, 1));
        }
        int raw = countOf(html, "product-element-bottom");
        if (raw == 0 && page != 1) {                                     // an empty page: fall back to page 1
            avPages.remove(cat);
            page = 1;
            html = httpString(artveeUrl(cat, 1));
            raw = countOf(html, "product-element-bottom");
        }
        String emptyTitle = null;
        for (int tries = 0; raw == 0 && page == 1 && tries < 2; tries++) {
            // Artvee sends the TV an empty page 1 for some categories (Asian Art); the later pages still list artworks.
            if (emptyTitle == null) emptyTitle = titleOf(html);
            Integer hint = AV_PAGE_HINT.get(cat);
            page = 2 + rnd.nextInt(Math.max(1, (hint != null ? hint : 40) - 1));
            try { html = httpString(artveeUrl(cat, page)); } catch (RuntimeException e) { html = ""; }
            raw = countOf(html, "product-element-bottom");
            if (raw > 0 && hint != null && !avPages.containsKey(cat)) avPages.put(cat, hint);
        }
        Integer total = artveeTotal(html);
        List<Candidate> got = parseArtvee(html, kind);
        if (total != null && raw > 0 && (page == 1 || !avPages.containsKey(cat) || AV_PAGE_HINT.containsKey(cat))) {
            int perPage = Math.max(20, raw);                             // items per page as actually served (about 30)
            avPages.put(cat, Math.max(1, (total + perPage - 1) / perPage));
        }
        Collections.shuffle(got, rnd);
        List<Candidate> out = new ArrayList<>();
        int checked = 0, words = 0, bright = 0, cold = 0;
        StringBuilder bz = new StringBuilder();
        for (Candidate c : got) {
            if (out.size() >= 10 || checked >= 24) break;
            if (AV_REJECT.matcher(c.title).matches() || AV_NUDE.matcher(c.title).matches()) { words++; continue; }
            if (AV_POSTERS.equals(kind) && c.year > 1950) { words++; continue; }     // vintage posters only
            boolean named = AV_AMBER_WORDS.matcher(c.title).matches();
            if (c.thumb != null) {                                       // judge by the small preview before downloading
                checked++;
                float[] t = thumbTone(c.thumb);
                if (t == null) continue;
                if (t[0] > maxLuma(kind)) { bright++; continue; }
                if (amber && !(t[1] - t[2] >= 0.05f && t[0] <= 0.45f) && !(named && t[0] <= 0.5f)) { cold++; continue; }
                c.busy = t[4];
                if (busy(kind)) bz.append(Math.round(t[4] * 1000)).append(' ');
                if (busy(kind) && t[4] > BUSY_MAX) continue;
            } else if (amber && !named) {
                continue;
            }
            out.add(c);
        }
        note("artvee " + cat + " page " + page + "/" + avPages.get(cat) + ": " + raw + " on page, " + stats[0] + " without key, " + stats[1] + " wrong shape, " + got.size() + " landscape-shaped, "
                + words + " skipped by title, " + checked + " previews, " + bright + " too bright"
                + (amber ? ", " + cold + " not warm" : "") + ", " + out.size() + " kept"
                + (bz.length() > 0 ? " · corner busyness\u2030 " + bz : "") + (raw == 0 ? " · " + html.length() + " chars" : "") + (emptyTitle != null ? " · page 1 was empty: \"" + emptyTitle + "\"" : ""));
        return out;
    }

    /** Writes the saved Artvee log back to logcat in one burst, so tv.sh diag catches it before logd prunes it. */
    void dumpLog() {
        try {
            for (String l : readString(logFile).split("\n")) Log.i(TAG, "artlog " + l);
        } catch (Exception e) {
            Log.i(TAG, "artlog: none yet (" + e + ")");
        }
    }

    /** Logs, and keeps the last lines in cache/artlog.txt so tv.sh diag can read them after logcat has pruned them. */
    private void note(String line) {
        Log.i(TAG, line);
        try {
            File f = logFile;
            List<String> lines = new ArrayList<>();
            if (f.exists()) lines.addAll(java.util.Arrays.asList(readString(f).split("\n")));
            lines.add(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()) + " " + line);
            while (lines.size() > 80) lines.remove(0);
            OutputStream o = new FileOutputStream(f);
            try { o.write(android.text.TextUtils.join("\n", lines).getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) { }
    }

    /** Rough page counts (about 30 per page), used when page 1 can't be read. */
    private static final Map<String, Integer> AV_PAGE_HINT = new HashMap<>();
    static { AV_PAGE_HINT.put("asian-art", 240); }

    /** The page's <title>, for the log. */
    static String titleOf(String html) {
        int a = html.indexOf("<title"), b = html.indexOf("</title>");
        if (a < 0 || b < a) return "no title";
        int c = html.indexOf('>', a);
        return clip(text(html.substring(c + 1, b)).trim());
    }

    /** Page 1 is the category's own address: Artvee answers /page/1/ with an empty page for some categories. */
    static String artveeUrl(String cat, int page) {
        return "https://artvee.com/c/" + cat + "/" + (page <= 1 ? "" : "page/" + page + "/");
    }

    static int countOf(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /** Edge energy in the clock corner above which a poster or illustration is mostly lettering (hard limit; the calmer half is kept anyway). */
    private static final float BUSY_MAX = 0.16f;

    private static boolean busy(String kind) { return AV_POSTERS.equals(kind) || AV_ILLUS.equals(kind); }

    /** Brightness, red, blue and busyness (edge energy) of a listing thumbnail; null if it can't be read. */
    private static float[] thumbTone(String url) {
        HttpURLConnection con = null;
        try {
            con = open(url);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); total += n; if (total > 1_500_000) break; }
            in.close();
            byte[] d = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (b == null) return null;
            float[] t = tone(b);
            b.recycle();
            return t;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** "1,234 items" in the result counter → 1234. */
    static Integer artveeTotal(String html) {
        int at = html.indexOf("woocommerce-result-count");
        if (at < 0) return null;
        int end = html.indexOf("</p>", at);
        String part = html.substring(at, end > at ? end : Math.min(html.length(), at + 300)).replaceAll("<[^>]*>", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9][0-9,]*)").matcher(part);
        int best = -1;
        while (m.find()) {
            try { best = Math.max(best, Integer.parseInt(m.group(1).replace(",", ""))); } catch (NumberFormatException ignored) { }
        }
        return best > 0 ? best : null;
    }

    private static final java.util.regex.Pattern AV_LINK = java.util.regex.Pattern.compile(
            "href=[\"'](https?://(?:www\\.)?artvee\\.com/dl/[^\"']+)[\"'][^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern AV_ARTIST = java.util.regex.Pattern.compile(
            "woodmart-product-brands-links[^>]*>(.*?)</div>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern AV_SK_KEY = java.util.regex.Pattern.compile("\"sk\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern AV_SDL_SIZE = java.util.regex.Pattern.compile(
            "\"sdlimagesize\"\\s*:\\s*\"(\\d+)\\s*x\\s*(\\d+)");
    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");
    private static final java.util.regex.Pattern PAREN = java.util.regex.Pattern.compile("^(.+?)\\s*\\(([^()]*)\\)\\s*$");

    /** Each artwork on a category page: title link, artist, and a data-sk JSON with the image key and size. */
    /** From the last parseArtvee call: cards without a download key, cards of the wrong shape. */
    static final int[] stats = new int[2];

    static List<Candidate> parseArtvee(String html, String kind) {
        List<Candidate> out = new ArrayList<>();
        stats[0] = stats[1] = 0;
        String[] parts = html.split("product-element-bottom");
        for (int i = 1; i < parts.length; i++) {
            String chunk = parts[i].length() > 8000 ? parts[i].substring(0, 8000) : parts[i];
            try {
                String plain = unescape(chunk);              // data-sk holds JSON; read its fields without a strict parse
                java.util.regex.Matcher sk = AV_SK_KEY.matcher(plain);
                if (!sk.find()) { stats[0]++; continue; }
                String key = sk.group(1);
                java.util.regex.Matcher sz = AV_SDL_SIZE.matcher(plain);
                if (sz.find() && !goodShape(Integer.parseInt(sz.group(1)), Integer.parseInt(sz.group(2)))) { stats[1]++; continue; }
                java.util.regex.Matcher a = AV_LINK.matcher(chunk);
                String title = a.find() ? text(a.group(2)) : "";
                String date = "";
                java.util.regex.Matcher tp = PAREN.matcher(title);
                if (tp.matches()) { title = tp.group(1).trim(); date = tp.group(2).trim(); }
                java.util.regex.Matcher am = AV_ARTIST.matcher(chunk);
                String artist = am.find() ? text(am.group(1)) : "";
                java.util.regex.Matcher ap = PAREN.matcher(artist);
                if (ap.matches()) artist = ap.group(1).trim();
                Candidate c = new Candidate();
                c.url = "https://mdl.artvee.com/sdl/" + key + "sdl.jpg";
                c.thumb = thumbIn(parts[i - 1]);
                c.title = clip(title.isEmpty() ? "Untitled" : title);
                String who = artist + (date.isEmpty() ? "" : (artist.isEmpty() ? "" : ", ") + date);
                c.credit = clip((who.isEmpty() ? "" : who + "  ·  ") + "Artvee (public domain)");
                c.kind = kind;
                java.util.regex.Matcher yr = YEAR.matcher(date);
                if (yr.find()) c.year = Integer.parseInt(yr.group(1));
                out.add(c);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static final java.util.regex.Pattern AV_IMG = java.util.regex.Pattern.compile(
            "(?:data-src|data-lazy-src|data-original|src)=[\"'](https?://[^\"']+?\\.(?:jpe?g|png|webp)(?:\\?[^\"']*)?)[\"']",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The preview image of the artwork whose card ends this chunk (the last <img> before "product-element-bottom"). */
    private static String thumbIn(String before) {
        int k = before.lastIndexOf("<img");
        if (k < 0) return null;
        int e = before.indexOf('>', k);
        String tag = before.substring(k, e > k ? e + 1 : before.length());
        java.util.regex.Matcher m = AV_IMG.matcher(tag);
        String best = null;
        while (m.find()) {                                   // prefer the lazy-load URL over a placeholder src
            String u = unescape(m.group(1));
            if (best == null || tag.substring(Math.max(0, m.start() - 1), m.start() + 5).contains("data-")) best = u;
        }
        return best;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#34;", "\"").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static final java.util.regex.Pattern NUM_ENTITY = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** Tags stripped, entities decoded (named ones above, and any &#8216; / &#x2019; style code). */
    private static String text(String html) {
        String s = unescape(html.replaceAll("<[^>]*>", " ")).replace("&nbsp;", " ");
        java.util.regex.Matcher m = NUM_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (NumberFormatException e) { cp = '?'; }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
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
            if (kind.startsWith("av-") && (AV_NUDE.matcher(lines[0]).matches() || AV_REJECT.matcher(lines[0]).matches())) {
                discard(f);                                              // cached by an older version
                continue;
            }
            if (!ks.contains(kind)) continue;
            Item it = decode(f, tw, th, maxLuma(kind), AV_AMBER.equals(kind));
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

    private static Item decode(File f, int tw, int th, float maxLuma, boolean mustBeWarm) {
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
        float[] t = tone(b);                     // luma, red, blue (0..1)
        if (t[0] > maxLuma) return null;
        if (mustBeWarm && (t[1] - t[2] < 0.05f || t[0] > 0.45f)) return null;   // "amber nights": warm and dark only
        Item it = new Item();
        it.bitmap = b;
        if (t[0] > 0.42f) it.dim = Math.max(0.48f, 0.42f / t[0]);
        return it;
    }

    /** Average brightness, red and blue (0..1) from a tiny copy of the picture. */
    private static float[] tone(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 32, 18, true);
        long sum = 0, red = 0, blue = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int p = s.getPixel(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                sum += (r * 299 + g * 587 + bl * 114) / 1000;
                red += r;
                blue += bl;
            }
        }
        // busyness: mean brightness change between neighbours on a 64 x 36 copy
        Bitmap e = Bitmap.createScaledBitmap(b, 64, 36, true);
        int[] l = new int[64 * 36];
        for (int y = 0; y < 36; y++) for (int x = 0; x < 64; x++) {
            int p = e.getPixel(x, y);
            l[y * 64 + x] = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
        }
        long dx = 0, dy = 0;
        for (int y = 0; y < 36; y++) for (int x = 1; x < 64; x++) dx += Math.abs(l[y * 64 + x] - l[y * 64 + x - 1]);
        for (int y = 1; y < 36; y++) for (int x = 0; x < 64; x++) dy += Math.abs(l[y * 64 + x] - l[(y - 1) * 64 + x]);
        long cx = 0, cy = 0;                     // the clock corner: left 40 %, bottom 45 %
        for (int y = 20; y < 36; y++) for (int x = 1; x < 26; x++) cx += Math.abs(l[y * 64 + x] - l[y * 64 + x - 1]);
        for (int y = 21; y < 36; y++) for (int x = 0; x < 26; x++) cy += Math.abs(l[y * 64 + x] - l[(y - 1) * 64 + x]);
        if (e != b) e.recycle();
        if (s != b) s.recycle();
        float n = 32f * 18f * 255f;
        float busy = dx / (36f * 63f * 255f) + dy / (35f * 64f * 255f);
        float corner = cx / (16f * 25f * 255f) + cy / (15f * 26f * 255f);
        return new float[]{sum / n, red / n, blue / n, busy, corner};
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
