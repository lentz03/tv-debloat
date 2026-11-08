package tv.galleryhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import java.io.InputStream;
import java.util.Calendar;
import java.util.Locale;

/** Backgrounds for the commute board, chosen from its menu (OK). Overlays are 1920 × 1080 PNGs in assets/bg. */
final class CommuteBackgrounds {
    private CommuteBackgrounds() { }

    static final String[] KEYS = {"ink", "sky", "weather", "contours", "trails", "skyline", "sharp", "led"};
    static final String[] NAMES = {"Ink", "Sky by the hour", "Weather", "Contours", "Light trails", "Skyline",
            "Sharp and dark", "LED board"};

    static String name(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return NAMES[0];
    }

    /** Keys from older versions (route map, blurred wallpaper) fall back to Ink. */
    static String valid(String key) {
        for (String k : KEYS) if (k.equals(key)) return key;
        return "ink";
    }

    // ------------------------------------------------------------------ overlays

    private static String cachedName;
    private static Bitmap cachedBitmap;

    /** One overlay is kept in memory at a time (~8 MB). */
    static synchronized Bitmap overlay(Context c, String name) {
        if (name.equals(cachedName) && cachedBitmap != null) return cachedBitmap;
        InputStream in = null;
        try {
            in = c.getAssets().open("bg/" + name + ".png");
            Bitmap b = BitmapFactory.decodeStream(in);
            cachedName = name;
            cachedBitmap = b;
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ sky by the hour

    // hour → (top colour, middle colour): night, dawn, day, dusk, night
    private static final float[] HOURS = {0f, 5f, 6.5f, 8.5f, 16.5f, 18.3f, 19.8f, 24f};
    private static final int[] TOP = {0xFF0B0D12, 0xFF0B0D12, 0xFF3A2418, 0xFF1B2735, 0xFF1B2735, 0xFF2A1D3A, 0xFF0B0D12, 0xFF0B0D12};
    private static final int[] MID = {0xFF07080B, 0xFF07080B, 0xFF140D09, 0xFF121A24, 0xFF121A24, 0xFF110C18, 0xFF07080B, 0xFF07080B};

    static float hourNow() {
        Calendar c = Calendar.getInstance(Commute.HK, Locale.US);
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
    }

    static boolean night(float h) { return h < 6.5f || h >= 19f; }

    /** The sky gradient for this time of day, blended smoothly between dawn, day, dusk and night. */
    static GradientDrawable sky(float h) {
        int i = 0;
        while (i < HOURS.length - 2 && h >= HOURS[i + 1]) i++;
        float t = (h - HOURS[i]) / (HOURS[i + 1] - HOURS[i]);
        int top = mix(TOP[i], TOP[i + 1], t), mid = mix(MID[i], MID[i + 1], t);
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, mid, 0xFF08090C, Noir.INK});
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ weather

    /** What the weather background shows: a base gradient, an overlay (or null) and how strong it is. */
    static final class Look {
        GradientDrawable base;
        String overlay;
        float alpha = 1f;
    }

    /** Follows the HKO report: rain streaks for rain (stronger under a rainstorm warning), cloud, or stars on a clear night. */
    static Look weather(Weather.Data d, float hour) {
        Look l = new Look();
        boolean isNight = night(hour);
        String icon = d == null || !d.ok() ? null : d.icon;
        boolean rainWarning = false;
        if (d != null) for (Weather.Warn w : d.warns) if (w.text != null && w.text.toLowerCase(Locale.US).contains("rain")) rainWarning = true;
        if (rainWarning || "rain".equals(icon) || "thunder".equals(icon)) {
            l.base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF141A21, 0xFF0A0C10, Noir.INK});
            l.overlay = "rain";
            l.alpha = rainWarning || "thunder".equals(icon) ? 1f : 0.75f;
        } else if ("cloud".equals(icon) || "mist".equals(icon)) {
            l.base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF14171C, 0xFF0B0D10, Noir.INK});
            l.overlay = "clouds";
        } else if ("suncloud".equals(icon)) {
            l.base = sky(hour);
            l.overlay = "clouds";
            l.alpha = 0.6f;
        } else if (isNight || "moon".equals(icon)) {
            l.base = sky(hour);
            l.overlay = "stars";
        } else {
            l.base = sky(hour);                       // sunny: just the daytime sky
        }
        return l;
    }

    // ------------------------------------------------------------------ LED board & sharp wallpaper

    /** A faint dot grid like an airport departure board, fading out towards the edges. Drawn once, then cached. */
    static final class Led extends View {
        private Bitmap cache;

        Led(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
                cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas b = new Canvas(cache);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                float step = Ui.dp(getContext(), 6), r = Math.max(1f, Ui.dp(getContext(), 0.65f));
                float cx = w / 2f, cy = h * 0.45f, rx = w * 0.9f / 2f * 1.4f, ry = h * 0.85f / 2f * 1.4f;
                for (float y = step / 2; y < h; y += step) {
                    for (float x = step / 2; x < w; x += step) {
                        float dx = (x - cx) / rx, dy = (y - cy) / ry;
                        float dd = (float) Math.sqrt(dx * dx + dy * dy);
                        float a = dd < 0.35f ? 1f : Math.max(0f, 1f - (dd - 0.35f) / 0.65f);
                        if (a <= 0) continue;
                        p.setColor(((int) (a * 0.11f * 255) << 24) | 0x00ECE4D8);
                        b.drawCircle(x, y, r, p);
                    }
                }
            }
            c.drawBitmap(cache, 0, 0, null);
        }
    }

    /** Darkens the left (journey) and right (list) sides of a sharp wallpaper so the text stays readable. */
    static View sharpShade(Context c) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x8C050505, 0x26050505, 0x99050505}));
        return v;
    }
}
