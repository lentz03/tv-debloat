package tv.galleryhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists launchable apps (TV apps first, phone-style apps as a fallback). */
final class AppRepo {

    static final class App {
        String pkg;
        String label;
        Drawable banner; // may be null
        Drawable icon;
        Intent launch;
        Drawable logo;     // a ready-made white logo (built-in screens); apps get theirs keyed out of the banner
        boolean internal;  // a Gallery Home screen, not an installed app
    }

    static List<App> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        addAll(ctx, pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        addAll(ctx, pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        List<App> out = new ArrayList<>(byPkg.values());
        DockExtras.addTo(ctx, out);
        final Collator col = Collator.getInstance();
        Collections.sort(out, new Comparator<App>() {
            @Override public int compare(App a, App b) { return col.compare(a.label, b.label); }
        });
        return out;
    }

    private static void addAll(Context ctx, PackageManager pm, Map<String, App> byPkg,
                               String category, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            if (ai.packageName.equals(ctx.getPackageName())) continue;
            if (byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            CharSequence l = ri.loadLabel(pm);
            a.label = l == null ? ai.packageName : l.toString();
            try {
                a.banner = ai.loadBanner(pm);
                if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) { }
            try {
                a.icon = ri.loadIcon(pm);
            } catch (RuntimeException ignored) { }
            a.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (!leanback && a.banner == null && a.icon == null) continue;
            byPkg.put(a.pkg, a);
        }
    }
}
