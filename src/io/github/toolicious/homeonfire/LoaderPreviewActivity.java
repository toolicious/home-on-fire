// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Full-screen preview of {@link LoaderView}, reachable as a hidden easter egg by
 * activating the logo in the info dialog. The animation plays once, then fades out
 * over the configured overlay-fade duration and closes, so it doubles as a way to
 * preview the fade. Back or OK closes it early; relaunch from the logo to replay.
 */
public class LoaderPreviewActivity extends Activity {

    /** Intent extra (boolean): preview the plain black screen instead of the animation. */
    public static final String EXTRA_BLACK = "black";
    /** How long the static black-screen preview holds before it auto-fades. */
    private static final long BLACK_PREVIEW_MS = 3000L;

    private LoaderView loader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The activity is translucent (see the manifest theme): the loader paints
        // opaque while playing, then fades to transparent at the end, revealing the
        // screen behind it. So there are no opaque backgrounds here.
        final Prefs prefs = new Prefs(this);

        final FrameLayout root = new FrameLayout(this);
        final boolean black = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_BLACK, false);

        loader = new LoaderView(this);
        loader.setCaptions(getString(R.string.loader_caption_loading),
                getString(R.string.loader_caption_ready));
        loader.setLooping(false); // play once, then fade out
        loader.setBlackScreen(black);

        // On end (or, for the static black screen, after a short beat) fade the whole
        // overlay out over the configured fade, revealing the screen behind, then close.
        if (black) {
            // The black screen is static (no end event fires), so auto-fade after a
            // short beat to mimic the launcher taking over; Back / OK closes it sooner.
            root.postDelayed(new Runnable() {
                @Override
                public void run() { fadeOutAndClose(root, prefs.getMaskFadeMs()); }
            }, BLACK_PREVIEW_MS);
        } else {
            loader.setOnEndListener(new LoaderView.OnEndListener() {
                @Override
                public void onEnd() { fadeOutAndClose(root, prefs.getMaskFadeMs()); }
            });
        }

        root.addView(loader, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Only a fresh OK press closes the preview. When it was opened by a long-press
            // on the cover box, the key is still held as this activity comes up, so its
            // auto-repeat DOWNs (repeatCount > 0) arrive here; finishing on those would
            // close it instantly (a flash then gone). Swallow them without closing.
            if (event.getRepeatCount() == 0) finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Fades the whole preview overlay out over {@code fadeMs}, then closes. */
    private void fadeOutAndClose(final android.view.View overlay, long fadeMs) {
        overlay.animate().alpha(0f).setDuration(fadeMs)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() { finish(); }
                }).start();
    }
}
