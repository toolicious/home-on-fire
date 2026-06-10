// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Receives BOOT_COMPLETED so we can launch the user's chosen target app
 * right after device boot. This replaces the need for a separate
 * Launch-on-Boot helper app.
 *
 * The action is only taken when the user has explicitly enabled the
 * "launch on boot" option AND our accessibility service is enabled
 * (the config UI greys launch-on-boot out as a dependency of the
 * service, so the two must agree); otherwise we do nothing and the
 * standard Amazon boot flow proceeds.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }

        Prefs prefs = new Prefs(ctx);
        if (!prefs.getLaunchOnBoot()) return;
        // Honour the dependency the UI advertises: with the accessibility
        // service off, the greyed-out boot switch should be truthful and
        // no boot launch happens.
        if (!AccessibilityHelper.isOurServiceEnabled(ctx)) return;

        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) return;

        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(target);
        if (launch == null) launch = pm.getLaunchIntentForPackage(target);
        if (launch == null) {
            Log.w(HijackService.TAG, "Boot: target not launchable: " + target);
            return;
        }
        // Mirror HijackService.launchTarget: if the target declares a HOME
        // activity, add CATEGORY_HOME so a Back press right after boot stops
        // in the target instead of falling through to the Amazon launcher.
        // addCategory must precede setFlags (setFlags replaces flags).
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_HOME);
        probe.setPackage(target);
        if (!pm.queryIntentActivities(probe, 0).isEmpty()) {
            launch.addCategory(Intent.CATEGORY_HOME);
        }
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(launch);
            Log.i(HijackService.TAG, "Boot: launched " + target);
        } catch (Exception e) {
            Log.e(HijackService.TAG, "Boot: failed to launch " + target, e);
        }
    }
}
