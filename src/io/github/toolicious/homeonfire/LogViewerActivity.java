// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Full-screen diagnostic screen, reachable from the round button in the
 * "Verbose logging" row of {@link MainActivity} (which only appears while
 * verbose logging is on). OK or Back closes it.
 *
 * It shows the current runtime state first (Fire OS / Android version,
 * whether our accessibility service is actually <em>bound</em>, the chosen
 * target, granted permissions, feature toggles) and then a best-effort
 * tail of this app's own logcat output. The point is that a tester can
 * photograph one screen with their phone instead of setting up adb: a
 * single picture already answers most "is it working, and if not why"
 * questions. Nothing leaves the device; reading our own log via logcat
 * needs no extra permission and only ever returns this app's own lines.
 *
 * The screen is deliberately read-only and built fully programmatically,
 * matching the rest of the app (no XML layouts, no AppCompat).
 */
@SuppressWarnings("deprecation")
public class LogViewerActivity extends Activity {

    private static final int OK_GREEN = 0xFF4CAF50;
    private static final int BAD_RED  = 0xFFFF6B6B;
    private static final int DIM      = 0xFF9AA0A6;
    private static final int BG       = 0xFF121212;
    private static final int CARD_BG  = 0xFF1E1E1E;
    private static final int LOG_FG   = 0xFFCAD2DA;

