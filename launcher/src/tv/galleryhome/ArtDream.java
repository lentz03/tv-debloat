package tv.galleryhome;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screensaver in the Noir HUD style: the painting fills the screen, a little brighter, with a quiet clock. */
public class ArtDream extends DreamService {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService bg;
    private Art art;
    private Prefs prefs;
    private ImageView front, back;
    private TextView clock, title, credit;
    private int w, h;
    private boolean running;
    private LinearLayout wxBox;
    private NestViews.Line nest;
    private final Nest.Listener nestListener = new Nest.Listener() {
        @Override public void onNest(Nest.Status s) { nest.update(s); }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        art = new Art(this);
        prefs = new Prefs(this);
        bg = Executors.newSingleThreadExecutor();
        DisplayMetrics m = getResources().getDisplayMetrics();
        w = Math.max(m.widthPixels, m.heightPixels);
        h = Math.min(m.widthPixels, m.heightPixels);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Noir.INK);
        back = new ImageView(this);
        front = new ImageView(this);
        float bright = Math.min(0.85f, prefs.brightness() + 0.15f);
        for (ImageView iv : new ImageView[]{back, front}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Noir.dim(iv, bright);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(Noir.vignette(this, w), new FrameLayout.LayoutParams(-1, -1));
        root.addView(Noir.amberGlow(this, w), new FrameLayout.LayoutParams(-1, -1));
        // (no HUD corner brackets in the screensaver: just the picture, clock, weather and caption)

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        boolean orbs = !"hud".equals(prefs.style());
        clock = Noir.label(this, orbs ? Noir.tall(this) : Noir.thin(this), orbs ? 52 : 40, 0.02f, 0.9f);
        title = Noir.label(this, Noir.sans(this), 11, 0.03f, 0.95f);
        title.setPadding(0, Ui.dp(this, 12), 0, 0);
        credit = Noir.label(this, Noir.mono(this), 6.5f, 0.22f, 0.7f);
        credit.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(clock);
        wxBox = new LinearLayout(this);
        wxBox.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = Ui.dp(this, 8);
        box.addView(wxBox, wl);
        if (prefs.weather()) WeatherViews.fillCompact(wxBox, Weather.cached(this));
        nest = new NestViews.Line(this);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = Ui.dp(this, 10);
        box.addView(nest, nl);
        box.addView(title);
        box.addView(credit);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 600), -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(Ui.dp(this, 56), 0, 0, Ui.dp(this, 52));
        root.addView(box, lp);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        tick.run();
        next(true);
        wxTick.run();
        Nest.get(this).addListener(nestListener);
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        Nest.get(this).removeListener(nestListener);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (bg != null) bg.shutdownNow();
        super.onDetachedFromWindow();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean h24 = DateFormat.is24HourFormat(ArtDream.this);
            clock.setText(DateFormat.format(h24 ? "HH:mm" : "hh:mm", new Date()));
            if (running) ui.postDelayed(this, 30_000);
        }
    };

    private final Runnable wxTick = new Runnable() {
        @Override public void run() {
            if (!running || !prefs.weather()) return;
            if (Weather.age(ArtDream.this) > Weather.REFRESH_MS) {
                bg.execute(new Runnable() {
                    @Override public void run() {
                        final Weather.Data d = Weather.fetch(ArtDream.this);
                        ui.post(new Runnable() { public void run() { if (running && d != null) WeatherViews.fillCompact(wxBox, d); } });
                    }
                });
            }
            ui.postDelayed(this, 60_000);
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override public void run() { next(false); }
    };

    private void next(final boolean cachedFirst) {
        final String source = prefs.source();
        bg.execute(new Runnable() {
            @Override public void run() {
                Art.Item it = cachedFirst ? art.cached(source, w, h) : null;
                if (it == null) it = art.next(source, w, h);
                final Art.Item r = it;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!running) return;
                        if (r != null) show(r);
                        ui.postDelayed(rotate, 90_000);
                    }
                });
            }
        });
    }

    private void show(Art.Item it) {
        final Bitmap bmp = it.bitmap;
        front.animate().cancel();
        back.setImageBitmap(bmp);
        front.animate().alpha(0f).setDuration(2000).withEndAction(new Runnable() {
            @Override public void run() {
                front.setImageBitmap(bmp);
                front.setAlpha(1f);
                back.setImageDrawable(null);
            }
        }).start();
        boolean cap = prefs.captions();
        title.setText(cap && it.title != null ? it.title : "");
        credit.setText(cap && it.credit != null ? it.credit.replace("  ·  ", " · ").toUpperCase(Locale.getDefault()) : "");
    }
}
