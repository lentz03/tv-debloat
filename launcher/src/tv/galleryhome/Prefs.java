package tv.galleryhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small wrapper around SharedPreferences. */
final class Prefs {
    /** Default dock, in order. Apps that aren't installed are simply skipped. */
    static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv",          // YouTube
            "com.netflix.ninja",                      // Netflix
            "com.disney.disneyplus",                  // Disney+
            "com.amazon.amazonvideo.livingroom",      // Prime Video
            "com.wbd.stream",                         // HBO Max (current)
            "com.hbo.hbonow",                         // HBO Max (older builds)
            DockExtras.COMMUTE,                       // Commute board (built in)
            "com.spotify.tv.android",                 // Spotify
    };

    static final String SOURCE_BOTH = "both";
    static final String SOURCE_NATURE = "nature";
    static final String SOURCE_ART = "art";

    private final SharedPreferences sp;

    static final String APPLE_TV = "com.apple.atve.androidtv.appletv";

    Prefs(Context c) {
        sp = c.getSharedPreferences("galleryhome", Context.MODE_PRIVATE);
        migrate();
    }

    private static final String SPELUNKR = "web:spelunkr";   // removed in 5.3

    /** 5.3: COMMUTE takes the place of SPELUNKR (5.2) or Apple TV (5.1) in a dock saved before. Runs once. */
    private void migrate() {
        if (sp.getBoolean("mig_commute", false)) return;
        String s = sp.getString("dock", null);
        if (s != null) {
            List<String> d = new ArrayList<>(Arrays.asList(s.split(",")));
            if (!d.contains(DockExtras.COMMUTE)) {
                int i = d.indexOf(SPELUNKR);
                if (i < 0) i = d.indexOf(APPLE_TV);
                if (i >= 0) d.set(i, DockExtras.COMMUTE); else d.add(DockExtras.COMMUTE);
            }
            d.remove(SPELUNKR);
            d.remove(APPLE_TV);
            sp.edit().putString("dock", TextUtils.join(",", d)).apply();
        }
        sp.edit().putBoolean("mig_commute", true).remove("spelunkr_logo").apply();
    }

    List<String> dock() {
        String s = sp.getString("dock", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_DOCK));
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (p.length() > 0) out.add(p);
        return out;
    }

    void setDock(List<String> pkgs) {
        sp.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }

    void resetDock() {
        sp.edit().remove("dock").apply();
    }

    /** Wallpaper kinds, comma separated (see Art.KINDS). New key so older "art"/"nature" choices don't carry over. */
    String source() { return sp.getString("wallpapers", "space,city,dark"); }
    void setSource(String s) { sp.edit().putString("wallpapers", s).apply(); }

    int intervalMinutes() { return sp.getInt("interval", 5); }
    void setIntervalMinutes(int m) { sp.edit().putInt("interval", m).apply(); }

    /** auto (light by day, dark from 6pm to 7am), light, or dark */
    String theme() { return sp.getString("theme", "auto"); }
    void setTheme(String t) { sp.edit().putString("theme", t).apply(); }

    /** How bright the painting is behind the HUD (0.45 dim … 0.8 bright). */
    float brightness() { return sp.getFloat("brightness", 0.62f); }
    void setBrightness(float b) { sp.edit().putFloat("brightness", b).apply(); }

    /** Home screen style: "line" (Logo Line), "orbs" (Glass Orbs) or "hud" (Noir HUD). */
    String style() { return sp.getString("style", "line"); }
    void setStyle(String s) { sp.edit().putString("style", s).apply(); }

    boolean weather() { return sp.getBoolean("weather", true); }
    void setWeather(boolean b) { sp.edit().putBoolean("weather", b).apply(); }

    /** Mini player for the Nest Mini (Google Cast) on the home screen, wallpaper mode and screensaver. */
    boolean player() { return sp.getBoolean("player", true); }
    void setPlayer(boolean b) { sp.edit().putBoolean("player", b).apply(); }

    /** Commute board background (see CommuteBackgrounds.KEYS); old "map" / "blur" fall back to Ink. */
    String commuteBg() { return sp.getString("commute_bg", "ink"); }
    void setCommuteBg(String k) { sp.edit().putString("commute_bg", k).apply(); }

    /** Commute board journey style: "line" (one route line with times) or "glyphs" (three steps, each with its piece of route). */
    String commuteJourney() { return sp.getString("commute_journey", "line"); }
    void setCommuteJourney(String k) { sp.edit().putString("commute_journey", k).apply(); }

    boolean captions() { return sp.getBoolean("captions", true); }
    void setCaptions(boolean b) { sp.edit().putBoolean("captions", b).apply(); }
}