    private View okButton;
    /** Whether our accessibility service is actually bound; drives both the status row and the empty-log message. */
    private boolean serviceBound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serviceBound = isServiceBound();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(18), dp(28), dp(24));

        content.addView(buildStatusCard());
        content.addView(buildLogSection());

        scroll.addView(content);
        // ScrollView takes the remaining height so the OK bar stays pinned.
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildBottomBar());

        setContentView(root);
        if (okButton != null) okButton.requestFocus();
    }

    /** Brand-colored title bar with the screen name and the app version. */
    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Colors.BRAND);
        bar.setPadding(dp(24), dp(12), dp(24), dp(12));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.log_viewer_title);
        title.setTextSize(24);
        title.setTextColor(Colors.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ver = new TextView(this);
        ver.setText(appVersion());
        ver.setTextSize(14);
        ver.setTextColor(0xFFF1C9C9);
        bar.addView(ver);
        return bar;
    }

    /** Status block: one label/value row per diagnostic fact. */
    private LinearLayout buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD_BG);
        card.setPadding(dp(20), dp(10), dp(20), dp(10));

        Prefs prefs = new Prefs(this);

        addRow(card, "Fire OS", fireOsVersion(), Colors.WHITE);
        addRow(card, "Android", Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")",
                Colors.WHITE);
        addRow(card, "Device", Build.MODEL + " / " + Build.DEVICE, Colors.WHITE);

        boolean bound = serviceBound;
        addRow(card, "Accessibility", bound ? "BOUND" : "NOT BOUND",
                bound ? OK_GREEN : BAD_RED);

        // Setting vs. actually-bound: a mismatch (set but not bound) is the
        // signature of a firmware/profile-owner lockdown, so flag it red.
        boolean inSettings = AccessibilityHelper.isOurServiceEnabled(this);
        addRow(card, "In settings", inSettings ? "enabled" : "off",
                (inSettings == bound) ? DIM : BAD_RED);

        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) {
            addRow(card, "Target", "(none set)", BAD_RED);
        } else {
            boolean installed = isInstalled(target);
            addRow(card, "Target", target + (installed ? "  ✓" : "  (not installed)"),
                    installed ? Colors.WHITE : BAD_RED);
        }

        boolean wss = AccessibilityHelper.hasWriteSecureSettings(this);
        addRow(card, "Write secure settings", wss ? "granted" : "not granted",
                wss ? OK_GREEN : DIM);
        addRow(card, "Replace Home", prefs.isHijackEnabled() ? "on" : "off", DIM);
        addRow(card, "Launch on boot", prefs.getLaunchOnBoot() ? "on" : "off", DIM);

        return card;
    }

    /**
     * Heading + the log box. Real log lines are shown verbatim in
     * monospace. When there are none, a context-aware explanation takes
     * their place (in normal type for readability): if the service is
     * bound there simply are no events yet; if it is not bound, this Fire
     * OS build blocks it, so there is nothing to record and nothing to fix.
     */
    private LinearLayout buildLogSection() {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setPadding(0, dp(18), 0, 0);

        TextView heading = new TextView(this);
        heading.setText("Recent log (tag HomeOnFire)");
        heading.setTextSize(15);
        heading.setTextColor(DIM);
        heading.setPadding(dp(2), 0, 0, dp(8));
        sec.addView(heading);

        TextView log = new TextView(this);
        log.setTextSize(13);
        log.setBackgroundColor(CARD_BG);
        log.setPadding(dp(16), dp(14), dp(16), dp(14));

        String tail = readOwnLog();
        if (!tail.isEmpty()) {
            log.setTypeface(Typeface.MONOSPACE);
            log.setTextColor(LOG_FG);
            log.setText(tail);
        } else {
            log.setTextColor(DIM);
            log.setLineSpacing(dp(2), 1f);
            log.setText(serviceBound
                    ? "No events captured yet. Press Home or open the target app, then reopen this screen."
                    : "This Fire OS build blocks third-party accessibility services (shown as \"NOT BOUND\" above), so Home on Fire's Home-button redirect never starts and has nothing to record. This is an Amazon firmware restriction, not a missing setting or a bug, so there is nothing to enable or fix here.");
        }
        sec.addView(log);
        return sec;
    }

    /** Bottom bar: hint on the left, focusable OK button on the right. */
    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Colors.TIP_BG);
        bar.setPadding(dp(24), dp(10), dp(24), dp(10));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView hint = new TextView(this);
        hint.setText("Press ◀ Back or OK to close");
        hint.setTextSize(14);
        hint.setTextColor(Colors.NEUTRAL);
        bar.addView(hint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ok = new TextView(this);
        ok.setText(R.string.info_dialog_ok);
        ok.setTextSize(17);
        ok.setGravity(Gravity.CENTER);
        ok.setTextColor(Colors.WHITE);
        ok.setBackground(getDrawable(R.drawable.row_focus_bg));
        ok.setFocusable(true);
        ok.setClickable(true);
        ok.setPadding(dp(30), dp(8), dp(30), dp(8));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(ok);
        okButton = ok;
        return bar;
    }

    /**
     * Adds a tightly-spaced "label   value" row, the value tinted with
     * {@code valueColor}. Vertical padding is kept small and the default
     * font padding is removed so the status block reads as a compact table
     * rather than a loosely-spaced list.
     */
    private void addRow(LinearLayout parent, String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(2));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(15);
        l.setTextColor(DIM);
        l.setIncludeFontPadding(false);
        row.addView(l, new LinearLayout.LayoutParams(
                dp(190), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(15);
        v.setTextColor(valueColor);
        v.setIncludeFontPadding(false);
        row.addView(v, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    /** True if our HijackService is in the list of currently bound services. */
    private boolean isServiceBound() {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            for (AccessibilityServiceInfo si : list) {
                String id = si.getId();
                if (id != null && id.startsWith(getPackageName())) return true;
            }
        } catch (Exception ignored) {
            // Treat any lookup failure as "not bound".
        }
        return false;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String appVersion() {
        return "v" + AppInfo.versionName(this);
    }

    private String fireOsVersion() {
        return AppInfo.fireOsVersion();
    }

    /** How many of our own log lines the viewer keeps, newest last. */
    private static final int MAX_LOG_LINES = 400;

    /**
     * Best-effort tail of our own logcat (own-UID lines only, no permission
     * needed). The "--------- beginning of system/main" lines logcat prints
     * at each buffer boundary are stripped, so a build where the service has
     * never produced a line shows as genuinely empty rather than as a lone
     * marker.
     *
     * No -t: logcat takes its tail count off the whole device log before the
     * filter runs, so on a chatty device the last 400 lines hold a handful of
     * ours and the part worth reading is already gone. Dump everything we wrote
     * and cut it down here.
     */
    private String readOwnLog() {
        String raw = AppInfo.runCmd(new String[]{
                "logcat", "-d", "-v", "time", "HomeOnFire:V", "*:S"});
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.trim().startsWith("---------")) continue;
            lines.add(line);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - MAX_LOG_LINES); i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
