// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Build and device identity, plus the one log line that carries it.
 *
 * Testers routinely share a raw {@code adb logcat -s HomeOnFire} dump
 * rather than a photo of {@link LogViewerActivity} (the marker lines
 * logcat prints at each buffer boundary give that away, since the log
 * viewer strips them). Such a dump used to say nothing about which
 * build produced it, so every report needed a follow-up question, and
 * a wrong guess sends debugging down the wrong path entirely.
 *
 * {@link #logIdentity} therefore writes the version and device into
 * the log itself, at the two moments that reliably precede a report:
 * when the accessibility service connects, and when verbose logging
 * is switched on (which a tester does right before reproducing). The
 * line is not gated on the verbose setting, otherwise the very act of
 * enabling it would race its own condition.
 */
@SuppressWarnings("deprecation") // PackageInfo.versionCode: int is enough for our range
final class AppInfo {

    private AppInfo() {
    }

    /** Version name as declared in the manifest, or "?" if unreadable. */
    static String versionName(Context ctx) {
        PackageInfo pi = packageInfo(ctx);
        return (pi == null || pi.versionName == null) ? "?" : pi.versionName;
    }

    /** Version code as declared in the manifest, or -1 if unreadable. */
    static int versionCode(Context ctx) {
        PackageInfo pi = packageInfo(ctx);
        return pi == null ? -1 : pi.versionCode;
    }

    private static PackageInfo packageInfo(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fire OS release as the device reports it (e.g. "8.1.8.0"), or "?"
     * on a build that exposes neither property.
     */
    static String fireOsVersion() {
        String v = getProp("ro.build.version.name");
        if (v.isEmpty()) v = getProp("ro.build.version.fireos");
        return v.isEmpty() ? "?" : v;
    }

    private static String getProp(String name) {
        return runCmd(new String[]{"getprop", name}).trim();
    }

    /**
     * Single-line summary of everything a bug report needs to be
     * actionable: our build, the Fire OS release, the Android API
     * level the code branches on, and the hardware.
     */
    static String identity(Context ctx) {
        return "Home on Fire " + versionName(ctx) + " (code " + versionCode(ctx) + ")"
                + " | Fire OS " + fireOsVersion()
                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " | " + Build.MODEL + " / " + Build.DEVICE;
    }

    /**
     * Writes {@link #identity} to the log under the shared HomeOnFire
     * tag, prefixed with what triggered it.
     */
    static void logIdentity(Context ctx, String tag, String reason) {
        Log.i(tag, reason + ": " + identity(ctx));
    }

    /**
     * Runs a short command and returns its combined output, capped so a
     * runaway process can't grow the string without bound. Any failure
     * (process blocked, SELinux denial on a locked build) yields "".
     */
    static String runCmd(String[] cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 600) {
                sb.append(line).append('\n');
                n++;
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
