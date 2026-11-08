package tv.galleryhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns an app's own TV banner into a clean white logo on transparent background
 * (the banner's background colour is keyed out), for the monochrome Glass Orbs dock.
 * Apps without a banner fall back to their icon in greyscale.
 */
final class Logos {
    private Logos() { }

    private static final Map<String, Bitmap> cache = new HashMap<>();

    static Drawable white(Resources res, AppRepo.App a) {
        if (a.logo != null) return a.logo.getConstantState() != null ? a.logo.getConstantState().newDrawable(res) : a.logo;
        Bitmap b = cache.get(a.pkg);
        if (b == null) {
            b = a.banner != null ? keyOut(a.banner) : null;
            if (b == null && a.icon != null) b = grey(a.icon);
            if (b == null) return null;
            cache.put(a.pkg, b);
        }
        return new BitmapDrawable(res, b);
    }

    static void clear() { cache.clear(); }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private static Bitmap keyOut(Drawable banner) {
        final int W = 320, H = 180, IN = 10;   // ignore rounded corners / borders
        Bitmap src = render(banner, W, H);
        int[] px = new int[W * H];
        src.getPixels(px, 0, W, 0, 0, W, H);
        // Background = average of the four inset corner patches
        long r = 0, g = 0, bl = 0, n = 0;
        int[][] corners = {{IN, IN}, {W - IN - 8, IN}, {IN, H - IN - 8}, {W - IN - 8, H - IN - 8}};
        for (int[] cc : corners) {
            for (int y = cc[1]; y < cc[1] + 8; y++) {
                for (int x = cc[0]; x < cc[0] + 8; x++) {
                    int p = px[y * W + x];
                    r += (p >> 16) & 255; g += (p >> 8) & 255; bl += p & 255; n++;
                }
            }
        }
        int br = (int) (r / n), bg = (int) (g / n), bb = (int) (bl / n);
        int minX = W, minY = H, maxX = -1, maxY = -1;
        int[] out = new int[W * H];
        for (int y = IN; y < H - IN; y++) {
            for (int x = IN; x < W - IN; x++) {
                int p = px[y * W + x];
                int dr = ((p >> 16) & 255) - br, dg = ((p >> 8) & 255) - bg, db = (p & 255) - bb;
                double d = Math.sqrt(dr * dr + dg * dg + db * db);
                int a = (int) Math.max(0, Math.min(255, (d - 40) * 255 / 120));
                if (a > 0) {
                    out[y * W + x] = (a << 24) | 0x00FFFFFF;
                    if (a > 40) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x;
                        if (y < minY) minY = y; if (y > maxY) maxY = y;
                    }
                }
            }
        }
        src.recycle();
        if (maxX < minX || maxY < minY) return null;
        Bitmap full = Bitmap.createBitmap(out, W, H, Bitmap.Config.ARGB_8888);
        Bitmap crop = Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1);
        if (crop != full) full.recycle();
        return crop;
    }

    private static Bitmap grey(Drawable icon) {
        Bitmap src = render(icon, 96, 96);
        Bitmap out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        src.recycle();
        return out;
    }
}
