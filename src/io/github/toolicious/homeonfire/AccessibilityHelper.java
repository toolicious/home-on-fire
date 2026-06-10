// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Helpers around Android's enabled_accessibility_services secure setting.
 *
 * Background:
 * Normally a user enables an accessibility service via
 * Settings → Accessibility. Some stripped Fire OS builds don't expose that
 * page (clicking it shows a blank screen), which makes activation by hand
 * impossible. As a workaround we can write the secure setting directly,
 * which requires the WRITE_SECURE_SETTINGS permission. That permission is
 * a signature-level permission, so a regular app cannot grant it to itself
 * via runtime prompts. But it can be granted once via adb:
 *
 *     adb shell pm grant io.github.toolicious.homeonfire android.permission.WRITE_SECURE_SETTINGS
 *
 * With the grant in place, our app can self-(re)enable its accessibility
 * service if it ever gets disabled. Without the grant the app simply tells
 * the user how to run that adb command.
 */
public class AccessibilityHelper {

    /** Short form used when writing the secure setting. */
    public static final String SERVICE_ID = "io.github.toolicious.homeonfire/.HijackService";

    /** Fully qualified form Android may store after normalization. */
    public static final String SERVICE_ID_FULL =
            "io.github.toolicious.homeonfire/io.github.toolicious.homeonfire.HijackService";

    private static final String PERM_WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS";

    /**
     * Returns true if our HijackService is listed in
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. Tolerates both the
     * short ("/.HijackService") and fully qualified component name forms.
     */
    public static boolean isOurServiceEnabled(Context ctx) {
        String value = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(value)) return false;
        for (String entry : value.split(":")) {
            if (SERVICE_ID.equals(entry) || SERVICE_ID_FULL.equals(entry)) return true;
        }
        return false;
    }

    /**
     * Appends our service to the secure setting (without disturbing any
     * already-enabled services) and switches the master accessibility flag
     * on. Requires WRITE_SECURE_SETTINGS; returns false if the write was
     * rejected (e.g. permission not granted).
     */
    public static boolean enableOurService(Context ctx) {
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated;
            if (TextUtils.isEmpty(current)) {
                updated = SERVICE_ID;
            } else if (current.contains(SERVICE_ID) || current.contains(SERVICE_ID_FULL)) {
                updated = current;
            } else {
                updated = current + ":" + SERVICE_ID;
            }
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** True if the WRITE_SECURE_SETTINGS permission has been granted (via adb). */
    public static boolean hasWriteSecureSettings(Context ctx) {
        return ctx.checkSelfPermission(PERM_WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Removes our service from the secure setting list while leaving
     * any other enabled accessibility services untouched. Requires
     * WRITE_SECURE_SETTINGS; returns false if the write was rejected.
     */
    public static boolean disableOurService(Context ctx) {
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(current)) return true;
            StringBuilder kept = new StringBuilder();
            for (String entry : current.split(":")) {
                if (SERVICE_ID.equals(entry) || SERVICE_ID_FULL.equals(entry)) continue;
                if (kept.length() > 0) kept.append(":");
                kept.append(entry);
            }
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept.toString());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
