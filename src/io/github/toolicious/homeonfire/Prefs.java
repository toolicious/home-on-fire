// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Thin wrapper around SharedPreferences holding the user choices:
 *  - target package: which app to launch in place of the Amazon launcher
 *  - hijack enabled: master toggle for the Home-button redirect
 *  - launch on boot: start the target app once at device boot
 *  - menu long-press: open our config screen on a held Menu key
 *  - verbose logging: extra logcat output for debugging the hijack
 */
public class Prefs {

    private static final String FILE = "home_on_fire";

    private static final String KEY_TARGET = "target_package";
    private static final String KEY_BOOT = "launch_on_boot";
    private static final String KEY_ENABLED = "hijack_enabled";
    private static final String KEY_VERBOSE = "verbose_logging";
    private static final String KEY_MENU_LP = "menu_longpress_launch";

    /**
     * Empty by default; the user is expected to pick a target via the
     * configuration screen before anything is launched. The hijack,
     * boot launcher and "Open target" button all early-return on an
     * empty target package.
     */
    private static final String DEFAULT_TARGET = "";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getTargetPackage() {
        return sp.getString(KEY_TARGET, DEFAULT_TARGET);
    }

    public void setTargetPackage(String pkg) {
        sp.edit().putString(KEY_TARGET, pkg).apply();
    }

    /** Defaults to true so a fresh install starts the target on next boot. */
    public boolean getLaunchOnBoot() {
        return sp.getBoolean(KEY_BOOT, true);
    }

    public void setLaunchOnBoot(boolean enabled) {
        sp.edit().putBoolean(KEY_BOOT, enabled).apply();
    }

    /** Defaults to true so the redirect works right after install. */
    public boolean isHijackEnabled() {
        return sp.getBoolean(KEY_ENABLED, true);
    }

    public void setHijackEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * When true, the hijack service logs every key event and every
     * window-state event it receives. Useful for diagnosing why the
     * hijack didn't fire in a particular situation. Off by default to
     * keep production logs clean.
     */
    public boolean isVerboseLogging() {
        return sp.getBoolean(KEY_VERBOSE, false);
    }

    public void setVerboseLogging(boolean enabled) {
        sp.edit().putBoolean(KEY_VERBOSE, enabled).apply();
    }

    /**
     * When true, a long press of the remote's Menu key opens this
     * configuration app. Useful as a "from anywhere" shortcut into
     * Home on Fire without having to navigate to its tile.
     */
    public boolean isMenuLongPressLaunch() {
        return sp.getBoolean(KEY_MENU_LP, true);
    }

    public void setMenuLongPressLaunch(boolean enabled) {
        sp.edit().putBoolean(KEY_MENU_LP, enabled).apply();
    }
}
