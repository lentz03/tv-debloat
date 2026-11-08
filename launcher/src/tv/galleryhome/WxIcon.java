package tv.galleryhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Thin line weather / warning glyphs drawn in a 48x48 box (original artwork, no image files). */
final class WxIcon extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String kind;

    WxIcon(Context c, String kind, int color) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    void set(String kind, int color) { this.kind = kind; p.setColor(color); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        if (kind == null) return;
        float s = Math.min(getWidth(), getHeight()) / 48f;
        c.save();
        c.translate((getWidth() - 48 * s) / 2f, (getHeight() - 48 * s) / 2f);
        c.scale(s, s);
        boolean heavy = kind.equals("cyclone") || kind.equals("drops") || kind.equals("bolt") || kind.equals("thermo");
        p.setStrokeWidth(heavy ? 3.4f : 2.2f);
        switch (kind) {
            case "sun": sun(c, 24, 24, 8, 18); break;
            case "moon": moon(c); break;
            case "cloud": c.drawPath(cloud(0, 4), p); break;
            case "suncloud":
                c.drawArc(new RectF(8, 10, 26, 28), 160, 150, false, p);
                c.drawLine(17, 4, 17, 7, p); c.drawLine(7, 13, 10, 14, p); c.drawLine(26, 8, 24, 10, p);
                c.drawPath(cloud(3, 7), p);
                break;
            case "rain":
                c.drawPath(cloud(0, -2), p);
                c.drawLine(17, 37, 15, 42, p); c.drawLine(25, 37, 23, 42, p); c.drawLine(33, 37, 31, 42, p);
                break;
            case "thunder":
                c.drawPath(cloud(0, -2), p);
                Path b = new Path();
                b.moveTo(26, 33); b.lineTo(21, 40); b.lineTo(26, 40); b.lineTo(23, 46);
                c.drawPath(b, p);
                break;
            case "mist":
                c.drawLine(8, 18, 40, 18, p); c.drawLine(12, 26, 36, 26, p); c.drawLine(8, 34, 40, 34, p);
                break;
            case "cyclone":
                c.drawCircle(24, 24, 5, p);
                Path a1 = new Path(); a1.moveTo(29, 24); a1.quadTo(29, 9, 14, 8); c.drawPath(a1, p);
                Path a2 = new Path(); a2.moveTo(19, 24); a2.quadTo(19, 39, 34, 40); c.drawPath(a2, p);
                break;
            case "drops":
                c.drawLine(14, 8, 9, 20, p); c.drawLine(26, 8, 21, 20, p); c.drawLine(38, 8, 33, 20, p);
                c.drawLine(20, 28, 15, 40, p); c.drawLine(32, 28, 27, 40, p);
                break;
            case "bolt":
                Path z = new Path();
                z.moveTo(27, 5); z.lineTo(12, 27); z.lineTo(23, 27); z.lineTo(20, 43); z.lineTo(36, 20); z.lineTo(25, 20); z.close();
                c.drawPath(z, p);
                break;
            case "thermo":
                c.drawRoundRect(new RectF(20, 6, 28, 32), 4, 4, p);
                c.drawCircle(24, 35, 7, p);
                break;
            default: break;
        }
        c.restore();
    }

    private void sun(Canvas c, float cx, float cy, float r, float ray) {
        c.drawCircle(cx, cy, r, p);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x1 = cx + (float) Math.cos(a) * (r + 4), y1 = cy + (float) Math.sin(a) * (r + 4);
            float x2 = cx + (float) Math.cos(a) * ray, y2 = cy + (float) Math.sin(a) * ray;
            c.drawLine(x1, y1, x2, y2, p);
        }
    }

    private void moon(Canvas c) {
        Path m = new Path();
        m.moveTo(30, 8);
        m.cubicTo(18, 9, 11, 21, 15, 31);
        m.cubicTo(19, 41, 33, 44, 40, 34);
        m.cubicTo(30, 35, 22, 27, 23, 18);
        m.cubicTo(24, 13, 27, 10, 30, 8);
        c.drawPath(m, p);
    }

    /** Cloud outline, offset by dx/dy inside the 48 box. */
    private static Path cloud(float dx, float dy) {
        Path q = new Path();
        q.moveTo(14, 32);
        q.lineTo(35, 32);
        q.cubicTo(40, 32, 43, 28, 43, 24);
        q.cubicTo(43, 19, 39, 16, 35, 16.5f);
        q.cubicTo(33.5f, 10.5f, 28.5f, 7, 23, 7.5f);
        q.cubicTo(17.5f, 8, 13.5f, 12, 13, 17);
        q.cubicTo(8, 17, 5, 21, 5, 24.5f);
        q.cubicTo(5, 28.5f, 9, 32, 14, 32);
        q.close();
        q.offset(dx, dy);
        return q;
    }
}
