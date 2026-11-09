package tv.galleryhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "Noir HUD" home screen: dimmed full-screen wallpaper (space, night cities, dark landscapes), big thin clock, numbered app line. */
public class HomeActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService wxExec = Executors.newSingleThreadExecutor();
    private LinearLayout wxBox, ambWx;
    private Weather.Data wx;
    private boolean wxDemo, wxLoading;

    private Prefs prefs;
    private Art art;
    private int screenW, screenH;

    private ImageView bgFront, bgBack;
    private TextView clock, date, artTitle, artLine1, artLine2;
    private LinearLayout captionBox, dock;
    private FrameLayout overlay;
    private LinearLayout overlayGrid;

    private List<AppRepo.App> apps = new ArrayList<>();
    private final Map<String, AppRepo.App> appByPkg = new HashMap<>();

    private boolean resumed, loadingArt, artAgain, firstAppLoad = true;
    private boolean ambient, swallowKeyUp;
    private final List<View> hud = new ArrayList<>();
    private LinearLayout ambientBox;
    private TextView ambClock, ambTitle, ambCredit;
    private String curTitle = "", curCredit = "";
    private String style;                 // "line" (Logo Line), "orbs" (Glass Orbs) or "hud" (Noir HUD)
    private boolean orbs;                 // true for line + orbs: tall Big Shoulders clock top-right, caption top-left
    private TextView focusLabel;
    private long lastArtAt;
    private float artDim = 1f;            // extra dimming for the current picture (bright prints and posters)

    /** Wallpaper brightness right now: the setting, a bit brighter in wallpaper mode, times the picture's own dimming. */
    private float wallBrightness(float pictureDim) {
        float b = ambient ? Math.min(0.85f, prefs.brightness() + 0.15f) : prefs.brightness();
        return b * pictureDim;
    }
    private NestViews.Panel nestPanel;     // Nest Mini mini player under the forecast
    private NestViews.Line ambNest;        // its one-line version in wallpaper mode
    private View lastDockFocus;
    private final Nest.Listener nestListener = new Nest.Listener() {
        @Override public void onNest(Nest.Status s) {
            nestPanel.update(s);
            ambNest.update(s);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        style = prefs.style();
        if (!"orbs".equals(style) && !"hud".equals(style)) style = "line";
        orbs = !"hud".equals(style);
        art = new Art(this);
        DisplayMetrics m = getResources().getDisplayMetrics();
        screenW = Math.max(m.widthPixels, m.heightPixels);
        screenH = Math.min(m.widthPixels, m.heightPixels);
        buildViews();
        reloadApps();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        applyExtras(getIntent());
        loadArt(true);
        showWeather(Weather.cached(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        tickClock.run();
        if (System.currentTimeMillis() - lastArtAt > prefs.intervalMinutes() * 60_000L) loadArt(false);
        scheduleArt();
        refreshWeather.run();
        Nest.get(this).addListener(nestListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tickClock);
        ui.removeCallbacks(rotateArt);
        ui.removeCallbacks(refreshWeather);
        Nest.get(this).removeListener(nestListener);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (RuntimeException ignored) { }
        bg.shutdownNow();
        wxExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("dumplog", false)) {
            bg.execute(new Runnable() { @Override public void run() { art.dumpLog(); } });
            return;
        }
        if (applyExtras(intent)) loadArt(false);
        hideOverlay();
        if (intent != null && intent.getBooleanExtra("ambient", false)) { enterAmbient(); return; }
        exitAmbient();
        focusFirstDockItem();
        if (intent != null) {
            int idx = intent.getIntExtra("focus", -1);       // test hook: focus the n-th dock item
            for (int i = 0, n = 0; idx >= 0 && i < dock.getChildCount(); i++) {
                if (!dock.getChildAt(i).isFocusable()) continue;
                if (n++ == idx) { dock.getChildAt(i).requestFocus(); break; }
            }
            if (intent.getBooleanExtra("apps", false)) showOverlay();
            if (intent.hasExtra("wxdemo")) { wxDemo = intent.getBooleanExtra("wxdemo", false); showWeather(Weather.cached(this)); }
            String cbg = intent.getStringExtra("commutebg");                                                      // test hook: board background
            if (cbg != null) prefs.setCommuteBg(cbg);
            String cj = intent.getStringExtra("commutejourney");                                                  // test hook: journey style
            if (cj != null) prefs.setCommuteJourney(cj);
            String open = intent.getStringExtra("open");                                                          // test hook: open a dock item
            if (open != null && appByPkg.containsKey(open)) launch(appByPkg.get(open));
            if (intent.hasExtra("nestdemo")) Nest.get(this).setDemo(intent.getBooleanExtra("nestdemo", false));   // test hook: sample song
            if (intent.getBooleanExtra("nestfocus", false)) {                                                      // test hook: focus play/pause
                ui.postDelayed(new Runnable() { public void run() { lastDockFocus = getCurrentFocus(); nestPanel.focusMain(); } }, 600);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (ambient) {                       // any key leaves wallpaper mode
            if (e.getAction() == KeyEvent.ACTION_DOWN) { exitAmbient(); swallowKeyUp = true; }
            return true;
        }
        if (swallowKeyUp && e.getAction() == KeyEvent.ACTION_UP) { swallowKeyUp = false; return true; }
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN
                && overlay.getVisibility() != View.VISIBLE && dock.hasFocus() && nestPanel.getVisibility() == View.VISIBLE) {
            lastDockFocus = getCurrentFocus();             // up from the dock goes to the player's play/pause
            if (nestPanel.focusMain()) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Wallpaper mode: hide the HUD and show the picture a little brighter, like the screensaver. */
    private void enterAmbient() {
        if (ambient) return;
        ambient = true;
        for (View v : hud) v.animate().alpha(0f).setDuration(500).start();
        Noir.dim(bgFront, wallBrightness(artDim));
        Noir.dim(bgBack, wallBrightness(artDim));
        ambTitle.setText(prefs.captions() ? curTitle : "");
        ambCredit.setText(prefs.captions() ? curCredit.replace("  ·  ", " · ").toUpperCase(Locale.getDefault()) : "");
        ambClock.setText(clock.getText());
        ambientBox.setAlpha(0f);
        ambientBox.setVisibility(View.VISIBLE);
        ambientBox.animate().alpha(1f).setDuration(700).start();
    }

    private void exitAmbient() {
        if (!ambient) return;
        ambient = false;
        for (View v : hud) v.animate().alpha(1f).setDuration(300).start();
        Noir.dim(bgFront, wallBrightness(artDim));
        Noir.dim(bgBack, wallBrightness(artDim));
        ambientBox.setVisibility(View.GONE);
        for (int i = 0; i < dock.getChildCount(); i++) {
            if ("util:0".equals(dock.getChildAt(i).getTag())) { dock.getChildAt(i).requestFocus(); return; }
        }
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) hideOverlay();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (nestPanel.getVisibility() == View.VISIBLE && overlay.getVisibility() != View.VISIBLE) {   // remote's media keys
            Nest n = Nest.get(this);
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    n.playPause(); return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT: n.skip(1); return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: n.skip(-1); return true;
                default: break;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            Object tag = f == null ? null : f.getTag();
            if (tag instanceof AppRepo.App) appMenu((AppRepo.App) tag, f.getParent() == dock);
            else optionsMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Lets the setup script choose wallpapers: am start ... --es wallpapers space,city,dark  (or --ez next true) */
    private boolean applyExtras(Intent i) {
        if (i == null) return false;
        String w = i.getStringExtra("wallpapers");
        if (w != null) {
            prefs.setSource(android.text.TextUtils.join(",", Art.kinds(w)));
            return true;
        }
        return i.getBooleanExtra("next", false);
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Logos.clear(); reloadApps(); }
    };

    // ------------------------------------------------------------------ views

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Noir.INK);
        root.setClipChildren(false);          // a focused logo grows 12%: at the ends of the dock it must not be cut off
        root.setClipToPadding(false);
        getWindow().setBackgroundDrawable(new ColorDrawable(Noir.INK));

        bgBack = new ImageView(this);
        bgFront = new ImageView(this);
        for (ImageView iv : new ImageView[]{bgBack, bgFront}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Noir.dim(iv, prefs.brightness());
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(Noir.vignette(this, screenW), new FrameLayout.LayoutParams(-1, -1));
        View shade = Noir.bottomShade(this);
        root.addView(shade, new FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM));
        root.addView(Noir.amberGlow(this, screenW), new FrameLayout.LayoutParams(-1, -1));
        hud.add(shade);
        if (!orbs) {
            View brackets = new Noir.Brackets(this);
            root.addView(brackets, new FrameLayout.LayoutParams(-1, -1));
            hud.add(brackets);
        }

        // Clock + date: top-left thin (HUD) or top-right tall condensed (Glass Orbs)
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.setGravity(orbs ? Gravity.END : Gravity.START);
        clock = orbs ? Noir.label(this, Noir.tall(this), 96, 0.01f, 1f)
                     : Noir.label(this, Noir.thin(this), 72, 0.02f, 1f);
        date = Noir.label(this, Noir.mono(this), orbs ? 7.5f : 8, 0.32f, 0.65f);
        date.setPadding(orbs ? 0 : dp(3), dp(orbs ? 6 : 10), orbs ? dp(3) : 0, 0);
        tl.addView(clock);
        tl.addView(date);
        wxBox = new LinearLayout(this);
        wxBox.setVisibility(View.GONE);
        tl.addView(wxBox, new LinearLayout.LayoutParams(-2, -2));
        nestPanel = new NestViews.Panel(this, new NestViews.Panel.Host() {
            @Override public void leavePanel() {
                if (lastDockFocus != null && lastDockFocus.getParent() == dock && lastDockFocus.requestFocus()) return;
                focusFirstDockItem();
            }
        });
        LinearLayout.LayoutParams npl = new LinearLayout.LayoutParams(dp(4 * 46), -2);   // same width as the forecast
        npl.topMargin = dp(16);
        tl.addView(nestPanel, npl);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | (orbs ? Gravity.END : Gravity.START));
        if (orbs) tlp.setMargins(0, dp(30), dp(54), 0); else tlp.setMargins(dp(54), dp(46), 0, 0);
        root.addView(tl, tlp);
        hud.add(tl);

        // Top-right: painting caption
        captionBox = new LinearLayout(this);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        int capG = orbs ? Gravity.START : Gravity.END;
        captionBox.setGravity(capG);
        artTitle = Noir.label(this, Noir.sans(this), 12, 0.03f, 1f);
        artTitle.setGravity(capG);
        artLine1 = Noir.label(this, Noir.mono(this), 7, 0.22f, 0.75f);
        artLine1.setGravity(capG);
        artLine1.setPadding(0, dp(9), 0, 0);
        artLine2 = Noir.label(this, Noir.mono(this), 7, 0.22f, 0.75f);
        artLine2.setGravity(capG);
        artLine2.setPadding(0, dp(6), 0, 0);
        captionBox.addView(artTitle, new LinearLayout.LayoutParams(-1, -2));
        captionBox.addView(artLine1, new LinearLayout.LayoutParams(-1, -2));
        captionBox.addView(artLine2, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(420), -2, Gravity.TOP | capG);
        if (orbs) cp.setMargins(dp(56), dp(48), 0, 0); else cp.setMargins(0, dp(52), dp(56), 0);
        root.addView(captionBox, cp);
        hud.add(captionBox);

        // Bottom: numbered app line
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        FrameLayout.LayoutParams dlp;
        if ("orbs".equals(style)) {
            dock.setGravity(Gravity.CENTER_VERTICAL);
            dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dlp.setMargins(0, 0, 0, dp(38));
        } else if ("line".equals(style)) {
            dock.setGravity(Gravity.BOTTOM);
            dock.setBackground(new TopLine(Noir.text(0.15f), Math.max(1, dp(0.6f))));
            dlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            dlp.setMargins(dp(56), 0, dp(56), dp(46));
        } else {
            dock.setBackground(new TopLine(Noir.text(0.18f), Math.max(1, dp(0.6f))));
            dlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            dlp.setMargins(dp(56), 0, dp(56), dp(52));
        }
        root.addView(dock, dlp);
        hud.add(dock);
        if ("orbs".equals(style)) {
            focusLabel = Noir.label(this, Noir.mono(this), 7.5f, 0.28f, 0.9f);
            focusLabel.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(400), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            flp.setMargins(0, 0, 0, dp(20));
            root.addView(focusLabel, flp);
            hud.add(focusLabel);
        }

        // Wallpaper (ambient) mode: just the picture, a small clock and caption
        ambientBox = new LinearLayout(this);
        ambientBox.setOrientation(LinearLayout.VERTICAL);
        ambClock = Noir.label(this, orbs ? Noir.tall(this) : Noir.thin(this), orbs ? 52 : 40, 0.02f, 0.9f);
        ambTitle = Noir.label(this, Noir.sans(this), 11, 0.03f, 0.95f);
        ambTitle.setPadding(0, dp(12), 0, 0);
        ambCredit = Noir.label(this, Noir.mono(this), 6.5f, 0.22f, 0.7f);
        ambCredit.setPadding(0, dp(6), 0, 0);
        ambientBox.addView(ambClock);
        ambWx = new LinearLayout(this);
        ambWx.setVisibility(View.GONE);
        LinearLayout.LayoutParams awl = new LinearLayout.LayoutParams(-2, -2);
        awl.topMargin = dp(8);
        ambientBox.addView(ambWx, awl);
        ambNest = new NestViews.Line(this);
        LinearLayout.LayoutParams anl = new LinearLayout.LayoutParams(-2, -2);
        anl.topMargin = dp(10);
        ambientBox.addView(ambNest, anl);
        ambientBox.addView(ambTitle);
        ambientBox.addView(ambCredit);
        ambientBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(600), -2, Gravity.BOTTOM | Gravity.START);
        alp.setMargins(dp(56), 0, 0, dp(52));
        root.addView(ambientBox, alp);

        // All-apps overlay
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF2050505);
        overlay.setVisibility(View.GONE);
        LinearLayout ol = new LinearLayout(this);
        ol.setOrientation(LinearLayout.VERTICAL);
        TextView title = Noir.label(this, Noir.thin(this), 30, 0.04f, 1f);
        title.setText("APPS");
        title.setPadding(dp(56), dp(36), 0, dp(6));
        TextView hint = Noir.label(this, Noir.mono(this), 7, 0.2f, 0.55f);
        hint.setText("OK  OPEN   ·   HOLD OK  ADD / REMOVE FROM DOCK");
        hint.setPadding(dp(58), 0, 0, dp(12));
        ol.addView(title);
        ol.addView(hint);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        overlayGrid = new LinearLayout(this);
        overlayGrid.setOrientation(LinearLayout.VERTICAL);
        overlayGrid.setClipChildren(false);
        overlayGrid.setClipToPadding(false);
        overlayGrid.setPadding(dp(44), dp(10), dp(44), dp(40));
        sv.addView(overlayGrid, new FrameLayout.LayoutParams(-1, -2));
        ol.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        overlay.addView(ol, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    /** A 1px hairline across the top of the dock. */
    private static final class TopLine extends ColorDrawable {
        private final int h;
        TopLine(int color, int h) { super(color); this.h = h; }
        @Override public void draw(android.graphics.Canvas c) {
            c.save();
            c.clipRect(getBounds().left, getBounds().top, getBounds().right, getBounds().top + h);
            super.draw(c);
            c.restore();
        }
    }

    private void reloadApps() {
        bg.execute(new Runnable() {
            @Override public void run() {
                final List<AppRepo.App> loaded = AppRepo.load(HomeActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        apps = loaded;
                        appByPkg.clear();
                        for (AppRepo.App a : apps) appByPkg.put(a.pkg, a);
                        boolean takeFocus = firstAppLoad || dock.hasFocus() || getCurrentFocus() == null;
                        View f = getCurrentFocus();
                        Object keep = firstAppLoad || f == null ? null : f.getTag();
                        firstAppLoad = false;
                        buildDock();
                        if (overlay.getVisibility() == View.VISIBLE) buildGrid();
                        if (takeFocus && !focusTag(keep)) focusFirstDockItem();
                    }
                });
            }
        });
    }

    private List<AppRepo.App> dockApps() {
        List<AppRepo.App> out = new ArrayList<>();
        for (String p : prefs.dock()) {
            AppRepo.App a = appByPkg.get(p);
            if (a != null && !out.contains(a)) out.add(a);
        }
        return out;
    }

    /** One dock cell: tick mark, small index, label; amber bar + glow on focus. */
    private FrameLayout dockItem(String index, String label, boolean utility, Object tagObj, int width,
                                 View.OnClickListener click, View.OnLongClickListener longClick) {
        final FrameLayout cell = new FrameLayout(this);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setTag(tagObj);

        View tick = new View(this);
        tick.setBackground(new ColorDrawable(Noir.text(0.35f)));
        cell.addView(tick, new FrameLayout.LayoutParams(Math.max(1, dp(0.6f)), dp(5), Gravity.TOP | Gravity.START));

        final View bar = new View(this);
        bar.setBackground(new ColorDrawable(Noir.AMBER));
        bar.setPivotX(0);
        bar.setScaleX(0f);
        cell.addView(bar, new FrameLayout.LayoutParams(Math.round(width * 0.7f), Math.max(2, dp(1.1f)), Gravity.TOP | Gravity.START));
        final View glow = new View(this);
        glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30F5B54A, 0x00F5B54A}));
        glow.setAlpha(0f);
        cell.addView(glow, new FrameLayout.LayoutParams(Math.round(width * 0.7f), dp(8), Gravity.TOP | Gravity.START));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(11), dp(6), dp(4));
        final TextView idx = Noir.label(this, Noir.mono(this), 6, 0.2f, 0.6f);
        idx.setText(index);
        idx.setShadowLayer(0, 0, 0, 0);
        final TextView name = utility
                ? Noir.label(this, Noir.mono(this), 7.5f, 0.2f, 0.5f)
                : Noir.label(this, Noir.sans(this), 11, 0.06f, 0.5f);
        name.setText(label);
        name.setPadding(0, dp(4), 0, 0);
        col.addView(idx);
        col.addView(name);
        cell.addView(col, new FrameLayout.LayoutParams(-1, -2));

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                bar.animate().scaleX(has ? 1f : 0f).setDuration(220).start();
                glow.animate().alpha(has ? 1f : 0f).setDuration(260).start();
                name.setTextColor(has ? 0xFFFFFFFF : Noir.text(0.5f));
                idx.setTextColor(has ? Noir.AMBER : Noir.text(0.6f));
            }
        });
        cell.setOnClickListener(click);
        if (longClick != null) {
            cell.setLongClickable(true);
            cell.setOnLongClickListener(longClick);
        }
        return cell;
    }

    private void buildDock() {
        dock.removeAllViews();
        if ("orbs".equals(style)) { buildOrbs(); return; }
        if ("line".equals(style)) { buildLine(); return; }
        List<AppRepo.App> list = dockApps();
        int slots = Math.max(10, list.size() + 4);
        int width = (screenW - dp(112)) / slots;
        int n = 1;
        for (final AppRepo.App a : list) {
            FrameLayout cell = dockItem(String.format(Locale.US, "%02d", n++), a.label, false, a, width,
                    new View.OnClickListener() { public void onClick(View v) { launch(a); } },
                    new View.OnLongClickListener() { public boolean onLongClick(View v) { appMenu(a, true); return true; } });
            dock.addView(cell, new LinearLayout.LayoutParams(width, -2));
        }
        View spacer = new View(this);
        dock.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        String[][] util = {{"◐", "WALLPAPER"}, {"IN", "HDMI · TV"}, {"≡", "APPS"}, {"⚙", "SETTINGS"}};
        for (int i = 0; i < util.length; i++) {
            final int which = i;
            FrameLayout cell = dockItem(util[i][0], util[i][1], true, "util:" + i, width,
                    new View.OnClickListener() { public void onClick(View v) {
                        if (which == 0) enterAmbient(); else if (which == 1) openInputs();
                        else if (which == 2) showOverlay(); else optionsMenu();
                    } }, null);
            dock.addView(cell, new LinearLayout.LayoutParams(width, -2));
        }
    }

    // ------------------------------------------------------------------ Glass Orbs dock

    private static GradientDrawable orbBg(boolean focused, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(focused ? 0x29F5B54A : 0x12FFFFFF);
        g.setStroke(stroke, focused ? 0xD9F5B54A : 0x2EFFFFFF);
        return g;
    }

    /** One round glass button. Big orbs hold an app's white logo; small ones a utility glyph. */
    private FrameLayout orbItem(final String label, Drawable logo, String glyph, Object tagObj, boolean small,
                                View.OnClickListener click, View.OnLongClickListener longClick) {
        final int cellSize = dp(small ? 46 : 72), orbSize = dp(small ? 30 : 54);
        final FrameLayout cell = new FrameLayout(this);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setTag(tagObj);

        final View glow = new View(this);
        GradientDrawable gg = new GradientDrawable();
        gg.setShape(GradientDrawable.OVAL);
        gg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gg.setColors(new int[]{0x66F5B54A, 0x26F5B54A, 0x00F5B54A});
        gg.setGradientRadius(cellSize * 0.75f);
        glow.setBackground(gg);
        glow.setAlpha(0f);
        int gs = Math.round(cellSize * 1.5f);
        cell.addView(glow, new FrameLayout.LayoutParams(gs, gs, Gravity.CENTER));

        final int stroke = Math.max(1, dp(small ? 0.6f : 0.7f));
        final FrameLayout orb = new FrameLayout(this);
        orb.setBackground(orbBg(false, stroke));
        final View content;
        if (logo != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(logo);
            orb.addView(iv, new FrameLayout.LayoutParams(dp(34), dp(20), Gravity.CENTER));
            content = iv;
        } else {
            TextView t = Noir.label(this, Noir.sans(this), small ? 9 : 12, 0, 1f);
            t.setText(glyph);
            t.setGravity(Gravity.CENTER);
            t.setShadowLayer(0, 0, 0, 0);
            orb.addView(t, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            content = t;
        }
        content.setAlpha(0.72f);
        cell.addView(orb, new FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER));

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                float sc = has ? 1.26f : 1f;
                orb.animate().scaleX(sc).scaleY(sc).setDuration(180).start();
                orb.setBackground(orbBg(has, has ? Math.max(2, stroke * 2) : stroke));
                glow.animate().alpha(has ? 1f : 0f).setDuration(220).start();
                content.animate().alpha(has ? 1f : 0.72f).setDuration(180).start();
                if (has && focusLabel != null) focusLabel.setText(label.toUpperCase(Locale.getDefault()));
            }
        });
        cell.setOnClickListener(click);
        if (longClick != null) {
            cell.setLongClickable(true);
            cell.setOnLongClickListener(longClick);
        }
        cell.setLayoutParams(new LinearLayout.LayoutParams(cellSize, cellSize));
        return cell;
    }

    // ------------------------------------------------------------------ Logo Line dock

    /** A white app logo on the hairline; when focused it brightens and a thin amber line marks it. No glow. */
    private FrameLayout lineItem(Drawable logo, String text, Object tagObj,
                                 View.OnClickListener click, View.OnLongClickListener longClick) {
        final FrameLayout cell = new FrameLayout(this);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setClipChildren(false);
        cell.setTag(tagObj);
        final View content;
        int h = dp(17), w;
        if (logo != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(logo);
            float ar = logo.getIntrinsicHeight() > 0 ? logo.getIntrinsicWidth() / (float) logo.getIntrinsicHeight() : 2f;
            w = Math.max(dp(18), Math.min(dp(104), Math.round(h * ar)));
            if (ar < 1.6f) { h = dp(22); w = Math.max(dp(22), Math.round(h * ar)); }   // stacked logos (HBO Max) a bit taller
            content = iv;
        } else {
            TextView t = Noir.label(this, Noir.mono(this), 7, 0.24f, 1f);
            t.setText(text);
            t.setShadowLayer(0, 0, 0, 0);
            t.setGravity(Gravity.CENTER);
            t.measure(0, 0);
            w = t.getMeasuredWidth();
            h = t.getMeasuredHeight();
            content = t;
        }
        content.setAlpha(logo != null ? 0.55f : 0.5f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, h, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = dp(4);
        cell.addView(content, clp);
        final View bar = new View(this);
        bar.setBackground(new ColorDrawable(Noir.AMBER));
        bar.setScaleX(0f);
        cell.addView(bar, new FrameLayout.LayoutParams(w, Math.max(2, dp(1f)), Gravity.TOP));   // exact width: a
        // MATCH_PARENT child would make the wrap_content cell stretch across the whole dock
        cell.setPadding(0, 0, 0, 0);
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                content.animate().alpha(has ? 1f : (content instanceof ImageView ? 0.55f : 0.5f))
                        .scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(160).start();
                bar.animate().scaleX(has ? 1f : 0f).setDuration(200).start();
            }
        });
        cell.setOnClickListener(click);
        if (longClick != null) {
            cell.setLongClickable(true);
            cell.setOnLongClickListener(longClick);
        }
        cell.setMinimumWidth(w);
        return cell;
    }

    private void buildLine() {
        int cellH = dp(17 + 4 + 22);           // space above the logo for the focus line
        for (final AppRepo.App a : dockApps()) {
            FrameLayout cell = lineItem(Logos.white(getResources(), a), a.label, a,
                    new View.OnClickListener() { public void onClick(View v) { launch(a); } },
                    new View.OnLongClickListener() { public boolean onLongClick(View v) { appMenu(a, true); return true; } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell.getMinimumWidth(), cellH);
            lp.setMargins(0, 0, dp(32), 0);
            dock.addView(cell, lp);
        }
        dock.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        String[] util = {"◐ WALLPAPER", "⇥ HDMI · TV", "▦ APPS", "⚙ SETTINGS"};
        for (int i = 0; i < util.length; i++) {
            final int which = i;
            FrameLayout cell = lineItem(null, util[i], "util:" + i,
                    new View.OnClickListener() { public void onClick(View v) {
                        if (which == 0) enterAmbient(); else if (which == 1) openInputs();
                        else if (which == 2) showOverlay(); else optionsMenu();
                    } }, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell.getMinimumWidth(), cellH);
            lp.setMargins(i == 0 ? 0 : dp(20), 0, 0, 0);
            dock.addView(cell, lp);
        }
    }

    private void buildOrbs() {
        for (final AppRepo.App a : dockApps()) {
            FrameLayout cell = orbItem(a.label, Logos.white(getResources(), a), a.label, a, false,
                    new View.OnClickListener() { public void onClick(View v) { launch(a); } },
                    new View.OnLongClickListener() { public boolean onLongClick(View v) { appMenu(a, true); return true; } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dock.addView(cell, lp);
        }
        View gap = new View(this);
        dock.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));
        String[][] util = {{"◐", "Wallpaper mode"}, {"⇥", "HDMI · TV"}, {"▦", "All apps"}, {"⚙", "Settings"}};
        for (int i = 0; i < util.length; i++) {
            final int which = i;
            FrameLayout cell = orbItem(util[i][1], null, util[i][0], "util:" + i, true,
                    new View.OnClickListener() { public void onClick(View v) {
                        if (which == 0) enterAmbient(); else if (which == 1) openInputs();
                        else if (which == 2) showOverlay(); else optionsMenu();
                    } }, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
            lp.setMargins(dp(3), 0, dp(3), 0);
            dock.addView(cell, lp);
        }
    }

    /** Focus the dock item with this tag (an App or "util:n"); false if not found. */
    private boolean focusTag(Object tag) {
        if (tag == null) return false;
        for (int i = 0; i < dock.getChildCount(); i++) {
            Object t = dock.getChildAt(i).getTag();
            boolean same = tag.equals(t) || (tag instanceof AppRepo.App && t instanceof AppRepo.App
                    && ((AppRepo.App) tag).pkg.equals(((AppRepo.App) t).pkg));
            if (same) { dock.getChildAt(i).requestFocus(); return true; }
        }
        return false;
    }

    private void focusFirstDockItem() {
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    // ------------------------------------------------------------------ all apps

    private void showOverlay() {
        buildGrid();
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(180).start();
        ViewGroup firstRow = overlayGrid.getChildCount() > 0 ? (ViewGroup) overlayGrid.getChildAt(0) : null;
        if (firstRow != null && firstRow.getChildCount() > 0) firstRow.getChildAt(0).requestFocus();
    }

    private void hideOverlay() {
        if (overlay.getVisibility() != View.VISIBLE) return;
        overlay.setVisibility(View.GONE);
        for (int i = 0; i < dock.getChildCount(); i++) {
            if ("util:2".equals(dock.getChildAt(i).getTag())) { dock.getChildAt(i).requestFocus(); return; }
        }
        focusFirstDockItem();
    }

    private void buildGrid() {
        overlayGrid.removeAllViews();
        int cols = 6;
        int availDp = (int) (screenW / getResources().getDisplayMetrics().density) - 88;
        int w = dp(availDp / cols - 22), h = Math.round(w * 9f / 16f);
        LinearLayout row = null;
        List<String> docked = prefs.dock();
        for (int i = 0; i < apps.size(); i++) {
            final AppRepo.App a = apps.get(i);
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                overlayGrid.addView(row, new LinearLayout.LayoutParams(-2, -2));
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setLongClickable(true);
            cell.setTag(a);
            cell.setClipChildren(false);
            FrameLayout t = Ui.tile(this, a, w, h);
            t.setElevation(dp(2));
            cell.addView(t, new LinearLayout.LayoutParams(w, h));
            TextView label = Noir.label(this, Noir.sans(this), 9.5f, 0.04f, 0.8f);
            label.setText((docked.contains(a.pkg) ? "● " : "") + a.label);
            label.setPadding(dp(2), dp(7), 0, 0);
            cell.addView(label, new LinearLayout.LayoutParams(w, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(11), dp(10), dp(11), dp(10));
            cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) { Ui.lift(v, has, 1.08f); }
            });
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { launch(a); }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { appMenu(a, false); return true; }
            });
            row.addView(cell, lp);
        }
    }

    // ------------------------------------------------------------------ actions

    private void launch(AppRepo.App a) {
        try {
            startActivity(a.launch);
        } catch (RuntimeException e) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (i != null) {
                try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                catch (RuntimeException ignored) { }
            }
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** The TV's own input picker (HDMI 1-3, DTV, ATV, AV). */
    private void openInputs() {
        Intent[] tries = {
                new Intent("com.android.tv.action.VIEW_INPUTS"),
                new Intent("com.realtek.tv.action.VIEW_INPUTS"),
                new Intent().setComponent(new ComponentName("com.realtek.tv", "com.realtek.tv.SelectInputActivity")),
        };
        for (Intent i : tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            catch (RuntimeException ignored) { }
        }
        Toast.makeText(this, "Input picker not available", Toast.LENGTH_SHORT).show();
    }

    private void appMenu(final AppRepo.App a, final boolean fromDock) {
        final boolean inDock = prefs.dock().contains(a.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Open");
        acts.add(new Runnable() { public void run() { launch(a); } });
        if (inDock) {
            final List<AppRepo.App> visible = dockApps();
            final int idx = visible.indexOf(a);
            if (idx > 0) {
                labels.add("Move left");
                acts.add(new Runnable() { public void run() { move(a, visible, -1); } });
            }
            if (idx >= 0 && idx < visible.size() - 1) {
                labels.add("Move right");
                acts.add(new Runnable() { public void run() { move(a, visible, 1); } });
            }
            labels.add("Remove from dock");
            acts.add(new Runnable() { public void run() {
                List<String> d = prefs.dock();
                d.remove(a.pkg);
                prefs.setDock(d);
                afterDockChange(fromDock, null);
            } });
        } else {
            labels.add("Add to dock");
            acts.add(new Runnable() { public void run() {
                List<String> d = prefs.dock();
                d.add(a.pkg);
                prefs.setDock(d);
                afterDockChange(fromDock, a);
                Toast.makeText(HomeActivity.this, a.label + " added to the dock", Toast.LENGTH_SHORT).show();
            } });
        }
        if (!a.internal) labels.add("App info");
        if (!a.internal) acts.add(new Runnable() { public void run() {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
        } });
        dialog(a.label, labels, acts);
    }

    private void move(AppRepo.App a, List<AppRepo.App> visible, int delta) {
        int i = visible.indexOf(a), j = i + delta;
        if (i < 0 || j < 0 || j >= visible.size()) return;
        AppRepo.App other = visible.get(j);
        List<String> d = prefs.dock();
        int pi = d.indexOf(a.pkg), pj = d.indexOf(other.pkg);
        d.set(pi, other.pkg);
        d.set(pj, a.pkg);
        prefs.setDock(d);
        afterDockChange(true, a);
    }

    private void afterDockChange(boolean fromDock, AppRepo.App focus) {
        buildDock();
        if (overlay.getVisibility() == View.VISIBLE) {
            buildGrid();
            if (!fromDock) {
                for (int r = 0; r < overlayGrid.getChildCount(); r++) {
                    ViewGroup row = (ViewGroup) overlayGrid.getChildAt(r);
                    for (int c = 0; c < row.getChildCount(); c++) {
                        if (row.getChildAt(c).getTag() == focus) { row.getChildAt(c).requestFocus(); return; }
                    }
                }
            }
            return;
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            if (dock.getChildAt(i).getTag() == focus) { dock.getChildAt(i).requestFocus(); return; }
        }
        focusFirstDockItem();
    }

    private void optionsMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Next wallpaper");
        acts.add(new Runnable() { public void run() { loadArt(false); } });
        labels.add("Change wallpaper every " + prefs.intervalMinutes() + " min");
        acts.add(new Runnable() { public void run() { pickInterval(); } });
        labels.add("Wallpapers: " + wallpaperNames(Art.kinds(prefs.source())));
        acts.add(new Runnable() { public void run() { pickWallpapers(); } });
        float b = prefs.brightness();
        labels.add("Wallpaper brightness: " + (b < 0.55f ? "dim" : b > 0.7f ? "bright" : "medium"));
        acts.add(new Runnable() { public void run() { pickBrightness(); } });
        labels.add("Wallpaper captions: " + (prefs.captions() ? "on" : "off"));
        acts.add(new Runnable() { public void run() {
            prefs.setCaptions(!prefs.captions());
            captionBox.setVisibility(prefs.captions() ? View.VISIBLE : View.INVISIBLE);
        } });
        labels.add("Style: " + ("orbs".equals(style) ? "Glass Orbs" : "hud".equals(style) ? "Noir HUD" : "Logo Line"));
        acts.add(new Runnable() { public void run() { pickStyle(); } });
        labels.add("Weather (Hong Kong Observatory): " + (prefs.weather() ? "on" : "off"));
        acts.add(new Runnable() { public void run() {
            prefs.setWeather(!prefs.weather());
            showWeather(prefs.weather() ? Weather.cached(HomeActivity.this) : null);
            if (prefs.weather()) { ui.removeCallbacks(refreshWeather); refreshWeather.run(); }
        } });
        labels.add("Speaker mini player (Nest Mini): " + (prefs.player() ? "on" : "off"));
        acts.add(new Runnable() { public void run() {
            prefs.setPlayer(!prefs.player());
            Nest.get(HomeActivity.this).setEnabled(prefs.player());
        } });
        labels.add("Reset dock to defaults");
        acts.add(new Runnable() { public void run() { prefs.resetDock(); buildDock(); focusFirstDockItem(); } });
        labels.add("Android TV settings");
        acts.add(new Runnable() { public void run() {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException e) { Toast.makeText(HomeActivity.this, "Settings unavailable", Toast.LENGTH_SHORT).show(); }
        } });
        dialog("Gallery Home", labels, acts);
    }

    private static final String[] KIND_NAMES = {"Space (NASA)", "Night cities & architecture", "Dark landscapes", "Museum paintings",
            "Artvee · Landscapes", "Japanese prints · Artvee & museums", "Artvee · Vintage posters", "Artvee · Illustrations & engravings",
            "Artvee · Amber nights (warm, for your room)"};

    private static String wallpaperNames(List<String> ks) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < Art.KINDS.length; i++) {
            if (ks.contains(Art.KINDS[i])) n.add(KIND_NAMES[i].replace(" (NASA)", "").replace(" & architecture", "")
                    .replace("Artvee · ", "").replace(" (warm, for your room)", "").replace(" & engravings", ""));
        }
        return android.text.TextUtils.join(", ", n);
    }

    private void pickWallpapers() {
        List<String> current = Art.kinds(prefs.source());
        final boolean[] checked = new boolean[Art.KINDS.length];
        for (int i = 0; i < checked.length; i++) checked[i] = current.contains(Art.KINDS[i]);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Wallpapers")
                .setMultiChoiceItems(KIND_NAMES, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean on) { checked[which] = on; }
                })
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        List<String> ks = new ArrayList<>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) ks.add(Art.KINDS[i]);
                        prefs.setSource(android.text.TextUtils.join(",", Art.kinds(android.text.TextUtils.join(",", ks))));
                        loadArt(false);
                    }
                })
                .show();
    }

    private void pickStyle() {
        final String[] keys = {"line", "orbs", "hud"};
        String[] names = {"Logo Line", "Glass Orbs", "Noir HUD"};
        List<String> l = new ArrayList<>();
        List<Runnable> r = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            l.add(names[i]);
            r.add(new Runnable() { public void run() { prefs.setStyle(k); recreate(); } });
        }
        dialog("Style", l, r);
    }

    private void pickBrightness() {
        final float[] vals = {0.45f, 0.62f, 0.8f};
        String[] names = {"Dim (best at night)", "Medium", "Bright (daytime)"};
        List<String> l = new ArrayList<>();
        List<Runnable> r = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) {
            final float v = vals[i];
            l.add(names[i]);
            r.add(new Runnable() { public void run() {
                prefs.setBrightness(v);
                Noir.dim(bgFront, wallBrightness(artDim));
                Noir.dim(bgBack, wallBrightness(artDim));
            } });
        }
        dialog("Wallpaper brightness", l, r);
    }

    private void pickInterval() {
        int[] mins = {1, 2, 5, 10, 15, 30, 60};
        List<String> l = new ArrayList<>();
        List<Runnable> r = new ArrayList<>();
        for (final int m : mins) {
            l.add(m + (m == 1 ? " minute" : " minutes"));
            r.add(new Runnable() { public void run() { prefs.setIntervalMinutes(m); scheduleArt(); } });
        }
        dialog("Change wallpaper every", l, r);
    }

    private void dialog(String title, List<String> labels, final List<Runnable> acts) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { acts.get(which).run(); }
                })
                .show();
    }

    // ------------------------------------------------------------------ clock & art

    private final Runnable tickClock = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(HomeActivity.this);
            clock.setText(DateFormat.format(h24 ? "HH:mm" : "hh:mm", now));
            if (ambClock != null) ambClock.setText(clock.getText());
            String ds = DateFormat.format(orbs ? "EEE · d MMM" : "EEEE · d MMMM", now).toString();
            if (wx != null && wx.ok()) ds += " · North Point";
            date.setText(ds.toUpperCase(Locale.getDefault()));
            ui.removeCallbacks(this);
            if (resumed) ui.postDelayed(this, 60_000 - (System.currentTimeMillis() % 60_000) + 50);
        }
    };

    private final Runnable rotateArt = new Runnable() {
        @Override public void run() { loadArt(false); scheduleArt(); }
    };

    // ------------------------------------------------------------------ weather (HKO)

    private final Runnable refreshWeather = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (!prefs.weather()) return;
            if (Weather.age(HomeActivity.this) > Weather.REFRESH_MS && !wxLoading) {
                wxLoading = true;
                wxExec.execute(new Runnable() {
                    @Override public void run() {
                        final Weather.Data d = Weather.fetch(HomeActivity.this);
                        ui.post(new Runnable() { public void run() { wxLoading = false; if (d != null) showWeather(d); } });
                    }
                });
            }
            if (resumed) ui.postDelayed(this, 60_000);   // checks each minute, fetches when 10 min old
        }
    };

    private void showWeather(Weather.Data d) {
        if (!prefs.weather()) d = null;
        if (d != null && wxDemo) {          // test hook: sample warnings so the badges can be checked
            d.warns.clear();
            String[][] demo = {{"TC8NE", "", ""}, {"WRAINA", "", ""}, {"WTS", "", ""}, {"WHOT", "", ""}};
            for (String[] x : demo) d.warns.add(Weather.warning(x[0], x[0], ""));
        }
        wx = d;
        WeatherViews.fillFull(wxBox, d, orbs);
        WeatherViews.fillCompact(ambWx, d);
        tickClock.run();
    }

    private void scheduleArt() {
        ui.removeCallbacks(rotateArt);
        if (resumed) ui.postDelayed(rotateArt, prefs.intervalMinutes() * 60_000L);
    }

    private void loadArt(final boolean cachedFirst) {
        if (loadingArt) {                       // e.g. the wallpaper sets changed mid-download: load again when this one is done
            if (!cachedFirst) artAgain = true;
            return;
        }
        loadingArt = true;
        final String source = prefs.source();
        bg.execute(new Runnable() {
            @Override public void run() {
                Art.Item it = null;
                if (cachedFirst) it = art.cached(source, screenW, screenH);
                if (it == null) {
                    try { it = art.next(source, screenW, screenH); } catch (RuntimeException ignored) { }
                }
                final Art.Item result = it;
                ui.post(new Runnable() {
                    @Override public void run() {
                        loadingArt = false;
                        boolean stale = !source.equals(prefs.source());
                        if (result != null && !(stale && artAgain)) showArt(result);
                        if (artAgain) { artAgain = false; loadArt(false); }
                    }
                });
            }
        });
    }

    /** Cross-fade: new picture underneath, old one fades away on top, then the top view takes over. */
    private void showArt(Art.Item it) {
        lastArtAt = System.currentTimeMillis();
        final Bitmap bmp = it.bitmap;
        final float dim = it.dim;
        bgFront.animate().cancel();
        bgBack.setImageBitmap(bmp);
        Noir.dim(bgBack, wallBrightness(dim));          // the new picture fades in at its own brightness
        bgFront.animate().alpha(0f).setDuration(1400).withEndAction(new Runnable() {
            @Override public void run() {
                artDim = dim;
                Noir.dim(bgFront, wallBrightness(dim));
                bgFront.setImageBitmap(bmp);
                bgFront.setAlpha(1f);
                bgBack.setImageDrawable(null);
            }
        }).start();
        String credit = it.credit == null ? "" : it.credit;
        String l1 = credit, l2 = "";
        int cut = credit.lastIndexOf("  ·  ");
        if (cut > 0) { l1 = credit.substring(0, cut); l2 = credit.substring(cut + 5); }
        curTitle = it.title == null ? "" : it.title;
        curCredit = credit;
        if (ambient) {
            ambTitle.setText(prefs.captions() ? curTitle : "");
            ambCredit.setText(prefs.captions() ? credit.replace("  ·  ", " · ").toUpperCase(Locale.getDefault()) : "");
        }
        artTitle.setText(it.title == null ? "" : it.title);
        artLine1.setText(l1.replace(", ", " · ").toUpperCase(Locale.getDefault()));
        artLine2.setText(l2.toUpperCase(Locale.getDefault()));
        captionBox.setVisibility(prefs.captions() ? View.VISIBLE : View.INVISIBLE);
        if (!ambient) {
            captionBox.setAlpha(0f);
            captionBox.animate().alpha(1f).setDuration(900).start();
        }
    }

    private int dp(float v) { return Ui.dp(this, v); }
}
