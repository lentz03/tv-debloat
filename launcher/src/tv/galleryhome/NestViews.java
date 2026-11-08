package tv.galleryhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** The Nest Mini mini player ("Clock Column"): a compact block under the forecast, plus a one-line version for wallpaper mode. */
final class NestViews {
    private NestViews() { }

    private static int dp(Context c, float v) { return Ui.dp(c, v); }

    static String time(double s) {
        if (s < 0 || Double.isNaN(s)) s = 0;
        int t = (int) Math.floor(s);
        return t >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
                         : String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }

    // ------------------------------------------------------------------ drawn glyphs (24-unit grid, 2-unit round stroke)

    static final class Glyph extends View {
        static final int PREV = 0, NEXT = 1, PLAY = 2, PAUSE = 3, VOL = 4;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int kind;

        Glyph(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setColor(Noir.text(0.78f));
        }

        void setKind(int k) { if (k != kind) { kind = k; invalidate(); } }
        void setColor(int c) { p.setColor(c); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            c.scale(s, s);
            p.setStrokeWidth(2f);
            Path path = new Path();
            switch (kind) {
                case PREV:
                    c.drawLine(6, 5, 6, 19, p);
                    path.moveTo(18, 6); path.lineTo(9, 12); path.lineTo(18, 18); path.close();
                    break;
                case NEXT:
                    c.drawLine(18, 5, 18, 19, p);
                    path.moveTo(6, 6); path.lineTo(15, 12); path.lineTo(6, 18); path.close();
                    break;
                case PLAY:
                    path.moveTo(7, 5); path.lineTo(19, 12); path.lineTo(7, 19); path.close();
                    break;
                case PAUSE:
                    c.drawLine(8.5f, 5, 8.5f, 19, p);
                    c.drawLine(15.5f, 5, 15.5f, 19, p);
                    break;
                default:
                    path.moveTo(4, 9.5f); path.lineTo(7.5f, 9.5f); path.lineTo(12, 6); path.lineTo(12, 18);
                    path.lineTo(7.5f, 14.5f); path.lineTo(4, 14.5f); path.close();
                    c.drawPath(path, p);
                    path = new Path();
                    c.drawArc(new RectF(12, 8.5f, 19, 15.5f), -55, 110, false, p);
                    c.drawArc(new RectF(11, 5, 25, 19), -55, 110, false, p);
                    break;
            }
            c.drawPath(path, p);
            c.restore();
        }
    }

    /** Hairline track with an amber fill. */
    static final class Progress extends View {
        private final Paint track = new Paint(), fill = new Paint();
        private float frac;

        Progress(Context c) {
            super(c);
            track.setColor(Noir.text(0.18f));
            fill.setColor(Noir.AMBER);
        }

