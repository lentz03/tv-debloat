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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Departures near Kei Wah Court (9 Tin Chiu Street, North Point), all from official open data:
 *  - Citybus ETA (rt.data.gov.hk v2): A11 from North Point Ferry Pier, A12 and B13 from the nearest King's Road stop.
 *    The nearest stop on each route is found automatically from the stop coordinates, then cached for a week.
 *  - MTR Next Train (rt.data.gov.hk v1): Island Line both ways at North Point, and the work journey
 *    TKO Line North Point → Yau Tong, Kwun Tong Line Yau Tong → Kowloon Tong.
 *  - Sun Ferry live departures (North Point – Hung Hom / Kowloon City) over the Transport Department timetable;
 *    Fortune Ferry (North Point – Kwun Tong / Kai Tak) from its timetable.
 *  - Public holidays from 1823.gov.hk, for the ferry timetables.
 * Not thread-safe: call from one background thread.
 */
final class Commute {
    private static final String TAG = "GalleryHome";
    static final TimeZone HK = TimeZone.getTimeZone("Asia/Hong_Kong");
    static final double HOME_LAT = 22.29139, HOME_LNG = 114.20306;        // Tin Chiu Street

    // Walking minutes from the front door (to the platform for the MTR)
    static final int WALK_ISL = 5, WALK_TKL = 6, WALK_PIER = 5;
    static final int YAU_TONG_CHANGE = 2;                                   // TKO Line → Kwun Tong Line platform
    static final int YAU_TONG_TO_KOWLOON_TONG = 17;                         // 9 stops on the Kwun Tong Line

    /** Citybus routes: route, direction, where it goes. Add more here. */
    static final String[][] BUSES = {
            {"A11", "outbound", "Airport"},
            {"A12", "outbound", "Airport"},
            {"B13", "inbound", "Huanggang"},
    };

    // ------------------------------------------------------------------ model

    static final class Dep {
        final long at;
        boolean live, cancelled;
        String note = "";
        Dep(long at, boolean live) { this.at = at; this.live = live; }
    }

    static final class Line {
        String code, name;
        int walk;
        boolean live, ok;
        String error;                         // e.g. MTR special arrangement message
        final List<Dep> deps = new ArrayList<>();
    }

    /** The journey to work: leave home, TKO Line from North Point, change at Yau Tong, Kowloon Tong. */
    static final class Work {
        long leave, tko, yauTong, ktl, arrive;
        boolean yauTongEst, ktlEst;
    }

    static final class Board {
        final List<Line> bus = new ArrayList<>(), mtr = new ArrayList<>(), ferry = new ArrayList<>();
        final List<Work> work = new ArrayList<>();   // next few chains, soonest first
        String mtrNote;                               // service message for the TKO / Kwun Tong lines
        long updated;
    }

    private final SharedPreferences sp;
    private final Board last = new Board();
    private long mtrAt, busAt, ferryAt;

    Commute(Context c) {
        sp = c.getSharedPreferences("commute", Context.MODE_PRIVATE);
    }

    private static Commute shared;

    /** One instance per process, so re-opening the board reuses fresh data instead of asking every API again. */
    static synchronized Commute get(Context c) {
        if (shared == null) shared = new Commute(c.getApplicationContext());
        return shared;
    }

    /** Refreshes what is due (MTR and buses every 20 s, ferries every minute) and returns a fresh snapshot. */
    synchronized Board refresh(boolean force) {
        long now = System.currentTimeMillis();
        Board b = new Board();
        if (force || now - busAt > 20_000 || last.bus.isEmpty()) { fetchBuses(b.bus); busAt = now; } else b.bus.addAll(last.bus);
        if (force || now - mtrAt > 20_000 || last.mtr.isEmpty()) { fetchMtr(b); mtrAt = now; }
        else { b.mtr.addAll(last.mtr); b.work.addAll(last.work); b.mtrNote = last.mtrNote; }
        if (force || now - ferryAt > 60_000 || last.ferry.isEmpty()) { fetchFerries(b.ferry); ferryAt = now; } else b.ferry.addAll(last.ferry);
        b.updated = now;
        last.bus.clear(); last.bus.addAll(b.bus);
        last.mtr.clear(); last.mtr.addAll(b.mtr);
        last.work.clear(); last.work.addAll(b.work);
        last.mtrNote = b.mtrNote;
        last.ferry.clear(); last.ferry.addAll(b.ferry);
        return b;
    }

