// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "which app?" list, shared by the target picker and the custom button
 * mappings. Lists every app that declares either LEANBACK_LAUNCHER (TV apps) or
 * LAUNCHER (phone apps), sorted by label. The Amazon launcher and our own package
 * are excluded: the first would re-create the hijack loop, the second is nonsense.
 */
public final class AppPicker {

    private AppPicker() {}

    /** Result of the picker; only fired when the user actually chose an app. */
    public interface OnPicked {
        void onPicked(String pkg);
    }

    /**
     * Shows the picker on top of {@code host}. Returns the dialog so the caller can
     * hook dismissal (the target picker finishes its activity there), or null if the
     * device has no launchable app at all, in which case a toast has been shown.
     */
    public static AlertDialog show(Activity host, String title, final OnPicked cb) {
        final List<Item> items = collect(host);
        if (items.isEmpty()) {
            Toast.makeText(host, R.string.toast_no_launchable_apps, Toast.LENGTH_LONG).show();
            return null;
        }
        return new AlertDialog.Builder(host, R.style.AppDialogTheme)
                .setTitle(title)
                .setAdapter(new IconAdapter(host, items), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (cb != null) cb.onPicked(items.get(which).pkg);
                    }
                })
                .show();
    }

    /** All launchable apps, TV entries taking precedence over phone duplicates. */
    private static List<Item> collect(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectLaunchable(ctx, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, seen, items);
        collectLaunchable(ctx, pm, Intent.CATEGORY_LAUNCHER, seen, items);
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return items;
    }

    /**
     * Resolves all activities matching MAIN + category, appending unseen
     * packages to {@code items} along with their label and icon.
     */
    private static void collectLaunchable(Context ctx, PackageManager pm, String category,
                                          Set<String> seen, List<Item> items) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            if (pkg.equals("com.amazon.tv.launcher")) continue;
            if (!seen.add(pkg)) continue;

            CharSequence label = ri.loadLabel(pm);
            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception ignored) {
                // Some apps fail to load their icon; we just leave it null
                // and the row renders without an image.
            }
            items.add(new Item(pkg, label != null ? label.toString() : pkg, icon));
        }
    }

    /** Plain value holder for one row of the picker. */
    static class Item {
        final String pkg;
        final String label;
        final Drawable icon;

        Item(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    /**
     * Renders one picker row: app icon on the left, label and package name
     * stacked on the right. Built programmatically so we don't need an XML
     * layout file or AppCompat support.
     */
    private static class IconAdapter extends ArrayAdapter<Item> {

        private final Context ctx;

        IconAdapter(Context ctx, List<Item> data) {
            super(ctx, 0, data);
            this.ctx = ctx;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(24, 20, 24, 20);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView iconView = new ImageView(ctx);
            int iconSize = 96;
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLp.rightMargin = 28;
            iconView.setLayoutParams(iconLp);
            Item item = getItem(position);
            if (item.icon != null) {
                iconView.setImageDrawable(item.icon);
            }
            row.addView(iconView);

            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);

            TextView label = new TextView(ctx);
            label.setText(item.label);
            label.setTextSize(18);
            // Mirrors @color/text_white in res/values/colors.xml.
            label.setTextColor(0xFFFFFFFF);
            text.addView(label);

            TextView pkg = new TextView(ctx);
            pkg.setText(item.pkg);
            pkg.setTextSize(12);
            // Dim gray for the package-name subtitle; not part of the
            // named colors.xml palette.
            pkg.setTextColor(0xFFAAAAAA);
            text.addView(pkg);

            row.addView(text);
            return row;
        }
    }
}
