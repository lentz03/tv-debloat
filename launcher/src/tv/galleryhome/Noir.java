package tv.galleryhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** "Noir HUD" look: near-black, dimmed full-screen painting, thin type, amber accents like the room's LED strip. */
final class Noir {
    private Noir() { }

    static final int INK = 0xFF050505;
    static final int TEXT = 0xFFECE4D8;
    static final int AMBER = 0xFFF5B54A;

    static int text(float alpha) { return (Math.round(alpha * 255) << 24) | (TEXT & 0x00FFFFFF); }

    // ------------------------------------------------------------ fonts (all SIL OFL 1.1, see assets/fonts)
    private static Typeface thin, mono, sans, tall;

    static Typeface tall(Context c) {   // Big Shoulders: tall condensed clock (Glass Orbs style)
        if (tall == null) tall = load(c, "fonts/BigShoulders-Regular.ttf", Typeface.SANS_SERIF);
        return tall;
    }

    static Typeface thin(Context c) {   // Jura Light: large clock
        if (thin == null) thin = load(c, "fonts/Jura-Light.ttf", Typeface.SANS_SERIF);
        return thin;
    }

    static Typeface mono(Context c) {   // Geist Mono: small HUD labels
        if (mono == null) mono = load(c, "fonts/GeistMono-Regular.ttf", Typeface.MONOSPACE);
        return mono;
    }

    static Typeface sans(Context c) {   // Outfit: app names, painting titles
        if (sans == null) sans = load(c, "fonts/Outfit-Regular.ttf", Typeface.SANS_SERIF);
        return sans;
    }

    private static Typeface load(Context c, String path, Typeface fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), path); }
        catch (RuntimeException e) { return fallback; }
    }

    static TextView label(Context c, Typeface tf, float sp, float spacing, float alpha) {
        TextView t = new TextView(c);
        t.setTypeface(tf);
        t.setTextSize(sp);
        t.setLetterSpacing(spacing);
        t.setTextColor(text(alpha));
        t.setMaxLines(1);
        t.setHorizontallyScrolling(false);
        if (sp < 20) t.setEllipsize(android.text.TextUtils.TruncateAt.END);   // never ellipsize the big clock
        t.setIncludeFontPadding(false);
        t.setShadowLayer(Ui.dp(c, 8), 0, 0, 0x99000000);
        return t;
    }

    /** Dim and slightly desaturate the painting so it sits back like a wall in a dark room. */
    static void dim(ImageView iv, float brightness) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.85f);
        ColorMatrix b = new ColorMatrix(new float[]{
                brightness, 0, 0, 0, 0,
                0, brightness, 0, 0, 0,
                0, 0, brightness, 0, 0,
                0, 0, 0, 1, 0});
        cm.postConcat(b);
        iv.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    /** Fades the picture into black towards the edges, strongest at the bottom where the dock sits. */
    static View vignette(Context c, int screenW) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x00050505, 0x00050505, 0x8C050505, 0xF0050505});
        g.setGradientCenter(0.5f, 0.42f);
        g.setGradientRadius(screenW * 0.62f);
        v.setBackground(g);
        return v;
    }

    static View bottomShade(Context c) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF0050505, 0xB3050505, 0x00050505}));
        return v;
    }

    /**
     * Warm glow rising from the bottom edge, matching the LED strip under the TV unit.
     * Must be added full-screen: the glow fades to nothing well inside the view, so it has no visible edge.
     */
    static View amberGlow(Context c, int screenW) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x28F5A53C, 0x0FF5A53C, 0x00F5A53C, 0x00F5A53C});
        g.setGradientCenter(0.5f, 1.22f);
        g.setGradientRadius(screenW * 0.40f);
        v.setBackground(g);
        return v;
    }

    /** A 1px hairline across the top of a view. */
    static final class TopLine extends android.graphics.drawable.ColorDrawable {
        private final int h;
        TopLine(int color, int h) { super(color); this.h = h; }
        @Override public void draw(Canvas c) {
            c.save();
            c.clipRect(getBounds().left, getBounds().top, getBounds().right, getBounds().top + h);
            super.draw(c);
            c.restore();
        }
    }

    /** Four thin HUD corner brackets. */
    static final class Brackets extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float inset, len;

        Brackets(Context c) {
            super(c);
            p.setColor(text(0.42f));
            p.setStrokeWidth(Math.max(1f, Ui.dp(c, 0.6f)));
            inset = Ui.dp(c, 32);
            len = Ui.dp(c, 22);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), i = inset, l = len;
            cv.drawLine(i, i, i + l, i, p);         cv.drawLine(i, i, i, i + l, p);
            cv.drawLine(w - i, i, w - i - l, i, p); cv.drawLine(w - i, i, w - i, i + l, p);
            cv.drawLine(i, h - i, i + l, h - i, p); cv.drawLine(i, h - i, i, h - i - l, p);
            cv.drawLine(w - i, h - i, w - i - l, h - i, p); cv.drawLine(w - i, h - i, w - i, h - i - l, p);
        }
    }
}
