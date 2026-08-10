// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;

/**
 * Renders remote-button references inside small rounded "keycap" boxes wherever
 * they appear in UI text. Three kinds:
 *  - text keys ("Home", "Back", "OK", "Menu"): a Unicode glyph plus the word, boxed;
 *  - icon keys ("Apps"): a vector icon plus the word, boxed;
 *  - inline media icons (rewind / play / fast-forward): a vector icon with no box,
 *    because the Fire TV font renders the media glyphs as color emoji.
 * Non-ASCII glyphs are written as \\u escapes so the source stays byte-identical
 * to the matching strings.xml tokens.
 */
public final class KeyBadges {

    private KeyBadges() {}

    /** Non-breaking space: tooltips join glyph and word with it so a badge never wraps. */
    private static final String NB = " ";

    /** Glyph-plus-label pairs identifying text-only remote buttons (NBSP + space variants). */
    private static final String[] KEY_LABELS = {
            "⌂" + NB + "Home", "⌂ Home",
            "↩" + NB + "Back", "↩ Back",
            "◉" + NB + "OK",   "◉ OK",
            "☰" + NB + "Menu", "☰ Menu",
    };

    /** An icon-plus-word keycap: the in-text markers to match, the word, and its vector icon. */
    private static final class IconKey {
        final String[] tokens;
        final String label;
        final int iconRes;
        IconKey(String[] tokens, String label, int iconRes) {
            this.tokens = tokens;
            this.label = label;
            this.iconRes = iconRes;
        }
    }

    /** The "⊞ Apps" and "📺 Live TV" markers become vector icon keycaps. */
    private static final IconKey[] ICON_KEYS = {
            new IconKey(new String[]{"⊞" + NB + "Apps", "⊞ Apps"},
                    "Apps", R.drawable.ic_key_apps),
            new IconKey(new String[]{"📺" + NB + "Live TV", "📺 Live TV"},
                    "Live TV", R.drawable.ic_key_livetv),
    };

    // Single-char markers in the tooltip, each replaced by an inline (no keycap)
    // media icon. They are never displayed, so their own font glyph does not matter.
    private static final char MEDIA_REWIND  = '⏪';
    private static final char MEDIA_PLAY    = '⏵';
    private static final char MEDIA_FORWARD = '⏩';

    /** Fill colors for the four remote color-button circles (red, green, yellow, blue). */
    private static final int[] DOT_COLORS = {0xFFFF5252, 0xFF4CAF50, 0xFFFFEB3B, 0xFF448AFF};

