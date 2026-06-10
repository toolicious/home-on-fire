// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;

/**
 * Small UI helpers shared by Activities and dialog builders.
 */
public final class UiUtil {

    private UiUtil() {}

    /** Converts a dp value to integer pixels using the given context's display density. */
    public static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