    // ------------------------------------------------------------------ Citybus

    private void fetchBuses(List<Line> out) {
        for (String[] r : BUSES) {
            Line l = new Line();
            l.code = r[0];
            l.live = true;
            try {
                String[] stop = nearestStop(r[0], r[1]);          // id, seq, name, metres
                l.name = r[2] + " · " + shortStop(stop[2]);
                l.walk = walkMinutes(Double.parseDouble(stop[3]));
                int seq = Integer.parseInt(stop[1]);
                JSONArray data = getJson("https://rt.data.gov.hk/v2/transport/citybus/eta/CTB/" + stop[0] + "/" + r[0]).optJSONArray("data");
                String dir = r[1].startsWith("in") ? "I" : "O";
                int bestSeq = -1;
                for (int i = 0; data != null && i < data.length(); i++) {      // the entries for our stop (nearest seq)
                    JSONObject e = data.getJSONObject(i);
                    if (!dir.equals(e.optString("dir"))) continue;
                    int s = e.optInt("seq", -1);
                    if (bestSeq < 0 || Math.abs(s - seq) < Math.abs(bestSeq - seq)) bestSeq = s;
                }
                for (int i = 0; data != null && i < data.length(); i++) {
                    JSONObject e = data.getJSONObject(i);
                    if (!dir.equals(e.optString("dir")) || e.optInt("seq", -2) != bestSeq) continue;
                    long at = parseIso(e.optString("eta", ""));
                    if (at <= 0) continue;
                    Dep d = new Dep(at, true);
                    d.note = e.optString("rmk_en", "");
                    l.deps.add(d);
                }
                sortDeps(l.deps);
                l.ok = true;
            } catch (Exception e) {
                Log.w(TAG, "commute: bus " + r[0] + " " + e);
                if (l.name == null) l.name = r[2];
            }
            out.add(l);
        }
    }

    /** "Healthy Gardens, King's Road" → "Healthy Gardens" (the name you know the stop by); "North Point Ferry Pier" → "Ferry Pier". */
    static String shortStop(String name) {
        String s = name;
        int c = s.indexOf(", ");
        if (c > 0) s = s.substring(0, c);
        return s.replace("North Point ", "").replace(" Road", " Rd").replace(" Street", " St");
    }

    static int walkMinutes(double metres) {
        return Math.max(1, (int) Math.ceil(metres * 1.3 / 75.0));        // streets aren't straight; ~75 m a minute
    }

    /** {stop id, seq, name, metres} of the route's stop closest to home; worked out once a week. */
    private String[] nearestStop(String route, String dir) throws Exception {
        String key = "stop_" + route + "_" + dir;
        String cached = sp.getString(key, null);
        if (cached != null) {
            String[] p = cached.split("\\|");
            if (p.length == 5 && System.currentTimeMillis() - Long.parseLong(p[4]) < 7L * 86_400_000L) return p;
        }
        JSONArray stops = getJson("https://rt.data.gov.hk/v2/transport/citybus/route-stop/CTB/" + route + "/" + dir).getJSONArray("data");
        String[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < stops.length(); i++) {
            JSONObject s = stops.getJSONObject(i);
            String id = s.getString("stop");
            String info = sp.getString("ctb_" + id, null);
            if (info == null) {
                JSONObject st = getJson("https://rt.data.gov.hk/v2/transport/citybus/stop/" + id).getJSONObject("data");
                info = st.getDouble("lat") + "|" + st.getDouble("long") + "|" + st.optString("name_en", id);
                sp.edit().putString("ctb_" + id, info).apply();
            }
            String[] p = info.split("\\|", 3);
            double d = metres(HOME_LAT, HOME_LNG, Double.parseDouble(p[0]), Double.parseDouble(p[1]));
            if (d < bestD) { bestD = d; best = new String[]{id, String.valueOf(s.optInt("seq")), p[2], String.valueOf(Math.round(d)), ""}; }
        }
        if (best == null) throw new Exception("no stops for " + route);
        best[4] = String.valueOf(System.currentTimeMillis());
        sp.edit().putString(key, best[0] + "|" + best[1] + "|" + best[2] + "|" + best[3] + "|" + best[4]).apply();
        Log.i(TAG, "commute: " + route + " " + dir + " boards at " + best[2] + " (stop " + best[0] + ", " + best[3] + " m)");
        return best;
    }

    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // ------------------------------------------------------------------ MTR

