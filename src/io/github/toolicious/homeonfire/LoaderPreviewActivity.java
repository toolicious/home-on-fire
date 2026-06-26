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
 * activating the logo in the info dialog. It exists purely so the loading
 * animation can be watched on the device without wiring it into the real
 * Home-redirect flow yet. The animation loops here; Back or OK closes it.
 */
public class LoaderPreviewActivity extends Activity {

    private LoaderView loader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF100F0D);

        loader = new LoaderView(this);
        loader.setCaptions(getString(R.string.loader_caption_loading),
                getString(R.string.loader_caption_ready));
        loader.setLooping(true);
        root.addView(loader, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Dim close hint, just above the animated progress bar at the bottom.
        TextView hint = new TextView(this);
        hint.setText(R.string.loader_preview_hint);
        hint.setTextSize(14);
        hint.setTextColor(0x80FFFFFF);
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
