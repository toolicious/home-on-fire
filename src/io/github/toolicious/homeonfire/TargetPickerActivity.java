// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Dialog activity that lets the user pick which installed app should
 * become the target of the Home-button redirect. The list itself lives in
 * {@link AppPicker}, which the custom button mappings use as well; this
 * activity only stores the result and closes.
 */
public class TargetPickerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AlertDialog dialog = AppPicker.show(this, getString(R.string.picker_title),
                new AppPicker.OnPicked() {
                    @Override
                    public void onPicked(String pkg) {
                        new Prefs(TargetPickerActivity.this).setTargetPackage(pkg);
                        pickActivity(pkg);
                    }
                });
        if (dialog == null) { // nothing launchable on this device; the picker showed a toast
            finish();
            return;
        }
        // Covers both paths: picking an app dismisses the dialog too, so this is the
        // single place the activity goes away.
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (!choosingActivity) finish();
            }
        });
    }

    private boolean choosingActivity;

    /** Second step: let the user pin a specific activity (e.g. a launcher alias) or keep automatic. */
    private void pickActivity(String pkg) {
        final Prefs prefs = new Prefs(this);
        ActivityInfo[] acts;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            acts = pi.activities;
        } catch (Exception e) {
            acts = null;
        }
        if (acts == null) { prefs.setTargetActivity(""); return; }
        final java.util.List<String> names = new java.util.ArrayList<String>();
        for (ActivityInfo a : acts) if (a.exported) names.add(a.name);
        if (names.isEmpty()) { prefs.setTargetActivity(""); return; }
        String[] items = new String[names.size() + 1];
        items[0] = "Automatic (default launcher)";
        for (int i = 0; i < names.size(); i++) items[i + 1] = names.get(i);
        choosingActivity = true;
        new AlertDialog.Builder(this)
                .setTitle("Launch which activity?")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.setTargetActivity(which == 0 ? "" : names.get(which - 1));
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) { finish(); }
                })
                .show();
    }
}
