package tv.galleryhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Hong Kong Observatory open data (free, no key): current weather, 9-day forecast and warnings.
 * North Point has no HKO temperature sensor; Happy Valley is the nearest station to Kei Wah Court.
 * The raw responses are kept in SharedPreferences so the last known weather shows instantly
 * (and offline). fetch() blocks; call it from a background thread.
 */
final class Weather {
    private static final String TAG = "GalleryHome";
    private static final String BASE = "https://data.weather.gov.hk/weatherAPI/opendata/weather.php?lang=en&dataType=";
    static final long REFRESH_MS = 10 * 60 * 1000L;
    static final String[] STATIONS = {"Happy Valley", "Hong Kong Observatory", "Shau Kei Wan", "Hong Kong Park"};

    static final class Day { String label; int temp; String icon; }
    static final class Warn { String text; String icon; boolean severe; int rank; }
    static final class Data {
        int temp = Integer.MIN_VALUE;
        String icon, cond;
        int humidity = -1;
        final List<Day> days = new ArrayList<>();
        final List<Warn> warns = new ArrayList<>();
        long at;
        boolean ok() { return temp != Integer.MIN_VALUE; }
    }

    private Weather() { }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("weather", Context.MODE_PRIVATE); }

    static long age(Context c) { return System.currentTimeMillis() - sp(c).getLong("at", 0); }

    static Data cached(Context c) {
        SharedPreferences s = sp(c);
        Data d = parse(s.getString("rh", null), s.getString("fnd", null), s.getString("warn", null));
        if (d != null) d.at = s.getLong("at", 0);
        return d;
    }

    static Data fetch(Context c) {
        String rh = null, fnd = null, warn = null;
        try { rh = http(BASE + "rhrread"); } catch (Exception e) { Log.w(TAG, "weather rhrread failed: " + e); }
        try { fnd = http(BASE + "fnd"); } catch (Exception e) { Log.w(TAG, "weather fnd failed: " + e); }
        try { warn = http(BASE + "warnsum"); } catch (Exception e) { Log.w(TAG, "weather warnsum failed: " + e); }
        SharedPreferences.Editor ed = sp(c).edit();
        if (rh != null) ed.putString("rh", rh);
        if (fnd != null) ed.putString("fnd", fnd);
        if (warn != null) ed.putString("warn", warn);
        if (rh != null) ed.putLong("at", System.currentTimeMillis());
        ed.apply();
        Data d = cached(c);
        if (d != null) {
            Log.i(TAG, "weather: " + d.temp + "C icon=" + d.icon + " (" + d.cond + ") days=" + d.days.size()
                    + " warnings=" + d.warns.size());
        }
        return d;
    }

    // ---------------------------------------------------------------- parsing

    static Data parse(String rh, String fnd, String warn) {
        if (rh == null) return null;
        Data d = new Data();
        try {
            JSONObject r = new JSONObject(rh);
            JSONObject t = r.optJSONObject("temperature");
            JSONArray td = t == null ? null : t.optJSONArray("data");
            if (td != null) {
                for (String want : STATIONS) {
                    for (int i = 0; i < td.length() && d.temp == Integer.MIN_VALUE; i++) {
                        JSONObject o = td.optJSONObject(i);
                        if (o != null && want.equalsIgnoreCase(o.optString("place")) && o.has("value")) {
                            d.temp = (int) Math.round(o.optDouble("value"));
                        }
                    }
                }
                if (d.temp == Integer.MIN_VALUE && td.length() > 0) d.temp = (int) Math.round(td.optJSONObject(0).optDouble("value"));
            }
            JSONObject h = r.optJSONObject("humidity");
            JSONArray hd = h == null ? null : h.optJSONArray("data");
            if (hd != null && hd.length() > 0) d.humidity = hd.optJSONObject(0).optInt("value", -1);
            JSONArray ic = r.optJSONArray("icon");
            int code = ic != null && ic.length() > 0 ? ic.optInt(0) : 0;
            d.icon = icon(code, null);
            d.cond = cond(code);
        } catch (Exception e) {
            Log.w(TAG, "weather rhrread parse: " + e + " :: " + head(rh));
            return null;
        }
        try {
            if (fnd != null) {
                JSONArray wf = new JSONObject(fnd).optJSONArray("weatherForecast");
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("Asia/Hong_Kong"));
                String today = f.format(new Date());
                for (int i = 0; wf != null && i < wf.length() && d.days.size() < 3; i++) {
                    JSONObject o = wf.optJSONObject(i);
                    if (o == null) continue;
                    String date = o.optString("forecastDate", "");
                    if (date.compareTo(today) <= 0) continue;          // only days after today
                    Day day = new Day();
                    String wk = o.optString("week", "");
                    day.label = wk.length() >= 3 ? wk.substring(0, 3).toUpperCase(Locale.US) : wk.toUpperCase(Locale.US);
                    JSONObject mx = o.optJSONObject("forecastMaxtemp");
                    day.temp = mx == null ? Integer.MIN_VALUE : (int) Math.round(mx.optDouble("value"));
                    int fc = o.optInt("ForecastIcon", o.optInt("forecastIcon", 0));
                    day.icon = icon(fc, o.optString("forecastWeather", ""));
                    d.days.add(day);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "weather fnd parse: " + e + " :: " + head(fnd));
        }
        try {
            if (warn != null && warn.trim().startsWith("{")) {
                JSONObject w = new JSONObject(warn);
                Iterator<String> keys = w.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject o = w.optJSONObject(key);
                    if (o == null || "CANCEL".equalsIgnoreCase(o.optString("actionCode"))) continue;
                    Warn x = warning(o.optString("code", key), key, o.optString("name", key));
                    if (x != null) d.warns.add(x);
                }
                Collections.sort(d.warns, new Comparator<Warn>() {
                    @Override public int compare(Warn a, Warn b) {
                        if (a.severe != b.severe) return a.severe ? -1 : 1;
                        return b.rank - a.rank;
                    }
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "weather warnsum parse: " + e + " :: " + head(warn));
        }
        return d;
    }

    /** HKO weather icon numbers → glyph name; falls back to words in the forecast text. */
    static String icon(int code, String text) {
        if (code == 50 || code == 81 || code == 90 || code == 91) return "sun";
        if (code == 51 || code == 52) return "suncloud";
        if (code == 53 || code == 54 || (code >= 62 && code <= 64)) return "rain";
        if (code == 65) return "thunder";
        if (code == 60 || code == 61 || code == 76 || code == 80 || code == 82 || code == 92 || code == 93) return "cloud";
        if (code >= 70 && code <= 77) return "moon";
        if (code >= 83 && code <= 85) return "mist";
        String t = text == null ? "" : text.toLowerCase(Locale.US);
        if (t.contains("thunder")) return "thunder";
        if (t.contains("rain") || t.contains("shower")) return "rain";
        if (t.contains("sunny") || t.contains("fine")) return t.contains("cloud") ? "suncloud" : "sun";
        if (t.contains("cloud") || t.contains("overcast")) return "cloud";
        return "cloud";
    }

    static String cond(int code) {
        switch (code) {
            case 50: return "Sunny"; case 51: return "Sunny periods"; case 52: return "Sunny intervals";
            case 53: return "Sunny periods, few showers"; case 54: return "Sunny intervals, showers";
            case 60: return "Cloudy"; case 61: return "Overcast"; case 62: return "Light rain";
            case 63: return "Rain"; case 64: return "Heavy rain"; case 65: return "Thunderstorms";
            case 76: return "Mainly cloudy"; case 77: return "Mainly fine";
            case 80: return "Windy"; case 81: return "Dry"; case 82: return "Humid"; case 83: return "Fog";
            case 84: return "Mist"; case 85: return "Haze"; case 90: return "Hot"; case 91: return "Warm";
            case 92: return "Cool"; case 93: return "Cold";
            default: return code >= 70 && code <= 75 ? "Fine" : "";
        }
    }

    /** HKO warning codes → badge. Serious ones (T8+, red/black rain) are drawn solid. */
    static Warn warning(String code, String key, String name) {
        Warn w = new Warn();
        String c = code == null ? "" : code.toUpperCase(Locale.US);
        if (c.startsWith("TC")) {
            String n = c.substring(2);                      // 1, 3, 8NE, 8SE, 8NW, 8SW, 9, 10
            String num = n.replaceAll("[^0-9]", ""), dir = n.replaceAll("[0-9]", "");
            w.text = "T" + num + (dir.isEmpty() ? "" : " " + dir);
            int v = num.isEmpty() ? 0 : Integer.parseInt(num);
            w.severe = v >= 8;
            w.rank = 60 + v * 4;
            w.icon = "cyclone";
            return w;
        }
        switch (c) {
            case "WRAINA": w.text = "Amber rain"; w.icon = "drops"; w.rank = 50; break;
            case "WRAINR": w.text = "Red rain"; w.icon = "drops"; w.rank = 85; w.severe = true; break;
            case "WRAINB": w.text = "Black rain"; w.icon = "drops"; w.rank = 95; w.severe = true; break;
            case "WTS": w.text = "Thunderstorm"; w.icon = "bolt"; w.rank = 40; break;
            case "WL": w.text = "Landslip"; w.icon = "drops"; w.rank = 45; break;
            case "WFNTSA": w.text = "Flooding NE NT"; w.icon = "drops"; w.rank = 42; break;
            case "WHOT": w.text = "Very hot"; w.icon = "thermo"; w.rank = 30; break;
            case "WCOLD": w.text = "Cold"; w.icon = "thermo"; w.rank = 30; break;
            case "WFROST": w.text = "Frost"; w.icon = "thermo"; w.rank = 28; break;
            case "WMSGNL": w.text = "Strong monsoon"; w.icon = "cyclone"; w.rank = 35; break;
            case "WFIREY": w.text = "Yellow fire"; w.rank = 20; break;
            case "WFIRER": w.text = "Red fire"; w.rank = 25; break;
            case "WTMW": w.text = "Tsunami"; w.rank = 70; break;
            default:
                if (name == null || name.isEmpty()) return null;
                w.text = name.length() > 18 ? name.substring(0, 18) : name;
                w.rank = 10;
        }
        return w;
    }

    // ---------------------------------------------------------------- http

    private static String http(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "GalleryHome/5.0 (personal Android TV launcher)");
        try {
            if (con.getResponseCode() != 200) throw new RuntimeException("HTTP " + con.getResponseCode());
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            try { while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); } finally { in.close(); }
            return bo.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static String head(String s) {
        if (s == null) return "null";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
