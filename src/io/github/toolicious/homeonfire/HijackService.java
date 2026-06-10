// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Accessibility service that redirects the Fire TV's Home button to
 * the user's chosen target launcher.
 *
 * Why an accessibility service: Fire OS 8 intercepts KEYCODE_HOME
 * inside FireTVKeyPolicyManager (Android's PhoneWindowManager) before
 * any user-space app sees it, and the amazon.intent.action.HOME_PRESSED
 * broadcast is signature-gated. The only observable side-effect we
 * can hook is "the Amazon launcher's home activity has become
 * foreground"; from that we launch the target on top so Amazon's home
 * only flashes for ~250 ms.
 *
 * Hijack triggers (each described in its handler's javadoc):
 *  - {@link #handleAmazonHomeArrival}: a fresh HomeActivity_vNext
 *    foreground transition → launch target. Suppressed for Amazon-
 *    area sub-page returns, the long-press bypass window, and
 *    detected double-presses.
 *  - {@link #handleQuickSettingsLongPress}: the Quick-Settings panel
 *    appearing is Fire OS's only observable signal for a long-press
 *    of Home. From target → escape to Amazon home; from Amazon → open
 *    target; from third-party apps → do nothing (they may use that
 *    gesture for their own menus).
 *  - {@link #tryBackOrCenterHijack}: Back/OK on the Amazon-home
 *    Home tab → launch target. Reaches us because Fire OS does NOT
 *    intercept Back/OK at firmware level.
 *  - {@link #maybeInferHomePress}: focus jumps to the Home tab on
 *    the Amazon launcher → fire hijack (catches the case where the
 *    user pressed Home from elsewhere on Amazon home; the Home key
 *    itself is invisible to us but its side-effect on focus is not).
 *
 * Escape paths (so the user CAN reach the real Amazon home):
 *  - Long-press Home in the target opens Amazon home with a
 *    bypass window ({@link #LONGPRESS_REDIRECT_BYPASS_MS}) during
 *    which the auto-hijack is suspended.
 *  - Double-press Home: a second Amazon-home arrival within
 *    {@link #HIJACK_DEBOUNCE_MS} of the first hijack (with no Back
 *    keypress in between) is bypassed. The Back-since-hijack check
 *    keeps back-induced exits firing the hijack normally.
 *  - {@link #requestBypass}: MainActivity can call this before
 *    firing an intent that might briefly transit through the
 *    Amazon launcher.
 *
 * Activity-class filter: only HomeActivity_vNext is hijacked. Other
 * launcher-package activities (settings sub-screens, etc.) pass
 * through so D-pad navigation inside Amazon's UI keeps working.
 */
// AccessibilityNodeInfo.recycle() was deprecated in API 33 in favour
// of an auto-managed lifecycle. We still target API levels where the
// explicit recycle is the documented contract and the auto-managed
// path is unavailable, so the calls stay. The warnings are
// suppressed class-wide.
@SuppressWarnings("deprecation")
public class HijackService extends AccessibilityService {

    static final String TAG = "HomeOnFire";

    /** Package whose foreground appearance we want to intercept. */
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";

    /**
     * The specific Activity in the launcher package that represents the
     * actual home screen. Other activities in the package (such as its
     * settings page) should not be hijacked.
     */
    private static final String AMAZON_LAUNCHER_HOME_ACTIVITY =
            "com.amazon.tv.launcher.ui.HomeActivity_vNext";

    /**
     * Fire OS shows this overlay panel when the user holds the Home
     * button on the remote. Detecting its appearance is the only way
     * we can react to a long-press of Home. The key event itself is
     * intercepted by the system before reaching any service.
     */
    private static final String QUICKSETTINGS_PKG = "com.amazon.tv.quicksettings.ui";

    /** How long we suppress our own hijack after a long-press redirect. */
    private static final long LONGPRESS_REDIRECT_BYPASS_MS = 5_000L;

    /** Threshold above which we treat a Menu key as a long press. */
    private static final long MENU_LONG_PRESS_THRESHOLD_MS = 500L;

    /** Handler on the main looper, used to schedule Menu long-press fires. */
    private android.os.Handler mainHandler;

    /**
     * Single Prefs instance for the lifetime of the service. The
     * SharedPreferences object inside is itself singleton-cached by
     * the framework, so this just spares us repeatedly wrapping it.
     * Initialised in {@link #onServiceConnected}.
     */
    private Prefs prefs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        // A fresh service instance must not inherit a suppression window
        // left in the static bypassUntil by a previous instance. The
        // instance timing fields (lastHijackAt, lastKeyTime, lastKeyCode)
        // reset implicitly; bypassUntil is static (so requestBypass can
        // stay a static entry point) and therefore needs an explicit clear.
        bypassUntil = 0L;
        consumedDownKey = -1;
        // Seed foreground tracking from whatever is on screen now so a
        // long-press immediately after service start (e.g. right after
        // an install or accessibility toggle) has a valid prev to work
        // with, instead of leaving both trackers as null and falling
        // through every match in the QS long-press handler.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                CharSequence pkg = root.getPackageName();
                if (pkg != null) {
                    currentForegroundPkg = pkg.toString();
                }
            } finally {
                root.recycle();
            }
        }
        registerScreenOnReceiver();
    }

    /** Set while a screen-on receiver is registered; cleared on unbind. */
    private android.content.BroadcastReceiver screenOnReceiver;

    /**
     * On wake from standby, Fire OS often surfaces Amazon's home
     * activity instead of restoring whatever the user had foreground
     * before. The WSE-based auto-hijack does not always fire here
     * (state may be preserved silently, or the prev tracker has gone
     * stale during standby), so we hook ACTION_SCREEN_ON explicitly
     * and re-launch the target if Amazon home is what shows up.
     *
     * ACTION_SCREEN_ON can only be registered dynamically (manifest
     * registration is silently ignored on Android 7+).
     */
    private void registerScreenOnReceiver() {
        if (screenOnReceiver != null) return;
        screenOnReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context,
                                  android.content.Intent intent) {
                handleWakeFromStandby();
            }
        };
        registerReceiver(screenOnReceiver,
                new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /**
     * Brings the target back to foreground if the device woke into
     * Amazon home. Gated by the launch-on-boot preference (same
     * conceptual surface: device coming alive). Delays the check
     * briefly so Fire OS's wake animation has time to settle and the
     * post-wake activity is actually visible to {@link #isCurrentlyOnAmazonLauncher}.
     */
    private void handleWakeFromStandby() {
        if (!prefs.isHijackEnabled()) return;
        if (!prefs.getLaunchOnBoot()) return;
        final String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) return;
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Honor an active long-press / external bypass window so a
                // deliberate escape to Amazon home isn't undone if the
                // screen cycles off and on within that window. launchTarget
                // arms lastHijackAt itself, so a racing WSE / focus event
                // is debounced.
                if (System.currentTimeMillis() < bypassUntil) return;
                if (isCurrentlyOnAmazonLauncher()) {
                    launchTarget(target, "Wake from standby, Amazon home foreground");
                }
            }
        }, WAKE_LAUNCH_DELAY_MS);
    }

    /** Delay between SCREEN_ON and our wake-foreground check. */
    private static final long WAKE_LAUNCH_DELAY_MS = 500L;

    /**
     * Pending Menu-long-press fire, or {@code null} if no Menu key is
     * currently held. Scheduled on ACTION_DOWN of the Menu key, ran
     * after {@link #MENU_LONG_PRESS_THRESHOLD_MS} unless cancelled by
     * an earlier ACTION_UP. Firing during the press (rather than on
     * release) makes the shortcut feel immediate.
     */
    private Runnable pendingMenuLongPress = null;

    /**
     * Set when the Menu long-press shortcut has actually committed
     * (openSelf fired). While true, {@link #onKeyEvent} swallows the
     * remaining events of that same Menu press (repeat DOWNs and the
     * trailing UP) so the launcher underneath does not also act on the
     * Menu key. Reset when the press ends or a new one starts.
     */
    private boolean menuLongPressFired = false;

    /**
     * Wall-clock window after a hijack during which a follow-up
     * Amazon-home arrival is treated as a deliberate double-press
     * escape: provided no Back keypress occurred in between. The
     * Back-since-hijack check lets back-induced exits (which are
     * NOT a double-press) still trigger the auto-hijack inside this
     * same window.
     *
     * Picked to be tight enough that an isolated single Home press
     * a second or two after the previous hijack is NOT misread as a
     * double-press, while still loose enough for a comfortable
     * double-click (which typically settles around 800-1200 ms
     * including the brief Amazon-home flash and the target's return
     * to foreground).
     */
    private static final long HIJACK_DEBOUNCE_MS = 1_000L;

    /**
     * Wall-clock deadline (System.currentTimeMillis()) until which the
     * hijack is suspended. Set by {@link #requestBypass} only. The
     * double-press path does NOT set a lingering bypass; the next Home
     * press re-engages the hijack normally.
     */
    private static volatile long bypassUntil = 0L;

    /** Wall-clock time of the most recent hijack we performed. */
    private long lastHijackAt = 0L;

    /**
     * Wall-clock time and code of the most recent key event we saw,
     * used to disambiguate "long-press Home opened the Quick-Settings
     * overlay" from "the user pressed OK on a tile that happens to
     * live in the same com.amazon.tv.quicksettings.ui package" (e.g.
     * the Sound & Display settings entry on Amazon's home settings
     * tab opens an activity in that very package).
     */
    private volatile long lastKeyTime = 0L;
    private volatile int lastKeyCode = -1;

    /**
     * KeyCode whose ACTION_DOWN {@link #tryBackOrCenterHijack}
     * consumed, so we can also swallow its matching ACTION_UP and
     * avoid delivering a torn (UP-without-DOWN) event to the app that
     * the hijack just brought to the foreground. {@code -1} = none.
     */
    private volatile int consumedDownKey = -1;

    /**
     * Set by {@link #onKeyEvent} on every d-pad navigation DOWN and
     * consumed by the next launcher focus event in
     * {@link #handleFocusEvent}. While true, the focus change that
     * follows is attributed to the d-pad rather than to a Home press.
     * After consumption it stays false until the next d-pad key, so
     * any further spontaneous focus jumps to the Home tab fire the
     * home-press inference normally.
     */
    private volatile boolean dpadDrivenFocusPending = false;


    /** How recent an OK press has to be to count as a tile-open trigger. */
    private static final long RECENT_OK_WINDOW_MS = 1_500L;

    /**
     * Set by the window-state handler whenever a foreground change is
     * observed. Read by {@link #onKeyEvent} to decide whether to
     * intercept Back. {@code volatile} because the two callbacks may
     * run on different threads.
     */
    private volatile boolean onAmazonHomeActivity = false;

    /**
     * Foreground package the user just came from. We need this to
     * recognise the "user held Home inside the target app" pattern:
     * Quick-Settings appears with the target as the immediately
     * preceding foreground. {@code volatile} because the field is
     * read by code paths that may run on different threads than the
     * accessibility event delivery.
     */
    private volatile String previousForegroundPkg = null;
    private volatile String currentForegroundPkg = null;

    /**
     * Bounds of the launcher's Home tab. Captured once per service
     * lifetime when we first see a plausibly tab-shaped focused node
     * inside the Amazon launcher, then left alone: the launcher
     * layout is stable between sessions, and re-capturing after a
     * sub-page return could land on the wrong element. Service
     * restart (reboot, accessibility toggle, app update) is the
     * authoritative way to refresh.
     */
    private volatile android.graphics.Rect cachedHomeTabBounds = null;

    /**
     * Armed when we expect a Home-tab capture to land (fresh arrival
     * at HomeActivity_vNext while the cache is empty, or right after
     * we triggered a long-press redirect). Cleared by the next
     * plausible capture, or when the user leaves the Amazon ecosystem
     * for a non-transient destination ({@link #updateCaptureFlagsFromWse}
     * keeps the flag alive across Quick-Settings overlays and the
     * generic FrameLayout/ViewGroup/LinearLayout WSEs that fire
     * during activity hand-off).
     */
    private volatile boolean pendingHomeTabCapture = false;

    /**
     * Allows other components (typically MainActivity) to temporarily
     * suspend the hijack. Useful right before firing an intent that
     * might pass through the Amazon launcher.
     *
     * @param ms suspension duration in milliseconds
     */
    public static void requestBypass(long ms) {
        bypassUntil = System.currentTimeMillis() + ms;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            handleFocusEvent(event);
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        String pkgStr = pkg != null ? pkg.toString() : null;

        // Foreground tracking. Updated only on package change so
        // intermediate events for the same window don't shift state.
        if (pkgStr != null && !pkgStr.equals(currentForegroundPkg)) {
            previousForegroundPkg = currentForegroundPkg;
            currentForegroundPkg = pkgStr;
        }

        boolean isAmazonHome = AMAZON_LAUNCHER.equals(pkgStr)
                && cls != null
                && AMAZON_LAUNCHER_HOME_ACTIVITY.equals(cls.toString());
        onAmazonHomeActivity = isAmazonHome;
        boolean inAmazonLauncher = AMAZON_LAUNCHER.equals(pkgStr);

        if (prefs.isVerboseLogging()) {
            Log.i(TAG, "verbose win pkg=" + pkg + " cls=" + cls
                    + " prev=" + previousForegroundPkg
                    + " amazonHome=" + isAmazonHome
                    + " cached=" + (cachedHomeTabBounds == null
                            ? "null"
                            : cachedHomeTabBounds.flattenToString()));
        }

        updateCaptureFlagsFromWse(isAmazonHome, inAmazonLauncher, pkgStr, cls);
        if (inAmazonLauncher && pendingHomeTabCapture) {
            tryCaptureFromCurrentFocus();
        }

        if (QUICKSETTINGS_PKG.equals(pkgStr)) {
            handleQuickSettingsLongPress();
            return;
        }
        if (isAmazonHome) {
            handleAmazonHomeArrival();
        }
    }

    /**
     * Manages {@link #pendingHomeTabCapture} across window-state events.
     * Arm on fresh HomeActivity_vNext arrival while the cache is empty
     * (the launcher layout is stable, so re-capturing later can only
     * poison the cache with sub-page focus). Clear when we leave the
     * Amazon ecosystem, but treat the Quick-Settings overlay and
     * transient container WSEs (FrameLayout/ViewGroup/LinearLayout)
     * as in-flight states that should not invalidate the pending flag.
     */
    private void updateCaptureFlagsFromWse(boolean isAmazonHome,
                                            boolean inAmazonLauncher,
                                            String pkgStr,
                                            CharSequence cls) {
        if (isAmazonHome && cachedHomeTabBounds == null) {
            pendingHomeTabCapture = true;
        }
        boolean isTransientContainerWse = cls != null
                && ("android.widget.FrameLayout".equals(cls.toString())
                    || "android.view.ViewGroup".equals(cls.toString())
                    || "android.widget.LinearLayout".equals(cls.toString()));
        if (!inAmazonLauncher
                && !QUICKSETTINGS_PKG.equals(pkgStr)
                && !isTransientContainerWse) {
            pendingHomeTabCapture = false;
        }
    }

    /**
     * Reacts to the Quick-Settings overlay appearance: Fire OS's
     * only observable side-effect of a long-press of Home (the key
     * event itself is firmware-intercepted).
     *
     *  - long-press in target        -> escape to Amazon home
     *  - long-press in Amazon area   -> open target
     *  - long-press in third-party   -> do NOTHING (the app may use
     *                                    that gesture for its own menu)
     *
     * QS also hosts genuine settings activities (e.g. Sound & Display
     * reachable from the Amazon home Settings tab). To avoid treating
     * a tile click as a long-press, skip when an OK key was pressed
     * within the last RECENT_OK_WINDOW_MS.
     */
    private void handleQuickSettingsLongPress() {
        if (!prefs.isHijackEnabled()) return;

        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) return;

        // Live window list trumps the tracked previousForegroundPkg:
        // tracking can be stale when Fire OS resumes the target from
        // the back stack without firing a fresh WSE.
        String prev = findUnderlyingAppPkg();
        if (prev == null) prev = previousForegroundPkg;

        // A genuine tile click (e.g. the Sound & Display entry on the
        // Amazon home Settings tab opens an activity inside the
        // quicksettings.ui package) only ever happens while the
        // underlying app IS the Amazon UI. Only there should a recent OK
        // make us treat this QS appearance as a tile open rather than a
        // long-press Home. Scoping it this way is load-bearing: the
        // real Home key is firmware-intercepted and never refreshes
        // lastKeyCode/lastKeyTime, so an OK pressed inside the target
        // shortly before a genuine long-press-Home escape would
        // otherwise be misread as a tile click and swallow the escape.
        long now = System.currentTimeMillis();
        boolean recentOk =
                (lastKeyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || lastKeyCode == KeyEvent.KEYCODE_ENTER)
                && (now - lastKeyTime) < RECENT_OK_WINDOW_MS;
        boolean tileClickInAmazon = recentOk
                && prev != null && prev.startsWith("com.amazon.")
                && !target.equals(prev);
        if (tileClickInAmazon) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "quicksettings.ui after recent OK in Amazon ("
                        + (now - lastKeyTime) + "ms ago). Treating as tile click");
            }
            return;
        }

        if (prefs.isVerboseLogging()) {
            Log.i(TAG, "QS long-press handler: prev=" + prev
                    + " (tracked=" + previousForegroundPkg
                    + ") target=" + target);
        }

        if (target.equals(prev)) {
            redirectToAmazonHome();
            return;
        }
        if (prev != null && prev.startsWith("com.amazon.")) {
            launchTarget(target, "Long-press Home in Amazon area (prev=" + prev + ")");
        }
        // No fallback for third-party apps: long-press Home there is
        // deliberately NOT hijacked so the app keeps its own menu.
    }

    /**
     * Auto-hijack when Amazon home appears as a fresh foreground.
     * Skips:
     *  - in-Amazon-area arrivals (closing a sub-page, leaving settings)
     *  - active long-press bypass window
     *  - detected double-presses (within debounce, no Back between
     *    last hijack and this WSE: Fire OS firmware-intercepts the
     *    Home key, so anything we DO see between hijacks can only be
     *    a non-Home press; absence of a Back key implies the user
     *    pressed Home twice)
     */
    private void handleAmazonHomeArrival() {
        if (!prefs.isHijackEnabled()) return;

        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty() || target.equals(getPackageName())) return;

        String prev = previousForegroundPkg;
        if (prev != null && prev.startsWith("com.amazon.")) return;

        long now = System.currentTimeMillis();
        if (now < bypassUntil) return;

        if (now - lastHijackAt < HIJACK_DEBOUNCE_MS) {
            boolean backSinceHijack = lastKeyCode == KeyEvent.KEYCODE_BACK
                    && lastKeyTime > lastHijackAt;
            if (!backSinceHijack) {
                if (prefs.isVerboseLogging()) {
                    Log.i(TAG, "Auto-hijack skipped: within debounce, no Back"
                            + " since last hijack (double-press escape)");
                }
                return;
            }
        }
        // lastHijackAt is armed inside launchTarget on success.
        launchTarget(target, "Redirected Home (from " + prev + ")");
    }

    /**
     * Sends the user to Amazon home by dispatching a system-level
     * Home press ({@code GLOBAL_ACTION_HOME}). Sets the long-press
     * bypass window so the upcoming Amazon-home WSE doesn't trigger
     * our auto-hijack right back into the target, and pre-arms the
     * Home-tab capture so the launcher's focus event can land bounds
     * without waiting for the WSE. Used to honour the long-press-
     * Home-from-target gesture.
     *
     * GLOBAL_ACTION_HOME is preferred over starting the home activity
     * directly because the latter surfaces the launcher with whatever
     * tab the previous Amazon-home visit ended on, which breaks our
     * Back/OK-on-Home-tab return-to-target gesture.
     */
    private void redirectToAmazonHome() {
        bypassUntil = System.currentTimeMillis() + LONGPRESS_REDIRECT_BYPASS_MS;
        pendingHomeTabCapture = true;
        boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
        Log.i(TAG, "Long-press Home in target. Dispatched GLOBAL_ACTION_HOME ok=" + ok);
    }

    /**
     * Key-event entry point. Dispatches to four concerns:
     *  - {@link #trackKeyState} stores the last key for downstream
     *    disambiguators (QS / double-press).
     *  - {@link #handleMenuKey} schedules the Menu long-press shortcut.
     *  - Opportunistic Home-tab capture, when one is still pending.
     *  - {@link #tryBackOrCenterHijack} fires the Back/OK-on-Home-tab
     *    return-to-target gesture.
     *
     * We deliberately do NOT consume Back when the target is in
     * front. Many launchers (LtvLauncher, FLauncher) draw overflow
     * menus and sub-screens inside the same activity with no extra
     * windows, so we can't tell from outside whether a Back closes
     * a menu (wanted) or exits the launcher (handled by the auto-
     * hijack on the resulting Amazon-home arrival).
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean isUp = event.getAction() == KeyEvent.ACTION_UP;

        if (isDown) {
            trackKeyState(keyCode);
        }

        // Capture the swallow decision BEFORE handleMenuKey, whose UP
        // branch clears the pending Menu state.
        boolean swallowMenu = keyCode == KeyEvent.KEYCODE_MENU && menuLongPressFired;
        handleMenuKey(event, keyCode);
        if (swallowMenu) {
            // The long-press shortcut already opened our config screen.
            // Swallow the remaining repeat DOWNs and the trailing UP of
            // this Menu press so the launcher underneath doesn't also
            // fire its own Menu action.
            if (isUp) menuLongPressFired = false;
            return true;
        }

        if (prefs.isVerboseLogging() && isDown) {
            Log.i(TAG, "verbose key code=" + keyCode
                    + " amazonHome=" + onAmazonHomeActivity);
            logFocusedNode("key:" + keyCode);
        }
        if (isDown && pendingHomeTabCapture) {
            // Opportunistic capture: when the launcher's WSE is
            // delayed and no focus event fires either, the cache
            // stays null. Reading the current focus on each key
            // DOWN gives us another chance to land the bounds.
            tryCaptureFromCurrentFocus();
        }

        if (!isDown) {
            // Swallow the UP that pairs with a DOWN we consumed, so the
            // app the hijack just foregrounded doesn't receive a torn
            // (UP-without-DOWN) Back/OK event.
            if (keyCode == consumedDownKey) {
                consumedDownKey = -1;
                return true;
            }
            return false;
        }

        boolean consumed = tryBackOrCenterHijack(keyCode);
        // Arm the UP-swallow only for a consumed DOWN; clear otherwise so
        // a stale value (e.g. if a paired UP was ever lost) can't swallow
        // an unrelated later key's UP. The matching UP has no intervening
        // DOWN, so this is safe.
        consumedDownKey = consumed ? keyCode : -1;
        return consumed;
    }

    /**
     * Updates the key-state trackers used by other parts of the
     * service. lastKeyCode + lastKeyTime feed the QS disambiguator
     * (recent-OK vs. real long-press Home) and the auto-hijack
     * double-press detector. dpadDrivenFocusPending feeds the
     * focus-based Home-press inference.
     */
    private void trackKeyState(int keyCode) {
        lastKeyTime = System.currentTimeMillis();
        lastKeyCode = keyCode;
        if (isDpadNavKey(keyCode)) {
            dpadDrivenFocusPending = true;
        }
    }

    /**
     * Menu long-press scheduling. Fires on threshold (not on release)
     * so the shortcut feels responsive. The pending Runnable is
     * cancelled when Menu UPs before the threshold passes.
     */
    private void handleMenuKey(KeyEvent event, int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_MENU) return;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            scheduleMenuLongPress();
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            cancelMenuLongPress();
        }
    }

    /**
     * Back/OK on the Amazon-home Home tab → launch target. Other
     * focused elements (other tabs, content tiles) are deliberately
     * not intercepted. Returns true iff we consumed the event.
     */
    private boolean tryBackOrCenterHijack(int keyCode) {
        boolean isBack = keyCode == KeyEvent.KEYCODE_BACK;
        boolean isCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER;
        if (!isBack && !isCenter) return false;
        if (!prefs.isHijackEnabled()) return false;

        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty() || target.equals(getPackageName())) return false;

        boolean amazonHome = onAmazonHomeActivity || isCurrentlyOnAmazonLauncher();
        if (!(amazonHome && isHomeTabFocused())) return false;

        // launchTarget arms lastHijackAt on success, so a TYPE_VIEW_FOCUSED
        // event firing right after this Back/OK (e.g. the Amazon launcher
        // re-focusing its Home tab during the transition) is debounced and
        // doesn't also trigger the focus-based inference (double-launch).
        String reason = isBack ? "Back on Home tab" : "OK on Home tab";
        return launchTarget(target, reason);
    }

    /**
     * Resolves the target package to a launchable Intent and starts it.
     *
     * Two flag choices that matter here:
     *  - FLAG_ACTIVITY_NEW_TASK only (no CLEAR_TOP) so that an already
     *    running instance of the target is brought to the front rather
     *    than re-created. Avoids unnecessary "loading" screens when the
     *    user repeatedly hops between the launcher and other apps.
     *  - CATEGORY_HOME (added if the target advertises it) makes the
     *    target act as the system home activity from this call onwards.
     *    Effect: pressing Back inside the target stops there instead of
     *    falling back through the activity stack into the Amazon launcher
     *    again, which would otherwise create a back-and-forth loop.
     */
    private boolean launchTarget(String target, String reasonForLog) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(target);
        if (intent == null) intent = pm.getLaunchIntentForPackage(target);
        if (intent == null) {
            Log.w(TAG, "Target package not installed or not launchable: " + target);
            return false;
        }
        // If the target declares itself as a home activity, mark this
        // launch as a HOME action so back-stack handling treats it as
        // such. We probe once. The call is cheap.
        if (canHandleHome(pm, target)) {
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            // Do NOT clear cachedHomeTabBounds here. Amazon home can
            // resurface without firing a fresh HomeActivity_vNext WSE
            // (e.g. when Fire OS resumes it from the back stack after
            // the user backs out of the target), and clearing the
            // cache would strand the user on Amazon home with no
            // working Back/OK return because isHomeTabFocused()
            // requires the bounds to be set. The launcher layout is
            // stable between sessions; if it really changes, the next
            // WSE-triggered capture overwrites the cache anyway.
            pendingHomeTabCapture = false;
            // Arm the double-launch debounce centrally for every launch
            // path (auto-hijack, Back/OK, focus inference, wake) so a
            // near-simultaneous WSE / focus event is suppressed and no
            // caller can forget to set it.
            lastHijackAt = System.currentTimeMillis();
            // Clear any active long-press bypass window. We've just
            // explicitly taken the user back into the target, so the
            // "stay on Amazon home" intent from a prior long-press is
            // over. Without this clear, a subsequent Back inside the
            // target (which may exit it back to Amazon home) would
            // hit the still-active bypass and strand the user there.
            bypassUntil = 0L;
            Log.i(TAG, reasonForLog + ". Launched " + target);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + target, e);
            return false;
        }
    }

    /**
     * Walks the live window stack and returns the package of the
     * topmost {@link android.view.accessibility.AccessibilityWindowInfo#TYPE_APPLICATION TYPE_APPLICATION}
     * window that is NOT Quick-Settings. Used as a fallback for
     * detecting "what was foreground before the long-press triggered
     * the QS overlay" when our cached previousForegroundPkg is null
     * (service just started) or stale (QS overlay appeared without an
     * intervening activity change).
     *
     * Requires {@code flagRetrieveInteractiveWindows} in the
     * accessibility config so getWindows() returns the full stack.
     */
    private String findUnderlyingAppPkg() {
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        for (android.view.accessibility.AccessibilityWindowInfo win : windows) {
            if (win == null) continue;
            try {
                if (win.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = win.getRoot();
                if (root == null) continue;
                try {
                    CharSequence pkg = root.getPackageName();
                    if (pkg == null) continue;
                    String pkgStr = pkg.toString();
                    if (QUICKSETTINGS_PKG.equals(pkgStr)) continue;
                    return pkgStr;
                } finally {
                    root.recycle();
                }
            } finally {
                win.recycle();
            }
        }
        return null;
    }

    /**
     * Live query for the package of the currently active window.
     * Returns null if the system does not let us see the window or
     * there is no active window. Requires canRetrieveWindowContent
     * in the accessibility config.
     */
    private String currentForegroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg != null ? pkg.toString() : null;
        } finally {
            root.recycle();
        }
    }

    /**
     * Convenience wrapper around {@link #currentForegroundPackage}.
     * Used as a fallback when the cached foreground tracker is out
     * of date.
     */
    private boolean isCurrentlyOnAmazonLauncher() {
        return AMAZON_LAUNCHER.equals(currentForegroundPackage());
    }

    /**
     * Returns true when the focused node's screen bounds match the
     * Home tab bounds we captured the last time Amazon home was
     * freshly foregrounded with default focus.
     *
     * Returns false (and the Back/OK intercept stays silent) whenever
     * the cache is empty. E.g. on the very first Amazon home visit
     * after install / service restart, or after a hijack invalidated
     * the cache.
     */
    private boolean isHomeTabFocused() {
        android.graphics.Rect cached = cachedHomeTabBounds;
        if (cached == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null) return false;
            try {
                android.graphics.Rect b = new android.graphics.Rect();
                focused.getBoundsInScreen(b);
                return Math.abs(b.centerX() - cached.centerX()) <= TAB_MATCH_TOLERANCE_PX
                        && Math.abs(b.centerY() - cached.centerY()) <= TAB_MATCH_TOLERANCE_PX;
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * TYPE_VIEW_FOCUSED handler. Does two things:
     *
     *  1. Bounds capture: when we're still waiting for a Home-tab
     *     capture inside the Amazon launcher, store the focused
     *     node's bounds (overwriting any previous cache value so the
     *     most recent arrival wins).
     *
     *  2. Home-press inference: Fire OS intercepts KEYCODE_HOME at
     *     firmware level so we never see the key event, but if the
     *     user presses Home while already on Amazon home with a
     *     non-default tab selected, focus jumps back to the Home tab.
     *     That focus jump is a strong proxy for a Home press: if it
     *     happens without any recent d-pad navigation, treat it as
     *     the user asking for the target launcher.
     *
     * Known limitation: when the user has navigated Down from the
     * top tab row into a content row, the launcher's OS-level focus
     * stays pinned on the Home-tab container while content selection
     * moves internally (Amazon's launcher uses a non-focus selection
     * mechanism for content). A Home press in that state generates
     * no accessibility event at all (verified by subscribing to
     * typeAllMask: no focus, no WSE, no selected, no scrolled, no
     * content-change attributable to the Home press). The inference
     * cannot help there. Workaround for the user: press Back instead
     * (Back fires our isHomeTabFocused() path because OS focus is on
     * the Home tab container) or use long-press Home.
     */
    private void handleFocusEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !AMAZON_LAUNCHER.equals(pkg.toString())) return;

        // Consume the d-pad-attribution flag exactly once per launcher
        // focus event. The d-pad press that caused this event (if any)
        // is now "spent"; subsequent focus events without a fresh
        // d-pad press cannot be attributed to navigation.
        boolean wasDpadDriven = dpadDrivenFocusPending;
        dpadDrivenFocusPending = false;

        AccessibilityNodeInfo src = event.getSource();
        if (src == null) {
            if (pendingHomeTabCapture && prefs.isVerboseLogging()) {
                Log.i(TAG, "focus event in launcher: src=null");
            }
            return;
        }
        try {
            android.graphics.Rect b = new android.graphics.Rect();
            src.getBoundsInScreen(b);
            if (pendingHomeTabCapture) {
                storeBoundsIfPlausible(b, src.getClassName(), "focus path");
                return;
            }
            maybeInferHomePress(b, wasDpadDriven);
        } finally {
            src.recycle();
        }
    }

    /**
     * Home-press inference on a launcher focus event. Fires the
     * hijack when the focus is on the cached Home tab and no d-pad
     * press caused this focus change.
     *
     * Note: We do NOT additionally require the previous focus to
     * have been somewhere else. On Amazon launcher the OS-level
     * focus often stays pinned on the Home-tab container even while
     * the user has visually selected a tile in a row below (the
     * launcher uses an internal selection mechanism for content
     * rows, not OS focus). When the user then presses Home, the
     * only signal we can observe is a re-focus event for the same
     * Home tab; suppressing that as "no real jump" would lose the
     * signal entirely for the lower-row case.
     */
    private void maybeInferHomePress(android.graphics.Rect b, boolean wasDpadDriven) {
        android.graphics.Rect cached = cachedHomeTabBounds;
        if (cached == null) return;
        boolean matchesHomeTab =
                Math.abs(b.centerX() - cached.centerX()) <= TAB_MATCH_TOLERANCE_PX
                && Math.abs(b.centerY() - cached.centerY()) <= TAB_MATCH_TOLERANCE_PX;
        if (!matchesHomeTab) return;
        if (!shouldInferHomePress(wasDpadDriven)) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "focus on Home tab, but inference suppressed"
                        + " (dpadDriven=" + wasDpadDriven + ")");
            }
            return;
        }
        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty() || target.equals(getPackageName())) return;
        // lastHijackAt is armed inside launchTarget on success.
        launchTarget(target, "Inferred Home press from focus event on Home tab");
    }

    /**
     * Returns true if a focus jump to the cached Home tab should fire
     * the hijack. Suppresses when:
     *  - hijack is disabled
     *  - we're inside an explicit long-press bypass window
     *  - we just fired a hijack (debounce)
     *  - a d-pad nav key is responsible for this focus change (the
     *    flag is set on the d-pad key DOWN and consumed by the next
     *    launcher focus event, so a single d-pad press attributes
     *    exactly one focus change to navigation; everything after is
     *    fair game for the home-press inference)
     */
    private boolean shouldInferHomePress(boolean dpadDriven) {
        if (!prefs.isHijackEnabled()) return false;
        long now = System.currentTimeMillis();
        if (now < bypassUntil) return false;
        if (now - lastHijackAt < HIJACK_DEBOUNCE_MS) return false;
        if (dpadDriven) return false;
        return true;
    }

    /**
     * Window-state-path fallback to {@link #handleFocusEvent}. Used
     * when the launcher activity does not emit TYPE_VIEW_FOCUSED but
     * the OS has nevertheless already set default focus on the Home
     * tab; we read it straight off the current root.
     */
    private void tryCaptureFromCurrentFocus() {
        if (!pendingHomeTabCapture) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Only capture when the launcher itself owns the active
            // window. Otherwise we'd grab bounds from whatever overlay
            // (typically Quick-Settings during a long-press redirect)
            // happens to be on top and store nonsense in the cache.
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg == null || !AMAZON_LAUNCHER.equals(rootPkg.toString())) {
                return;
            }
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null) return;
            try {
                android.graphics.Rect b = new android.graphics.Rect();
                focused.getBoundsInScreen(b);
                storeBoundsIfPlausible(b, focused.getClassName(), "window-state path");
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Shared store-on-plausible step for both capture paths. If the
     * bounds look like a tab icon, cache them and clear the pending
     * flag. The pathLabel is "focus path" or "window-state path" and
     * appears in the diagnostic + confirmation log lines.
     */
    private void storeBoundsIfPlausible(android.graphics.Rect b,
                                         CharSequence cls,
                                         String pathLabel) {
        boolean plausible = isPlausibleTabBounds(b);
        if (prefs.isVerboseLogging()) {
            Log.i(TAG, pathLabel.replace(" path", "") + " capture attempt"
                    + " bounds=" + b.flattenToString()
                    + " cls=" + cls
                    + " plausible=" + plausible);
        }
        if (!plausible) return;
        cachedHomeTabBounds = new android.graphics.Rect(b);
        pendingHomeTabCapture = false;
        Log.i(TAG, "Captured Home tab bounds (" + pathLabel + "): "
                + b.flattenToString());
    }

    /** True for the four d-pad arrow keys. Used to attribute focus changes to navigation. */
    private static boolean isDpadNavKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /**
     * Sanity check applied to a candidate Home-tab capture: the node
     * has to look like a small, roughly-square icon button rather
     * than a content tile or a wide banner.
     */
    private static boolean isPlausibleTabBounds(android.graphics.Rect b) {
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return false;
        if (w > MAX_TAB_DIM_PX || h > MAX_TAB_DIM_PX) return false;
        return Math.abs(w - h) <= MAX_TAB_ASPECT_DELTA;
    }

    /** Largest side length (px) a node may have to still count as a tab icon. */
    private static final int MAX_TAB_DIM_PX = 300;

    /** Largest allowed width-vs-height difference (px) for "square-ish". */
    private static final int MAX_TAB_ASPECT_DELTA = 80;

    /** Per-axis pixel tolerance when comparing focused vs. cached tab bounds. */
    private static final int TAB_MATCH_TOLERANCE_PX = 30;

    /**
     * Posts a delayed Runnable that opens this app's configuration
     * activity after {@link #MENU_LONG_PRESS_THRESHOLD_MS}. The
     * Runnable re-checks the user preferences and foreground at fire
     * time so a quick release (short Menu press) leaves the system
     * Menu behavior alone.
     */
    private void scheduleMenuLongPress() {
        cancelMenuLongPress();
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        pendingMenuLongPress = new Runnable() {
            @Override
            public void run() {
                pendingMenuLongPress = null;
                if (!prefs.isMenuLongPressLaunch()) return;
                String fg = currentForegroundPackage();
                if (fg == null) fg = currentForegroundPkg;
                String tgt = prefs.getTargetPackage();
                boolean inAmazon = fg != null && fg.startsWith("com.amazon.");
                boolean inTarget = tgt != null && !tgt.isEmpty() && tgt.equals(fg);
                if (inAmazon || inTarget) {
                    menuLongPressFired = true;
                    openSelf();
                }
            }
        };
        mainHandler.postDelayed(pendingMenuLongPress, MENU_LONG_PRESS_THRESHOLD_MS);
    }

    /** Cancels a pending Menu long-press fire if the key was released early. */
    private void cancelMenuLongPress() {
        if (pendingMenuLongPress != null && mainHandler != null) {
            mainHandler.removeCallbacks(pendingMenuLongPress);
        }
        pendingMenuLongPress = null;
        menuLongPressFired = false;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cancelMenuLongPress();
        // Flush every pending main-handler post (the wake-from-standby
        // runnable in particular) so nothing fires after the service
        // disconnects.
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        consumedDownKey = -1;
        if (screenOnReceiver != null) {
            try {
                unregisterReceiver(screenOnReceiver);
            } catch (Exception ignored) {
                // already unregistered, or never registered on this run
            }
            screenOnReceiver = null;
        }
        return super.onUnbind(intent);
    }

    /** Opens this app's configuration activity. Used by the Menu long-press shortcut. */
    private void openSelf() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "Long-press Menu. Opened Home on Fire");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open self", e);
        }
    }

    /** Returns true if the target package has an Activity that handles HOME. */
    private boolean canHandleHome(PackageManager pm, String pkg) {
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_HOME);
        probe.setPackage(pkg);
        return !pm.queryIntentActivities(probe, 0).isEmpty();
    }

    @Override
    public void onInterrupt() {
        // Required override. Nothing to do. We hold no interruptible state.
    }

    /**
     * Verbose-log helper: dumps the currently focused node's view-id
     * resource name, class, text, content description and screen
     * bounds. Used to discover language-independent identifiers for
     * the launcher's Home tab so we can stop relying on translated
     * label matching.
     */
    private void logFocusedNode(String context) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.i(TAG, "verbose node " + context + " root=null");
            return;
        }
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null) {
                Log.i(TAG, "verbose node " + context + " pkg=" + root.getPackageName()
                        + " focused=null");
                return;
            }
            try {
                android.graphics.Rect b = new android.graphics.Rect();
                focused.getBoundsInScreen(b);
                Log.i(TAG, "verbose node " + context
                        + " pkg=" + focused.getPackageName()
                        + " id=" + focused.getViewIdResourceName()
                        + " cls=" + focused.getClassName()
                        + " text=" + focused.getText()
                        + " desc=" + focused.getContentDescription()
                        + " bounds=" + b.flattenToString());
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }
}
