package tv.galleryhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StrikethroughSpan;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "To work first" commute board: when to leave for Kowloon Tong (TKO Line → Yau Tong → Kwun Tong Line),
 * then the next buses, Island Line trains and ferries. Departures you can't reach on foot are dimmed;
 * the next one you can catch is amber. Refreshes live while on screen. OK refreshes now, BACK goes home.
 */
public class CommuteActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Commute commute;
    private Commute.Board board;
    private boolean visible, loading;

    private FrameLayout bgLayer;
    private Bitmap wallpaper;            // the current home-screen picture, for the Sharp and Blurred backgrounds
    private Prefs prefs;
    private int sw, sh;
    private LinearLayout legsRow;
    private JourneyViews.Line journeyLine;                // style A
    private final View[] legGlyph = new View[3];          // style E
    private ImageView overlay;
    private long bgAt;
    private TextView clock, date, sub, lead, bigNum, bigText, footL;
    private final TextView[] legK = new TextView[3], legT = new TextView[3], legD = new TextView[3];
    private LinearLayout side;
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat hhmmss = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hhmm.setTimeZone(Commute.HK);
        hhmmss.setTimeZone(Commute.HK);
        commute = Commute.get(this);
        DisplayMetrics m = getResources().getDisplayMetrics();
        sw = Math.max(m.widthPixels, m.heightPixels);
        sh = Math.min(m.widthPixels, m.heightPixels);
        prefs = new Prefs(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Noir.INK);
        bgLayer = new FrameLayout(this);
        root.addView(bgLayer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(Noir.vignette(this, sw), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Noir.amberGlow(this, sw), new FrameLayout.LayoutParams(-1, -1));

        // Title, top-left
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        TextView h1 = Noir.label(this, Noir.sans(this), 22, 0.01f, 1f);
        h1.setText("Commute");
        sub = Noir.label(this, Noir.mono(this), 7, 0.24f, 0.55f);
        sub.setPadding(0, dp(7), 0, 0);
        sub.setText("FROM KEI WAH COURT · LOADING");
        title.addView(h1);
        title.addView(sub);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(500), -2, Gravity.TOP | Gravity.START);
        tp.setMargins(dp(56), dp(38), 0, 0);
        root.addView(title, tp);

        // Clock, top-right (same as the home screen)
        LinearLayout ck = new LinearLayout(this);
        ck.setOrientation(LinearLayout.VERTICAL);
        ck.setGravity(Gravity.END);
        clock = Noir.label(this, Noir.tall(this), 60, 0.01f, 1f);
        date = Noir.label(this, Noir.mono(this), 7.5f, 0.3f, 0.65f);
        date.setPadding(0, dp(7), dp(2), 0);
        ck.addView(clock);
        ck.addView(date);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(28), dp(54), 0);
        root.addView(ck, cp);

        // Hero: when to leave for work
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        lead = Noir.label(this, Noir.mono(this), 7.5f, 0.3f, 1f);
        lead.setTextColor(Noir.AMBER);
        lead.setText("TO WORK · KOWLOON TONG");
        hero.addView(lead);
        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.BOTTOM);
        bigNum = Noir.label(this, Noir.tall(this), 110, 0f, 1f);
        bigNum.setTextColor(Noir.AMBER);
        bigNum.setText("–");
        bigText = Noir.label(this, Noir.sans(this), 20, 0.01f, 0.92f);
        bigText.setPadding(dp(12), 0, 0, dp(9));
        big.addView(bigNum);
        big.addView(bigText, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(4);
        hero.addView(big, bl);
        LinearLayout legs = new LinearLayout(this);
        legsRow = legs;
        legs.setOrientation(LinearLayout.HORIZONTAL);
        legs.setBackground(new Noir.TopLine(Noir.text(0.16f), Math.max(1, dp(0.6f))));
        for (int i = 0; i < 3; i++) {
            LinearLayout leg = new LinearLayout(this);
            leg.setOrientation(LinearLayout.VERTICAL);
            leg.setPadding(i == 0 ? 0 : dp(13), dp(11), dp(13), 0);
            if (i > 0) leg.setBackground(new LeftLine(Noir.text(0.1f), Math.max(1, dp(0.5f))));
            legK[i] = Noir.label(this, Noir.mono(this), 6, 0.24f, 0.55f);
            legT[i] = Noir.label(this, Noir.tall(this), 28, 0.01f, 1f);
            legT[i].setPadding(0, dp(5), 0, 0);
            legD[i] = Noir.label(this, Noir.sans(this), 9.5f, 0.01f, 0.8f);
            legD[i].setPadding(0, dp(2), 0, 0);
            legGlyph[i] = new JourneyViews.Glyph(this, i);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(17));
            gl.bottomMargin = dp(7);
            leg.addView(legGlyph[i], gl);
            leg.addView(legK[i]);
            leg.addView(legT[i]);
            leg.addView(legD[i]);
            legs.addView(leg, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(-1, -2);
        lgp.topMargin = dp(20);
        hero.addView(legs, lgp);
        journeyLine = new JourneyViews.Line(this, hhmm);
        LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(-1, -2);
        jl.topMargin = dp(15);
        hero.addView(journeyLine, jl);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(490), -2, Gravity.TOP | Gravity.START);
        hp.setMargins(dp(56), dp(135), 0, 0);
        root.addView(hero, hp);

        // Everything else, right column
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(300), -2, Gravity.TOP | Gravity.END);
        sp.setMargins(0, dp(150), dp(56), 0);
        root.addView(side, sp);

        // Footer
        footL = Noir.label(this, Noir.mono(this), 6.5f, 0.24f, 0.45f);
        footL.setText("LIVE · CITYBUS · MTR · SUN FERRY");
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        fl.setMargins(dp(56), 0, 0, dp(24));
        root.addView(footL, fl);
        TextView footR = Noir.label(this, Noir.mono(this), 6.5f, 0.24f, 0.45f);
        footR.setText("OK · MENU    BACK · HOME");
        FrameLayout.LayoutParams fr = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fr.setMargins(0, 0, dp(56), dp(24));
        root.addView(footR, fr);

        setContentView(root);
        applyBackground();
        loadWallpaper();
        render();
    }

    /** The current home-screen picture (only needed for the Sharp and dark background). */
    private void loadWallpaper() {
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    Art.Item it = new Art(CommuteActivity.this).cached(prefs.source(), sw, sh);
                    if (it == null || it.bitmap == null) return;
                    final Bitmap b = it.bitmap;
                    ui.post(new Runnable() { public void run() { wallpaper = b; if ("sharp".equals(bgKey())) applyBackground(); } });
                } catch (Exception ignored) { }
            }
        });
    }

    private String bgKey() { return CommuteBackgrounds.valid(prefs.commuteBg()); }

    private void applyBackground() {
        String k = bgKey();
        bgAt = System.currentTimeMillis();
        bgLayer.removeAllViews();
        bgLayer.setBackground(null);
        float hour = CommuteBackgrounds.hourNow();
        switch (k) {
            case "led":
                bgLayer.addView(new CommuteBackgrounds.Led(this), new FrameLayout.LayoutParams(-1, -1));
                break;
            case "sharp":
                if (wallpaper == null) break;
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(wallpaper);
                Noir.dim(iv, 0.2f);
                bgLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                bgLayer.addView(CommuteBackgrounds.sharpShade(this), new FrameLayout.LayoutParams(-1, -1));
                break;
            case "sky":
                bgLayer.setBackground(CommuteBackgrounds.sky(hour));
                break;
            case "weather": {
                CommuteBackgrounds.Look l = CommuteBackgrounds.weather(Weather.cached(this), hour);
                bgLayer.setBackground(l.base);
                if (l.overlay != null) addOverlay(l.overlay, l.alpha);
                break;
            }
            case "contours": case "trails": case "skyline":
                if ("skyline".equals(k)) bgLayer.setBackground(new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Noir.INK, 0xFF07080B, 0xFF12131A, 0xFF0A0A0D}));
                addOverlay(k, 1f);
                break;
            default:                               // "ink": the dark, the vignette and the amber glow
                break;
        }
    }

    private void addOverlay(final String name, final float alpha) {
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setAlpha(alpha);
        bgLayer.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        bg.execute(new Runnable() {                // decode off the UI thread
            @Override public void run() {
                final Bitmap b = CommuteBackgrounds.overlay(CommuteActivity.this, name);
                ui.post(new Runnable() { public void run() { if (b != null) iv.setImageBitmap(b); } });
            }
        });
    }

    private String journeyKey() { return JourneyViews.valid(prefs.commuteJourney()); }

    /** OK (or MENU): refresh now, journey style, or background. */
    private void showMenu() {
        final String cur = bgKey(), js = journeyKey();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();
        labels.add("Refresh now");
        acts.add(new Runnable() { public void run() { refresh(true); } });
        for (final String j : new String[]{JourneyViews.LINE, JourneyViews.GLYPHS}) {
            labels.add("Journey: " + JourneyViews.name(j) + (j.equals(js) ? "   ✓" : ""));
            acts.add(new Runnable() { public void run() { prefs.setCommuteJourney(j); render(); } });
        }
        for (int i = 0; i < CommuteBackgrounds.KEYS.length; i++) {
            final String k = CommuteBackgrounds.KEYS[i];
            labels.add("Background: " + CommuteBackgrounds.NAMES[i] + (k.equals(cur) ? "   ✓" : ""));
            acts.add(new Runnable() { public void run() { prefs.setCommuteBg(k); applyBackground(); } });
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Commute")
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { acts.get(which).run(); }
                })
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        tick.run();
        refresh(false);
    }

    @Override protected void onPause() {
        visible = false;
        ui.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ data

    private void refresh(final boolean force) {
        if (loading) return;
        loading = true;
        bg.execute(new Runnable() {
            @Override public void run() {
                final Commute.Board b = commute.refresh(force);
                ui.post(new Runnable() { public void run() { loading = false; board = b; render(); } });
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (!visible) return;
            if (board != null && System.currentTimeMillis() - board.updated > 20_000) refresh(false);
            String k = bgKey();                    // the sky and the weather change through the day
            if (("sky".equals(k) || "weather".equals(k)) && System.currentTimeMillis() - bgAt > 60_000) applyBackground();
            ui.postDelayed(this, 5_000);
        }
    };

    // ------------------------------------------------------------------ drawing

    private void render() {
        long now = System.currentTimeMillis();
        Date d = new Date(now);
        clock.setText(hhmm.format(d));
        SimpleDateFormat ds = new SimpleDateFormat("EEE · d MMM", Locale.US);
        ds.setTimeZone(Commute.HK);
        String dt = ds.format(d).toUpperCase(Locale.US);
        Weather.Data wx = Weather.cached(this);
        if (wx != null && wx.ok() && wx.temp != Integer.MIN_VALUE) dt += " · " + wx.temp + "°";
        date.setText(dt);
        if (board == null) return;
        sub.setText("FROM KEI WAH COURT · UPDATED " + hhmmss.format(new Date(board.updated)));
        renderWork(now);
        renderSide(now);
    }

    private void renderWork(long now) {
        Commute.Work w = null;
        for (Commute.Work x : board.work) if (x.leave >= now - 30_000L) { w = x; break; }
        lead.setText(board.mtrNote != null ? ("TO WORK · " + board.mtrNote).toUpperCase(Locale.US) : "TO WORK · KOWLOON TONG");
        if (w == null) {                     // overnight, or between listings: say so plainly
            bigNum.setVisibility(View.GONE);
            bigText.setPadding(0, dp(10), 0, 0);
            bigText.setText(board.work.isEmpty() ? "No TKO Line trains right now" : "The next train is on its way");
            legsRow.setVisibility(View.GONE);
            journeyLine.setVisibility(View.GONE);
            return;
        }
        bigNum.setVisibility(View.VISIBLE);
        bigText.setPadding(dp(12), 0, 0, dp(9));
        boolean line = JourneyViews.LINE.equals(journeyKey());
        legsRow.setVisibility(line ? View.GONE : View.VISIBLE);
        journeyLine.setVisibility(line ? View.VISIBLE : View.GONE);
        journeyLine.set(w);
        long mins = (long) Math.ceil((w.leave - now) / 60_000.0);
        if (mins <= 0) { bigNum.setText("0"); bigText.setText("leave now"); }
        else { bigNum.setText(String.valueOf(mins)); bigText.setText(mins == 1 ? "min until you should leave" : "min until you should leave"); }
        legK[0].setText("WALK · " + Commute.WALK_TKL + " MIN");
        legT[0].setText(hhmm.format(new Date(w.leave)));
        legD[0].setText("Leave Kei Wah Court");
        legK[1].setText("TKO LINE · NORTH POINT");
        legT[1].setText(hhmm.format(new Date(w.tko)));
        legD[1].setText("→ Yau Tong " + (w.yauTongEst ? "≈ " : "") + hhmm.format(new Date(w.yauTong)));
        legK[2].setText(w.ktlEst ? "KWUN TONG LINE · EST" : "KWUN TONG LINE · YAU TONG");
        legT[2].setText(hhmm.format(new Date(w.ktl)));
        legD[2].setText("→ Kowloon Tong ≈ " + hhmm.format(new Date(w.arrive)));
    }

    private void renderSide(long now) {
        side.removeAllViews();
        group("Citybus", board.bus, now, true);
        group("Island Line · North Point", board.mtr, now, false);
        group("Ferry · North Point Pier", board.ferry, now, false);
    }

    private void group(String name, List<Commute.Line> lines, long now, boolean first) {
        TextView h = Noir.label(this, Noir.mono(this), 6.5f, 0.3f, 0.55f);
        boolean offline = !lines.isEmpty();
        for (Commute.Line l : lines) if (l.ok) offline = false;
        h.setText(name.toUpperCase(Locale.US) + (offline ? " · NO DATA" : ""));
        h.setPadding(0, 0, 0, dp(6));
        h.setBackground(new BottomLine(Noir.text(0.14f), Math.max(1, dp(0.5f))));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = first ? 0 : dp(13);
        side.addView(h, hl);
        for (Commute.Line l : lines) side.addView(row(l, now), new LinearLayout.LayoutParams(-1, -2));
    }

    private View row(Commute.Line l, long now) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        r.setBackground(new BottomLine(Noir.text(0.07f), Math.max(1, dp(0.5f))));
        TextView code = Noir.label(this, Noir.tall(this), 17, 0.01f, 1f);
        code.setText(l.code);
        r.addView(code, new LinearLayout.LayoutParams(dp(43), -2));
        TextView name = Noir.label(this, Noir.sans(this), 10, 0.01f, 0.88f);
        name.setText(l.error != null ? l.name + " · " + l.error : l.name);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView times = Noir.label(this, Noir.tall(this), 16, 0.02f, 1f);
        times.setText(times(l, now));
        r.addView(times, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    /** "1 · 19 · 39 min": missed ones dimmed, the next catchable one amber, far ones as clock times. */
    private CharSequence times(Commute.Line l, long now) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!l.ok && l.deps.isEmpty()) { sb.append("—"); return sb; }
        int shown = 0;
        boolean amberDone = false, anyMins = false;
        for (Commute.Dep d : l.deps) {
            if (d.at < now - 30_000L) continue;
            if (shown == 3) break;
            long mins = (long) Math.floor((d.at - now) / 60_000.0);
            boolean missed = mins < l.walk;
            if (missed && shown >= 1) continue;                       // keep at most one missed one
            if (shown > 0) appendDot(sb);
            int start = sb.length();
            if (mins >= 100) sb.append(hhmm.format(new Date(d.at)));
            else { sb.append(String.valueOf(Math.max(0, mins))); anyMins = true; }
            int color;
            if (d.cancelled || missed) color = Noir.text(0.35f);
            else if (!amberDone) { color = Noir.AMBER; amberDone = true; }
            else color = Noir.TEXT;
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (d.cancelled) sb.setSpan(new StrikethroughSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown++;
        }
        if (shown == 0) { sb.append("—"); return sb; }
        if (anyMins) {
            int s = sb.length();
            sb.append("  min");
            sb.setSpan(new FontSpan(Noir.mono(this)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(6, true), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Noir.text(0.5f)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void appendDot(SpannableStringBuilder sb) {
        int s = sb.length();
        sb.append(" · ");
        sb.setSpan(new ForegroundColorSpan(Noir.text(0.4f)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Typeface span (TypefaceSpan(Typeface) is API 28+). */
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface tf;
        FontSpan(Typeface tf) { this.tf = tf; }
        @Override public void updateDrawState(TextPaint p) { p.setTypeface(tf); p.setLetterSpacing(0.18f); }
        @Override public void updateMeasureState(TextPaint p) { p.setTypeface(tf); p.setLetterSpacing(0.18f); }
    }

    private static final class BottomLine extends android.graphics.drawable.ColorDrawable {
        private final int h;
        BottomLine(int color, int h) { super(color); this.h = h; }
        @Override public void draw(android.graphics.Canvas c) {
            c.save();
            c.clipRect(getBounds().left, getBounds().bottom - h, getBounds().right, getBounds().bottom);
            super.draw(c);
            c.restore();
        }
    }

    private static final class LeftLine extends android.graphics.drawable.ColorDrawable {
        private final int w;
        LeftLine(int color, int w) { super(color); this.w = w; }
        @Override public void draw(android.graphics.Canvas c) {
            c.save();
            c.clipRect(getBounds().left, getBounds().top, getBounds().left + w, getBounds().bottom);
            super.draw(c);
            c.restore();
        }
    }

    private int dp(float v) { return Ui.dp(this, v); }
}
