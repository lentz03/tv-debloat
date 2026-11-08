package tv.galleryhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** View helpers shared by the home screen and the screensaver. */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, float sp, boolean light) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(light ? "sans-serif-light" : "sans-serif-medium", Typeface.NORMAL));
        t.setShadowLayer(dp(c, 6), 0, dp(c, 1), 0xAA000000);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static View gradient(Context c, boolean top, int alpha) {
        View v = new View(c);
        int dark = Color.argb(alpha, 0, 0, 0);
        GradientDrawable g = new GradientDrawable(
                top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{dark, Color.argb(alpha / 3, 0, 0, 0), Color.TRANSPARENT});
        v.setBackground(g);
        return v;
    }

    static void round(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** A 16:9 app tile: the app's TV banner, or its icon on a soft colour if it has none. */
    static FrameLayout tile(Context c, AppRepo.App app, int w, int h) {
        FrameLayout f = new FrameLayout(c);
        ImageView iv = new ImageView(c);
        if (app != null && app.banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            f.addView(iv, new FrameLayout.LayoutParams(w, h));
        } else {
            f.setBackground(new ColorDrawable(softColor(app == null ? "" : app.label)));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable d = app == null ? null : app.icon;
            iv.setImageDrawable(d);
            int s = Math.round(h * 0.55f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
            f.addView(iv, lp);
        }
        round(f, dp(c, 10));
        return f;
    }

    /** A plain rounded pill button with a label. */
    static TextView pill(Context c, String label) {
        TextView t = text(c, 14, false);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 18), dp(c, 7), dp(c, 18), dp(c, 7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(70, 255, 255, 255));
        g.setCornerRadius(dp(c, 20));
        t.setBackground(g);
        t.setFocusable(true);
        t.setShadowLayer(0, 0, 0, 0);
        return t;
    }

    static int softColor(String seed) {
        float hue = (Math.abs(seed.hashCode()) % 360);
        return Color.HSVToColor(new float[]{hue, 0.35f, 0.42f});
    }

    /** Apple TV-style lift on focus. */
    static void lift(View v, boolean focused, float scale) {
        v.animate().cancel();
        v.animate()
                .scaleX(focused ? scale : 1f)
                .scaleY(focused ? scale : 1f)
                .translationZ(focused ? dp(v.getContext(), 14) : 0)
                .setDuration(140)
                .start();
    }
}
