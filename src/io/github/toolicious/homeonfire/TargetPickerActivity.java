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
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialog activity that lets the user pick which installed app should
 * become the target of the Home-button redirect. Lists every app that
 * declares either LEANBACK_LAUNCHER (TV apps) or LAUNCHER (phone apps),
 * sorted by label. The Amazon launcher and our own package are excluded
 * because they would either re-create the hijack loop or be nonsensical
 * choices.
 *
 * The activity is shown as a translucent dialog (theme set in the
 * manifest); the underlying MainActivity stays visible behind it.
 */
public class TargetPickerActivity extends Activity {

    private final List<Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PackageManager pm = getPackageManager();

        // LEANBACK first so TV-style entries take precedence over phone-style
        // duplicates of the same package.
        Set<String> seen = new HashSet<>();
        collectLaunchable(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, seen);
        collectLaunchable(pm, Intent.CATEGORY_LAUNCHER, seen);

        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        if (items.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.toast_no_launchable_apps,
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ArrayAdapter<Item> adapter = new IconAdapter(this, items);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.picker_title)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Prefs(TargetPickerActivity.this).setTargetPackage(items.get(which).pkg);
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    /**
     * Resolves all activities matching MAIN + category, appending unseen
     * packages to {@link #items} along with their label and icon.
     */
    private void collectLaunchable(PackageManager pm, String category, Set<String> seen) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
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
            // Dim grey for the package-name subtitle; not part of the
            // named colors.xml palette.
            pkg.setTextColor(0xFFAAAAAA);
            text.addView(pkg);

            row.addView(text);
            return row;
        }
    }
}
