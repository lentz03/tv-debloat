package tv.galleryhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The two ways the board can draw the journey to work: one route line, or the three steps each with its own piece of route. */
final class JourneyViews {
    private JourneyViews() { }

    static final String LINE = "line", GLYPHS = "glyphs";

    private static final int GREY = 0x99ECE4D8, AMBER_TEXT = 0xD9F5B54A, SEG = 0x99F5B54A;

    private static Paint stroke(int color, float w, boolean dotted) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        p.setStrokeWidth(w);
        if (dotted) p.setPathEffect(new DashPathEffect(new float[]{1, 10}, 0));
        return p;
    }

    private static void ring(Canvas c, float x, float y, float r, int color, float w) {
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(Noir.INK);
        c.drawCircle(x, y, r, f);
        c.drawCircle(x, y, r, stroke(color, w, false));
    }

    /**
     * A: Journey line. Home ··· North Point ━ (under the harbour) ━ Yau Tong ━ Kowloon Tong, with the times under the stops.
     * Drawn on a 980 × 250 design grid (the hero is 980 px wide on a 1920 px screen) and scaled to the view.
     */
    static final class Line extends View {
        private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG), tall = new Paint(Paint.ANTI_ALIAS_FLAG), sans = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Commute.Work w;
        private final SimpleDateFormat hm;

        Line(Context c, SimpleDateFormat hm) {
            super(c);
            this.hm = hm;
            mono.setTypeface(Noir.mono(c));
            mono.setLetterSpacing(0.25f);
            tall.setTypeface(Noir.tall(c));
            tall.setColor(Noir.TEXT);
            sans.setTypeface(Noir.sans(c));
        }

        void set(Commute.Work w) { this.w = w; invalidate(); }

        @Override protected void onMeasure(int ws, int hs) {
            int width = MeasureSpec.getSize(ws);
            setMeasuredDimension(width, Math.round(width * 250f / 980f));
        }

        @Override protected void onDraw(Canvas c) {
            if (w == null) return;
            float s = getWidth() / 980f;
            c.save();
            c.scale(s, s);
            Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
            band.setColor(0x1F789EBE);
            c.drawRoundRect(262, 104, 548, 120, 2, 2, band);             // the harbour, under the TKO Line
            c.drawLine(14, 98, 250, 98, stroke(0x73ECE4D8, 3, true));     // walk
            c.drawLine(250, 98, 960, 98, stroke(Noir.AMBER, 4, false));   // TKO Line + Kwun Tong Line
            ring(c, 14, 98, 8, GREY, 3);
            ring(c, 250, 98, 10, Noir.AMBER, 3);
            ring(c, 560, 98, 10, Noir.AMBER, 3);
            ring(c, 960, 98, 13, Noir.AMBER, 3);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Noir.AMBER);
            c.drawCircle(960, 98, 4, dot);

            text(c, mono, "HOME", 14, 60, 12, 0x8CECE4D8, Paint.Align.LEFT);
            text(c, mono, "NORTH POINT", 250, 60, 12, AMBER_TEXT, Paint.Align.CENTER);
            text(c, mono, "YAU TONG · CHANGE", 560, 60, 12, AMBER_TEXT, Paint.Align.CENTER);
            text(c, mono, "KOWLOON TONG", 966, 60, 12, AMBER_TEXT, Paint.Align.RIGHT);
            text(c, mono, "WALK " + Commute.WALK_TKL + " MIN", 132, 88, 10, 0x66ECE4D8, Paint.Align.CENTER);
            text(c, mono, "TKO LINE", 405, 88, 10, SEG, Paint.Align.CENTER);
            text(c, mono, "KWUN TONG LINE · 9 STOPS", 760, 88, 10, SEG, Paint.Align.CENTER);

            float base = 122 + 56 * 0.9855f;                                // Big Shoulders ascent
            String leave = hm.format(new Date(w.leave));
            text(c, tall, leave, 0, base, 56, Noir.TEXT, Paint.Align.LEFT);
            float lw = tall.measureText(leave);
            text(c, mono, "LEAVE", lw + 12, base, 12, 0x8CECE4D8, Paint.Align.LEFT);
            text(c, tall, hm.format(new Date(w.tko)), 250, base, 56, Noir.TEXT, Paint.Align.CENTER);
            // Yau Tong: arrive → depart, centred on the station
            String a = (w.yauTongEst ? "≈" : "") + hm.format(new Date(w.yauTong)), d = (w.ktlEst ? "≈" : "") + hm.format(new Date(w.ktl));
            tall.setTextSize(56);
            sans.setTextSize(30);
            float aw = tall.measureText(a), dw = tall.measureText(d), arrow = sans.measureText("→") + 20;
            float x0 = 560 - (aw + arrow + dw) / 2;
            text(c, tall, a, x0, base, 56, Noir.TEXT, Paint.Align.LEFT);
            text(c, sans, "→", x0 + aw + 10, base - 12, 30, 0x80ECE4D8, Paint.Align.LEFT);
            text(c, tall, d, x0 + aw + arrow, base, 56, Noir.TEXT, Paint.Align.LEFT);
            String arr = hm.format(new Date(w.arrive));
            text(c, tall, arr, 960, base, 56, Noir.TEXT, Paint.Align.RIGHT);
            text(c, sans, "≈ ", 960 - tall.measureText(arr) - 6, base - 6, 40, Noir.TEXT, Paint.Align.RIGHT);
            c.restore();
        }

        private static void text(Canvas c, Paint p, String s, float x, float y, float size, int color, Paint.Align a) {
            p.setTextSize(size);
            p.setColor(color);
            p.setTextAlign(a);
            c.drawText(s, x, y, p);
        }
    }

    /** E: a small piece of route drawn above each journey step (walk, TKO Line under the harbour, Kwun Tong Line). */
    static final class Glyph extends View {
        static final int WALK = 0, TKO = 1, KTL = 2;
        private final int kind;

        Glyph(Context c, int kind) { super(c); this.kind = kind; }

        @Override protected void onDraw(Canvas c) {
            float dw = kind == WALK ? 246 : kind == TKO ? 264 : 286;   // design width incl. the end ring
            float s = Math.min(getHeight() / 34f, getWidth() / dw);   // fit the column; never clip the end ring
            c.save();
            c.scale(s, s);
            if (kind == WALK) {
                c.drawLine(8, 17, 230, 17, stroke(0x80ECE4D8, 3, true));
                ring(c, 8, 17, 6, 0x99ECE4D8, 2.5f);
                ring(c, 236, 17, 7, Noir.AMBER, 2.5f);
            } else if (kind == TKO) {
                Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
                band.setColor(0x1A789EBE);
                c.drawRect(60, 4, 180, 30, band);
                Path p = new Path();
                p.moveTo(8, 26); p.lineTo(60, 26); p.cubicTo(100, 26, 120, 8, 180, 8); p.lineTo(250, 8);
                c.drawPath(p, stroke(Noir.AMBER, 3.5f, false));
                ring(c, 8, 26, 7, Noir.AMBER, 2.5f);
                ring(c, 254, 8, 6, Noir.AMBER, 2.5f);
            } else {
                c.drawLine(8, 17, 270, 17, stroke(Noir.AMBER, 3.5f, false));
                Paint t = stroke(0x8CF5B54A, 2, false);
                for (int i = 1; i <= 8; i++) c.drawLine(18 + i * 28, 12, 18 + i * 28, 22, t);
                ring(c, 8, 17, 6, Noir.AMBER, 2.5f);
                ring(c, 274, 17, 9, Noir.AMBER, 3);
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(Noir.AMBER);
                c.drawCircle(274, 17, 3, dot);
            }
            c.restore();
        }
    }

    static String name(String key) { return GLYPHS.equals(key) ? "Steps with route" : "Journey line"; }

    static String valid(String key) { return GLYPHS.equals(key) ? GLYPHS : LINE; }

    @SuppressWarnings("unused") private static final Locale L = Locale.US;
}
