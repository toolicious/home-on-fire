// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.toolicious.homeonfire;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Builds the launch intent for the configured target, honouring an explicit activity. */
final class TargetIntents {
    private TargetIntents() {}

    /** Explicit component if one is configured and still resolvable, else the usual launcher lookup. */
    static Intent build(PackageManager pm, Prefs prefs, String pkg) {
        String cls = prefs.getTargetActivity();
        if (cls != null && !cls.isEmpty()) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(pkg, cls));
            if (pm.resolveActivity(i, 0) != null) return i;
        }
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }
}
