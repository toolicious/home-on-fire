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
 *  - Brand-colored top bar with the app logo, name and a round info
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
    private Switch accessSwitch, hijackSwitch, bootSwitch, verboseSwitch, menuLpSwitch,
            launchKeyEnabledSwitch;

    /** The map-custom-buttons switch-row (left half); pinned into the vertical focus chain. */
    private LinearLayout launchKeyRow;
    /** The Replace-Home switch-row; kept so the Fire OS 7 loading-cover box can hang off it. */
    private LinearLayout hijackRow;
    /** Fire OS 7 only: the loading-cover choice box (Animation vs. black screen) beside Replace Home. */
    private TextView coverBox;
    /**
     * Escape-gesture sub-row under Replace Home, plus its two switch-and-label blocks.
     * Unlike every other row, the switches here are inside focusable blocks rather than
     * owned by the row: two switches cannot share one focusable row. Each block carries
     * its Switch as its tag.
     */
    private LinearLayout escapeRow;
    private LinearLayout escapeLongPair, escapeDoublePair;
    /** The two "map custom button" value boxes: one for the target launcher, one for Amazon home. */
    private TextView launcherBox, amazonBox;
    /** Third box on that row: opens the dialog for freely defined "button opens app" mappings. */
    private TextView customBox;

    /**
     * The custom-mapping dialog while it is open, plus the value boxes of its rows
     * (indexed exactly like {@link Prefs#getCustomMaps()}, so a learn can address a
     * row by number). All null while the dialog is closed.
     */
    private android.app.AlertDialog customDialog;
    private java.util.List<TextView> customAppBoxes;
    private java.util.List<TextView> customButtonBoxes;
    /** Row container of the open dialog, so a new row can be appended without a rebuild. */
    private LinearLayout customRowsHost;
    /**
     * True while we are dismissing the dialog only because the Activity is going away
     * (a mapped app destroyed us mid-learn). The dismiss handler then keeps the state
     * that {@link #onResume} needs to put the dialog back up.
     */
    private boolean customDialogTearingDown;
    /**
     * The dialog was open when this screen was destroyed, so re-open it once we are
     * back. Static for the same reason as {@link #sPendingSlot}: it has to survive the
     * Activity, not just a pause.
     */
    private static boolean sCustomDialogOpen;
    /**
     * The box currently in learn mode, or null. While learning we listen for BOTH a
     * keycode (the button sends a key) and an app window (the button opens an Amazon
     * app), and bind whichever the pressed button produces. These hold that box's
     * two prefs so either signal can be stored.
     */
    private TextView learningBox;
    private IntPref learningKeyPref;
    private StrPref learningWinPref;

    /**
     * An app window captured mid-learn while our config was backgrounded (the app the
     * button opened is now foreground). Finished in onResume once the service has pulled
     * the config screen back to the front. Static and slot-based so it survives this
     * Activity being destroyed by a heavy app (e.g. Prime) and recreated.
     */
    private static int sPendingSlot = -1;          // a SLOT_* value, or -1
    private static String sPendingWindow;          // sentinel or "pkg/activity", or null
    private static final int SLOT_LAUNCHER = 0;
    private static final int SLOT_AMAZON = 1;
    /** Custom mapping N is slot {@code SLOT_CUSTOM_BASE + N}, N being its row index. */
    private static final int SLOT_CUSTOM_BASE = 100;

    /**
     * Shell / navigation surfaces that must never be captured as a mapped button
     * during learn: Amazon home, the long-press side panel (Fire OS 8 Quick Settings),
     * and Settings / the Fire OS 6-7 long-press HUD. The target launcher and our own
     * screen are added dynamically in {@link #isIgnoredLearnWindow}. Seeing any of these
     * mid-learn means the user bailed (e.g. long-pressed Home), so we cancel, not bind.
     */
    private static final String[] LEARN_IGNORE_PKGS = {
            "com.amazon.tv.launcher",         // Amazon home shell
            "com.amazon.tv.quicksettings.ui", // long-press side panel (Fire OS 8)
            "com.amazon.tv.settings.v2",      // Settings and the Fire OS 6/7 long-press HUD
    };

    /** Amazon's Appstore package; its Apps-grid window is what the ⊞ Apps button opens. */
    private static final String APPS_GRID_PKG = "com.amazon.venezia";

    /** Amazon's Live TV app, what the remote's Live TV button opens. */
    private static final String LIVETV_PKG = "com.amazon.tv.livetv";

    /** Vertical padding inside every settings row; kept small so more rows fit one screen. */
    private static final int ROW_PAD_V = 8;

    /**
     * Horizontal padding inside a value box. Deliberately larger than {@link #ROW_PAD_V}:
     * the vertical padding sits on the text line box, which already carries about 10px of
     * its own air above the capitals and below the baseline, while the horizontal one sits
     * right against the glyphs. Equal numbers therefore look unequal; these two measure the
     * same on screen.
     */
    private static final int BOX_PAD_H = 12;

    /**
     * Fixed width of the button column in the custom-mapping dialog. Unlike the boxes on
     * the main screen, which are sized by their content, these are stacked on top of each
     * other and have to line up. Wide enough for the longest text the box can ever show,
     * which is the "Press a button…" prompt during a learn (~117dp) plus
     * {@link #BOX_PAD_H} on both sides; every real value is shorter, so the column stays
     * put while mappings are added. Longer window bindings (an app with a long name) are
     * ellipsized rather than allowed to break the alignment.
     */
    private static final int CUSTOM_BUTTON_COL_W = 144;

    /** Tip box at the bottom that shows the description of the focused option. */
    private TextView tipView;

    /** First focusable view; receives focus on startup so the tip box is non-empty. */
    private View initialFocus;

    /** Round "show log" button on the right of the verbose row; visible only while verbose logging is on. */
    private View logBtn;

    /** Hidden beta overlay-timing section; revealed by a long-press on the verbose row. */
    private View tuningSection;
    /** The focusable verbose-logging row, used for focus return when hiding the section. */
    private View verboseRow;
    /** Hold time on the verbose row that toggles the overlay-timing section (fires while held). */
    private static final long TUNING_REVEAL_HOLD_MS = 3000L;

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
        addTuningRows(content);
        wireVerticalNav();

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
        pickerArea.setOnKeyListener(new RightNavGuard(new PickerLongPressListener()));
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
     * Holding OK on the Verbose-logging row for {@link #TUNING_REVEAL_HOLD_MS} reveals
     * (or hides) the otherwise hidden overlay-timing section, a support aid for diagnosing
     * the loading-overlay flash on Fire OS 7. The toggle fires the moment the hold time is
     * reached, while the key is still held, not on release; the long delay keeps it from
     * happening by accident. A short press still toggles verbose logging. The CENTER key is
     * fully owned here so the row's own click never double-fires for the d-pad.
     */
    private class VerboseLongPressListener implements View.OnKeyListener {
        private boolean counting = false;
        private boolean fired = false;
        private final Runnable reveal = new Runnable() {
            @Override
            public void run() {
                fired = true;
                toggleTuningSection();
            }
        };

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            boolean isOkKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (!isOkKey) {
                // Key rollover: another key pressed while OK is held moves focus away,
                // so the matching ACTION_UP would land on a different view and never
                // cancel the pending reveal. Abort the hold before focus moves on.
                if (counting && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    v.removeCallbacks(reveal);
                    counting = false;
                }
                return false;
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    counting = true;
                    fired = false;
                    v.removeCallbacks(reveal);
                    v.postDelayed(reveal, TUNING_REVEAL_HOLD_MS);
                }
                return true; // own CENTER so the row's click never fires for the d-pad
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                v.removeCallbacks(reveal);
                // isCanceled(): a focus-stealing window (system dialog, the Home
                // redirect) synthesizes a canceled UP, which is not a deliberate press.
                if (counting && !fired && !event.isCanceled()) {
                    v.performClick(); // short press: same as a normal tap on the row
                }
                counting = false;
                return true;
            }
            return false;
        }
    }

    /** Shows the hidden overlay-timing section (focus moves into it), or hides it again. */
    private void toggleTuningSection() {
        if (tuningSection == null) return;
        if (tuningSection.getVisibility() == View.VISIBLE) {
            tuningSection.setVisibility(View.GONE);
            if (verboseRow != null) verboseRow.requestFocus();
        } else {
            tuningSection.setVisibility(View.VISIBLE);
            tuningSection.requestFocus();
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
        btn.setOnKeyListener(new RightNavGuard(null)); // rightmost in its row
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
        addHomeRow(content);
        // The custom-button mapping sits right under Replace Home. It also covers
        // app-buttons like ⊞ Apps and Live TV (learned as screen redirects), which
        // used to be a separate "Replace Apps button" switch.
        addLaunchKeyRow(content);
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
        addVerboseRow(content);
    }

    /**
     * Replace-Home row. A plain switch-row on every Fire OS version; on Fire OS 7 it also
     * carries a right-hand choice box, in the same column as the map-custom-buttons boxes
     * below, for what covers the screen during the ~5 s launch delay: the loading animation
     * or a plain black screen with a loading message. Fire OS 8 has no delay and no overlay,
     * so the box is Fire OS 7 only.
     */
    private void addHomeRow(LinearLayout content) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(ROW_PAD_V), dp(16), dp(ROW_PAD_V));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(getDrawable(R.drawable.row_focus_bg));

        final Switch sw = new Switch(this);
        sw.setChecked(prefs.isHijackEnabled());
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (suppressSwitchEvents) return;
                prefs.setHijackEnabled(isChecked);
                updateCoverBoxState(); // the cover choice only applies while the redirect runs
                updateEscapeBoxState(); // so do the escape gestures
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = dp(16);
        row.addView(sw, swLp);

        TextView tv = new TextView(this);
        tv.setText(badgeKeys(getString(R.string.switch_hijack)));
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
        row.setOnKeyListener(new RightNavGuard(null));
        attachTip(row, getString(isFos7OrOlder()
                ? R.string.switch_hijack_tip_fos7 : R.string.switch_hijack_tip));

        hijackSwitch = sw;
        hijackRow = row;

        // Everything else on this row hangs to the right of the weighted label: first the
        // escape block, then (Fire OS 7 only) the loading-cover choice.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(row, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addEscapeBlock(outer);
        if (isFos7OrOlder()) {
            coverBox = addCoverBox(outer);
            updateCoverBoxState(); // start grayed/unfocusable if the redirect is off
        }
        content.addView(outer);
    }

    /**
     * The two escape gestures, appended to the Replace Home row itself rather than to a
     * row of their own: they only qualify that redirect, so they belong beside it and
     * save a whole line of vertical space. Caption plus both switch blocks form one
     * right-hand group; the row's weighted label pushes the group to the right.
     */
    private void addEscapeBlock(LinearLayout parent) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);

        TextView caption = new TextView(this);
        caption.setText(getString(R.string.escape_label));
        caption.setTextSize(15);
        caption.setTextColor(Colors.NEUTRAL);
        block.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        escapeLongPair = addTogglePair(block, getString(R.string.escape_long_label),
                new BoolPref() {
                    @Override public boolean get() { return prefs.isEscapeLongPress(); }
                    @Override public void set(boolean v) { prefs.setEscapeLongPress(v); }
                }, 0, R.string.escape_long_box_tip);
        escapeDoublePair = addTogglePair(block, getString(R.string.escape_double_label),
                new BoolPref() {
                    @Override public boolean get() { return prefs.isEscapeDoublePress(); }
                    @Override public void set(boolean v) { prefs.setEscapeDoublePress(v); }
                }, dp(2), R.string.escape_double_box_tip);

        escapeRow = block;
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockLp.leftMargin = dp(12);
        parent.addView(block, blockLp);
        updateEscapeBoxState();
    }

    /** Get/set bridge for a boolean-valued pref shown in a value box. */
    private interface BoolPref {
        boolean get();
        void set(boolean value);
    }

    /**
     * Builds one focusable "[switch] Label" block, using the same Switch widget the rows
     * start with. The Switch itself stays non-focusable and listener-free, exactly like
     * everywhere else; the surrounding block owns focus and the click, so a programmatic
     * setChecked can never loop back into a listener. Returns the block, with the Switch
     * stashed as its tag.
     */
    private LinearLayout addTogglePair(LinearLayout parent, String label, final BoolPref pref,
                                       int leftMargin, int tipRes) {
        final LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.setGravity(Gravity.CENTER_VERTICAL);
        pair.setPadding(dp(8), dp(4), dp(8), dp(4));
        pair.setBackground(getDrawable(R.drawable.row_focus_bg));
        pair.setFocusable(true);
        pair.setClickable(true);

        final Switch sw = new Switch(this);
        sw.setChecked(pref.get());
        sw.setFocusable(false);
        sw.setClickable(false);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = dp(10);
        pair.addView(sw, swLp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(Colors.NEUTRAL);
        pair.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pair.isEnabled()) return;
                pref.set(!pref.get());
                sw.setChecked(pref.get());
            }
        });
        pair.setOnKeyListener(new RightNavGuard(null));
        pair.setTag(sw);
        attachTip(pair, getString(tipRes));

        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pairLp.leftMargin = leftMargin;
        parent.addView(pair, pairLp);
        return pair;
    }

    /** Enables/greys one switch block and drops it out of the focus order when unusable. */
    private void setPairUsable(LinearLayout pair, boolean usable, View fallbackRow) {
        if (pair == null) return;
        boolean wasFocused = pair.isFocused();
        pair.setEnabled(usable);
        pair.setFocusable(usable);
        pair.setClickable(usable);
        pair.setAlpha(usable ? 1f : 0.4f);
        if (!usable && wasFocused && fallbackRow != null) fallbackRow.requestFocus();
    }

    /** The escape switches are usable only while the service AND Replace Home are on. */
    private void updateEscapeBoxState() {
        boolean usable = accessSwitch.isChecked() && prefs.isHijackEnabled();
        setPairUsable(escapeLongPair, usable, hijackRow);
        setPairUsable(escapeDoublePair, usable, hijackRow);
        if (escapeRow != null) {
            // Never made focusable: the caption is not a control, the two switch blocks
            // are. Only the dimming is shared with them.
            escapeRow.setEnabled(usable);
            escapeRow.setAlpha(usable ? 1f : 0.4f);
        }
    }

    /**
     * Builds the "Loading cover: [Animation | Black screen]" choice box and appends it to
     * {@code parent}. OK / click toggles between the two; the masking overlay reads the
     * value on Fire OS 7. Styled like the map-custom-buttons value boxes.
     */
    private TextView addCoverBox(LinearLayout parent) {
        TextView label = new TextView(this);
        label.setText(R.string.mask_cover_label);
        label.setTextSize(15);
        label.setTextColor(Colors.NEUTRAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(12);
        labelLp.rightMargin = dp(6);
        parent.addView(label, labelLp);

        final TextView box = new TextView(this);
        box.setText(coverBoxText());
        box.setTextSize(16);
        box.setTextColor(Colors.WHITE);
        box.setGravity(Gravity.CENTER);
        box.setMinWidth(dp(56));
        box.setPadding(dp(BOX_PAD_H), dp(ROW_PAD_V), dp(BOX_PAD_H), dp(ROW_PAD_V));
        box.setBackground(getDrawable(R.drawable.target_chip_bg));
        box.setFocusable(true);
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setMaskBlackScreen(!prefs.isMaskBlackScreen());
                box.setText(coverBoxText());
            }
        });
        box.setOnKeyListener(new RightNavGuard(new CoverBoxListener())); // rightmost; long-OK previews
        box.setTag(label); // grayed together with the box when Replace Home is off
        attachTip(box, getString(R.string.mask_cover_tip));
        parent.addView(box);
        return box;
    }

    /** Current label for the loading-cover box: "Animation" or "Black screen". */
    private CharSequence coverBoxText() {
        return getString(prefs.isMaskBlackScreen()
                ? R.string.mask_cover_black : R.string.mask_cover_animation);
    }

    /**
     * Key handling for the loading-cover box: short OK toggles the choice (via the box's
     * OnClickListener); long OK plays a full-screen preview of the currently selected
     * loading screen, so the choice can be checked without triggering a real Home redirect.
     * Modelled on {@link PickerLongPressListener} (return false on the OK DOWN so the
     * framework tracks the press for long-press + click; swallow the trailing click).
     */
    private class CoverBoxListener implements View.OnKeyListener {
        private boolean longPressTriggered = false;

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            boolean isOkKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (!isOkKey) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) longPressTriggered = false;
                if (event.isLongPress()) {
                    longPressTriggered = true;
                    previewLoadingScreen();
                }
                return false; // let the framework track the press (long-press + click)
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && longPressTriggered) {
                longPressTriggered = false;
                return true; // swallow the click so a long-press doesn't also toggle
            }
            return false;
        }
    }

    /** Full-screen preview of the currently selected loading screen (animation or black). */
    private void previewLoadingScreen() {
        Intent i = new Intent(this, LoaderPreviewActivity.class);
        i.putExtra(LoaderPreviewActivity.EXTRA_BLACK, prefs.isMaskBlackScreen());
        startActivity(i);
    }

    /**
     * Custom-launch-key row. Two focusable parts, like the verbose row:
     *  - a switch on the left that enables/disables the shortcut WITHOUT losing
     *    the assigned button, and
     *  - a "data field" box on the right showing the bound button (or "None").
     * Turning the switch on while nothing is bound jumps straight into learn
     * mode; if a button is already bound it just re-enables. To change the
     * button, move right onto the box and press OK. During learn mode the
     * accessibility service swallows the d-pad so focus can't drift; press the
     * remote button to bind it, or Back to cancel. Unlike the Home redirect this
     * launches with no app-switch lock, so on Fire OS 7 there is no delay.
     */
    private void addLaunchKeyRow(LinearLayout content) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(ROW_PAD_V), dp(16), dp(ROW_PAD_V));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(getDrawable(R.drawable.row_focus_bg));

        final Switch sw = new Switch(this);
        sw.setChecked(prefs.isLaunchKeyEnabled());
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (suppressSwitchEvents) return;
                prefs.setLaunchKeyEnabled(isChecked); // one toggle gates both mappings
                updateLaunchKeyBoxState();
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = dp(16);
        row.addView(sw, swLp);

        TextView tv = new TextView(this);
        tv.setText(getString(R.string.launch_key_label));
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
        // RIGHT reaches the value boxes only while enabled; otherwise stay put.
        row.setOnKeyListener(new RightNavGuard(null));
        attachTip(row, getString(isFos7OrOlder()
                ? R.string.launch_key_tip_fos7 : R.string.launch_key_tip));
        outer.addView(row, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Two labeled value boxes on the right: target launcher, then Amazon home.
        launchKeyEnabledSwitch = sw;
        launchKeyRow = row;
        launcherBox = addKeyBox(outer, getString(R.string.launch_key_launcher_label),
                launcherPref(), launcherWinPref(), SLOT_LAUNCHER, dp(12),
                R.string.launch_key_launcher_box_tip);
        amazonBox = addKeyBox(outer, getString(R.string.launch_key_amazon_label),
                amazonPref(), amazonWinPref(), SLOT_AMAZON, dp(18),
                R.string.launch_key_amazon_box_tip);
        customBox = addCustomBox(outer);

        updateLaunchKeyBoxState(); // start grayed/unfocusable if the shortcut is off
        content.addView(outer);
    }

    /**
     * Third box of the row: everything beyond the two fixed slots. It holds no value of
     * its own, it opens {@link #showCustomMapsDialog()} and shows how many mappings are
     * set up (a plus sign while there are none), so the row itself stays one line no
     * matter how many buttons the user maps.
     */
    private TextView addCustomBox(LinearLayout parent) {
        TextView label = new TextView(this);
        label.setText(getString(R.string.launch_key_custom_label));
        label.setTextSize(15);
        label.setTextColor(Colors.NEUTRAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(18);
        labelLp.rightMargin = dp(6);
        parent.addView(label, labelLp);

        // Square floor instead of a fixed minimum width: holding nothing but a "+" this
        // box would otherwise be a wide, flat sliver. It still grows in width once the
        // app icons go in.
        final TextView box = new TextView(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                int side = getMeasuredHeight();
                if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.AT_MOST) {
                    side = Math.min(side, View.MeasureSpec.getSize(widthSpec));
                }
                if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.EXACTLY
                        && getMeasuredWidth() < side) {
                    // Measure again at the square width instead of just reporting it: the
                    // text layout is built inside onMeasure, so overriding the dimension
                    // afterwards would leave the glyph centred in the old, narrow layout.
                    super.onMeasure(
                            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                            heightSpec);
                }
            }
        };
        box.setText(customBoxText());
        box.setTextSize(16);
        box.setTextColor(Colors.WHITE);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(BOX_PAD_H), dp(ROW_PAD_V), dp(BOX_PAD_H), dp(ROW_PAD_V));
        box.setBackground(getDrawable(R.drawable.target_chip_bg));
        box.setFocusable(true);
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (learningBox == null) showCustomMapsDialog();
            }
        });
        box.setOnKeyListener(new RightNavGuard(null));
        box.setTag(label); // grayed together with the box when the shortcut is off
        attachTip(box, getString(R.string.launch_key_custom_box_tip));
        parent.addView(box);
        return box;
    }

    /** Icons shown in the Custom box before the row would get too long for one line. */
    private static final int CUSTOM_BOX_MAX_ICONS = 5;

    /**
     * The icons of the mapped apps side by side, or a plus sign while there are none.
     * Icons rather than a count: they say which apps are behind the box without costing
     * more width than a number would. Only mappings that actually have a button are
     * shown, since one without does nothing yet, and past
     * {@link #CUSTOM_BOX_MAX_ICONS} the rest is summed up as an ellipsis so the row
     * stays one line however many mappings there are.
     */
    private CharSequence customBoxText() {
        java.util.List<android.graphics.drawable.Drawable> icons = new java.util.ArrayList<>();
        int mapped = 0;
        for (CustomMap m : prefs.getCustomMaps()) {
            if (!m.isActive()) continue;
            mapped++;
            if (icons.size() >= CUSTOM_BOX_MAX_ICONS) continue;
            android.graphics.drawable.Drawable icon = null;
            try {
                icon = getPackageManager().getApplicationIcon(m.app);
            } catch (Exception ignored) { /* uninstalled or icon-less: fall back below */ }
            if (icon == null) icon = getDrawable(android.R.drawable.sym_def_app_icon);
            if (icon != null) icons.add(icon);
        }
        if (icons.isEmpty()) return getString(R.string.custom_maps_add);
        CharSequence row = KeyBadges.iconRow(icons);
        // Separated by the same single space that sits between the icons, otherwise the
        // ellipsis sticks to the last one.
        return mapped > icons.size()
                ? android.text.TextUtils.concat(row, " ", getString(R.string.custom_maps_more))
                : row;
    }

    private void updateCustomBox() {
        if (customBox != null) customBox.setText(customBoxText());
    }

    /** IntPref bridge for the target-launcher keycode. */
    private IntPref launcherPref() {
        return new IntPref() {
            @Override public int get() { return prefs.getLaunchKeycode(); }
            @Override public void set(int v) { prefs.setLaunchKeycode(v); }
        };
    }

    /** IntPref bridge for the Amazon-home keycode. */
    private IntPref amazonPref() {
        return new IntPref() {
            @Override public int get() { return prefs.getAmazonKeycode(); }
            @Override public void set(int v) { prefs.setAmazonKeycode(v); }
        };
    }

    /** StrPref bridge for the target-launcher window trigger. */
    private StrPref launcherWinPref() {
        return new StrPref() {
            @Override public String get() { return prefs.getLaunchWindow(); }
            @Override public void set(String v) { prefs.setLaunchWindow(v); }
        };
    }

    /** StrPref bridge for the Amazon-home window trigger. */
    private StrPref amazonWinPref() {
        return new StrPref() {
            @Override public String get() { return prefs.getAmazonWindow(); }
            @Override public void set(String v) { prefs.setAmazonWindow(v); }
        };
    }

    /** IntPref bridge for custom mapping {@code index}; a missing row reads as unbound. */
    private IntPref customKeyPref(final int index) {
        return new IntPref() {
            @Override public int get() {
                CustomMap m = customMap(index);
                return m == null ? Prefs.DEFAULT_LAUNCH_KEYCODE : m.keyCode;
            }
            @Override public void set(int v) {
                CustomMap m = customMap(index);
                if (m != null) putCustomMap(index, m.withKeyCode(v));
            }
        };
    }

    /** StrPref bridge for custom mapping {@code index}; a missing row reads as unbound. */
    private StrPref customWinPref(final int index) {
        return new StrPref() {
            @Override public String get() {
                CustomMap m = customMap(index);
                return m == null ? Prefs.NO_WINDOW : m.window;
            }
            @Override public void set(String v) {
                CustomMap m = customMap(index);
                if (m != null) putCustomMap(index, m.withWindow(v));
            }
        };
    }

    /** The stored mapping at {@code index}, or null for the trailing "add another" row. */
    private CustomMap customMap(int index) {
        java.util.List<CustomMap> maps = prefs.getCustomMaps();
        return index >= 0 && index < maps.size() ? maps.get(index) : null;
    }

    /** Writes one mapping back, appending when {@code index} is one past the end. */
    private void putCustomMap(int index, CustomMap map) {
        java.util.List<CustomMap> maps = new java.util.ArrayList<>(prefs.getCustomMaps());
        if (index == maps.size()) {
            maps.add(map);
        } else if (index >= 0 && index < maps.size()) {
            maps.set(index, map);
        } else {
            return;
        }
        prefs.setCustomMaps(maps);
        updateCustomBox();
    }

    /**
     * Builds one "SubLabel [box]" mapping control and appends it to {@code parent}.
     * A slot can be bound to a keycode OR an app window; OK on the box learns
     * whichever the pressed button produces, a long OK resets it to None.
     */
    private TextView addKeyBox(LinearLayout parent, String subLabel, final IntPref keyPref,
                               final StrPref winPref, final int slot, int leftMargin, int tipRes) {
        TextView label = new TextView(this);
        label.setText(subLabel);
        label.setTextSize(15);
        label.setTextColor(Colors.NEUTRAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = leftMargin;
        labelLp.rightMargin = dp(6);
        parent.addView(label, labelLp);

        final TextView box = new TextView(this);
        box.setText(boxText(keyPref, winPref));
        box.setTextSize(16);
        box.setTextColor(Colors.WHITE);
        box.setGravity(Gravity.CENTER);
        // Just a floor so a very short value still reads as a box. Anything wider is
        // sized by its padding alone; a larger minimum would centre the text in the
        // leftover space and widen the side gaps beyond BOX_PAD_H again.
        box.setMinWidth(dp(56));
        box.setPadding(dp(BOX_PAD_H), dp(ROW_PAD_V), dp(BOX_PAD_H), dp(ROW_PAD_V));
        box.setBackground(getDrawable(R.drawable.target_chip_bg));
        box.setFocusable(true);
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (learningBox == null) startLearning(box, keyPref, winPref, slot); // OK (re)assigns
            }
        });
        box.setOnKeyListener(new RightNavGuard(new KeyBoxListener(box, keyPref, winPref)));
        box.setTag(label); // grayed together with the box when the shortcut is off
        attachTip(box, getString(tipRes));
        parent.addView(box);
        return box;
    }

    /**
     * The value box is usable only when the accessibility service is on AND the
     * launch-key shortcut is enabled. When it isn't, gray it out and take it out
     * of the focus order so it can't be selected; the binding itself is kept.
     */
    private void updateLaunchKeyBoxState() {
        boolean usable = accessSwitch.isChecked() && prefs.isLaunchKeyEnabled();
        if (!usable && learningBox != null) cancelLearning();
        if (!usable && customDialog != null) customDialog.dismiss();
        setBoxUsable(launcherBox, usable, launchKeyRow);
        setBoxUsable(amazonBox, usable, launchKeyRow);
        setBoxUsable(customBox, usable, launchKeyRow);
    }

    /**
     * The Fire OS 7 loading-cover box is usable when the accessibility service is on AND
     * Replace Home is enabled (the cover only ever shows during that redirect). No-op on
     * Fire OS 8, where the box does not exist.
     */
    private void updateCoverBoxState() {
        setBoxUsable(coverBox, accessSwitch.isChecked() && prefs.isHijackEnabled(), hijackRow);
    }

    /**
     * Enables/grays one value box; if it loses focusability while focused, moves focus to
     * {@code fallbackRow} so the screen never ends up with nothing selected.
     */
    private void setBoxUsable(TextView box, boolean usable, View fallbackRow) {
        if (box == null) return;
        boolean wasFocused = box.isFocused();
        box.setEnabled(usable);
        box.setFocusable(usable);
        box.setClickable(usable);
        box.setAlpha(usable ? 1f : 0.4f);
        // Dim the sub-label ("Launcher:", "Amazon Home:", "Loading screen:") in step with
        // the box; it's stashed on the box's tag so we don't need a field per label.
        Object label = box.getTag();
        if (label instanceof View) ((View) label).setAlpha(usable ? 1f : 0.4f);
        if (!usable && wasFocused && fallbackRow != null) fallbackRow.requestFocus();
    }

    /**
     * Key handling for one value box:
     *  - Back cancels an in-progress learn on this box (swallowed so it doesn't leave the screen).
     *  - Short OK (re)assigns, via the box's OnClickListener.
     *  - Long OK resets this box (see {@link #resetKey}); the trailing click is
     *    swallowed so it doesn't immediately re-enter learn mode.
     * Assignable buttons never reach here: the service captures them during learn.
     * Modelled on {@link PickerLongPressListener} (return false on the OK DOWN so
     * the framework tracks the press for long-press + click).
     */
    private class KeyBoxListener implements View.OnKeyListener {
        private final TextView box;
        private final IntPref keyPref;
        private final StrPref winPref;
        private boolean longPressTriggered = false;

        KeyBoxListener(TextView box, IntPref keyPref, StrPref winPref) {
            this.box = box;
            this.keyPref = keyPref;
            this.winPref = winPref;
        }

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (learningBox != box) return false; // not learning here: let Back leave
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) cancelLearning();
                return true;
            }
            boolean isOkKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (!isOkKey || learningBox != null) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) longPressTriggered = false;
                if (event.isLongPress()) {
                    longPressTriggered = true;
                    resetKey(box, keyPref, winPref);
                }
                return false; // let the framework track the press (long-press + click)
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && longPressTriggered) {
                longPressTriggered = false;
                return true; // swallow the click that would otherwise re-assign
            }
            return false;
        }
    }

    /**
     * The custom mappings, as an overlay dialog so the row behind it stays a single
     * line. One row per mapping: the app on the left, the button that opens it on the
     * right, plus one empty row to add another (up to {@link Prefs#MAX_CUSTOM_MAPS}).
     * Both fields work like the fixed slots: OK sets, a held OK clears.
     *
     * Every change is written through immediately, because binding a button that opens
     * an app (Live TV, Prime, ...) can destroy this screen; rows are only dropped when
     * the dialog is closed, so an index keeps pointing at the same mapping throughout.
     */
    private void showCustomMapsDialog() {
        sCustomDialogOpen = true;
        customAppBoxes = new java.util.ArrayList<>();
        customButtonBoxes = new java.util.ArrayList<>();

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(4), dp(24), dp(4));
        // Sets the dialog width, which the rows then fill; without it the longest app
        // name would decide how wide the whole thing is.
        body.setMinimumWidth(dp(560));

        customRowsHost = new LinearLayout(this);
        customRowsHost.setOrientation(LinearLayout.VERTICAL);
        // Rows past this height scroll instead of pushing the hint off the screen. A
        // ScrollView on its own would still report its full content height to the dialog,
        // so the ceiling has to be imposed here.
        final int maxRowsHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        ScrollView rows = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = maxRowsHeight;
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
                    limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec,
                        View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        rows.addView(customRowsHost, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int stored = prefs.getCustomMaps().size();
        for (int i = 0; i < stored; i++) {
            addCustomRow(i);
        }
        if (stored < Prefs.MAX_CUSTOM_MAPS) addCustomRow(stored); // the "add another" row

        TextView hint = new TextView(this);
        hint.setText(badgeKeys(getString(R.string.custom_maps_hint)));
        hint.setTextSize(14);
        hint.setTextColor(Colors.NEUTRAL);
        hint.setPadding(dp(2), dp(14), dp(2), dp(2));
        // Same cap as the body's minimum, so this text wraps into the dialog instead of
        // stretching it to whatever fits on one line.
        hint.setMaxWidth(dp(560));
        body.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this,
                R.style.AppDialogTheme)
                .setIcon(R.drawable.ic_logo)
                .setTitle(R.string.custom_maps_title)
                .setView(body)
                .create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                onCustomDialogDismissed();
            }
        });
        customDialog = dialog;
        dialog.show();
        focusFirstFreeCustomRow();
    }

    /**
     * Opens on the first row that still needs an app, which is the empty one at the
     * bottom in the common case, so adding another mapping is a straight "OK". Posted
     * because the dialog hands initial focus to its first focusable during layout, and
     * that would otherwise win.
     */
    private void focusFirstFreeCustomRow() {
        if (customAppBoxes == null || customAppBoxes.isEmpty()) return;
        int wanted = customAppBoxes.size() - 1; // all rows filled: land on the last one
        for (int i = 0; i < customAppBoxes.size(); i++) {
            if (customApp(i).isEmpty()) {
                wanted = i;
                break;
            }
        }
        final TextView box = customAppBoxes.get(wanted);
        box.post(new Runnable() {
            @Override
            public void run() {
                box.requestFocus();
            }
        });
    }

    /**
     * Closing the dialog is what commits the "a row without an app is gone" rule.
     * Skipped while the Activity itself is being torn down mid-learn: there the dialog
     * only disappears with the window and has to come back in {@link #onResume}.
     */
    private void onCustomDialogDismissed() {
        customDialog = null;
        customRowsHost = null;
        customAppBoxes = null;
        customButtonBoxes = null;
        if (customDialogTearingDown) {
            customDialogTearingDown = false;
            // Only an unfinished learn justifies re-opening; without one the user left
            // the screen on purpose and should not be dropped back into the dialog.
            sCustomDialogOpen = learningBox != null || sPendingSlot >= 0;
            return;
        }
        sCustomDialogOpen = false;
        cancelLearning();
        pruneCustomMaps();
        updateCustomBox();
    }

    /** Drops the rows the user left without an app. */
    private void pruneCustomMaps() {
        java.util.List<CustomMap> stored = prefs.getCustomMaps();
        java.util.List<CustomMap> keep = new java.util.ArrayList<>(stored.size());
        for (CustomMap m : stored) {
            if (!m.app.isEmpty()) keep.add(m);
        }
        if (keep.size() != stored.size()) prefs.setCustomMaps(keep);
    }

    /**
     * Appends one dialog row. {@code index} is the position in the stored list, and one
     * past its end for the trailing "add another" row, which only becomes a real mapping
     * once an app is picked for it.
     */
    private void addCustomRow(final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        final TextView appBox = new TextView(this);
        appBox.setTextSize(16);
        appBox.setTextColor(Colors.WHITE);
        appBox.setGravity(Gravity.CENTER_VERTICAL);
        appBox.setPadding(dp(BOX_PAD_H), dp(ROW_PAD_V), dp(BOX_PAD_H), dp(ROW_PAD_V));
        appBox.setBackground(getDrawable(R.drawable.target_chip_bg));
        appBox.setFocusable(true);
        appBox.setClickable(true);
        appBox.setSingleLine(true);
        appBox.setEllipsize(android.text.TextUtils.TruncateAt.END);
        applyCustomAppBox(appBox, customApp(index));
        appBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (learningBox == null) pickCustomApp(index);
            }
        });
        appBox.setOnKeyListener(new CustomAppBoxListener(index));

        final TextView buttonBox = new TextView(this);
        buttonBox.setText(boxText(customKeyPref(index), customWinPref(index)));
        buttonBox.setTextSize(16);
        buttonBox.setTextColor(Colors.WHITE);
        buttonBox.setGravity(Gravity.CENTER);
        buttonBox.setMinWidth(dp(CUSTOM_BUTTON_COL_W));
        buttonBox.setMaxWidth(dp(CUSTOM_BUTTON_COL_W));
        buttonBox.setSingleLine(true);
        buttonBox.setEllipsize(android.text.TextUtils.TruncateAt.END);
        buttonBox.setPadding(dp(BOX_PAD_H), dp(ROW_PAD_V), dp(BOX_PAD_H), dp(ROW_PAD_V));
        buttonBox.setBackground(getDrawable(R.drawable.target_chip_bg));
        buttonBox.setFocusable(true);
        buttonBox.setClickable(true);
        buttonBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (learningBox == null) {
                    startLearning(buttonBox, customKeyPref(index), customWinPref(index),
                            SLOT_CUSTOM_BASE + index);
                }
            }
        });
        buttonBox.setOnKeyListener(new KeyBoxListener(buttonBox,
                customKeyPref(index), customWinPref(index)));

        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(appBox, appLp);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonLp.leftMargin = dp(12);
        row.addView(buttonBox, buttonLp);

        // Full dialog width, so the weighted app field actually has room to stretch into.
        customRowsHost.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        customAppBoxes.add(appBox);
        customButtonBoxes.add(buttonBox);
        updateCustomRowUsable(index);
    }

    /** Package of custom mapping {@code index}, or "" for the trailing "add another" row. */
    private String customApp(int index) {
        CustomMap m = customMap(index);
        return m == null ? "" : m.app;
    }

    /** Shows an app in a row's left field: its icon and name, or the "pick one" prompt. */
    private void applyCustomAppBox(TextView box, String pkg) {
        android.graphics.drawable.Drawable icon = null;
        CharSequence text;
        if (pkg == null || pkg.isEmpty()) {
            text = getString(R.string.custom_maps_pick_app);
        } else {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(ai);
                text = label != null ? label : pkg;
                try {
                    icon = pm.getApplicationIcon(ai);
                } catch (Exception ignored) { /* icon optional */ }
            } catch (Exception e) {
                text = pkg + getString(R.string.target_not_installed_suffix);
            }
        }
        if (icon != null) icon.setBounds(0, 0, dp(24), dp(24));
        box.setCompoundDrawables(icon, null, null, null);
        box.setCompoundDrawablePadding(icon != null ? dp(10) : 0);
        box.setText(text);
    }

    /**
     * The button field only becomes usable once the row has an app: a button without one
     * would have nothing to open, and every learn needs a stored row to write itself into.
     */
    private void updateCustomRowUsable(int index) {
        if (customButtonBoxes == null || index >= customButtonBoxes.size()) return;
        setBoxUsable(customButtonBoxes.get(index), !customApp(index).isEmpty(),
                customAppBoxes.get(index));
    }

    private void pickCustomApp(final int index) {
        AppPicker.show(this, getString(R.string.custom_maps_pick_title), new AppPicker.OnPicked() {
            @Override
            public void onPicked(String pkg) {
                setCustomApp(index, pkg);
            }
        });
    }

    /** Stores the picked app and, if this was the last row, offers one more. */
    private void setCustomApp(int index, String pkg) {
        CustomMap m = customMap(index);
        putCustomMap(index, m == null
                ? new CustomMap(pkg, Prefs.DEFAULT_LAUNCH_KEYCODE, Prefs.NO_WINDOW)
                : m.withApp(pkg));
        if (customAppBoxes == null || index >= customAppBoxes.size()) return;
        applyCustomAppBox(customAppBoxes.get(index), pkg);
        updateCustomRowUsable(index);
        if (index == customAppBoxes.size() - 1 && customAppBoxes.size() < Prefs.MAX_CUSTOM_MAPS) {
            addCustomRow(index + 1);
        }
    }

    /**
     * Hold OK on the app field: empties it. The row itself stays visible (and keeps its
     * position, so an in-flight learn still lands where it should) until the dialog closes.
     */
    private void clearCustomApp(int index) {
        CustomMap m = customMap(index);
        if (m == null || m.app.isEmpty()) return;
        putCustomMap(index, m.withApp(""));
        if (customAppBoxes == null || index >= customAppBoxes.size()) return;
        applyCustomAppBox(customAppBoxes.get(index), "");
        updateCustomRowUsable(index);
        Toast.makeText(this, R.string.custom_maps_row_cleared, Toast.LENGTH_SHORT).show();
    }

    /**
     * OK on a row's app field opens the picker (via the click listener), a held OK
     * empties it. Same shape as {@link PickerLongPressListener}: return false on the
     * OK DOWN so the framework still tracks long-press and click.
     */
    private class CustomAppBoxListener implements View.OnKeyListener {
        private final int index;
        private boolean longPressTriggered = false;

        CustomAppBoxListener(int index) {
            this.index = index;
        }

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            boolean isOkKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
            if (!isOkKey || learningBox != null) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) longPressTriggered = false;
                if (event.isLongPress()) {
                    longPressTriggered = true;
                    clearCustomApp(index);
                }
                return false; // let the framework track the press (long-press + click)
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && longPressTriggered) {
                longPressTriggered = false;
                return true; // swallow the click that would otherwise open the picker
            }
            return false;
        }
    }

    /**
     * Name of whatever already uses this button, or null when it is free. Bindings are
     * refused rather than silently taken over: two slots on one button would leave the
     * loser looking broken. {@code slot} is the one being assigned, so it never
     * conflicts with itself.
     */
    private CharSequence buttonInUseBy(int keyCode, String window, int slot) {
        if (slot != SLOT_LAUNCHER
                && sameButton(keyCode, window, prefs.getLaunchKeycode(), prefs.getLaunchWindow())) {
            return getString(R.string.launch_key_launcher_name);
        }
        if (slot != SLOT_AMAZON
                && sameButton(keyCode, window, prefs.getAmazonKeycode(), prefs.getAmazonWindow())) {
            return getString(R.string.launch_key_amazon_name);
        }
        java.util.List<CustomMap> maps = prefs.getCustomMaps();
        for (int i = 0; i < maps.size(); i++) {
            CustomMap m = maps.get(i);
            if (slot == SLOT_CUSTOM_BASE + i || m.app.isEmpty()) continue;
            if (sameButton(keyCode, window, m.keyCode, m.window)) return appLabel(m.app);
        }
        return null;
    }

    /** True when two bindings mean the same physical button (same key code or same window). */
    private static boolean sameButton(int keyA, String windowA, int keyB, String windowB) {
        if (keyA != Prefs.DEFAULT_LAUNCH_KEYCODE && keyA == keyB) return true;
        return windowA != null && !windowA.isEmpty() && windowA.equals(windowB);
    }

    /** Hold OK on a box: clear both bindings for that slot back to None. */
    private void resetKey(TextView box, IntPref keyPref, StrPref winPref) {
        keyPref.set(Prefs.DEFAULT_LAUNCH_KEYCODE);
        winPref.set(Prefs.NO_WINDOW);
        box.setText(boxText(keyPref, winPref));
        Toast.makeText(this, getString(R.string.launch_key_cleared), Toast.LENGTH_SHORT).show();
    }

    /**
     * Enters learn mode for one box. We arm BOTH signals and bind whichever the pressed
     * button produces: an assignable keycode (button sends a key, binds in place) or the
     * app window a branded button (Apps, Live TV, ...) opens. The latter backgrounds us
     * (the app opened), so it finishes in {@link #processPendingLearn} once we're front.
     */
    private void startLearning(final TextView box, final IntPref keyPref, final StrPref winPref,
                               final int slot) {
        learningBox = box;
        learningKeyPref = keyPref;
        learningWinPref = winPref;
        box.setText(R.string.launch_key_learning);

        HijackService.startLearning(new HijackService.KeyLearnListener() {
            @Override
            public void onKeyLearned(int keyCode) {
                // Posted on the main thread by the service. The button sent a key; the
                // config screen stayed in front, so bind in place.
                HijackService.stopWindowLearning();
                learningBox = null;
                learningKeyPref = null;
                learningWinPref = null;
                CharSequence owner = buttonInUseBy(keyCode, Prefs.NO_WINDOW, slot);
                if (owner != null) {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.map_button_in_use, owner), Toast.LENGTH_LONG).show();
                    box.setText(boxText(keyPref, winPref));
                    return;
                }
                keyPref.set(keyCode);
                winPref.set(Prefs.NO_WINDOW); // a slot is key OR window, never both
                box.setText(boxText(keyPref, winPref));
            }
        });

        HijackService.startWindowLearning(new HijackService.WindowLearnListener() {
            @Override
            public void onWindowLearned(String pkg, String activity) {
                // Posted on the main thread by the service. The button opened an app,
                // which may have backgrounded or even destroyed this screen. So we stash
                // the result statically and let the SERVICE pull us back to the front
                // (it outlives the Activity); the binding is finished in onResume.
                HijackService.stopLearning();
                learningBox = null;
                learningKeyPref = null;
                learningWinPref = null;
                // Shell / navigation surfaces (Amazon home, the long-press side panel,
                // Settings), the target launcher and our own screen are never valid
                // results: they mean the user bailed. Ignore, don't bind.
                if (isIgnoredLearnWindow(pkg)) {
                    if (box != null) box.setText(boxText(keyPref, winPref));
                    return;
                }
                // An uninstalled app's button only opens the Amazon Appstore product
                // page (e.g. Netflix when Netflix isn't installed), so there is nothing
                // app-specific to bind. Tell the user and return to the config screen.
                if (pkg.equals(APPS_GRID_PKG) && HijackService.isAppstoreProductPage(activity)) {
                    Toast.makeText(getApplicationContext(),
                            R.string.map_app_not_installed, Toast.LENGTH_LONG).show();
                    HijackService.bringConfigToFrontDelayed();
                    return;
                }
                sPendingSlot = slot;
                // Store the PACKAGE (matched package-level at runtime, since an app's
                // launch spans several activities). For the Apps grid the package IS the
                // APPS_WINDOW sentinel, so no special case is needed.
                sPendingWindow = pkg;
                HijackService.bringConfigToFrontDelayed();
            }
        });
    }

    /**
     * True if a window seen during learn is a shell / navigation surface (Amazon home,
     * the long-press side panel, Settings), the target launcher, or our own screen,
     * none of which are valid mapped-button results.
     */
    private boolean isIgnoredLearnWindow(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return true;
        String target = prefs.getTargetPackage();
        if (target != null && !target.isEmpty() && pkg.equals(target)) return true;
        for (String p : LEARN_IGNORE_PKGS) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    /** Leaves learn mode without binding anything (Back, toggle-off, or destroy). */
    private void cancelLearning() {
        if (learningBox == null) return;
        TextView box = learningBox;
        IntPref keyPref = learningKeyPref;
        StrPref winPref = learningWinPref;
        learningBox = null;
        learningKeyPref = null;
        learningWinPref = null;
        HijackService.stopLearning();
        HijackService.stopWindowLearning();
        if (box != null && keyPref != null && winPref != null) {
            box.setText(boxText(keyPref, winPref));
        }
    }

    /**
     * Finishes a window binding captured while we were backgrounded (see
     * {@link #startLearning}). A real content app (Netflix, Prime, ...) would become
     * unreachable everywhere if mapped, so confirm first; shell surfaces (Apps, Live
     * TV, ...) bind straight away. Called from {@link #onResume}.
     */
    private void processPendingLearn() {
        if (sPendingSlot < 0 || sPendingWindow == null) return;
        final int slot = sPendingSlot;
        final String win = sPendingWindow;
        sPendingSlot = -1;
        sPendingWindow = null;
        final int customIndex = slot >= SLOT_CUSTOM_BASE ? slot - SLOT_CUSTOM_BASE : -1;
        final TextView box;
        final IntPref keyPref;
        final StrPref winPref;
        if (customIndex >= 0) {
            // Only reachable with the dialog up; onResume re-opens it before we get here.
            if (customButtonBoxes == null || customIndex >= customButtonBoxes.size()) return;
            box = customButtonBoxes.get(customIndex);
            keyPref = customKeyPref(customIndex);
            winPref = customWinPref(customIndex);
        } else {
            box = (slot == SLOT_LAUNCHER) ? launcherBox : amazonBox;
            keyPref = (slot == SLOT_LAUNCHER) ? launcherPref() : amazonPref();
            winPref = (slot == SLOT_LAUNCHER) ? launcherWinPref() : amazonWinPref();
        }
        if (box == null) return;

        final String pkg = win.contains("/") ? win.substring(0, win.indexOf('/')) : win;
        // Binding the button that already opens this mapping's own app would be a
        // redirect from an app to itself.
        if (customIndex >= 0 && pkg.equals(customApp(customIndex))) {
            Toast.makeText(this, R.string.map_button_is_same_app, Toast.LENGTH_LONG).show();
            box.setText(boxText(keyPref, winPref));
            return;
        }
        CharSequence owner = buttonInUseBy(Prefs.DEFAULT_LAUNCH_KEYCODE, win, slot);
        if (owner != null) {
            Toast.makeText(this, getString(R.string.map_button_in_use, owner),
                    Toast.LENGTH_LONG).show();
            box.setText(boxText(keyPref, winPref));
            return;
        }
        // Back to the stored value: the learn is over, and declining the warning below
        // would otherwise leave the box stuck on its "Press a button…" prompt.
        box.setText(boxText(keyPref, winPref));
        // The Apps grid is a shell surface, not content, so it skips the warning below.
        // Matched by package: its window class varies (often a bare FrameLayout).
        boolean isAppsGrid = pkg.equals(APPS_GRID_PKG);
        boolean isContentApp = !isAppsGrid
                && getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
        if (isContentApp) {
            // Native dialog, but clearly ours: our logo plus a two-line title that
            // starts with the app name, so it can't be mistaken for a system prompt.
            new android.app.AlertDialog.Builder(this)
                    .setIcon(R.drawable.ic_logo)
                    .setTitle(getString(R.string.app_name) + ":\n"
                            + getString(R.string.redirect_learn_warn_title))
                    .setMessage(getString(R.string.redirect_learn_warn_msg, appLabel(pkg)))
                    .setPositiveButton(R.string.redirect_learn_warn_add,
                            new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            bindWindow(box, keyPref, winPref, win, pkg);
                        }
                    })
                    .setNegativeButton(R.string.redirect_learn_warn_cancel, null)
                    .show();
        } else {
            bindWindow(box, keyPref, winPref, win, pkg);
        }
    }

    /** Commits a window binding to a slot (clearing its keycode) and refreshes the box. */
    private void bindWindow(TextView box, IntPref keyPref, StrPref winPref, String win, String pkg) {
        winPref.set(win);                          // win is the package (Apps = venezia sentinel)
        keyPref.set(Prefs.DEFAULT_LAUNCH_KEYCODE); // a slot is key OR window, never both
        box.setText(boxText(keyPref, winPref));
        CharSequence what = win.equals(Prefs.APPS_WINDOW) ? "Apps" : appLabel(pkg);
        Toast.makeText(this, getString(R.string.redirect_learn_added, what),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Text for a value box: an app name with the ⧉ "screen" glyph if the slot is bound
     * to an app window, else the bound key's friendly name, else "None".
     */
    private CharSequence boxText(IntPref keyPref, StrPref winPref) {
        String win = winPref.get();
        if (win != null && !win.isEmpty()) {
            // Inline icon + word, the same box style the media keys use (no keycap border,
            // which would collapse the box). The two buttons we ship an icon for render it
            // here as well, so a given button looks the same in the tooltip and in the box.
            if (win.equals(Prefs.APPS_WINDOW)) {
                return KeyBadges.iconLabel(this, R.drawable.ic_key_apps, "Apps");
            }
            if (win.equals(LIVETV_PKG)) {
                return KeyBadges.iconLabel(this, R.drawable.ic_key_livetv,
                        getString(R.string.key_live_tv));
            }
            // Everything else stays generic on purpose: one neutral marker plus the app
            // name, rather than pulling each app's own icon.
            String pkg = win.contains("/") ? win.substring(0, win.indexOf('/')) : win;
            return "⧉ " + appLabel(pkg);
        }
        int kc = keyPref.get();
        return kc == Prefs.DEFAULT_LAUNCH_KEYCODE
                ? getString(R.string.launch_key_none)
                : keycodeLabel(kc);
    }

    /** Best-effort human name for a package, falling back to the package id. */
    private CharSequence appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * Friendly name for a bound launch keycode. Covers the buttons a Fire remote
     * actually delivers to us (color, number, channel, media, captions/teletext);
     * anything else falls back to the framework name with KEYCODE_ stripped.
     */
    private CharSequence keycodeLabel(int kc) {
        switch (kc) {
            // Color buttons render as a matching colored circle + word.
            case android.view.KeyEvent.KEYCODE_PROG_RED: return KeyBadges.colorLabel(0, getString(R.string.key_red));
            case android.view.KeyEvent.KEYCODE_PROG_GREEN: return KeyBadges.colorLabel(1, getString(R.string.key_green));
            case android.view.KeyEvent.KEYCODE_PROG_YELLOW: return KeyBadges.colorLabel(2, getString(R.string.key_yellow));
            case android.view.KeyEvent.KEYCODE_PROG_BLUE: return KeyBadges.colorLabel(3, getString(R.string.key_blue));
            case android.view.KeyEvent.KEYCODE_CHANNEL_UP: return getString(R.string.key_channel_up);
            case android.view.KeyEvent.KEYCODE_CHANNEL_DOWN: return getString(R.string.key_channel_down);
            case android.view.KeyEvent.KEYCODE_CAPTIONS: return getString(R.string.key_subtitles);
            case android.view.KeyEvent.KEYCODE_TV_TELETEXT: return getString(R.string.key_teletext);
            // Media keys render as a vector icon + word (the Fire font shows the
            // media Unicode glyphs as color emoji).
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                return KeyBadges.iconLabel(this, R.drawable.ic_media_play, getString(R.string.key_play));
            case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                return KeyBadges.iconLabel(this, R.drawable.ic_media_rewind, getString(R.string.key_rewind));
            case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return KeyBadges.iconLabel(this, R.drawable.ic_media_forward, getString(R.string.key_forward));
            default: break;
        }
        if (kc >= android.view.KeyEvent.KEYCODE_0 && kc <= android.view.KeyEvent.KEYCODE_9) {
            return getString(R.string.key_number, kc - android.view.KeyEvent.KEYCODE_0);
        }
        String name = android.view.KeyEvent.keyCodeToString(kc);
        if (name != null && name.startsWith("KEYCODE_")) name = name.substring("KEYCODE_".length());
        return name;
    }

    /**
     * True on Fire OS 6/7 (API &lt;= 28), where a Home press is delayed by the
     * system app-switch lock. The instant-shortcut tooltips use this to add the
     * "no delay, unlike the Home button" note that only applies there.
     */
    private boolean isFos7OrOlder() {
        return android.os.Build.VERSION.SDK_INT <= 28;
    }

    /**
     * Verbose-logging row. Unlike the plain switch rows this one carries a
     * second focusable control on the right: a round button that opens
     * {@link LogViewerActivity}. The button is only visible while verbose
     * logging is on (see {@link #updateLogButtonVisibility}). Modelled on
     * the target row (focusable area on the left, round action button on
     * the right) so d-pad focus stays predictable.
     */
    private void addVerboseRow(LinearLayout content) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(ROW_PAD_V), dp(16), dp(ROW_PAD_V));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(getDrawable(R.drawable.row_focus_bg));

        final Switch sw = new Switch(this);
        sw.setChecked(prefs.isVerboseLogging());
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (suppressSwitchEvents) return;
                prefs.setVerboseLogging(isChecked);
                // Stamp the build right where a tester starts reproducing,
                // so the detailed lines that follow are never orphaned.
                if (isChecked) {
                    AppInfo.logIdentity(MainActivity.this, "HomeOnFire", "Verbose logging on");
                }
                updateLogButtonVisibility();
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = dp(16);
        row.addView(sw, swLp);

        TextView tv = new TextView(this);
        tv.setText(R.string.switch_verbose);
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
        row.setOnKeyListener(new RightNavGuard(new VerboseLongPressListener()));
        verboseRow = row;
        attachTip(row, getString(R.string.switch_verbose_tip));

        outer.addView(row, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        logBtn = buildLogButton();
        int size = dp(48);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(size, size);
        logLp.leftMargin = dp(12);
        outer.addView(logBtn, logLp);

        // Keep the field pointing at the switch so refresh / dependency
        // handling keeps working exactly as for the other rows.
        verboseSwitch = sw;
        content.addView(outer);
    }

    /**
     * Round button on the right of the verbose row that opens the
     * full-screen diagnostic log. A "description" vector icon tinted via
     * the same color selector as the header info button and the target
     * launch button (white normally, brand red on focus), so it stays
     * crisp and consistent at any density.
     */
    private ImageView buildLogButton() {
        ImageView btn = new ImageView(this);
        btn.setImageResource(R.drawable.ic_log);
        btn.setImageTintList(getResources().getColorStateList(R.color.info_button_text));
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int iconPad = dp(12);
        btn.setPadding(iconPad, iconPad, iconPad, iconPad);
        btn.setBackground(getDrawable(R.drawable.info_button_bg));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setContentDescription(getString(R.string.log_button_desc));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogViewerActivity.class));
            }
        });
        btn.setOnKeyListener(new RightNavGuard(null)); // rightmost in its row
        attachTip(btn, getString(R.string.log_button_tip));
        return btn;
    }

    /**
     * Shows the log button only while verbose logging is on AND the
     * accessibility service is enabled. Gating on the service too means the
     * diagnostic log is only reachable when the redirect can actually run,
     * so its empty-log explanation never has to cover a plain "service
     * switched off" case.
     */
    private void updateLogButtonVisibility() {
        if (logBtn != null) {
            boolean show = prefs.isVerboseLogging() && accessSwitch.isChecked();
            logBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        }
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

    /** Get/set bridge for an int-valued pref, so one stepper builder serves all rows. */
    private interface IntPref {
        int get();
        void set(int value);
    }

    /** Get/set bridge for a string-valued pref (a slot's app-window binding). */
    private interface StrPref {
        String get();
        void set(String value);
    }

    /**
     * Beta overlay-timing section: a heading plus three d-pad steppers (start cover
     * delay, end hold, fade) and a reset row, placed below the switch rows. Shown on
     * all Fire OS versions so the layout can be checked anywhere; the values only take
     * effect on Fire OS 6/7, where the masking overlay actually runs. Each stepper is
     * adjusted with ◀ / ▶ and writes straight to prefs, which HijackService reads live
     * on the next Home press (no service restart).
     */
    private void addTuningRows(LinearLayout content) {
        final LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setVisibility(View.GONE); // revealed by a long-press on the verbose row

        TextView heading = new TextView(this);
        heading.setText(R.string.tuning_heading);
        heading.setTextSize(13);
        heading.setAllCaps(true);
        heading.setTextColor(Colors.NEUTRAL);
        heading.setPadding(dp(16), dp(20), dp(16), dp(4));
        section.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView vGrace = makeStepperRow(section,
                getString(R.string.tuning_grace), getString(R.string.tuning_grace_tip),
                0, 300, 10, new IntPref() {
                    @Override public int get() { return prefs.getMaskGraceMs(); }
                    @Override public void set(int v) { prefs.setMaskGraceMs(v); }
                });
        final TextView vHold = makeStepperRow(section,
                getString(R.string.tuning_hold), getString(R.string.tuning_hold_tip),
                0, 1500, 50, new IntPref() {
                    @Override public int get() { return prefs.getMaskHoldMs(); }
                    @Override public void set(int v) { prefs.setMaskHoldMs(v); }
                });
        final TextView vFade = makeStepperRow(section,
                getString(R.string.tuning_fade), getString(R.string.tuning_fade_tip),
                0, 600, 25, new IntPref() {
                    @Override public int get() { return prefs.getMaskFadeMs(); }
                    @Override public void set(int v) { prefs.setMaskFadeMs(v); }
                });

        LinearLayout reset = makeTuningRowShell(getString(R.string.tuning_reset));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetMaskTuning();
                vGrace.setText(stepperLabel(prefs.getMaskGraceMs()));
                vHold.setText(stepperLabel(prefs.getMaskHoldMs()));
                vFade.setText(stepperLabel(prefs.getMaskFadeMs()));
                Toast.makeText(MainActivity.this,
                        getString(R.string.tuning_reset_done), Toast.LENGTH_SHORT).show();
            }
        });
        attachTip(reset, getString(R.string.tuning_reset_tip,
                Prefs.DEFAULT_MASK_GRACE, Prefs.DEFAULT_MASK_HOLD, Prefs.DEFAULT_MASK_FADE));
        section.addView(reset);

        tuningSection = section;
        content.addView(section);
    }

    /**
     * OnKeyListener that stops a d-pad RIGHT from jumping to a different row: it
     * only lets RIGHT through when the next focusable to the right sits in the
     * same row (a real right-hand button). Wraps an optional delegate for rows
     * that also handle OK / Back etc.
     */
    private static class RightNavGuard implements View.OnKeyListener {
        private final View.OnKeyListener delegate;
        RightNavGuard(View.OnKeyListener delegate) { this.delegate = delegate; }

        @Override
        public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
            if (delegate != null && delegate.onKey(v, keyCode, event)) return true;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                View target = v.focusSearch(View.FOCUS_RIGHT);
                if (target == null || !sameRow(v, target)) return true; // no right button in this row
            }
            return false;
        }
    }

    /** True when {@code from}'s vertical center falls within {@code to}'s bounds (i.e. same row). */
    private static boolean sameRow(View from, View to) {
        int[] pf = new int[2];
        int[] pt = new int[2];
        from.getLocationOnScreen(pf);
        to.getLocationOnScreen(pt);
        int fromCenterY = pf[1] + from.getHeight() / 2;
        return fromCenterY >= pt[1] && fromCenterY <= pt[1] + to.getHeight();
    }

    /**
     * Explicit up/down focus wiring for the right-hand controls (launch button,
     * launch-key box, log button). Without it, d-pad UP/DOWN hops between those
     * vertically-aligned right-column controls instead of moving to the
     * full-width row directly above/below.
     */
    private void wireVerticalNav() {
        View accessRow = rowOf(accessSwitch);
        View bootRow = rowOf(bootSwitch);
        View menuRow = rowOf(menuLpSwitch);
        ensureId(escapeLongPair);
        ensureId(escapeDoublePair);
        // The settings rows form one column on the left, with every right-hand control
        // hanging off the row it belongs to. That column is wired in BOTH directions,
        // because the rows are MATCH_PARENT wide: a focus search downwards has every
        // control below inside its beam and then picks purely by weighted distance,
        // which lets a value box or an escape switch one line further down beat the row
        // directly underneath. The right-hand controls stay reachable via RIGHT.
        View[] column = {initialFocus, accessRow, hijackRow, launchKeyRow, bootRow, menuRow,
                verboseRow};
        for (View v : column) {
            ensureId(v);
        }
        for (int i = 0; i + 1 < column.length; i++) {
            View above = column[i];
            View below = column[i + 1];
            if (above == null || below == null) continue;
            above.setNextFocusDownId(below.getId());
            below.setNextFocusUpId(above.getId());
        }
        if (launchBtn != null && accessRow != null) {
            launchBtn.setNextFocusDownId(accessRow.getId());
        }
        // The escape switches sit on the Replace-Home row and are reached with RIGHT,
        // exactly like the loading-cover box; UP/DOWN leaves the row to its neighbours
        // instead of hopping sideways between the two switches.
        for (View pair : new View[]{escapeLongPair, escapeDoublePair}) {
            if (pair == null) continue;
            if (accessRow != null) pair.setNextFocusUpId(accessRow.getId());
            if (launchKeyRow != null) pair.setNextFocusDownId(launchKeyRow.getId());
        }
        for (TextView box : new TextView[]{launcherBox, amazonBox, customBox}) {
            if (box == null) continue;
            if (hijackRow != null) box.setNextFocusUpId(hijackRow.getId());
            if (bootRow != null) box.setNextFocusDownId(bootRow.getId());
        }
        // Fire OS 7 loading-cover box on the Replace-Home row: the accessibility row is
        // above, the map-custom-buttons row below; reach it via RIGHT, leave via UP/DOWN.
        if (coverBox != null) {
            if (accessRow != null) coverBox.setNextFocusUpId(accessRow.getId());
            if (launchKeyRow != null) coverBox.setNextFocusDownId(launchKeyRow.getId());
        }
        if (logBtn != null && menuRow != null) {
            logBtn.setNextFocusUpId(menuRow.getId());
        }
    }

    private static View rowOf(View child) {
        return child == null ? null : (View) child.getParent();
    }

    private static void ensureId(View v) {
        if (v != null && v.getId() == View.NO_ID) v.setId(View.generateViewId());
    }

    /**
     * Focusable row shell shared by the tuning rows: padded horizontal layout with
     * the focus background and a weight-1 label on the left. Callers append their
     * own right-hand content and listeners.
     */
    private LinearLayout makeTuningRowShell(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(ROW_PAD_V), dp(16), dp(ROW_PAD_V));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setBackground(getDrawable(R.drawable.row_focus_bg));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(18);
        tv.setTextColor(Colors.NEUTRAL);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * Builds one stepper row: label on the left, current value on the right shown as
     * "◀ N ms ▶". The row is focusable; ◀ / ▶ (d-pad left/right) decrement/increment by
     * {@code step}, clamped to [min, max], and persist via {@code pref}. Returns the
     * value view so the reset row can refresh it.
     */
    private TextView makeStepperRow(LinearLayout content, String label, String tip,
                                    final int min, final int max, final int step,
                                    final IntPref pref) {
        LinearLayout row = makeTuningRowShell(label);

        final TextView value = new TextView(this);
        value.setTextSize(18);
        value.setTextColor(Colors.WHITE);
        value.setMinWidth(dp(128));
        value.setGravity(Gravity.CENTER);
        value.setText(stepperLabel(pref.get()));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                boolean isLeft = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT;
                boolean isRight = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                if (!isLeft && !isRight) return false;
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    int cur = pref.get();
                    int next = cur + (isLeft ? -step : step);
                    if (next < min) next = min;
                    if (next > max) next = max;
                    // Skip no-op writes: a held key repeats at ~25 Hz and would
                    // otherwise re-persist the clamped edge value on every repeat.
                    if (next != cur) {
                        pref.set(next);
                        value.setText(stepperLabel(next));
                    }
                }
                return true; // own left/right entirely so focus never moves off the row
            }
        });
        attachTip(row, tip);
        content.addView(row);
        return value;
    }

    /** Formats a stepper value with the ◀ / ▶ affordance, e.g. "◀  500 ms  ▶". */
    private String stepperLabel(int ms) {
        return "◀  " + ms + " ms  ▶";
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
        // A mapped app can destroy this screen mid-learn; put the custom dialog back
        // first, so the row that started the learn exists again before we bind into it.
        if (sCustomDialogOpen && customDialog == null) {
            if (prefs.isLaunchKeyEnabled()) showCustomMapsDialog();
            else sCustomDialogOpen = false; // feature switched off meanwhile
        }
        // If a branded button was learned while we were backgrounded, finish it now
        // that we're front again (this is also where the content-app warning shows).
        processPendingLearn();
    }

    @Override
    protected void onDestroy() {
        // Dismiss rather than leak the window. The teardown flag keeps the dismiss
        // handler from applying the "closed by the user" rules (dropping app-less rows,
        // cancelling the learn), because this screen is coming straight back.
        if (customDialog != null) {
            customDialogTearingDown = true;
            customDialog.dismiss();
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // We deliberately do NOT cancel an in-progress learn here. Pressing a branded
        // button (Apps, Live TV, ...) opens its app and backgrounds this screen, and
        // that window is exactly what we're trying to capture. A learn is instead ended
        // by Back (box listener), by a "leaving" window (Amazon home or the target
        // launcher, meaning the user bailed via Home), or by onDestroy.
    }

    // onDestroy deliberately does not cancel a learn either: pressing a branded button
    // opens its app and may stop/destroy this screen right before its window arrives, so
    // cancelling there would drop the very capture we want. An armed learn instead ends
    // when a window actually arrives (captured, or ignored if it is a shell/leaving surface).

    /** Builds the persistent brand-colored title bar with logo, name and info button. */
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
        row.setPadding(dp(16), dp(ROW_PAD_V), dp(16), dp(ROW_PAD_V));
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
        // Switch rows have no right-hand button; don't let RIGHT jump to another row.
        row.setOnKeyListener(new RightNavGuard(null));
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
        updateCustomBox(); // an app behind a mapping may have been installed or removed
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
            launchKeyEnabledSwitch.setChecked(prefs.isLaunchKeyEnabled());
        } finally {
            suppressSwitchEvents = false;
        }
        // Not switches, but the same "re-read prefs" moment; skip the box that is
        // mid-learn so we don't overwrite its "Press a button…" prompt.
        if (launcherBox != null && learningBox != launcherBox) {
            launcherBox.setText(boxText(launcherPref(), launcherWinPref()));
        }
        if (amazonBox != null && learningBox != amazonBox) {
            amazonBox.setText(boxText(amazonPref(), amazonWinPref()));
        }
        if (coverBox != null) coverBox.setText(coverBoxText());
        // The switches inside these blocks carry no listener, so setting them here
        // cannot write back into prefs.
        if (escapeLongPair != null && escapeLongPair.getTag() instanceof Switch) {
            ((Switch) escapeLongPair.getTag()).setChecked(prefs.isEscapeLongPress());
        }
        if (escapeDoublePair != null && escapeDoublePair.getTag() instanceof Switch) {
            ((Switch) escapeDoublePair.getTag()).setChecked(prefs.isEscapeDoublePress());
        }
    }

    /**
     * The four feature switches (Replace Home, Launch on boot, Verbose
     * logging, Menu long-press) only do anything when the accessibility
     * service is enabled. Gray them out when it isn't so the UI makes
     * the dependency obvious.
     */
    private void updateDependentSwitches() {
        boolean accessOn = accessSwitch.isChecked();
        View focusedBefore = getCurrentFocus();
        setRowEnabled(hijackSwitch, accessOn);
        setRowEnabled(bootSwitch, accessOn);
        setRowEnabled(verboseSwitch, accessOn);
        setRowEnabled(menuLpSwitch, accessOn);
        setRowEnabled(launchKeyEnabledSwitch, accessOn);
        // The value box is gated on both the service AND the shortcut toggle.
        updateLaunchKeyBoxState();
        updateCoverBoxState();
        updateEscapeBoxState();
        updateLogButtonVisibility();
        // The tuning section's only reveal/hide anchor is the verbose row, which is
        // unfocusable while the service is off; collapse the section so it cannot
        // get stuck open with no way left to close it.
        if (!accessOn && tuningSection != null
                && tuningSection.getVisibility() == View.VISIBLE) {
            tuningSection.setVisibility(View.GONE);
        }
        // If the row that held d-pad focus just became non-focusable
        // (e.g. the service was disabled via ADB while a feature row was
        // focused, picked up on resume) or vanished with the collapsed
        // tuning section, move focus to the accessibility row, which is
        // never disabled, so the screen doesn't lose focus.
        if (focusedBefore != null
                && (!focusedBefore.isFocusable() || !focusedBefore.isShown())) {
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
