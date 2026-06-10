// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Modal "about" dialog shown when the user activates the round info
 * button in the top bar. Holds no instance state; everything goes
 * through {@link #show(Activity)}.
 *
 * Built programmatically (no XML layout) so the logo can sit above
 * the text and the OK button can be vertically aligned on the centre
 * axis. AlertDialog's own positive button would otherwise live
 * right-aligned at the bottom.
 */
public final class InfoDialog {

    private InfoDialog() {}

    public static void show(final Activity host) {
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiUtil.dp(host, 24), UiUtil.dp(host, 16),
                UiUtil.dp(host, 24), UiUtil.dp(host, 8));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        // Opaque fill behind the content. The Material dialog window
        // background stays untouched (its insets are what make the
        // FLAG_DIM_BEHIND machinery work); replacing it with a flat
        // ColorDrawable kills the dim on Fire OS regardless of
        // declared opacity.
        body.setBackgroundColor(Colors.TIP_BG);

        ImageView dialogLogo = new ImageView(host);
        dialogLogo.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                UiUtil.dp(host, 80), UiUtil.dp(host, 80));
        dlLp.bottomMargin = UiUtil.dp(host, 12);
        body.addView(dialogLogo, dlLp);

        body.addView(makeCenteredText(host, host.getString(R.string.app_name),
                22, Colors.NEUTRAL));

        String versionName = host.getString(R.string.info_dialog_version_unknown);
        try {
            String v = host.getPackageManager()
                    .getPackageInfo(host.getPackageName(), 0).versionName;
            if (v != null) versionName = v;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        body.addView(makeCenteredText(host,
                host.getString(R.string.info_dialog_version, versionName),
                14, Colors.NEUTRAL));

        final String repoUrl = host.getString(R.string.repo_url);
        TextView link = makeLinkRow(host, repoUrl, 12, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl));
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    host.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(host, R.string.toast_no_browser,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLp.topMargin = UiUtil.dp(host, 4);
        linkLp.bottomMargin = UiUtil.dp(host, 12);
        linkLp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(link, linkLp);

        // Clickable "Open Fire OS settings" line. Same visual style
        // as the GitHub link above so the two read as a pair.
        final AlertDialog[] dialogRef = new AlertDialog[1];
        TextView deviceInfo = makeLinkRow(host,
                host.getString(R.string.info_dialog_device_info_link),
                14, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (dialogRef[0] != null) dialogRef[0].dismiss();
                        SettingsLauncher.open(host);
                    }
                });
        LinearLayout.LayoutParams diLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        diLp.bottomMargin = UiUtil.dp(host, 12);
        diLp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(deviceInfo, diLp);

        body.addView(makeCenteredText(host,
                KeyBadges.wrap(host, host.getString(R.string.info_dialog_description)),
                14, Colors.NEUTRAL));

        // Wrap in a ScrollView so the OK button is never clipped off the
        // bottom on short screens or large display-font scales (AlertDialog
        // does not auto-scroll a custom setView panel). The scroller is
        // transparent, so the opaque Colors.TIP_BG fill stays on `body`
        // and the FLAG_DIM_BEHIND behaviour is unaffected.
        ScrollView scroller = new ScrollView(host);
        scroller.addView(body);
        final AlertDialog dialog = new AlertDialog.Builder(
                host, R.style.AppDialogTheme)
                .setView(scroller)
                .create();
        dialogRef[0] = dialog;

        Button okBtn = new Button(host);
        okBtn.setText(R.string.info_dialog_ok);
        okBtn.setAllCaps(false);
        okBtn.setTextColor(Colors.WHITE);
        okBtn.setBackground(host.getDrawable(R.drawable.info_dialog_ok_bg));
        okBtn.setPadding(UiUtil.dp(host, 28), UiUtil.dp(host, 8),
                UiUtil.dp(host, 28), UiUtil.dp(host, 8));
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.topMargin = UiUtil.dp(host, 16);
        okLp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(okBtn, okLp);

        dialog.show();
        // Re-state the dim flag + amount explicitly. The Material
        // window background drawable stays untouched (any override
        // here breaks FLAG_DIM_BEHIND on Fire OS).
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.08f;
            w.setAttributes(lp);
        }
        okBtn.requestFocus();
    }

    /**
     * Underlined, focusable, clickable centred text line: the visual
     * style used for both the GitHub repo link and the Fire-OS-settings
     * shortcut. Shares row_focus_bg so the focus highlight matches the
     * rest of the app.
     */
    private static TextView makeLinkRow(Activity host,
                                         String text,
                                         int sizeSp,
                                         View.OnClickListener onClick) {
        TextView tv = makeCenteredText(host, text, sizeSp, Colors.WHITE);
        tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setBackground(host.getDrawable(R.drawable.row_focus_bg));
        tv.setPadding(UiUtil.dp(host, 12), UiUtil.dp(host, 8),
                UiUtil.dp(host, 12), UiUtil.dp(host, 8));
        tv.setOnClickListener(onClick);
        return tv;
    }

    /**
     * Builds a TextView that fills the parent width and centres its
     * text. Every line in the dialog uses this so the column lines up
     * regardless of intrinsic text width.
     */
    private static TextView makeCenteredText(
            Activity host, CharSequence text, int sizeSp, int color) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }
}