    /**
     * Scans {@code text} for any known key token and decorates each occurrence:
     * a boxed text/icon keycap, an inline media icon, or a colored circle.
     */
    public static CharSequence wrap(Context ctx, CharSequence text) {
        int padHor = UiUtil.dp(ctx, 5);
        int padVer = UiUtil.dp(ctx, 1);
        int stroke = UiUtil.dp(ctx, 1);
        int iconGap = UiUtil.dp(ctx, 3);
        SpannableString out = new SpannableString(text);
        String src = text.toString();

        for (String key : KEY_LABELS) {
            for (int idx = src.indexOf(key); idx >= 0; idx = src.indexOf(key, idx + key.length())) {
                out.setSpan(new KeyBadgeSpan(padHor, padVer, stroke, Colors.NEUTRAL, null, null, 0, true),
                        idx, idx + key.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        for (IconKey ik : ICON_KEYS) {
            Drawable icon = loadTinted(ctx, ik.iconRes);
            for (String token : ik.tokens) {
                for (int idx = src.indexOf(token); idx >= 0; idx = src.indexOf(token, idx + token.length())) {
                    out.setSpan(new KeyBadgeSpan(padHor, padVer, stroke, Colors.NEUTRAL, icon, ik.label, iconGap, true),
                            idx, idx + token.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        applyMediaIcon(ctx, out, src, MEDIA_REWIND,  R.drawable.ic_media_rewind,  padVer);
        applyMediaIcon(ctx, out, src, MEDIA_PLAY,    R.drawable.ic_media_play,    padVer);
        applyMediaIcon(ctx, out, src, MEDIA_FORWARD, R.drawable.ic_media_forward, padVer);

        // Tint the "colored buttons" circles so the launch-key tooltip shows the
        // actual button colors instead of the color words.
        for (int ci = 0, from = 0; ci < DOT_COLORS.length; ci++) {
            int idx = src.indexOf('●', from);
            if (idx < 0) break;
            out.setSpan(new ForegroundColorSpan(DOT_COLORS[ci]), idx, idx + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = idx + 1;
        }
        return out;
    }

    private static void applyMediaIcon(Context ctx, SpannableString out, String src,
                                       char marker, int iconRes, int padVer) {
        Drawable icon = loadTinted(ctx, iconRes);
        for (int idx = src.indexOf(marker); idx >= 0; idx = src.indexOf(marker, idx + 1)) {
            out.setSpan(new KeyBadgeSpan(0, padVer, 0, Colors.NEUTRAL, icon, null, 0, false),
                    idx, idx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /**
     * Inline "media icon + label" (no keycap), used to show a bound media key in
     * the value box, e.g. a play triangle followed by "Play".
     */
    public static CharSequence iconLabel(Context ctx, int iconRes, String label) {
        int padVer = UiUtil.dp(ctx, 1);
        SpannableString ss = new SpannableString("￼ " + label);
        ss.setSpan(new KeyBadgeSpan(0, padVer, 0, Colors.NEUTRAL, loadTinted(ctx, iconRes), null, 0, false),
                0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /**
     * Inline "colored circle + label", used to show a bound color button in the
     * value box, e.g. a red dot followed by "Red". {@code colorIndex} indexes
     * {@link #DOT_COLORS} (0=red, 1=green, 2=yellow, 3=blue), the same palette as
     * the tooltip circles.
     */
    public static CharSequence colorLabel(int colorIndex, String text) {
        SpannableString ss = new SpannableString("● " + text);
        ss.setSpan(new ForegroundColorSpan(DOT_COLORS[colorIndex]), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private static Drawable loadTinted(Context ctx, int resId) {
        Drawable d = ctx.getDrawable(resId);
        if (d != null) {
            d = d.mutate();
            d.setTint(Colors.NEUTRAL);
        }
        return d;
    }

    /**
     * Replacement span drawing an optional rounded outline plus, inside it, either
     * the run's text, a vector icon, or an icon followed by a label. With
     * {@code border == false} it draws just the icon inline (no box, no padding).
     */
    static class KeyBadgeSpan extends ReplacementSpan {
        private final float padHor;
        private final float padVer;
        private final float strokeW;
        private final int borderColor;
        private final Drawable icon;   // null for text badges
        private final String label;    // null for icon-only badges
        private final float iconGap;
        private final boolean border;

        KeyBadgeSpan(float padHor, float padVer, float strokeW, int borderColor,
                     Drawable icon, String label, float iconGap, boolean border) {
            this.padHor = padHor;
            this.padVer = padVer;
            this.strokeW = strokeW;
            this.borderColor = borderColor;
            this.icon = icon;
            this.label = label;
            this.iconGap = iconGap;
            this.border = border;
        }

        /** Icon side length, sized to the text's visual height so it reads like a cap-height glyph. */
        private float iconSize(Paint paint) {
            return (paint.descent() - paint.ascent()) * 0.92f;
        }

        private float hpad() {
            return border ? padHor : 0f;
        }

        private float contentWidth(Paint paint, CharSequence text, int start, int end) {
            if (icon != null) {
                return iconSize(paint) + (label != null ? iconGap + paint.measureText(label) : 0f);
            }
            return paint.measureText(text, start, end);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return (int) Math.ceil(contentWidth(paint, text, start, end) + hpad() * 2);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            float hp = hpad();
            if (border) {
                float contentW = contentWidth(paint, text, start, end);
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setStyle(Paint.Style.STROKE);
                bp.setStrokeWidth(strokeW);
                bp.setColor(borderColor);
                float t = top + padVer;
                float b = bottom - padVer;
                float radius = (b - t) * 0.18f;
                canvas.drawRoundRect(x, t, x + contentW + hp * 2, b, radius, radius, bp);
            }
            if (icon != null) {
                float h = iconSize(paint);
                // Center the icon vertically within the text's ascent..descent band.
                float iconTop = y + paint.ascent() + ((paint.descent() - paint.ascent()) - h) / 2f;
                int ix = Math.round(x + hp);
                int iy = Math.round(iconTop);
                icon.setBounds(ix, iy, Math.round(ix + h), Math.round(iy + h));
                icon.draw(canvas);
                if (label != null) {
                    canvas.drawText(label, x + hp + h + iconGap, y, paint);
                }
            } else {
                canvas.drawText(text, start, end, x + hp, y, paint);
            }
        }
    }
}
