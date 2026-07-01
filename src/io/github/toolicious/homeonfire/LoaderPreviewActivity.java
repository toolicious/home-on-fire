// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen preview of {@link LoaderView}, reachable as a hidden easter egg by
 * activating the logo in the info dialog. The animation plays once, then fades out
 * over the configured overlay-fade duration and closes, so it doubles as a way to
 * preview the fade. Back or OK closes it early; relaunch from the logo to replay.
 */
public class LoaderPreviewActivity extends Activity {

    private LoaderView loader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The activity is translucent (see the manifest theme): the loader paints
        // opaque while playing, then fades to transparent at the end, revealing the
        // screen behind it. So there are no opaque backgrounds here.
        final Prefs prefs = new Prefs(this);

        final FrameLayout root = new FrameLayout(this);

        loader = new LoaderView(this);
        loader.setCaptions(getString(R.string.loader_caption_loading),
                getString(R.string.loader_caption_ready));
        loader.setLooping(false); // play once, then fade out

        // Dim close hint, just above the animated progress bar at the bottom.
        final TextView hint = new TextView(this);
        hint.setText(R.string.loader_preview_hint);
        hint.setTextSize(14);
        hint.setTextColor(0x80FFFFFF);

        // On end: fade the whole overlay (loader + hint) out uniformly over the
        // configured fade, revealing the translucent screen behind, then close.
        loader.setOnEndListener(new LoaderView.OnEndListener() {
            @Override
            public void onEnd() {
                root.animate().alpha(0f).setDuration(prefs.getMaskFadeMs())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() { finish(); }
                        }).start();
            }
        });

        root.addView(loader, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = dp(22);
        root.addView(hint, hintLp);

        setContentView(root);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
