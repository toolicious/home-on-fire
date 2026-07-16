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

    // Instant, delay-free launcher shortcuts that do NOT go through the Home
    // button (so the Fire OS 7 app-switch lock is never armed): a redirect off
    // the Amazon Apps-grid window, and a user-assigned remote keycode.
    private static final String KEY_APPS_REDIRECT = "redirect_apps_button";
    private static final String KEY_LAUNCH_KEY_ENABLED = "launch_key_enabled";
    private static final String KEY_LAUNCH_KEYCODE = "launch_keycode";
    private static final String KEY_AMAZON_KEYCODE = "amazon_keycode";

    /** No custom launch key assigned. */
    public static final int DEFAULT_LAUNCH_KEYCODE = -1;

    // Beta overlay-timing tuning (milliseconds). Read live by HijackService on
    // each redirect, written by the tuning rows in MainActivity. Only effective
    // on Fire OS 6/7, where the masking overlay actually runs.
    private static final String KEY_MASK_GRACE = "mask_grace_ms";
    private static final String KEY_MASK_HOLD = "mask_hold_ms";
    private static final String KEY_MASK_FADE = "mask_fade_ms";

    public static final int DEFAULT_MASK_GRACE = 0;
    public static final int DEFAULT_MASK_HOLD = 700;
    public static final int DEFAULT_MASK_FADE = 100;

    /** What the Fire OS 7 masking overlay shows: the loading animation, or a plain black screen. */
    private static final String KEY_MASK_BLACK = "mask_black_screen";

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

    /**
     * When true, pressing the remote's Apps button opens the target
     * launcher instead of Amazon's Apps grid. The button press itself is
     * invisible to us, so this is driven off the Apps-grid window
     * appearing (see HijackService.handleAppsGridArrival). Off by default.
     */
    public boolean isAppsButtonRedirect() {
        return sp.getBoolean(KEY_APPS_REDIRECT, false);
    }

    public void setAppsButtonRedirect(boolean enabled) {
        sp.edit().putBoolean(KEY_APPS_REDIRECT, enabled).apply();
    }

    /**
     * Master toggle for the custom launch key. Kept separate from the
     * assigned keycode so the user can switch the feature off without
     * losing which button they picked. The key only fires when this is
     * true AND a keycode is assigned. Off by default.
     */
    public boolean isLaunchKeyEnabled() {
        return sp.getBoolean(KEY_LAUNCH_KEY_ENABLED, false);
    }

    public void setLaunchKeyEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_LAUNCH_KEY_ENABLED, enabled).apply();
    }

    /**
     * Keycode of a remote button the user assigned to open the target
     * launcher directly (no Home-button detour, so no Fire OS 7 delay).
     * {@link #DEFAULT_LAUNCH_KEYCODE} (-1) means none assigned. Gated by
     * {@link #isLaunchKeyEnabled()}.
     */
    public int getLaunchKeycode() {
        return sp.getInt(KEY_LAUNCH_KEYCODE, DEFAULT_LAUNCH_KEYCODE);
    }

    public void setLaunchKeycode(int keyCode) {
        sp.edit().putInt(KEY_LAUNCH_KEYCODE, keyCode).apply();
    }

    /**
     * Keycode of a remote button the user assigned to open the Amazon home
     * screen (an escape from the redirect back to the stock launcher). -1 means
     * none. Also gated by {@link #isLaunchKeyEnabled()}.
     */
    public int getAmazonKeycode() {
        return sp.getInt(KEY_AMAZON_KEYCODE, DEFAULT_LAUNCH_KEYCODE);
    }

    public void setAmazonKeycode(int keyCode) {
        sp.edit().putInt(KEY_AMAZON_KEYCODE, keyCode).apply();
    }

    /** Start-cover grace before the masking overlay is shown (ms). */
    public int getMaskGraceMs() {
        return clamp(sp.getInt(KEY_MASK_GRACE, DEFAULT_MASK_GRACE), 0, 1000);
    }

    public void setMaskGraceMs(int ms) {
        sp.edit().putInt(KEY_MASK_GRACE, ms).apply();
    }

    /** Opaque hold after the target window appears, before the overlay fades (ms). */
    public int getMaskHoldMs() {
        return clamp(sp.getInt(KEY_MASK_HOLD, DEFAULT_MASK_HOLD), 0, 3000);
    }

    public void setMaskHoldMs(int ms) {
        sp.edit().putInt(KEY_MASK_HOLD, ms).apply();
    }

    /** Cross-fade duration when the overlay lifts (ms). */
    public int getMaskFadeMs() {
        return clamp(sp.getInt(KEY_MASK_FADE, DEFAULT_MASK_FADE), 0, 1000);
    }

    public void setMaskFadeMs(int ms) {
        sp.edit().putInt(KEY_MASK_FADE, ms).apply();
    }

    /**
     * When true, the Fire OS 7 masking overlay is a plain black screen with a loading
     * message instead of the loading animation. Off (animation) by default. Only has an
     * effect on Fire OS 6/7, where the overlay actually runs.
     */
    public boolean isMaskBlackScreen() {
        return sp.getBoolean(KEY_MASK_BLACK, false);
    }

    public void setMaskBlackScreen(boolean black) {
        sp.edit().putBoolean(KEY_MASK_BLACK, black).apply();
    }

    /** Restores all three overlay-timing values to their defaults. */
    public void resetMaskTuning() {
        sp.edit()
                .putInt(KEY_MASK_GRACE, DEFAULT_MASK_GRACE)
                .putInt(KEY_MASK_HOLD, DEFAULT_MASK_HOLD)
                .putInt(KEY_MASK_FADE, DEFAULT_MASK_FADE)
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
