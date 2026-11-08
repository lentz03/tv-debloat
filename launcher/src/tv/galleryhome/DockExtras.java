package tv.galleryhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;

import java.io.InputStream;
import java.util.List;

/**
 * Screens built into Gallery Home that sit in the dock like apps (the COMMUTE board).
 * They carry a hand-made white logo (assets/logos) so they match the other wordmarks on the line.
 */
final class DockExtras {
    private DockExtras() { }

    static final String COMMUTE = "gh:commute";

    static void addTo(Context c, List<AppRepo.App> out) {
        AppRepo.App a = new AppRepo.App();
        a.pkg = COMMUTE;
        a.label = "Commute";
        a.internal = true;
        a.launch = new Intent(c, CommuteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bitmap logo = asset(c, "logos/commute.png");
        if (logo != null) {
            a.logo = new BitmapDrawable(c.getResources(), logo);
            a.banner = new BitmapDrawable(c.getResources(), banner(logo, 0xFF15110B));
        }
        out.add(a);
    }

    private static Bitmap asset(Context c, String path) {
        InputStream in = null;
        try {
            in = c.getAssets().open(path);
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** A 16:9 tile for the APPS grid: the logo centred on a dark tile with an amber underline. */
    private static Bitmap banner(Bitmap logo, int color) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(color);
        float s = Math.min(w * 0.6f / logo.getWidth(), h * 0.26f / logo.getHeight());
        int lw = Math.round(logo.getWidth() * s), lh = Math.round(logo.getHeight() * s);
        Rect dst = new Rect((w - lw) / 2, (h - lh) / 2, (w + lw) / 2, (h + lh) / 2);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(logo, null, dst, p);
        p.setColor(Noir.AMBER);
        cv.drawRect(dst.left, dst.bottom + 22, dst.left + lw * 0.22f, dst.bottom + 26, p);
        return b;
    }
}
