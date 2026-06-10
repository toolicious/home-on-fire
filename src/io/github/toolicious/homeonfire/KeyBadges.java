// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ReplacementSpan;

/**
 * Renders remote-button references ("Home", "Back", "OK", "Menu",
 * each preceded by a Unicode glyph) inside small rounded "keycap"
 * boxes wherever they appear in UI text. Shared by the configuration
 * screen and the info dialog so both surfaces show buttons the same
 * way.
 */
public final class KeyBadges {

    private KeyBadges() {}

    /**
     * Glyph-plus-label pairs that identify remote buttons in UI text.
     * Both regular-space and NBSP-joined variants are listed because
     * switch labels use a normal space (which can wrap freely) while
     * tooltips use NBSP (no wrap between glyph and word).
     */
    private static final String[] KEY_LABELS = {
            "⌂ Home", "⌂ Home",
            "↩ Back", "↩ Back",
            "◉ OK",   "◉ OK",
            "☰ Menu", "☰ Menu",
    };

    /**
     * Scans {@code text} for any of {@link #KEY_LABELS} and wraps each
     * occurrence in a {@link KeyBadgeSpan} rounded keycap box.
     */
    public static CharSequence wrap(Context ctx, CharSequence text) {
        int padHor = UiUtil.dp(ctx, 5);
        int padVer = UiUtil.dp(ctx, 1);
        int stroke = UiUtil.dp(ctx, 1);
        SpannableString out = new SpannableString(text);
        String src = text.toString();
        for (String key : KEY_LABELS) {
            int from = 0;
            while (true) {
                int idx = src.indexOf(key, from);
                if (idx < 0) break;
                out.setSpan(new KeyBadgeSpan(padHor, padVer, stroke, Colors.NEUTRAL),
                        idx, idx + key.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = idx + key.length();
            }
        }
        return out;
    }

    /**
     * Replacement span that draws a thin rounded-corner outline
     * around its text run, like a keyboard-key glyph. Width is the
     * measured text plus horizontal padding on each side; box height
     * tracks the line metrics so it sits flush with the rest of the
     * label.
     */
    static class KeyBadgeSpan extends ReplacementSpan {
        private final float padHor;
        private final float padVer;
        private final float strokeW;
        private final int borderColor;

        KeyBadgeSpan(float padHor, float padVer, float strokeW, int borderColor) {
            this.padHor = padHor;
            this.padVer = padVer;
            this.strokeW = strokeW;
            this.borderColor = borderColor;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return (int) Math.ceil(paint.measureText(text, start, end) + padHor * 2);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            float textWidth = paint.measureText(text, start, end);
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(strokeW);
            border.setColor(borderColor);
            float left = x;
            float right = x + textWidth + padHor * 2;
            float t = top + padVer;
            float b = bottom - padVer;
            float radius = (b - t) * 0.18f;
            canvas.drawRoundRect(left, t, right, b, radius, radius, border);
            canvas.drawText(text, start, end, x + padHor, y, paint);
        }
    }
}
