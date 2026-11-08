package tv.galleryhome;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Builds the "Forecast Ticks" weather column and HKO warning badges (design 4). */
final class WeatherViews {
    private WeatherViews() { }

    private static int dp(Context c, float v) { return Ui.dp(c, v); }

    private static String deg(int t) { return t == Integer.MIN_VALUE ? "–" : t + "°"; }

    /** Home screen: NOW + 3 days in 46dp columns on a hairline, warning badges underneath. */
    static void fillFull(LinearLayout box, Weather.Data d, boolean alignEnd) {
        Context c = box.getContext();
        box.removeAllViews();
        if (d == null || !d.ok()) { box.setVisibility(View.GONE); return; }
        box.setVisibility(View.VISIBLE);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(alignEnd ? Gravity.END : Gravity.START);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(new Noir.TopLine(Noir.text(0.15f), Math.max(1, dp(c, 0.6f))));
        row.addView(column(c, "NOW", d.icon, d.temp, true));
        for (Weather.Day day : d.days) row.addView(column(c, day.label, day.icon, day.temp, false));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = dp(c, 13);
        box.addView(row, rl);

        LinearLayout badges = badgeRow(c, d, alignEnd);
        if (badges != null) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = dp(c, 11);
            box.addView(badges, bl);
        }
    }

    private static View column(Context c, String label, String icon, int temp, boolean now) {
        FrameLayout cell = new FrameLayout(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(c, 7), 0, 0);
        TextView l = Noir.label(c, Noir.mono(c), 6, 0.22f, 1f);
        l.setText(label);
        l.setGravity(Gravity.CENTER);
        col.addView(l, new LinearLayout.LayoutParams(-1, -2));
        WxIcon ic = new WxIcon(c, icon, Noir.TEXT);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(c, 15), dp(c, 15));
        il.topMargin = dp(c, 5);
        il.bottomMargin = dp(c, 4);
        col.addView(ic, il);
        TextView t = Noir.label(c, Noir.sans(c), 10, 0.02f, 1f);
        t.setText(deg(temp));
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        cell.addView(col, new FrameLayout.LayoutParams(dp(c, 46), -2));
        if (now) {
            View bar = new View(c);
            bar.setBackground(new ColorDrawable(Noir.AMBER));
            cell.addView(bar, new FrameLayout.LayoutParams(dp(c, 28), Math.max(2, dp(c, 1f)), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        } else {
            cell.setAlpha(0.6f);
        }
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 46), -2));
        return cell;
    }

    /** Wallpaper mode / screensaver: one compact line (icon NOW 26° · FRI 27° …) plus badges. */
    static void fillCompact(LinearLayout box, Weather.Data d) {
        Context c = box.getContext();
        box.removeAllViews();
        if (d == null || !d.ok()) { box.setVisibility(View.GONE); return; }
        box.setVisibility(View.VISIBLE);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(item(c, d.icon, "NOW " + deg(d.temp), 0.95f));
        for (Weather.Day day : d.days) row.addView(item(c, day.icon, day.label + " " + deg(day.temp), 0.6f));
        box.addView(row, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout badges = badgeRow(c, d, false);
        if (badges != null) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = dp(c, 8);
            box.addView(badges, bl);
        }
    }

    private static View item(Context c, String icon, String text, float alpha) {
        LinearLayout it = new LinearLayout(c);
        it.setOrientation(LinearLayout.HORIZONTAL);
        it.setGravity(Gravity.CENTER_VERTICAL);
        it.setAlpha(alpha);
        it.addView(new WxIcon(c, icon, Noir.TEXT), new LinearLayout.LayoutParams(dp(c, 9), dp(c, 9)));
        TextView t = Noir.label(c, Noir.mono(c), 6.5f, 0.22f, 1f);
        t.setText(text);
        t.setPadding(dp(c, 4), 0, dp(c, 13), 0);
        it.addView(t);
        return it;
    }

    /** Up to three badges, serious ones first; any more become "+n". Null when nothing is in force. */
    private static LinearLayout badgeRow(Context c, Weather.Data d, boolean alignEnd) {
        if (d.warns.isEmpty()) return null;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(alignEnd ? Gravity.END : Gravity.START);
        int shown = Math.min(3, d.warns.size());
        for (int i = 0; i < shown; i++) {
            Weather.Warn w = d.warns.get(i);
            row.addView(badge(c, w.icon, w.text, w.severe), margin(c, i));
        }
        if (d.warns.size() > shown) row.addView(badge(c, null, "+" + (d.warns.size() - shown), false), margin(c, shown));
        return row;
    }

    private static LinearLayout.LayoutParams margin(Context c, int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 15));
        lp.leftMargin = i == 0 ? 0 : dp(c, 5);
        return lp;
    }

    static View badge(Context c, String icon, String text, boolean severe) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 5.5f), 0, dp(c, 5.5f), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 1.5f));
        g.setColor(severe ? Noir.AMBER : 0x0FF5B54A);
        g.setStroke(Math.max(1, dp(c, 0.5f)), severe ? Noir.AMBER : 0xD9F5B54A);
        b.setBackground(g);
        int fg = severe ? 0xFF120C04 : Noir.AMBER;
        if (icon != null) {
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(c, 7.5f), dp(c, 7.5f));
            il.rightMargin = dp(c, 4);
            b.addView(new WxIcon(c, icon, fg), il);
        }
        TextView t = Noir.label(c, Noir.mono(c), 6, 0.16f, 1f);
        t.setText(text.toUpperCase(Locale.US));
        t.setTextColor(fg);
        t.setShadowLayer(0, 0, 0, 0);
        b.addView(t);
        return b;
    }
}
