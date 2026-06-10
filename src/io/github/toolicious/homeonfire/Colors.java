// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

/**
 * Color constants for the programmatically-built UI. Mirrors the
 * named entries in {@code res/values/colors.xml} so both Java and
 * XML drawables share a single canonical palette. Keep both in sync.
 */
public final class Colors {

    private Colors() {}

    /** Mirrors {@code @color/brand}. Home on Fire red. */
    public static final int BRAND = 0xFFB61414;

    /** Mirrors {@code @color/text_white}. */
    public static final int WHITE = 0xFFFFFFFF;

    /** Mirrors {@code @color/text_neutral}. Light grey for labels. */
    public static final int NEUTRAL = 0xFFE0E0E0;

    /** Mirrors {@code @color/surface_tip_box}. The pinned tip strip. */
    public static final int TIP_BG = 0xFF2A2A2A;
}