    private static final class Sched {
        final List<Long> up = new ArrayList<>(), down = new ArrayList<>();
        String message;
    }

    private final java.util.Map<String, Sched> lastSched = new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastSchedAt = new java.util.HashMap<>();

    /** A station's next trains; if the MTR says "too many requests" (or is unreachable), its last answer from the past 2 min. */
    private Sched mtr(String line, String sta) {
        String key = line + "-" + sta;
        Sched fresh = mtrFetch(line, sta);
        if (fresh != null) { lastSched.put(key, fresh); lastSchedAt.put(key, System.currentTimeMillis()); return fresh; }
        Long at = lastSchedAt.get(key);
        return at != null && System.currentTimeMillis() - at < 120_000L ? lastSched.get(key) : null;
    }

    private Sched mtrFetch(String line, String sta) {
        Sched s = new Sched();
        try {
            JSONObject j = getJson("https://rt.data.gov.hk/v1/transport/mtr/getSchedule.php?line=" + line + "&sta=" + sta + "&lang=EN");
            if (j.optInt("status", 1) == 0) s.message = j.optString("message", "Special arrangement");
            JSONObject d = j.optJSONObject("data");
            JSONObject e = d == null ? null : d.optJSONObject(line + "-" + sta);
            if (e != null) {
                times(e.optJSONArray("UP"), s.up);
                times(e.optJSONArray("DOWN"), s.down);
            }
            if ("Y".equals(j.optString("isdelay")) && s.message == null) s.message = "Delays on the " + lineName(line);
        } catch (Exception ex) {
            Log.w(TAG, "commute: MTR " + line + "-" + sta + " " + ex);
            s.message = null;
            return null;
        }
        return s;
    }

