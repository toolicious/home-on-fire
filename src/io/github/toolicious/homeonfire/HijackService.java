// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
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
     * Amazon Kids home shell, shown inside a child profile. A child profile is its own
     * Android user with separate app storage and secure settings, so Home on Fire only
     * runs there if it was installed AND its accessibility service enabled for that user.
     * Both are deliberate acts, which is why this needs no extra opt-in switch.
     *
     * Kept as a fixed second entry rather than resolving "whatever is currently home":
     * the target launcher declares CATEGORY_HOME as well, so a dynamic lookup could
     * mistake our own target for Amazon's home and redirect it onto itself.
     */
    private static final String KIDS_LAUNCHER = "com.amazon.tahoe";
    private static final String KIDS_LAUNCHER_HOME_ACTIVITY =
            "com.amazon.tahoe.ftv.HomeActivity";

    /** True for either Amazon home surface: the regular launcher or the kids launcher. */
    private static boolean isAmazonHomeWindow(String pkg, String cls) {
        if (pkg == null || cls == null) return false;
        return (AMAZON_LAUNCHER.equals(pkg) && AMAZON_LAUNCHER_HOME_ACTIVITY.equals(cls))
                || (KIDS_LAUNCHER.equals(pkg) && KIDS_LAUNCHER_HOME_ACTIVITY.equals(cls));
    }

    /**
     * Fire OS shows this overlay panel when the user holds the Home
     * button on the remote. Detecting its appearance is the only way
     * we can react to a long-press of Home. The key event itself is
     * intercepted by the system before reaching any service.
     */
    private static final String QUICKSETTINGS_PKG = "com.amazon.tv.quicksettings.ui";

    /**
     * Older equivalent of the Quick-Settings panel: where
     * {@link #QUICKSETTINGS_PKG} does not exist, a long-press of Home opens the settings
     * HUD overlay instead, which is why the long-press gestures never fired there (issue
     * #7, originally reported in #3).
     *
     * Matched on the ACTIVITY, never on the package: {@code com.amazon.tv.settings.v2} also
     * hosts the ordinary Settings screens, and treating those as a long-press would make
     * every trip into Settings jump to the launcher. Verified on Fire OS 6.7.1.1 that the
     * two are distinct: a long-press starts {@code .hud.HudActivity} (action
     * {@code SHOW_HUD}), while entering Settings normally starts {@code .tv.*} activities
     * ({@code .tv.device.DeviceActivity}, {@code .tv.preferences.PreferencesActivity}).
     */
    private static final String AMAZON_SETTINGS_PKG = "com.amazon.tv.settings.v2";
    private static final String HUD_ACTIVITY_SUFFIX = "hud.HudActivity";

    /**
     * Whether this device ships the new Fire TV UI, i.e. has {@link #QUICKSETTINGS_PKG}.
     * Cached because a package cannot appear without an OTA, which restarts us anyway.
     * {@code null} until first queried.
     */
    private Boolean quickSettingsUiPresent;

    /**
     * The long-press panel is one window or the other, never both, and which one it is
     * follows the UI generation rather than the Fire OS major version:
     *
     *  - New UI ({@link #QUICKSETTINGS_PKG} installed, Fire OS 8.1.8.0 and up): Quick
     *    Settings is the long-press panel, and the HUD is what the profile switch shows
     *    after the PIN. Treating the HUD as a long-press there yanks the user out of the
     *    profile switch (reported for the Amazon Kids launcher in issue #7).
     *  - Older UI (no such package, Fire OS 7 and Fire OS 8.1.1.6 and older): the HUD is
     *    the long-press panel. The remote's settings button opens the very same window, so
     *    the two are indistinguishable from here; the user decides via the long-press
     *    escape switch whether that is worth it.
     */
    private boolean hasQuickSettingsUi() {
        if (quickSettingsUiPresent == null) {
            boolean present;
            try {
                getPackageManager().getPackageInfo(QUICKSETTINGS_PKG, 0);
                present = true;
            } catch (Exception e) {
                // NameNotFound on older builds. Note this needs the matching
                // <queries><package> entry in the manifest, or package visibility
                // filtering hides it on API 30+ even when it is installed.
                present = false;
            }
            quickSettingsUiPresent = present;
            Log.i(TAG, "Long-press panel source: " + (present
                    ? QUICKSETTINGS_PKG + " (new Fire TV UI; settings HUD left alone)"
                    : AMAZON_SETTINGS_PKG + " HUD (no " + QUICKSETTINGS_PKG + " on this build)"));
        }
        return quickSettingsUiPresent;
    }

    /** The long-press HUD overlay, on builds where the HUD is what a long-press opens. */
    private boolean isHudPanel(String pkg, String cls) {
        if (hasQuickSettingsUi()) return false;
        return AMAZON_SETTINGS_PKG.equals(pkg) && cls != null && cls.endsWith(HUD_ACTIVITY_SUFFIX);
    }

    /** The long-press panel of whichever UI generation this device runs. */
    private boolean isLongPressPanel(String pkg, String cls) {
        return QUICKSETTINGS_PKG.equals(pkg) || isHudPanel(pkg, cls);
    }

    /**
     * Amazon's Appstore, whose {@code AppsGridLauncherActivity} is what the remote's
     * Apps button opens. We cannot see the Apps key itself (it is system-handled), so a
     * slot mapped to Apps is driven off this window appearing, matched via the
     * {@link Prefs#APPS_WINDOW} sentinel in {@link #windowMatches}.
     */
    private static final String AMAZON_APPS_GRID = "com.amazon.venezia";

    /** Callback the config screen registers to learn an assignable remote key. */
    public interface KeyLearnListener {
        void onKeyLearned(int keyCode);
    }

    /**
     * Non-null while the config screen is waiting for the user to press a
     * button to bind as the launch key. Set/cleared via {@link #startLearning}
     * / {@link #stopLearning}; the service and the Activity share one process.
     */
    private static volatile KeyLearnListener sLearnListener;

    /**
     * Callback the config screen registers to learn a branded-button window.
     * Branded remote buttons (Apps, Live TV, Guide, ...) emit no keycode, so
     * {@link KeyLearnListener} can't reach them; we capture the app window the
     * button opens instead. Beta universal-redirect feature.
     */
    public interface WindowLearnListener {
        void onWindowLearned(String pkg, String activity);
    }

    /** Non-null while the config screen waits for the user to press a branded button. */
    private static volatile WindowLearnListener sWindowLearnListener;

    /**
     * Live service instance, so static callers (the config screen) can ask the service
     * to bring the config activity back to the front after a learned app-button opened
     * its app. The service always outlives the config Activity, and its startActivity is
     * exempt from Android's background-activity-start limits while the a11y service is
     * bound, so this succeeds even when a heavy app (e.g. Prime) destroyed the Activity.
     */
    private static volatile HijackService sInstance;

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
     * Initialized in {@link #onServiceConnected}.
     */
    private Prefs prefs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        prefs = new Prefs(this);
        // Not gated on verbose logging: a shared logcat dump has to name
        // the build that produced it even when the tester only turned
        // verbose on later (or never).
        AppInfo.logIdentity(this, TAG, "Service connected");
        mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // A fresh service instance must not inherit a suppression window
        // left in the static bypassUntil by a previous instance. The
        // instance timing fields (lastHijackAt, lastKeyTime, lastKeyCode)
        // reset implicitly; bypassUntil is static (so requestBypass can
        // stay a static entry point) and therefore needs an explicit clear.
        bypassUntil = 0L;
        consumedDownKey = -1;
        cancelMask();
        schedulePrewarm();
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
     * after {@link #MENU_LONG_PRESS_THRESHOLD_MS} unless canceled by
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
     * Short guard used instead of {@link #HIJACK_DEBOUNCE_MS} when the double-press
     * escape is switched off: long enough to swallow the repeat events a single Home
     * press produces, short enough that a deliberate second press still redirects.
     */
    private static final long DUPLICATE_EVENT_GUARD_MS = 300L;

    /**
     * How long after a window-triggered redirect an arrival on the branded surface still
     * counts as that launch chain (Prime opens further activities right after) instead of
     * the user coming back out of the app we opened.
     */
    private static final long LAUNCH_CHAIN_MS = 3_000L;

    /**
     * How long a Back press still explains a return to the branded surface. Pressing the
     * button again from inside the app it opened looks identical from here, so the Back
     * is what tells the two apart: these buttons send no key code of their own.
     */
    private static final long BACK_OUT_WINDOW_MS = 3_000L;

    /**
     * Wall-clock deadline (System.currentTimeMillis()) until which the
     * hijack is suspended. Set by {@link #requestBypass} only. The
     * double-press path does NOT set a lingering bypass; the next Home
     * press re-engages the hijack normally.
     */
    private static volatile long bypassUntil = 0L;

    /** Wall-clock time of the most recent hijack we performed. */
    private long lastHijackAt = 0L;

    /** The re-assert scheduled by the last window-triggered redirect, so a new one can cancel it. */
    private Runnable pendingReassert = null;
    /** When that redirect ran, to tell its launch chain from a later return by the user. */
    private long lastWindowLaunchAt = 0L;
    /**
     * App a mapped button launched, and when. For {@link #BUTTON_LAUNCH_GRACE_MS} after
     * that launch an Amazon home arrival comes from the button's own screen closing, not
     * from a Home press, and the auto-hijack must not start the launcher over the app.
     * A fixed time window on purpose: Amazon's home can show up once or twice, before or
     * after the app's first window, so the app having shown a window proves nothing.
     */
    private String pendingButtonApp = null;
    private long pendingButtonAt = 0L;
    private int pendingButtonRelaunches = 0;
    private static final long BUTTON_LAUNCH_GRACE_MS = 5_000L;
    private static final int BUTTON_MAX_RELAUNCHES = 2;

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
     * How recently an OK/Back must have been seen for an Amazon-home arrival out of
     * Amazon's own shell to count as deliberate navigation rather than a Home press
     * (see {@link #handleAmazonHomeArrival}). Sized against the real thing: picking
     * "Inputs" from the navigation drawer put the arrival ~0.7s after the OK, so this
     * leaves generous headroom for a slower hand-off.
     */
    private static final long AMAZON_SHELL_NAV_WINDOW_MS = 2_000L;

    /**
     * Set by the window-state handler whenever a foreground change is
     * observed. Read by {@link #onKeyEvent} to decide whether to
     * intercept Back. {@code volatile} because the two callbacks may
     * run on different threads.
     */
    private volatile boolean onAmazonHomeActivity = false;

    /**
     * Foreground package the user just came from. We need this to
     * recognize the "user held Home inside the target app" pattern:
     * Quick-Settings appears with the target as the immediately
     * preceding foreground. {@code volatile} because the field is
     * read by code paths that may run on different threads than the
     * accessibility event delivery.
     */
    private volatile String previousForegroundPkg = null;
    private volatile String currentForegroundPkg = null;

    /**
     * Activity class of those same two foregrounds. Tracked alongside the
     * package because Amazon's home shell keeps HomeActivity_vNext and its
     * navigation drawer (com.amazon.tv.launcher.navigation.NavigationActivity)
     * in ONE package. With package-only tracking, a return from the drawer
     * leaves prev pointing at the last real app, so
     * {@link #handleAmazonHomeArrival} reads it as "Amazon home came up from
     * the target launcher", i.e. a Home press, and fires a redirect the user
     * never asked for (seen when picking "Inputs" from the drawer).
     */
    private volatile String previousForegroundCls = null;
    private volatile String currentForegroundCls = null;

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

        // Foreground tracking, at activity granularity rather than package:
        // a move inside a single package (Amazon's drawer back to its home)
        // has to be visible here, otherwise it reads as a fresh arrival from
        // whatever real app came before the whole visit. Transient container
        // windows (the android.* decor that fires mid-handoff, and our own
        // overlay) are skipped so they cannot shift the trackers either.
        String clsStr = cls != null ? cls.toString() : null;
        if (pkgStr != null && isRealContentClass(clsStr)
                && (!pkgStr.equals(currentForegroundPkg)
                    || !clsStr.equals(currentForegroundCls))) {
            previousForegroundPkg = currentForegroundPkg;
            previousForegroundCls = currentForegroundCls;
            currentForegroundPkg = pkgStr;
            currentForegroundCls = clsStr;
        }

        // Learn mode (beta universal redirect): the config screen asked us to capture
        // the next app window so the user can teach a branded button (Apps, Live TV, ...)
        // that emits no keycode. Filter by PACKAGE, not class: some apps open with an
        // android.* window class (Amazon's Apps grid is com.amazon.venezia showing an
        // android.widget.FrameLayout), so a class filter would wrongly skip them. We only
        // skip our own screen and pure framework/system decor packages.
        if (sWindowLearnListener != null && pkgStr != null
                && !pkgStr.equals(getPackageName())
                && !isSystemDecorPkg(pkgStr)) {
            final WindowLearnListener learn = sWindowLearnListener;
            sWindowLearnListener = null;
            // Suppress the auto-hijack for a moment: the branded app we just captured can
            // collapse back to Amazon home right after (e.g. an app that fails to load),
            // which would otherwise redirect us to the launcher instead of letting the
            // config screen return to the front to show the result.
            bypassUntil = System.currentTimeMillis() + LONGPRESS_REDIRECT_BYPASS_MS;
            final String learnedPkg = pkgStr;
            final String learnedCls = clsStr;
            mainHandler.post(new Runnable() {
                @Override public void run() { learn.onWindowLearned(learnedPkg, learnedCls); }
            });
            return;
        }

        // Drive the masking-overlay teardown from the target's window events. We wait
        // for the target's real content window (an app-specific class) rather than the
        // first transitional one, so the mask is not lifted before the launcher has
        // painted. maskArmed / maskTeardownPending are only set on Fire OS 6/7, so this
        // is a no-op on Fire OS 8.
        if ((maskArmed || maskTeardownPending) && pkgStr != null
                && pkgStr.equals(maskTargetPkg)) {
            onMaskTargetEvent(cls);
        }

        boolean isAmazonHome = isAmazonHomeWindow(pkgStr, clsStr);
        onAmazonHomeActivity = isAmazonHome;
        // Deliberately the regular launcher only: this gates the Home-tab bounds capture
        // for the Back/OK-on-Home-tab gesture, and the kids launcher has no such tab.
        boolean inAmazonLauncher = AMAZON_LAUNCHER.equals(pkgStr);

        if (prefs.isVerboseLogging()) {
            Log.i(TAG, "verbose win pkg=" + pkg + " cls=" + cls
                    + " prev=" + previousForegroundPkg
                    + " prevCls=" + previousForegroundCls
                    + " amazonHome=" + isAmazonHome
                    + " cached=" + (cachedHomeTabBounds == null
                            ? "null"
                            : cachedHomeTabBounds.flattenToString()));
        }

        updateCaptureFlagsFromWse(isAmazonHome, inAmazonLauncher, pkgStr, cls);
        if (inAmazonLauncher && pendingHomeTabCapture) {
            tryCaptureFromCurrentFocus();
        }

        if (isLongPressPanel(pkgStr, clsStr)) {
            handleQuickSettingsLongPress();
            return;
        }
        if (pkgStr != null && clsStr != null) {
            // Names the store screen we just refused to treat as the Apps grid, so a
            // report tells us straight away when Amazon renames or reshapes it.
            if (AMAZON_APPS_GRID.equals(pkgStr) && !isAppsGridWindow(clsStr)
                    && prefs.isVerboseLogging()) {
                Log.i(TAG, "Appstore screen ignored, not the Apps grid: " + clsStr);
            }
            boolean mapOn = prefs.isLaunchKeyEnabled();
            String launchWin = prefs.getLaunchWindow();
            boolean matchLaunch = windowMatches(pkgStr, clsStr, launchWin);
            boolean matchAmazon = !matchLaunch && windowMatches(pkgStr, clsStr, prefs.getAmazonWindow());
            if ((matchLaunch || matchAmazon) && prefs.isVerboseLogging()) {
                Log.i(TAG, "window-match pkg=" + pkgStr + " cls=" + clsStr
                        + " mapEnabled=" + mapOn + " launch=" + matchLaunch
                        + " amazon=" + matchAmazon + " launchWin=" + launchWin);
            }
            if (mapOn && matchLaunch) {
                handleWindowLaunch(pkgStr);
                return;
            }
            if (mapOn && matchAmazon) {
                handleWindowAmazon();
                return;
            }
            if (mapOn) {
                String customApp = customAppForWindow(pkgStr, clsStr);
                if (customApp != null) {
                    handleWindowLaunch(pkgStr, customApp, "Redirect from custom button");
                    return;
                }
            }
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
                && !isLongPressPanel(pkgStr, cls == null ? null : cls.toString())
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
        // Turned off, the panel is left alone entirely, so it stays on screen with its
        // tiles (profiles, sleep, mirroring) and a remote's settings button, which opens
        // the very same window and is indistinguishable from a long-press, stops
        // switching launchers. The system broadcast that WOULD tell the two apart
        // (com.amazon.tv.action.HOME_LONGPRESSED) is guarded by a signature-level
        // permission, so no amount of detection can separate them from here.
        if (!prefs.isEscapeLongPress()) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "Long-press panel ignored: long-press escape is off");
            }
            return;
        }

        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) return;

        // Which app is under the Quick-Settings panel?
        //
        // The live window list is authoritative when the platform exposes it,
        // so try that first. Several Fire OS 8 builds return nothing useful
        // here (findUnderlyingAppPkg == null), and then we fall back to the
        // last tracked *current* foreground, NOT the previous one: the panel
        // paints an android.* window, which the class-gated foreground tracking
        // deliberately ignores (see onAccessibilityEvent), so the panel never
        // advances the trackers. The app underneath is therefore still
        // currentForegroundPkg. previousForegroundPkg points one app further
        // back and, right after an app is opened from the target launcher,
        // still holds the target, which made a long-press Home inside a
        // third-party app read as "long-press Home in target" and bounce to
        // Amazon home (issue #5). Reading current restores the pre-class-gate
        // behavior. If a future device does track the panel as a real class,
        // current would be the panel itself, so fall back to the previous
        // foreground in that one case.
        String prev = findUnderlyingAppPkg();
        if (prev == null) {
            prev = currentForegroundPkg;
            // Unlike the Fire OS 8 panel (an untracked android.* window), the Fire OS 6/7
            // HUD has a real activity class and therefore DOES advance the foreground
            // tracker, so "current" is the panel itself; step back one in that case. The
            // class check keeps genuine Settings screens (.tv.*) out of this fallback.
            if (QUICKSETTINGS_PKG.equals(prev)
                    || isHudPanel(currentForegroundPkg, currentForegroundCls)) {
                prev = previousForegroundPkg;
            }
        }

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
                    + " (current=" + currentForegroundPkg
                    + " tracked=" + previousForegroundPkg
                    + ") target=" + target);
        }

        // Inside a kids profile the panel is the only way out. Amazon disables
        // com.amazon.tv.launcher there by policy, so the tiles behind this panel
        // (switch profile, exit the kids profile) are the single route back, and
        // the profile has no reachable Settings at all. Consuming the panel here
        // strands the user: long-press in the target escapes to the kids home,
        // and a long-press there would bounce straight back to the target. That
        // loop is only escapable by switching the Home replacement off in our own
        // config screen, which is exactly what a kids profile is meant to prevent
        // a child from reaching (issue #7). So leave the panel alone and let the
        // profile switch through. A short Home press is unaffected and still
        // opens the target from the kids home.
        if (KIDS_LAUNCHER.equals(prev)) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "Long-press panel left alone: kids launcher underneath"
                        + " (profile switch would otherwise be unreachable)");
            }
            return;
        }

        if (target.equals(prev)) {
            redirectToAmazonHome("Long-press Home in target");
            return;
        }
        // Only Amazon's own home shell (launcher, Settings, Quick-Settings, none
        // of which expose a Leanback launcher entry) opens the target from here.
        // Amazon content apps such as Prime Video (com.amazon.firebat) DO have a
        // Leanback entry, so they are content apps like any other and left alone,
        // matching the isLeanbackLaunchable split in handleAmazonHomeArrival.
        // Without this the panel over Prime yanked the user to the launcher.
        if (prev != null && prev.startsWith("com.amazon.") && !isLeanbackLaunchable(prev)) {
            launchTarget(target, "Long-press Home in Amazon area (prev=" + prev + ")");
        }
        // No fallback for third-party apps (Amazon content apps included): a
        // long-press Home there is deliberately NOT hijacked so the app keeps
        // its own menu.
    }

    /**
     * Auto-hijack when Amazon home appears as a fresh foreground.
     * Skips:
     *  - arrivals from within Amazon's own home shell (launcher, settings,
     *    quicksettings, none of which expose a Leanback launcher entry);
     *    returning from a full Amazon app such as Prime Video still redirects
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
        long now = System.currentTimeMillis();
        // A mapped button launched an app a moment ago. This Amazon home comes from the
        // button's own screen (the Apps grid) closing, not from a Home press, and it can
        // arrive before or after the app's first window, once or twice. Hijacking would
        // start the launcher over the app, so launch the app again. A Home press inside
        // this window lands back in the app; the next one works as usual.
        if (pendingButtonApp != null && now - pendingButtonAt < BUTTON_LAUNCH_GRACE_MS) {
            String app = pendingButtonApp;
            if (maskArmed && app.equals(maskTargetPkg)) {
                if (prefs.isVerboseLogging()) {
                    Log.i(TAG, "Amazon home appeared, relaunch of " + app + " still pending");
                }
                return;
            }
            if (pendingButtonRelaunches < BUTTON_MAX_RELAUNCHES) {
                pendingButtonRelaunches++;
                if (pendingReassert != null && mainHandler != null) {
                    mainHandler.removeCallbacks(pendingReassert);
                }
                // The relaunch can land in the app-switch lock like a Home redirect does,
                // so it gets the loading screen on Fire OS 7.
                launchTarget(app, "Amazon home appeared while " + app
                        + " was starting, launching it again (" + pendingButtonRelaunches
                        + ")", true);
                return;
            }
            Log.i(TAG, "Amazon home keeps covering " + app + ", leaving it");
            pendingButtonApp = null;
        }
        // The last hijack's launch is still held by the app-switch lock (mask armed for
        // this target). Another start would only queue up behind it.
        if (maskArmed && target.equals(maskTargetPkg)) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "Auto-hijack skipped: launch of " + target + " still pending");
            }
            return;
        }
        // Arrivals out of Amazon's own home shell (launcher / settings / quicksettings,
        // none of which expose a Leanback launcher entry; a full Amazon app such as Prime
        // Video does have one and redirects like any other app).
        //
        // Only a DELIBERATE move through that shell should be left alone, and every such
        // move leaves a visible key behind: Back to leave a settings page, OK to pick an
        // entry in the navigation drawer. A Home press leaves none, because the firmware
        // eats KEYCODE_HOME before any service sees it. So the ABSENCE of a recent OK/Back
        // is what identifies a Home press, and Home has to reach the target from inside
        // Amazon's settings just like it does everywhere else. D-pad keys deliberately do
        // not count: navigating to an entry and then pressing Home is still a Home press.
        if (prev != null && prev.startsWith("com.amazon.") && !isLeanbackLaunchable(prev)
                && isRecentShellNavKey(now)) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "Auto-hijack skipped: deliberate move inside the Amazon shell"
                        + " (prev=" + prev + " key=" + lastKeyCode
                        + " " + (now - lastKeyTime) + "ms ago)");
            }
            return;
        }

        if (now < bypassUntil) return;

        // This window serves two purposes at once, which is why turning the gesture off
        // shortens it instead of removing it:
        //  - technical: one Home press can produce several Amazon-home events, and
        //    without a guard each would launch the target again;
        //  - the feature: a deliberate second press within the full window means
        //    "leave me on Amazon home", i.e. the double-press escape.
        // With the escape off we keep only the short technical guard, so a deliberate
        // second press (well over 300 ms after the first) redirects again instead of
        // stranding the user on Amazon home.
        long debounce = prefs.isEscapeDoublePress()
                ? HIJACK_DEBOUNCE_MS : DUPLICATE_EVENT_GUARD_MS;
        if (now - lastHijackAt < debounce) {
            boolean backSinceHijack = lastKeyCode == KeyEvent.KEYCODE_BACK
                    && lastKeyTime > lastHijackAt;
            if (!backSinceHijack) {
                if (prefs.isVerboseLogging()) {
                    Log.i(TAG, "Auto-hijack skipped: within " + debounce + "ms guard,"
                            + " no Back since last hijack (double-press escape "
                            + (prefs.isEscapeDoublePress() ? "on" : "off") + ")");
                }
                return;
            }
        }
        // lastHijackAt is armed inside launchTarget on success.
        launchTarget(target, "Redirected Home (from " + prev + ")");
    }

    /** Framework/system packages whose windows are transient decor, never a learn target. */
    private boolean isSystemDecorPkg(String pkg) {
        return pkg.equals("android") || pkg.startsWith("com.android.");
    }

    /**
     * True if the current foreground package satisfies a slot's window binding. Matched
     * by PACKAGE, not activity: opening an app produces several activities (e.g. Prime's
     * DeepLinkRouting then Landing, or Amazon's Apps grid showing a bare FrameLayout),
     * so the exact activity seen at learn time may not be the one that appears on a later
     * press. The {@link Prefs#APPS_WINDOW} sentinel is simply the venezia package. The
     * startsWith clause keeps any legacy "package/activity" bindings working.
     */
    private boolean windowMatches(String pkgStr, String clsStr, String binding) {
        if (binding == null || binding.isEmpty() || pkgStr == null) return false;
        if (!(binding.equals(pkgStr) || binding.startsWith(pkgStr + "/"))) return false;
        // The Apps binding covers the whole venezia package, and Amazon's store lives in
        // that same package. Only the grid may fire, see isAppsGridWindow.
        if (AMAZON_APPS_GRID.equals(pkgStr) && !isAppsGridWindow(clsStr)) return false;
        return true;
    }

    /**
     * True for a venezia window that can be the Apps grid. The grid reports either an
     * AppsGrid activity or, on Fire OS 8, nothing but a bare framework container. Every
     * other class in that package is a store screen (app details, categories, deeplinks,
     * settings, install dialogs), and matching those would throw the user into the mapped
     * app while browsing the store.
     *
     * A whitelist on purpose: Amazon adds and renames store screens, and every one they
     * add would otherwise start firing until we notice.
     */
    static boolean isAppsGridWindow(String cls) {
        if (cls == null) return false;
        // "AppsGrid" and not just "Grid": CategoryGridActivity is store browsing.
        return cls.contains("AppsGrid") || cls.startsWith("android.");
    }

    /**
     * A "Map custom buttons" slot bound to an app window (Apps, Live TV, Guide, ...)
     * instead of a keycode: the branded button opened its app, so we redirect to the
     * target launcher. Instant apart from the brief window flash that is unavoidable,
     * since the system opens the branded app before we can react. No mask (this path
     * never arms the app-switch lock).
     */
    private void handleWindowLaunch(final String brandedPkg) {
        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty() || target.equals(getPackageName())) {
            if (prefs.isVerboseLogging()) Log.i(TAG, "windowLaunch skip: no/invalid target");
            return;
        }
        handleWindowLaunch(brandedPkg, target, "Redirect from mapped app button");
    }

    /**
     * @param appToLaunch what the button should open: the target launcher for the fixed
     *   slot, any installed app for a custom mapping.
     */
    private void handleWindowLaunch(final String brandedPkg, final String appToLaunch,
                                    String reason) {
        // Deliberately NOT gated by bypassUntil: a mapped button is an explicit user
        // action and must redirect even right after an escape-to-Amazon-home (which sets
        // the bypass to hold back the AUTO hijack, not explicit presses). The debounce
        // only dedups the several window events a single press emits.
        long now = System.currentTimeMillis();
        if (now - lastHijackAt < HIJACK_DEBOUNCE_MS) {
            if (prefs.isVerboseLogging()) {
                Log.i(TAG, "windowLaunch skip: debounce " + (now - lastHijackAt) + "ms since last");
            }
            return;
        }
        // Leaving the app this button opens drops the user back onto the branded surface
        // that opened it, and redirecting again would lock them inside that app with no
        // way out. The surface only moves the foreground trackers when it reports a real
        // content class, so the app they came from is in one tracker or the other. A fresh
        // Back is required as well, otherwise pressing the button again from inside the
        // app would toggle between the app and the launcher instead of just reopening it.
        String cameFrom = brandedPkg != null && brandedPkg.equals(currentForegroundPkg)
                ? previousForegroundPkg
                : currentForegroundPkg;
        boolean backedOut = lastKeyCode == KeyEvent.KEYCODE_BACK
                && now - lastKeyTime < BACK_OUT_WINDOW_MS;
        if (appToLaunch.equals(cameFrom) && backedOut
                && now - lastWindowLaunchAt >= LAUNCH_CHAIN_MS) {
            String target = prefs.getTargetPackage();
            if (target == null || target.isEmpty()) {
                if (prefs.isVerboseLogging()) {
                    Log.i(TAG, "windowLaunch skip: back out of " + appToLaunch + ", no target set");
                }
                return;
            }
            launchTarget(target, "Back out of " + appToLaunch + " onto " + brandedPkg
                    + ", going to the target", false);
            return;
        }
        // Nothing to re-assert after a launch that never happened (target uninstalled).
        if (!launchTarget(appToLaunch, reason, false)) return;
        lastWindowLaunchAt = now;
        pendingButtonApp = appToLaunch;
        pendingButtonAt = now;
        pendingButtonRelaunches = 0;
        // Some branded apps keep launching activities that re-cover the target right
        // after (e.g. Prime: DeepLinkRouting then Landing). Re-assert the target ONLY if
        // that same branded app is what came back to the front, so we win the launch-chain
        // race without yanking the user back if they deliberately navigated elsewhere in
        // the meantime (or if the redirect was already clean and the target is up).
        if (mainHandler != null && brandedPkg != null) {
            // Drop the previous press's re-asserts: they aim at an older branded package
            // and target, and letting both sets run stacks launches on a slow device.
            if (pendingReassert != null) mainHandler.removeCallbacks(pendingReassert);
            pendingReassert = new Runnable() {
                @Override public void run() {
                    if (brandedPkg.equals(currentForegroundPkg)) {
                        launchTarget(appToLaunch, "Redirect re-assert", false);
                    }
                }
            };
            mainHandler.postDelayed(pendingReassert, 700);
            mainHandler.postDelayed(pendingReassert, 1500);
        }
    }

    /** As {@link #handleWindowLaunch} but the slot's action is "go to Amazon home". */
    private void handleWindowAmazon() {
        long now = System.currentTimeMillis();
        if (now - lastHijackAt < HIJACK_DEBOUNCE_MS) return;
        redirectToAmazonHome("Mapped app button to Amazon home");
    }

    /**
     * The app a custom mapping wants opened for this window, or null if no mapping
     * covers it. Same trigger semantics as the two fixed slots, so a button that opens
     * an Amazon app (Live TV, Prime, ...) can point anywhere the user likes.
     */
    private String customAppForWindow(String pkgStr, String clsStr) {
        for (CustomMap m : prefs.getCustomMaps()) {
            if (m.app.isEmpty() || m.window.isEmpty()) continue;
            // A mapping onto its own app would redirect the app to itself; the config
            // screen refuses to create one, and an imported/edited setting can't either.
            if (m.app.equals(pkgStr)) continue;
            if (windowMatches(pkgStr, clsStr, m.window)) return m.app;
        }
        return null;
    }

    /** The app a custom mapping binds to this key code, or null if none does. */
    private String customAppForKey(int keyCode) {
        for (CustomMap m : prefs.getCustomMaps()) {
            if (m.app.isEmpty() || m.keyCode == Prefs.DEFAULT_LAUNCH_KEYCODE) continue;
            if (m.keyCode == keyCode) return m.app;
        }
        return null;
    }

    /**
     * True when the last key we saw was an OK or a Back within
     * {@link #AMAZON_SHELL_NAV_WINDOW_MS}. Those are the only keys a deliberate move
     * through Amazon's home shell can leave behind (Back to leave a settings page, OK to
     * pick a drawer entry). A Home press leaves none, since the firmware intercepts it,
     * so a {@code false} here means the arrival we are looking at was a Home press.
     */
    private boolean isRecentShellNavKey(long now) {
        boolean isNavKey = lastKeyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || lastKeyCode == KeyEvent.KEYCODE_ENTER
                || lastKeyCode == KeyEvent.KEYCODE_BACK;
        return isNavKey && (now - lastKeyTime) < AMAZON_SHELL_NAV_WINDOW_MS;
    }

    /**
     * True if {@code pkg} exposes a Leanback launcher entry, i.e. it is a user-facing
     * Fire TV app (Prime Video, Netflix, ...). Amazon's home-shell components (the
     * launcher, Settings, Quick Settings) have no Leanback entry, so this tells a content
     * app apart from the home shell without hardcoding package names.
     */
    private boolean isLeanbackLaunchable(String pkg) {
        try {
            return getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
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
    private void redirectToAmazonHome(String reason) {
        bypassUntil = System.currentTimeMillis() + LONGPRESS_REDIRECT_BYPASS_MS;
        pendingHomeTabCapture = true;
        boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
        Log.i(TAG, reason + ". Dispatched GLOBAL_ACTION_HOME ok=" + ok);
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

        // Learn mode: the config screen asked us to capture the next assignable
        // key so the user can bind it as the launch key (same-process handoff).
        // While waiting we also swallow the navigation keys (d-pad, OK, Menu,
        // Home) so the config UI can't move focus or activate anything mid-learn.
        // Back is deliberately left alone so the row's own handler can cancel.
        if (sLearnListener != null && isDown) {
            if (event.getRepeatCount() == 0 && isAssignableKey(keyCode)) {
                final KeyLearnListener learn = sLearnListener;
                sLearnListener = null;
                final int captured = keyCode;
                mainHandler.post(new Runnable() {
                    @Override public void run() { learn.onKeyLearned(captured); }
                });
                consumedDownKey = keyCode;
                return true;
            }
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                consumedDownKey = keyCode; // swallow the paired UP as well
                return true;
            }
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

        // Custom launch key: a user-assigned button opens the target directly.
        // It never routes through Home, so no app-switch lock and no FOS7 mask.
        // Re-assignment uses OK on the config box (handled by the learn hook
        // above, which swallows keys), so the bound key does not need to be
        // suppressed on our own screen; it just launches everywhere.
        int launchKey = prefs.getLaunchKeycode();
        if (prefs.isLaunchKeyEnabled()
                && launchKey != Prefs.DEFAULT_LAUNCH_KEYCODE && keyCode == launchKey
                && event.getRepeatCount() == 0) {
            String launchKeyTarget = prefs.getTargetPackage();
            if (launchKeyTarget != null && !launchKeyTarget.isEmpty()
                    && !launchKeyTarget.equals(getPackageName())) {
                launchTarget(launchKeyTarget, "Launch key " + keyCode, false);
                consumedDownKey = keyCode;
                return true;
            }
        }

        // Amazon-home key: reaches the stock Amazon launcher, arming the bypass
        // so our own Home redirect doesn't send it straight back to the target.
        int amazonKey = prefs.getAmazonKeycode();
        if (prefs.isLaunchKeyEnabled()
                && amazonKey != Prefs.DEFAULT_LAUNCH_KEYCODE && keyCode == amazonKey
                && event.getRepeatCount() == 0) {
            redirectToAmazonHome("Amazon key " + keyCode);
            consumedDownKey = keyCode;
            return true;
        }

        // Custom mappings: the same instant path, but each one opens its own app.
        // Swallowed only when the app really started, so a key bound to a since-removed
        // app keeps working as whatever the system makes of it.
        if (prefs.isLaunchKeyEnabled() && event.getRepeatCount() == 0) {
            String customApp = customAppForKey(keyCode);
            if (customApp != null
                    && launchTarget(customApp, "Custom key " + keyCode, false)) {
                consumedDownKey = keyCode;
                return true;
            }
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
     * canceled when Menu UPs before the threshold passes.
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
        return launchTarget(target, reasonForLog, true);
    }

    /**
     * @param withMask whether to arm the Fire OS 7 masking overlay. The instant
     *   shortcuts (custom launch key, Apps-button redirect) pass {@code false}:
     *   they never route through Home, so no app-switch lock is armed and there
     *   is no delay to cover.
     */
    private boolean launchTarget(String target, String reasonForLog, boolean withMask) {
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
            if (withMask) armMask(target);
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
                    // Skip the long-press panel itself; we want the app underneath it.
                    // Matched by PACKAGE on purpose: a window root node reports a View
                    // class (android.widget.FrameLayout, ...), never the activity, so an
                    // activity-level test could never match here. Skipping all of
                    // com.amazon.tv.settings.v2 also means a long-press while genuinely
                    // inside Settings falls back to the app before it, which is the same
                    // treatment the Fire OS 8 panel has always had.
                    if (QUICKSETTINGS_PKG.equals(pkgStr) || AMAZON_SETTINGS_PKG.equals(pkgStr)) {
                        continue;
                    }
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
     * True for keys the user may bind as a launch key. Excludes the navigation
     * and system keys the config screen needs (d-pad, OK/Enter, Back, Menu,
     * Home) so it stays operable while learning; everything else (color,
     * number, media, captions, teletext, ...) is fair game.
     */
    private static boolean isAssignableKey(int keyCode) {
        return !isDpadNavKey(keyCode)
                && keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                && keyCode != KeyEvent.KEYCODE_ENTER
                && keyCode != KeyEvent.KEYCODE_BACK
                && keyCode != KeyEvent.KEYCODE_MENU
                && keyCode != KeyEvent.KEYCODE_HOME
                && keyCode != KeyEvent.KEYCODE_UNKNOWN;
    }

    /** Config screen: capture the next assignable key press, then report it. */
    public static void startLearning(KeyLearnListener listener) {
        sLearnListener = listener;
    }

    /** Config screen: stop waiting for a key (on pause or cancel). */
    public static void stopLearning() {
        sLearnListener = null;
    }

    /** Config screen: capture the next foreground app window (a branded button press). */
    public static void startWindowLearning(WindowLearnListener listener) {
        sWindowLearnListener = listener;
    }

    /** Config screen: stop waiting for a branded-button window (on pause or cancel). */
    public static void stopWindowLearning() {
        sWindowLearnListener = null;
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
        cancelMask();
        if (screenOnReceiver != null) {
            try {
                unregisterReceiver(screenOnReceiver);
            } catch (Exception ignored) {
                // already unregistered, or never registered on this run
            }
            screenOnReceiver = null;
        }
        if (sInstance == this) sInstance = null;
        return super.onUnbind(intent);
    }

    /**
     * Brings the config activity back to the front after a learned app-button opened its
     * app. Called by the config screen once it has captured a window. Runs from the
     * service (which outlives the Activity and is BAL-exempt while bound), and fires
     * twice with a delay so it wins against the app's own multi-activity launch chain
     * (e.g. Prime's DeepLinkRouting then Landing) that would otherwise re-cover us.
     */
    public static void bringConfigToFrontDelayed() {
        final HijackService svc = sInstance;
        if (svc == null || svc.mainHandler == null) return;
        final Runnable bring = new Runnable() {
            @Override public void run() {
                try {
                    svc.startActivity(new Intent(svc, MainActivity.class).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                } catch (Exception ignored) { /* activity gone; nothing to do */ }
            }
        };
        svc.mainHandler.postDelayed(bring, 700);
        svc.mainHandler.postDelayed(bring, 1500);
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

    // FOS7 5-second-delay masking overlay.
    //
    // On Android 9 (Fire OS 6/7) a background activity start issued after HOME
    // is deferred by ActivityManager's app-switch lock (APP_SWITCH_DELAY_TIME =
    // 5s), so the target launcher only appears ~5s after Amazon's home flashes.
    // That delay cannot be removed without root, so we MASK it: a full-screen
    // TYPE_ACCESSIBILITY_OVERLAY (a window add, which is NOT subject to the
    // app-switch lock) covers the gap; once the target window actually appears
    // it stays opaque for a short hold (so the launcher can finish painting)
    // and then cross-fades out.
    //
    // It is self-calibrating, not an assumption: the overlay is only shown if
    // the target has not become foreground within a short grace window (so
    // prompt launches, e.g. Fire OS 8 where there is no delay, never flash it),
    // and it is removed on the real target window-state event (faded, because
    // that event can precede the first painted frame, and a same-frame removal
    // would briefly reveal a blank frame). A hard timeout plus onInterrupt and
    // onUnbind are safety nets so the overlay can never strand on screen. Gated
    // to SDK_INT <= 28: Android 10+ (Fire OS 8) has no such delay.

    /**
     * True where the Android app-switch delay exists: API &lt;= 28, i.e. Fire OS 7 and
     * older. Fire OS 8 (API 30) has no such delay and is excluded. Kept at &lt;= 28 rather
     * than == 28 in case some Fire OS 6 variant runs the app, but note that Home on Fire
     * likely does not work on Fire OS 6 at all for unrelated reasons (accessibility
     * restrictions), so in practice this gates the masking to Fire OS 7.
     */
    private static final boolean MASK_SUPPORTED = Build.VERSION.SDK_INT <= 28;
    // Start-cover grace, end-hold and cross-fade duration are tunable at runtime via
    // Prefs (the hidden overlay-timing rows): prefs.getMaskGraceMs() / getMaskHoldMs() /
    // getMaskFadeMs(); the defaults live in Prefs.DEFAULT_MASK_GRACE / HOLD / FADE (kept
    // there only, so this comment cannot drift). The grace is read live so a tester can
    // probe the start flash; the hold gives the launcher time to paint before the fade,
    // closing the end flash.
    /**
     * Safety net: force-remove the overlay if the target window never announces itself.
     * The app-switch lock alone holds a launch for 5 s and a cold start of the launcher
     * comes on top. A shorter timeout removes the overlay while the launch is still
     * queued, and Amazon's home stays visible until the launcher starts.
     */
    private static final long MASK_HARD_TIMEOUT_MS = 10_000L;
    /** Delay after the service connects before prewarming the overlay path. */
    private static final long MASK_PREWARM_DELAY_MS = 600L;
    /** How long the invisible prewarm overlay stays up (long enough to render a few frames). */
    private static final long MASK_PREWARM_MS = 160L;
    /** Cap on waiting for the target's real content window before lifting the mask anyway. */
    private static final long MASK_TEARDOWN_CAP_MS = 800L;

    private WindowManager windowManager;
    private LoaderView maskView;
    private boolean maskAttached = false;
    private volatile boolean maskArmed = false;
    private volatile String maskTargetPkg = null;
    /** When the launch behind the current mask was issued, for the timings in the log. */
    private volatile long maskArmedAt = 0L;
    private Runnable maskGraceRunnable = null;
    private Runnable maskTimeoutRunnable = null;
    /** Throwaway overlay added once at service start to warm the render path. */
    private LoaderView maskPrewarmView;
    private Runnable maskPrewarmRemove = null;
    /** Set after the first target event arrives while the mask is up, until the real content window confirms the lift. */
    private volatile boolean maskTeardownPending = false;
    private Runnable maskTeardownCapRunnable = null;
    /** Pending opaque-hold between the teardown trigger and the cross-fade. */
    private Runnable maskHoldRunnable = null;

    /**
     * Arms the masking overlay for a freshly issued redirect (called from
     * {@link #launchTarget} after a successful start). No-op on Fire OS 8+.
     * Resets any in-flight mask first so overlapping redirects cannot stack
     * overlays.
     */
    private void armMask(final String target) {
        if (!MASK_SUPPORTED) return;
        if (target == null || target.isEmpty()) return;
        cancelMask();
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        maskArmed = true;
        maskTargetPkg = target;
        maskArmedAt = System.currentTimeMillis();
        maskGraceRunnable = new Runnable() {
            @Override
            public void run() {
                maskGraceRunnable = null;
                // Target still not foreground after the grace window: the launch
                // is being deferred (the Android 9 case), so cover the gap.
                if (maskArmed && !maskAttached && !target.equals(currentForegroundPkg)) {
                    showMask();
                }
            }
        };
        mainHandler.postDelayed(maskGraceRunnable, prefs.getMaskGraceMs());
    }

    /**
     * Drives the mask teardown from the target's window-state events. The first target
     * event cancels a pending grace-show (and, if the mask was never shown, finishes).
     * If the mask is up, we do NOT lift it on that first event, because it can be a
     * transitional decor/FrameLayout window that fires up to ~0.5s before the launcher's
     * real content. Instead we wait for an event from the target's own (app-specific)
     * content window, with {@link #MASK_TEARDOWN_CAP_MS} as a safety cap, then cross-fade.
     */
    private void onMaskTargetEvent(CharSequence cls) {
        if (maskArmed) {
            // The one line that says how long Fire OS held our launch back. Without it a
            // report only shows that the target came up eventually, not how late.
            Log.i(TAG, "Target " + maskTargetPkg + " appeared " + sinceMaskArmed()
                    + "ms after the launch, cls=" + cls);
            maskArmed = false;
            if (maskGraceRunnable != null && mainHandler != null) {
                mainHandler.removeCallbacks(maskGraceRunnable);
                maskGraceRunnable = null;
            }
            if (!maskAttached) {
                // Fast launch: the target came up before the grace elapsed, so the mask
                // was never shown. Nothing to tear down.
                maskTargetPkg = null;
                return;
            }
            maskTeardownPending = true;
            scheduleTeardownCap();
        }
        if (maskTeardownPending && isRealContentClass(cls)) {
            holdThenFadeMask();
        }
    }

    /**
     * True for a window-state event whose class is app-specific rather than a generic
     * {@code android.*} container. The target's transitional window reports e.g.
     * {@code android.widget.FrameLayout}; its real content window reports its Activity class.
     */
    private static boolean isRealContentClass(CharSequence cls) {
        return cls != null && !cls.toString().startsWith("android.");
    }

    /** Forces the fade once the cap elapses, in case the target never emits a non-android window. */
    private void scheduleTeardownCap() {
        cancelTeardownCap();
        if (mainHandler == null) return;
        maskTeardownCapRunnable = new Runnable() {
            @Override
            public void run() {
                maskTeardownCapRunnable = null;
                if (maskTeardownPending) {
                    Log.i(TAG, "Mask teardown cap reached, fading without a confirmed content window");
                    holdThenFadeMask();
                }
            }
        };
        mainHandler.postDelayed(maskTeardownCapRunnable, MASK_TEARDOWN_CAP_MS);
    }

    private void cancelTeardownCap() {
        if (maskTeardownCapRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(maskTeardownCapRunnable);
            maskTeardownCapRunnable = null;
        }
    }

    /**
     * Adds the full-screen loading overlay. The view paints an opaque background
     * so it fully hides what is behind while shown; the window format is
     * translucent so the fade-out can cross-dissolve to the launcher underneath.
     */
    private void showMask() {
        if (maskAttached) return;
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) return;
        }
        removePrewarm();
        try {
            LoaderView view = new LoaderView(this);
            // The launcher caption only fits the launcher; a relaunched button app gets a
            // neutral one.
            boolean forLauncher = maskTargetPkg != null
                    && maskTargetPkg.equals(prefs.getTargetPackage());
            view.setCaptions(getString(forLauncher
                            ? R.string.loader_caption_loading : R.string.loader_caption_loading_app),
                    getString(R.string.loader_caption_ready));
            view.setLooping(false); // one-shot: play once, hold on the "Ready" frame
            view.setBlackScreen(prefs.isMaskBlackScreen()); // plain black instead of the animation
            windowManager.addView(view, maskLayoutParams());
            maskView = view;
            maskAttached = true;
            scheduleMaskTimeout();
            Log.i(TAG, "Mask shown for deferred launch of " + maskTargetPkg);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show mask", e);
            maskView = null;
            maskAttached = false;
        }
    }

    /** Shared overlay LayoutParams for the real mask and the prewarm. */
    private WindowManager.LayoutParams maskLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    /**
     * Schedules a one-time, invisible run of the overlay path shortly after the service
     * connects. The first real mask otherwise pays a cold-start cost (class load, shader
     * build, first GPU draw) that shows up as a ~0.5s Amazon-home flash on the first Home
     * press; warming the path here makes the first mask render as fast as later ones.
     */
    private void schedulePrewarm() {
        if (!MASK_SUPPORTED || mainHandler == null) return;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                prewarmMask();
            }
        }, MASK_PREWARM_DELAY_MS);
    }

    /**
     * Briefly adds a 1x1 loader overlay to warm the render path (gradient-shader compilation and
     * the first hardware-overlay composite, which are the process-global costs behind the
     * first-press flash), then removes it. Drawn at full alpha in a corner rather than transparent
     * or off-screen, because the renderer can skip a non-visible window entirely; 1px keeps it
     * imperceptible while still forcing a real draw.
     */
    private void prewarmMask() {
        if (!MASK_SUPPORTED || maskAttached || maskPrewarmView != null) return;
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) return;
        }
        try {
            LoaderView warm = new LoaderView(this);
            warm.setCaptions(getString(R.string.loader_caption_loading),
                    getString(R.string.loader_caption_ready));
            warm.setLooping(false);
            WindowManager.LayoutParams lp = maskLayoutParams();
            lp.width = 1;
            lp.height = 1;
            windowManager.addView(warm, lp);
            maskPrewarmView = warm;
            maskPrewarmRemove = new Runnable() {
                @Override
                public void run() {
                    maskPrewarmRemove = null;
                    removePrewarm();
                }
            };
            mainHandler.postDelayed(maskPrewarmRemove, MASK_PREWARM_MS);
            Log.i(TAG, "Mask path prewarmed");
        } catch (Exception e) {
            Log.e(TAG, "Mask prewarm failed", e);
            maskPrewarmView = null;
        }
    }

    /** Removes the invisible prewarm overlay if present. */
    private void removePrewarm() {
        if (maskPrewarmRemove != null && mainHandler != null) {
            mainHandler.removeCallbacks(maskPrewarmRemove);
            maskPrewarmRemove = null;
        }
        if (maskPrewarmView != null) {
            try {
                if (windowManager != null) windowManager.removeView(maskPrewarmView);
            } catch (Exception ignored) {
            }
            maskPrewarmView.stop();
            maskPrewarmView = null;
        }
    }

    /**
     * Keeps the overlay fully opaque for the configured End-hold after the teardown
     * trigger, then cross-fades. The window-state event that triggers teardown can
     * precede the launcher's first painted frame; holding opaque for that span means
     * the fade reveals the launcher rather than the Amazon home still behind the
     * overlay. Reached only after the target has appeared (real content event or the
     * teardown cap), so the hard timeout is repurposed here as a teardown backstop.
     */
    private void holdThenFadeMask() {
        maskTeardownPending = false;
        cancelTeardownCap();
        cancelMaskHold();
        if (!maskAttached || maskView == null) {
            removeMask();
            return;
        }
        long hold = prefs.getMaskHoldMs();
        final long fade = prefs.getMaskFadeMs();
        // Backstop: guarantee the overlay is gone within the hold + fade span even if a
        // dropped animation callback would otherwise leave it up. The fade duration is
        // read once here and passed through so backstop and animation always agree.
        rescheduleMaskTimeout(hold + fade + 400L,
                "Mask teardown backstop reached, removing");
        if (hold <= 0L || mainHandler == null) {
            fadeMask(fade);
            return;
        }
        maskHoldRunnable = new Runnable() {
            @Override
            public void run() {
                maskHoldRunnable = null;
                fadeMask(fade);
            }
        };
        mainHandler.postDelayed(maskHoldRunnable, hold);
    }

    /** Cross-fades the overlay out uniformly (revealing the launcher underneath), then detaches it. */
    private void fadeMask(long fadeMs) {
        if (!maskAttached || maskView == null) {
            removeMask();
            return;
        }
        maskView.animate().alpha(0f).setDuration(fadeMs)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        removeMask();
                    }
                }).start();
    }

    /** Cancels a pending opaque-hold scheduled before the fade. */
    private void cancelMaskHold() {
        if (maskHoldRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(maskHoldRunnable);
            maskHoldRunnable = null;
        }
    }

    /** Idempotent teardown: cancels the timeout, detaches the view, resets state. */
    private void removeMask() {
        cancelMaskTimeout();
        cancelTeardownCap();
        cancelMaskHold();
        maskTeardownPending = false;
        if (maskView != null) {
            try {
                maskView.animate().cancel();
            } catch (Exception ignored) {
            }
            try {
                if (maskAttached && windowManager != null) {
                    windowManager.removeView(maskView);
                }
            } catch (Exception ignored) {
                // already detached / never attached
            }
            maskView.stop();
            maskView = null;
        }
        maskAttached = false;
        maskArmed = false;
        maskTargetPkg = null;
    }

    /** Cancels a pending grace show and removes any shown overlay. */
    private void cancelMask() {
        if (maskGraceRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(maskGraceRunnable);
            maskGraceRunnable = null;
        }
        removePrewarm();
        removeMask();
    }

    /** Arms the initial hard timeout for a freshly shown mask (target never appears). */
    private void scheduleMaskTimeout() {
        rescheduleMaskTimeout(MASK_HARD_TIMEOUT_MS,
                "Mask hard-timeout: target window never appeared, removing");
    }

    private void cancelMaskTimeout() {
        if (maskTimeoutRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(maskTimeoutRunnable);
            maskTimeoutRunnable = null;
        }
    }

    /**
     * (Re-)arms the mask removal backstop. Single builder for both the initial
     * hard timeout and the teardown backstop, so a future fix to the runnable
     * cannot be applied to one copy and missed in the other.
     */
    private void rescheduleMaskTimeout(long delayMs, final String logMsg) {
        cancelMaskTimeout();
        if (mainHandler == null) return;
        maskTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                maskTimeoutRunnable = null;
                if (maskAttached) {
                    // With the state appended, a single log tells a launch that was only
                    // slow from one that never arrived, and names what took the screen.
                    Log.i(TAG, logMsg + " (foreground=" + currentForegroundPkg
                            + ", " + sinceMaskArmed() + "ms since the launch)");
                    removeMask();
                }
            }
        };
        mainHandler.postDelayed(maskTimeoutRunnable, delayMs);
    }

    /** Milliseconds since the launch this mask belongs to, or -1 if none is on record. */
    private long sinceMaskArmed() {
        return maskArmedAt > 0L ? System.currentTimeMillis() - maskArmedAt : -1L;
    }

    @Override
    public void onInterrupt() {
        // Tear down any masking overlay so a system interrupt can never strand
        // it on screen.
        removeMask();
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
