// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Configuration UI of Home on Fire.
 *
 * Layout:
 *  - Brand-coloured top bar with the app logo, name and a round info
 *    button on the right that opens a modal info dialog.
 *  - Scrollable content area with one row per setting (target app,
 *    accessibility, Replace Home, launch on boot, Menu long-press,
 *    verbose logging). Status rows use a SpannableString so only the
 *    part after the colon is tinted (green=ok, red=problem).
 *  - Two action buttons at the bottom: open the chosen target now, and
 *    a shortcut into the Fire OS device-info page (which gives access
 *    to all other system settings without going through the Amazon
 *    launcher gear).
 *  - Pinned tip box at the bottom that updates whenever a focusable
 *    control gains input focus, giving d-pad users a per-control
 *    explanation without needing a mouse-hover tooltip.
 *
 * The class-level {@code @SuppressWarnings("deprecation")} silences the
 * lint warning about {@link Switch}, which was deprecated in API 30 in
 * favour of SwitchCompat / MaterialSwitch from AppCompat. AppCompat is
 * intentionally not pulled in (no XML layouts, fully programmatic UI),
 * so plain Switch is the right widget here.
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    private TextView targetView;
    private ImageView targetIconView;
    /** Placeholder shown in place of the app icon when no target is set. */
    private TextView targetEmojiView;
    /** Round launch button on the right of the target row. */
    private TextView launchBtn;
    private Switch accessSwitch, hijackSwitch, bootSwitch, verboseSwitch, menuLpSwitch;

    /** Tip box at the bottom that shows the description of the focused option. */
    private TextView tipView;

    /** First focusable view; receives focus on startup so the tip box is non-empty. */
    private View initialFocus;

    /**
     * Flag flipped during programmatic switch updates (e.g. in
     * {@link #refresh}) so the onCheckedChange listeners don't think
     * the user toggled them and write the value back to prefs in a
     * loop.
     */
    private boolean suppressSwitchEvents = false;

    /** Single Prefs wrapper used by all callbacks; cheaper than rebuilding it per click. */
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(32), dp(20), dp(32), dp(24));

        content.addView(buildTargetRow(), targetRowLp());
        addSwitches(content);

        scroll.addView(content);
        // ScrollView takes remaining vertical space (weight = 1) so
        // the tip box at the bottom stays pinned regardless of scrolling.
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildTipBox());

        setContentView(root);

        // Focus the first option so the tip box always has content
        // on startup; the attachTip focus-listener will populate it.
        if (initialFocus != null) initialFocus.requestFocus();

        refresh();
    }

    private LinearLayout.LayoutParams targetRowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        return lp;
    }

    /**
     * Target row: chip with app icon (or emoji placeholder) + name on
     * the left, round launch button on the right. The chip IS the
     * focus target: d-pad lands on it as a single element wrapping
     * icon + text. Long-press OK on the chip opens the system "App
     * info" page for the target.
     */
    private LinearLayout buildTargetRow() {
        LinearLayout targetRow = new LinearLayout(this);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        targetRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout pickerArea = buildPickerChip();
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        targetRow.addView(pickerArea, paLp);

        launchBtn = buildLaunchButton();
        int launchSize = dp(48);
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(launchSize, launchSize);
        launchLp.leftMargin = dp(12);
        targetRow.addView(launchBtn, launchLp);
        return targetRow;
    }

    private LinearLayout buildPickerChip() {
        LinearLayout pickerArea = new LinearLayout(this);
        pickerArea.setOrientation(LinearLayout.HORIZONTAL);
        pickerArea.setGravity(Gravity.CENTER_VERTICAL);
        pickerArea.setPadding(dp(12), dp(6), dp(12), dp(6));
        pickerArea.setFocusable(true);
        pickerArea.setClickable(true);
        pickerArea.setBackground(getDrawable(R.drawable.target_chip_bg));
        pickerArea.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TargetPickerActivity.class));
            }
        });
        pickerArea.setOnKeyListener(new PickerLongPressListener());
        attachTip(pickerArea, getString(R.string.target_picker_tip));
        initialFocus = pickerArea;

        // Emoji placeholder shown when no target is picked yet.
        targetEmojiView = new TextView(this);
        targetEmojiView.setText(R.string.target_picker_emoji);
        targetEmojiView.setTextSize(22);
        targetEmojiView.setGravity(Gravity.CENTER);
        targetEmojiView.setContentDescription(getString(R.string.target_picker_emoji_desc));
        LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        emLp.rightMargin = dp(12);
        emLp.gravity = Gravity.CENTER_VERTICAL;
        pickerArea.addView(targetEmojiView, emLp);

        // Real app icon shown once a target is selected (refresh swaps
        // visibility between this and targetEmojiView).
        targetIconView = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconLp.rightMargin = dp(12);
        iconLp.gravity = Gravity.CENTER_VERTICAL;
        pickerArea.addView(targetIconView, iconLp);

        targetView = new TextView(this);
        targetView.setTextSize(18);
        targetView.setTextColor(Colors.WHITE);
        LinearLayout.LayoutParams targetTvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        targetTvLp.gravity = Gravity.CENTER_VERTICAL;
        pickerArea.addView(targetView, targetTvLp);
        return pickerArea;
    }

    /**
     * Long-press OK on the picker chip opens the target's system
     * "App info" page. Handled via OnKeyListener (not
     * OnLongClickListener) so we can mark the long-press on the
     * framework's FLAG_LONG_PRESS DOWN and consume the matching UP , 
     * otherwise that UP would activate the default-focused item on
     * the freshly-foregrounded app-info screen.
     */
    private class PickerLongPressListener implements View.OnKeyListener {
        private boolean longPressTriggered = false;

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            boolean isOkKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (!isOkKey) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) longPressTriggered = false;
                if (event.isLongPress()) longPressTriggered = true;
                return false;
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && longPressTriggered) {
                longPressTriggered = false;
                openTargetAppInfo();
                return true;
            }
            return false;
        }
    }

    /**
     * Round launch button on the right of the target row. Reuses the
     * header info-button drawable + text-color selector for visual
     * parity (translucent white disc, white-on-focus, inverted text).
     */
    private TextView buildLaunchButton() {
        TextView btn = new TextView(this);
        btn.setText(R.string.launch_button_glyph);
        btn.setTextSize(18);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(getResources().getColorStateList(R.color.info_button_text));
        btn.setBackground(getDrawable(R.drawable.info_button_bg));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setContentDescription(getString(R.string.launch_button_desc));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchTarget();
            }
        });
        attachTip(btn, getString(R.string.launch_button_tip));
        return btn;
    }

    /** Adds all switch rows in display order. */
    private void addSwitches(LinearLayout content) {
        accessSwitch = makeSwitchRow(content,
                getString(R.string.switch_accessibility),
                getString(R.string.switch_accessibility_tip),
                AccessibilityHelper.isOurServiceEnabled(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                        if (suppressSwitchEvents) return;
                        boolean ok = isChecked
                                ? AccessibilityHelper.enableOurService(MainActivity.this)
                                : AccessibilityHelper.disableOurService(MainActivity.this);
                        if (!ok) {
                            suppressSwitchEvents = true;
                            b.setChecked(!isChecked);
                            suppressSwitchEvents = false;
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.toast_grant_via_adb),
                                    Toast.LENGTH_LONG).show();
                        }
                        updateDependentSwitches();
                    }
                });
        hijackSwitch = makeSwitchRow(content,
                badgeKeys(getString(R.string.switch_hijack)),
                getString(R.string.switch_hijack_tip),
                prefs.isHijackEnabled(),
                prefToggle(new PrefSetter() {
                    @Override public void set(boolean v) { prefs.setHijackEnabled(v); }
                }));
        bootSwitch = makeSwitchRow(content,
                getString(R.string.switch_boot),
                getString(R.string.switch_boot_tip),
                prefs.getLaunchOnBoot(),
                prefToggle(new PrefSetter() {
                    @Override public void set(boolean v) { prefs.setLaunchOnBoot(v); }
                }));
        menuLpSwitch = makeSwitchRow(content,
                badgeKeys(getString(R.string.switch_menu_lp)),
                getString(R.string.switch_menu_lp_tip),
                prefs.isMenuLongPressLaunch(),
                prefToggle(new PrefSetter() {
                    @Override public void set(boolean v) { prefs.setMenuLongPressLaunch(v); }
                }));
        verboseSwitch = makeSwitchRow(content,
                getString(R.string.switch_verbose),
                getString(R.string.switch_verbose_tip),
                prefs.isVerboseLogging(),
                prefToggle(new PrefSetter() {
                    @Override public void set(boolean v) { prefs.setVerboseLogging(v); }
                }));
    }

    /**
     * Builds a Switch listener that writes the new value to prefs via
     * the given setter, skipping when {@link #suppressSwitchEvents} is
     * set (used by {@link #refresh} to update widgets programmatically
     * without re-triggering the listener).
     */
    private CompoundButton.OnCheckedChangeListener prefToggle(final PrefSetter setter) {
        return new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (suppressSwitchEvents) return;
                setter.set(isChecked);
            }
        };
    }

    /** Functional-interface stand-in (we target a Java level without lambdas). */
    private interface PrefSetter {
        void set(boolean value);
    }

    /**
     * Pinned tip box at the bottom of the screen. Shows the description
     * of the currently focused option, populated by {@link #attachTip}.
     */
    private LinearLayout buildTipBox() {
        LinearLayout tipBox = new LinearLayout(this);
        tipBox.setBackgroundColor(Colors.TIP_BG);
        tipBox.setPadding(dp(24), dp(12), dp(24), dp(12));
        tipView = new TextView(this);
        tipView.setTextSize(16);
        tipView.setTextColor(Colors.NEUTRAL);
        tipBox.addView(tipView);
        return tipBox;
    }

    /**
     * Wires a focus listener on the given view so that when it gains
     * input focus, {@code text} appears in the bottom tip box. Each
     * caller passes the text appropriate to the option it represents.
     * The text is run through {@link #badgeKeys} once at attach time
     * so remote-button references render in rounded "keycap" boxes.
     */
    private void attachTip(View v, final String text) {
        final CharSequence badged = badgeKeys(text);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus && tipView != null) {
                    tipView.setText(badged);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // State (target package, accessibility status, ...) might have
        // changed while this activity was paused, e.g. via the picker.
        refresh();
    }

    /** Builds the persistent brand-coloured title bar with logo, name and info button. */
    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Colors.BRAND);
        bar.setPadding(dp(24), dp(12), dp(24), dp(12));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoLp.rightMargin = dp(12);
        bar.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        title.setTextColor(Colors.WHITE);
        // Title takes remaining width so the info button can sit at the
        // far right of the bar.
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleLp);

        // Round info button: TextView so we can use a single Unicode
        // glyph as content and a state-list-drawable as background.
        // Using Button here would force a min size and lose the round
        // shape; using ImageView would lose the easy text glyph.
        TextView infoBtn = new TextView(this);
        infoBtn.setText(R.string.info_button_glyph);
        infoBtn.setTextSize(20);
        infoBtn.setGravity(Gravity.CENTER);
        infoBtn.setTextColor(getResources().getColorStateList(R.color.info_button_text));
        infoBtn.setBackground(getDrawable(R.drawable.info_button_bg));
        infoBtn.setFocusable(true);
        infoBtn.setClickable(true);
        infoBtn.setContentDescription(getString(R.string.info_button_desc));
        infoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InfoDialog.show(MainActivity.this);
            }
        });
        int size = dp(48);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(size, size);
        bar.addView(infoBtn, infoLp);
        attachTip(infoBtn, getString(R.string.info_button_tip));

        return bar;
    }

    /** Converts a dp value to integer pixels using the current display density. */
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Row with a Switch on the left and a label on the right. The row
     * itself owns focus (not the Switch) so d-pad navigation lights
     * up the whole row with a brand-tinted background; OK toggles the
     * Switch via {@link Switch#toggle}. The Switch is kept as a
     * non-focusable visual indicator.
     */
    private Switch makeSwitchRow(LinearLayout root, CharSequence label, String tip,
                                  boolean initialChecked,
                                  CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(getDrawable(R.drawable.row_focus_bg));

        final Switch sw = new Switch(this);
        sw.setChecked(initialChecked);
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = dp(16);
        row.addView(sw, swLp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(18);
        tv.setTextColor(Colors.NEUTRAL);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tv, tvLp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (row.isEnabled()) sw.toggle();
            }
        });
        attachTip(row, tip);

        root.addView(row);
        return sw;
    }

    /**
     * Opens the system "App info" page for the currently configured
     * target package (long-press on the picker area). No-op when no
     * target is set or the target package is missing. A toast
     * acknowledges those cases so the long-press doesn't feel dead.
     */
    private void openTargetAppInfo() {
        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.fromParts("package", target, null));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.toast_target_missing, target),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Launches the currently configured target app (used by the launch button). */
    private void launchTarget() {
        String target = prefs.getTargetPackage();
        if (target == null || target.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(target);
        if (i == null) i = pm.getLaunchIntentForPackage(target);
        if (i == null) {
            Toast.makeText(this,
                    getString(R.string.toast_target_missing, target),
                    Toast.LENGTH_LONG).show();
            return;
        }
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /**
     * Re-reads the current preferences and live system state and updates
     * every status row with up-to-date text and color.
     */
    private void refresh() {
        refreshTargetRow();
        refreshSwitches();
        updateDependentSwitches();
    }

    /**
     * Updates the target chip (label, icon-or-emoji, "not installed"
     * suffix) and the launch button's visibility / enabled state.
     */
    private void refreshTargetRow() {
        String target = prefs.getTargetPackage();
        String targetLabel;
        boolean targetInstalled = false;
        android.graphics.drawable.Drawable targetIcon = null;
        if (target == null || target.isEmpty()) {
            targetLabel = getString(R.string.target_none);
        } else {
            targetLabel = target;
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(target, 0);
                CharSequence l = pm.getApplicationLabel(ai);
                if (l != null) targetLabel = l + " (" + target + ")";
                try {
                    targetIcon = pm.getApplicationIcon(ai);
                } catch (Exception ignored) { /* icon optional */ }
                targetInstalled = true;
            } catch (Exception e) {
                targetLabel = target + getString(R.string.target_not_installed_suffix);
            }
        }
        targetView.setText(targetLabel);
        // Always white; the chip background and the " - not installed"
        // suffix handle the state signal without colliding with the
        // brand-red palette.
        targetView.setTextColor(Colors.WHITE);
        // Swap between the app icon (target picked) and the picker
        // emoji (no target yet); only one is ever visible.
        if (targetIcon != null) {
            targetIconView.setImageDrawable(targetIcon);
            targetIconView.setVisibility(View.VISIBLE);
            targetEmojiView.setVisibility(View.GONE);
        } else {
            targetIconView.setVisibility(View.GONE);
            targetEmojiView.setVisibility(View.VISIBLE);
        }
        // Launch button hidden entirely while there is no target.
        // The chip then stretches across the row via its weight=1.
        boolean hasTarget = target != null && !target.isEmpty();
        launchBtn.setVisibility(hasTarget ? View.VISIBLE : View.GONE);
        launchBtn.setEnabled(targetInstalled);
    }

    /**
     * Syncs every Switch widget with the underlying prefs / system
     * state. Wrapped in suppressSwitchEvents so the change listeners
     * don't write the value back in a loop.
     */
    private void refreshSwitches() {
        suppressSwitchEvents = true;
        try {
            accessSwitch.setChecked(AccessibilityHelper.isOurServiceEnabled(this));
            hijackSwitch.setChecked(prefs.isHijackEnabled());
            bootSwitch.setChecked(prefs.getLaunchOnBoot());
            verboseSwitch.setChecked(prefs.isVerboseLogging());
            menuLpSwitch.setChecked(prefs.isMenuLongPressLaunch());
        } finally {
            suppressSwitchEvents = false;
        }
    }

    /**
     * The four feature switches (Replace Home, Launch on boot, Verbose
     * logging, Menu long-press) only do anything when the accessibility
     * service is enabled. Grey them out when it isn't so the UI makes
     * the dependency obvious.
     */
    private void updateDependentSwitches() {
        boolean accessOn = accessSwitch.isChecked();
        View focusedBefore = getCurrentFocus();
        setRowEnabled(hijackSwitch, accessOn);
        setRowEnabled(bootSwitch, accessOn);
        setRowEnabled(verboseSwitch, accessOn);
        setRowEnabled(menuLpSwitch, accessOn);
        // If the row that held d-pad focus just became non-focusable
        // (e.g. the service was disabled via ADB while a feature row was
        // focused, picked up on resume), move focus to the accessibility
        // row, which is never disabled, so the screen doesn't lose focus.
        if (focusedBefore != null && !focusedBefore.isFocusable()) {
            View accessRow = (View) accessSwitch.getParent();
            if (accessRow != null) accessRow.requestFocus();
        }
    }

    /** Shorthand for {@link KeyBadges#wrap}. */
    private CharSequence badgeKeys(CharSequence text) {
        return KeyBadges.wrap(this, text);
    }

    /**
     * Propagates the enabled flag from a Switch to its containing row
     * (which is what now owns focus and the click). Without this the
     * row would still be focusable / clickable even though the Switch
     * itself ignores the toggle.
     */
    private void setRowEnabled(Switch sw, boolean enabled) {
        sw.setEnabled(enabled);
        View parent = (View) sw.getParent();
        if (parent != null) {
            parent.setEnabled(enabled);
            parent.setFocusable(enabled);
        }
    }
}