    private static void times(JSONArray a, List<Long> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null || "N".equals(t.optString("valid"))) continue;
            long at = parseLocal(t.optString("time", ""));
            if (at > 0) out.add(at);
        }
        Collections.sort(out);
    }

    static String lineName(String code) {
        return "ISL".equals(code) ? "Island Line" : "TKL".equals(code) ? "TKO Line" : "KTL".equals(code) ? "Kwun Tong Line" : code;
    }

    private void fetchMtr(Board b) {
        Sched isl = mtr("ISL", "NOP");
        String[][] dirs = {{"Chai Wan", "UP"}, {"Kennedy Town", "DOWN"}};
        for (String[] d : dirs) {
            Line l = new Line();
            l.code = "ISL";
            l.name = d[0];
            l.walk = WALK_ISL;
            l.live = true;
            if (isl != null) {
                l.ok = true;
                l.error = isl.message;
                for (long t : "UP".equals(d[1]) ? isl.up : isl.down) l.deps.add(new Dep(t, true));
            }
            b.mtr.add(l);
        }

        // Work: TKO Line North Point → Yau Tong, change, Kwun Tong Line Yau Tong → Kowloon Tong (towards Whampoa)
        Sched tkoNop = mtr("TKL", "NOP"), tkoYat = mtr("TKL", "YAT"), ktlYat = mtr("KTL", "YAT");
        if (tkoNop != null && tkoNop.message != null) b.mtrNote = tkoNop.message;
        else if (ktlYat != null && ktlYat.message != null) b.mtrNote = ktlYat.message;
        if (tkoNop == null) return;
        long lastKtl = 0;
        for (long dep : tkoNop.up) {
            Work w = new Work();
            w.tko = dep;
            w.leave = dep - WALK_TKL * 60_000L;
            w.yauTong = 0;
            if (tkoYat != null) {                       // the same train's arrival at Yau Tong (it's 2 stops on)
                for (long t : tkoYat.up) if (t >= dep + 3 * 60_000L && t <= dep + 12 * 60_000L) { w.yauTong = t; break; }
            }
            if (w.yauTong == 0) { w.yauTong = dep + 6 * 60_000L; w.yauTongEst = true; }
            long ready = w.yauTong + YAU_TONG_CHANGE * 60_000L;
            w.ktl = 0;
            if (ktlYat != null) {
                for (long t : ktlYat.down) if (t >= ready) { w.ktl = t; break; }
                if (!ktlYat.down.isEmpty()) lastKtl = ktlYat.down.get(ktlYat.down.size() - 1);
            }
            if (w.ktl == 0) { w.ktl = Math.max(ready, lastKtl) + 60_000L; w.ktlEst = true; }   // beyond the listed trains
            w.arrive = w.ktl + YAU_TONG_TO_KOWLOON_TONG * 60_000L;
            b.work.add(w);
        }
    }

    // ------------------------------------------------------------------ ferries

    // Departures from North Point. "w" = Mon–Fri except public holidays only; "e" = Sat, Sun and public holidays only.
    // Transport Department timetables: Sun Ferry effective 24 Aug 2026; Fortune Ferry effective 3 Sep 2023.
    private static final String[] NP_HH = ("w07:23 w07:53 08:23 08:53 09:23 09:53 10:23 e10:53 11:23 e11:53 12:23 e12:53 "
            + "13:23 e13:53 14:23 14:53 15:23 15:53 16:23 16:53 17:23 17:53 18:23 18:53 19:23").split(" ");
    private static final String[] NP_KC = ("w07:17 w07:47 08:17 08:47 09:17 09:47 10:17 10:47 e11:17 11:47 e12:17 12:47 "
            + "e13:17 13:47 e14:17 14:47 15:17 15:47 16:17 16:47 17:17 17:47 18:17 18:47 19:17").split(" ");
    private static final String[] NP_KT_WEEKDAY = ("07:00 07:30 08:00 08:30 09:00 09:30 10:00 10:30 12:00 12:30 13:00 13:30 "
            + "14:00 14:30 15:00 16:30 17:00 17:30 18:00 18:30 19:00 19:30").split(" ");
    private static final String[] NP_KT_SAT = "08:00 08:30 09:00 10:00 12:00 13:00 14:00 15:00 16:00 17:00 18:00 19:00".split(" ");
    private static final String[] NP_KT_HOLIDAY = "09:00 10:00 11:00 12:00 13:00 14:00 15:00 16:00 17:00 18:00 19:00".split(" ");

    private void fetchFerries(List<Line> out) {
        Set<String> holidays = holidays();
        out.add(sunFerry("HH", "Hung Hom", "NPHH", NP_HH, holidays));
        out.add(sunFerry("KC", "Kowloon City", "NPKC", NP_KC, holidays));
        Line kt = new Line();
        kt.code = "KT";
        kt.walk = WALK_PIER;
        kt.ok = true;
        long now = System.currentTimeMillis();
        for (int day = 0; day < 3 && kt.deps.size() < 3; day++) {
            Calendar c = dayStart(now, day);
            char type = dayType(c, holidays);
            String[] list = type == 'W' ? NP_KT_WEEKDAY : type == 'S' ? NP_KT_SAT : NP_KT_HOLIDAY;
            for (String t : list) {
                long at = at(c, t);
                if (at >= now - 60_000L) kt.deps.add(new Dep(at, false));
            }
        }
        char today = dayType(dayStart(now, 0), holidays);
        kt.name = today == 'W' ? "Kwun Tong · timetable" : "Kwun Tong, Kai Tak · timetable";
        out.add(kt);
    }

    private Line sunFerry(String code, String name, String route, String[] table, Set<String> holidays) {
        Line l = new Line();
        l.code = code;
        l.name = name;
        l.walk = WALK_PIER;
        l.ok = true;
        long now = System.currentTimeMillis();
        for (int day = 0; day < 3 && l.deps.size() < 4; day++) {
            Calendar c = dayStart(now, day);
            char type = dayType(c, holidays);
            for (String t : table) {
                boolean w = t.startsWith("w"), e = t.startsWith("e");
                if ((w && type != 'W') || (e && type == 'W')) continue;
                long at = at(c, w || e ? t.substring(1) : t);
                if (at >= now - 60_000L) l.deps.add(new Dep(at, false));
            }
        }
        try {                                           // live: confirmed / cancelled sailings and any extras
            JSONObject j = getJson("https://www.sunferry.com.hk/eta/?route=" + route);
            JSONArray data = j.optJSONArray("data");
            long gen = parseIso(j.optString("generated_timestamp", ""));
            if (gen <= 0) gen = now;
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject e = data.getJSONObject(i);
                String dt = e.optString("depart_time", "");
                if (!dt.matches("\\d{1,2}:\\d{2}")) continue;
                long at = nearestClock(gen, dt);
                if (!e.isNull("eta") && e.optString("eta", "").length() > 0) continue;   // already sailing
                Dep match = null;
                for (Dep d : l.deps) if (Math.abs(d.at - at) < 60_000L) match = d;
                if (match == null && at >= now - 60_000L) { match = new Dep(at, true); l.deps.add(match); }
                if (match == null) continue;
                match.live = true;
                String rmk = e.isNull("rmk_en") ? "" : e.optString("rmk_en", "");
                match.note = rmk;
                if (rmk.toLowerCase(Locale.US).contains("cancel") || rmk.toLowerCase(Locale.US).contains("suspend")) match.cancelled = true;
            }
            l.live = true;
        } catch (Exception ex) {
            Log.w(TAG, "commute: Sun Ferry " + route + " " + ex);
        }
        sortDeps(l.deps);
        return l;
    }

    /** 'W' weekday, 'S' Saturday, 'H' Sunday or public holiday. */
    static char dayType(Calendar c, Set<String> holidays) {
        String ymd = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SUNDAY || holidays.contains(ymd)) return 'H';
        return dow == Calendar.SATURDAY ? 'S' : 'W';
    }

    private static Calendar dayStart(long now, int plusDays) {
        Calendar c = Calendar.getInstance(HK, Locale.US);
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, plusDays);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static long at(Calendar day, String hhmm) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hhmm.substring(0, hhmm.indexOf(':'))));
        c.set(Calendar.MINUTE, Integer.parseInt(hhmm.substring(hhmm.indexOf(':') + 1)));
        return c.getTimeInMillis();
    }

    /** "HH:mm" on whichever of yesterday / today / tomorrow is closest to the reference time. */
    private static long nearestClock(long ref, String hhmm) {
        long best = 0;
        for (int d = -1; d <= 1; d++) {
            long t = at(dayStart(ref, d), hhmm);
            if (best == 0 || Math.abs(t - ref) < Math.abs(best - ref)) best = t;
        }
        return best;
    }

    /** Hong Kong public holidays (yyyyMMdd), from 1823.gov.hk, refreshed weekly. */
    private Set<String> holidays() {
        Set<String> out = new HashSet<>();
        String cached = sp.getString("holidays", "");
        long at = sp.getLong("holidays_at", 0);
        if (cached.isEmpty() || System.currentTimeMillis() - at > 7L * 86_400_000L) {
            try {
                JSONArray ev = getJson("https://www.1823.gov.hk/common/ical/en.json")
                        .getJSONArray("vcalendar").getJSONObject(0).getJSONArray("vevent");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ev.length(); i++) {
                    String d = ev.getJSONObject(i).getJSONArray("dtstart").getString(0);
                    if (sb.length() > 0) sb.append(',');
                    sb.append(d);
                }
                cached = sb.toString();
                sp.edit().putString("holidays", cached).putLong("holidays_at", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                Log.w(TAG, "commute: holidays " + e);
            }
        }
        for (String d : cached.split(",")) if (d.length() == 8) out.add(d);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void sortDeps(List<Dep> deps) {
        Collections.sort(deps, new Comparator<Dep>() {
            @Override public int compare(Dep a, Dep b) { return a.at < b.at ? -1 : a.at > b.at ? 1 : 0; }
        });
    }

    /** "2026-11-05T08:11:00+08:00" (Hong Kong offset) → epoch ms; 0 if blank. */
    static long parseIso(String s) {
        if (s == null || s.length() < 19) return 0;
        return parseLocal(s.substring(0, 10) + " " + s.substring(11, 19));
    }

    /** "2026-11-05 08:11:00" in Hong Kong time → epoch ms; 0 if malformed. */
    static long parseLocal(String s) {
        try {
            Calendar c = Calendar.getInstance(HK, Locale.US);
            c.clear();
            c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1, Integer.parseInt(s.substring(8, 10)),
                    Integer.parseInt(s.substring(11, 13)), Integer.parseInt(s.substring(14, 16)), Integer.parseInt(s.substring(17, 19)));
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "GalleryHome/5 (Android TV)");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String body = bo.toString("UTF-8").trim();
            if (body.startsWith("﻿")) body = body.substring(1);
            return new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }
}
