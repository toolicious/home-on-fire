// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
                finish();
            }
        });
    }
}
