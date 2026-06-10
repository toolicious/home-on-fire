// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Opens the Fire OS system settings while bypassing Amazon's launcher.
 *
 * On Fire OS the generic {@link Settings#ACTION_SETTINGS} resolves to
 * the Amazon launcher's own settings page, which would re-trigger our
 * hijack. Specific sub-actions like {@link Settings#ACTION_DEVICE_INFO_SETTINGS}
 * however resolve directly to {@code com.amazon.tv.settings.v2} and
 * land the user inside the proper settings app, from where the side
 * navigation reaches every other section.
 */
public final class SettingsLauncher {

    private SettingsLauncher() {}

    /**
     * Opens the most appropriate settings sub-action, falling through
     * to a toast if no candidate resolves to anything other than the
     * Amazon launcher (which would only happen on a stripped device).
     */
    public static void open(Activity host) {
        Intent intent = findBestSettingsIntent(host);
        if (intent == null) {
            Toast.makeText(host, R.string.toast_settings_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            host.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(host, R.string.toast_settings_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static Intent findBestSettingsIntent(Activity host) {
        PackageManager pm = host.getPackageManager();
        // Ordered by approximate "rootness" of the page the user lands
        // on. From any of these the Fire OS settings app exposes a side
        // navigation that reaches every other settings section.
        String[] candidateActions = {
                Settings.ACTION_DEVICE_INFO_SETTINGS,
                Settings.ACTION_WIFI_SETTINGS,
                Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_DATE_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS,
        };
        for (String action : candidateActions) {
            Intent probe = new Intent(action);
            for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
                if (!"com.amazon.tv.launcher".equals(ri.activityInfo.packageName)) {
                    Intent direct = new Intent(action);
                    direct.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return direct;
                }
            }
        }
        return null;
    }
}