        void set(float f) {
            f = Math.max(0f, Math.min(1f, f));
            if (Math.abs(f - frac) > 0.0005f) { frac = f; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), track);
            c.drawRect(0, 0, getWidth() * frac, getHeight(), fill);
        }
    }

    private static void roundCorners(View v, final float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
    }

    private static ImageView cover(Context c) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(Noir.text(0.08f));
        roundCorners(iv, dp(c, 1.5f));
        return iv;
    }

    // ------------------------------------------------------------------ home screen block

    /** Cover · title · artist, a progress hairline, then time and four round buttons. Sits in the clock column. */
    static final class Panel extends LinearLayout {
        interface Host { void leavePanel(); }

        private final ImageView art;
        private final TextView title, artist, time;
        private final Progress bar;
        private final FrameLayout[] btn = new FrameLayout[4];
        private final Glyph playGlyph;
        private Nest.Status st = Nest.Status.NONE;
        private boolean volMode;
        private final Host host;

        Panel(Context c, Host host) {
            super(c);
            this.host = host;
            setOrientation(VERTICAL);
            setBackground(new Noir.TopLine(Noir.text(0.15f), Math.max(1, dp(c, 0.6f))));
            setPadding(0, dp(c, 10), 0, 0);
            setClipChildren(false);
            setClipToPadding(false);
            setVisibility(GONE);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            art = cover(c);
            row.addView(art, new LayoutParams(dp(c, 32), dp(c, 32)));
            LinearLayout txt = new LinearLayout(c);
            txt.setOrientation(VERTICAL);
            title = Noir.label(c, Noir.sans(c), 11, 0.02f, 1f);
            artist = Noir.label(c, Noir.mono(c), 6, 0.22f, 0.7f);
            artist.setPadding(0, dp(c, 4), 0, 0);
            txt.addView(title, new LayoutParams(-1, -2));
            txt.addView(artist, new LayoutParams(-1, -2));
            LayoutParams tl = new LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(c, 8);
            row.addView(txt, tl);
            addView(row, new LayoutParams(-1, -2));

            bar = new Progress(c);
            LayoutParams bl = new LayoutParams(-1, Math.max(1, dp(c, 1f)));
            bl.topMargin = dp(c, 9);
            addView(bar, bl);

            LinearLayout bottom = new LinearLayout(c);
            bottom.setOrientation(HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setClipChildren(false);
            time = Noir.label(c, Noir.mono(c), 6, 0.14f, 0.6f);
            bottom.addView(time, new LayoutParams(0, -2, 1f));
            int[] kinds = {Glyph.PREV, Glyph.PAUSE, Glyph.NEXT, Glyph.VOL};
            Glyph pg = null;
            for (int i = 0; i < 4; i++) {
                final int which = i;
                final Glyph g = new Glyph(c, kinds[i]);
                if (i == 1) pg = g;
                final FrameLayout b = new FrameLayout(c);
                b.setFocusable(true);
                b.setClickable(true);
                b.setBackground(ring(c, false));
                b.addView(g, new FrameLayout.LayoutParams(dp(c, 8), dp(c, 8), Gravity.CENTER));
                b.setOnFocusChangeListener(new OnFocusChangeListener() {
                    @Override public void onFocusChange(View v, boolean has) {
                        b.setBackground(ring(getContext(), has));
                        g.setColor(has ? 0xFFFFFFFF : Noir.text(0.78f));
                        if (!has && which == 3) setVolMode(false);
                    }
                });
                b.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { press(which); }
                });
                b.setOnKeyListener(new OnKeyListener() {
                    @Override public boolean onKey(View v, int code, KeyEvent e) { return key(which, code, e); }
                });
                btn[i] = b;
                LayoutParams lp = new LayoutParams(dp(c, 20), dp(c, 20));
                lp.leftMargin = dp(c, 4);
                bottom.addView(b, lp);
            }
            playGlyph = pg;
            LayoutParams bo = new LayoutParams(-1, -2);
            bo.topMargin = dp(c, 7);
            addView(bottom, bo);
        }

        private static GradientDrawable ring(Context c, boolean focused) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(focused ? 0x1FF5B54A : 0x00000000);
            g.setStroke(Math.max(1, dp(c, focused ? 0.8f : 0.5f)), focused ? Noir.AMBER : Noir.text(0.16f));
            return g;
        }

        boolean focusMain() {
            if (getVisibility() != VISIBLE) return false;
            return btn[1].requestFocus();
        }

        private void press(int which) {
            Nest n = Nest.get(getContext());
            if (which == 0) n.skip(-1);
            else if (which == 1) n.playPause();
            else if (which == 2) n.skip(1);
            else setVolMode(!volMode);
        }

        private boolean key(int which, int code, KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (volMode && which == 3) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (down) {
                        float v = st.volume < 0 ? 0.3f : st.volume;
                        Nest.get(getContext()).setVolume(v + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 0.05f : -0.05f));
                        removeCallbacks(volTimeout);
                        postDelayed(volTimeout, 5_000);
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (down) setVolMode(false);
                    return true;
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && which == 0) return true;      // stay inside the player
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && which == 3) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) {
                if (down) host.leavePanel();
                return true;
            }
            return false;
        }

        private final Runnable volTimeout = new Runnable() { public void run() { setVolMode(false); } };

        private void setVolMode(boolean on) {
            volMode = on;
            removeCallbacks(volTimeout);
            if (on) postDelayed(volTimeout, 5_000);
            render();
        }

        void update(Nest.Status s) {
            boolean had = st.active;
            Bitmap old = st.art;
            st = s;
            if (s.active != had || getVisibility() != (s.active ? VISIBLE : GONE)) {
                if (s.active) {
                    setAlpha(0f);
                    setVisibility(VISIBLE);
                    animate().alpha(1f).setDuration(400).start();
                } else {
                    if (hasFocus()) host.leavePanel();
                    setVolMode(false);
                    setVisibility(GONE);
                }
            }
            if (s.art != old) art.setImageBitmap(s.art);
            title.setText(s.title);
            artist.setText(s.artist.toUpperCase(Locale.getDefault()));
            playGlyph.setKind(s.playing ? Glyph.PAUSE : Glyph.PLAY);
            btn[0].setAlpha(s.canPrev ? 1f : 0.45f);
            btn[2].setAlpha(s.canNext ? 1f : 0.45f);
            render();
            removeCallbacks(ticker);
            if (s.active) post(ticker);
        }

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                render();
                if (st.active && st.playing) postDelayed(this, 1000);
            }
        };

        private void render() {
            if (volMode) {
                float v = st.volume < 0 ? 0 : st.volume;
                time.setText("VOLUME " + Math.round(v * 100) + "%");
                time.setTextColor(Noir.AMBER);
                bar.set(v);
                return;
            }
            time.setTextColor(Noir.text(0.6f));
            double p = st.positionNow();
            if (st.duration > 0) {
                time.setText(time(p) + " / " + time(st.duration));
                bar.set((float) (p / st.duration));
            } else {
                time.setText(st.active ? (st.playing ? "LIVE" : "PAUSED") : "");
                bar.set(0);
            }
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(ticker);
            removeCallbacks(volTimeout);
            super.onDetachedFromWindow();
        }
    }

    // ------------------------------------------------------------------ wallpaper mode / screensaver line

    /** Small cover, "Title — Artist", and a short progress hairline under the text. */
    static final class Line extends LinearLayout {
        private final ImageView art;
        private final TextView text;
        private final Progress bar;
        private Nest.Status st = Nest.Status.NONE;

        Line(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setVisibility(GONE);
            art = cover(c);
            addView(art, new LayoutParams(dp(c, 22), dp(c, 22)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            text = Noir.label(c, Noir.sans(c), 9, 0.02f, 0.95f);
            col.addView(text, new LayoutParams(-2, -2));
            bar = new Progress(c);
            LayoutParams bl = new LayoutParams(dp(c, 130), Math.max(1, dp(c, 1f)));
            bl.topMargin = dp(c, 5);
            col.addView(bar, bl);
            LayoutParams cl = new LayoutParams(-2, -2);
            cl.leftMargin = dp(c, 7);
            addView(col, cl);
        }

        void update(Nest.Status s) {
            Bitmap old = st.art;
            st = s;
            setVisibility(s.active ? VISIBLE : GONE);
            if (s.art != old) art.setImageBitmap(s.art);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(s.title);
            if (!s.artist.isEmpty()) {
                int at = sb.length();
                sb.append(" — ").append(s.artist);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Noir.text(0.6f)), at, sb.length(), 0);
            }
            text.setText(sb);
            removeCallbacks(ticker);
            if (s.active) post(ticker);
        }

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                bar.set(st.duration > 0 ? (float) (st.positionNow() / st.duration) : 0f);
                bar.setVisibility(st.duration > 0 ? VISIBLE : INVISIBLE);
                if (st.active && st.playing) postDelayed(this, 1000);
            }
        };

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(ticker);
            super.onDetachedFromWindow();
        }
    }
}
