// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import java.util.ArrayList;
import java.util.List;

/**
 * One user-defined "this button opens that app" mapping, as configured in the
 * Custom dialog of the "Map custom buttons" row. It uses the same two trigger
 * forms as the two fixed slots: a key code (the button sends one) or an app
 * window (the button opens an Amazon app instead of sending a key, so the app
 * showing up is the only signal we get).
 *
 * Persisted as one line per mapping with '|' between the fields:
 * {@code app|keyCode|window}. Neither a package name nor a key code can contain
 * that character, so nothing needs escaping.
 *
 * A row with an empty {@link #app} is inert and exists only while the dialog is
 * open (the user cleared the app field but hasn't closed the dialog yet). Rows
 * keep their position so a mapping stays addressable by index even if the config
 * screen is destroyed mid-learn.
 */
public final class CustomMap {

    private static final String FIELD_SEP = "|";
    private static final String ROW_SEP = "\n";

    /** Package to open. Empty while no app is picked, which makes the row inert. */
    public final String app;
    /** Bound key code, or {@link Prefs#DEFAULT_LAUNCH_KEYCODE} when none is bound. */
    public final int keyCode;
    /** Package whose window triggers this mapping, or {@link Prefs#NO_WINDOW}. */
    public final String window;

    public CustomMap(String app, int keyCode, String window) {
        this.app = app == null ? "" : app;
        this.keyCode = keyCode;
        this.window = window == null ? Prefs.NO_WINDOW : window;
    }

    /** True once the row does something: an app to open and a button that opens it. */
    public boolean isActive() {
        return !app.isEmpty()
                && (keyCode != Prefs.DEFAULT_LAUNCH_KEYCODE || !window.isEmpty());
    }

    /** Copy of this mapping with a different app, keeping the bound button. */
    public CustomMap withApp(String newApp) {
        return new CustomMap(newApp, keyCode, window);
    }

    /** Copy of this mapping with a different key code, keeping the app. */
    public CustomMap withKeyCode(int newKeyCode) {
        return new CustomMap(app, newKeyCode, window);
    }

    /** Copy of this mapping with a different window trigger, keeping the app. */
    public CustomMap withWindow(String newWindow) {
        return new CustomMap(app, keyCode, newWindow);
    }

    /** Parses the stored form; unreadable lines are skipped rather than failing the lot. */
    static List<CustomMap> parse(String raw) {
        List<CustomMap> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split(ROW_SEP, -1)) {
            if (line.isEmpty()) continue;
            int first = line.indexOf(FIELD_SEP);
            int second = first < 0 ? -1 : line.indexOf(FIELD_SEP, first + 1);
            if (first < 0 || second < 0) continue;
            int keyCode;
            try {
                keyCode = Integer.parseInt(line.substring(first + 1, second));
            } catch (NumberFormatException e) {
                keyCode = Prefs.DEFAULT_LAUNCH_KEYCODE;
            }
            out.add(new CustomMap(line.substring(0, first), keyCode, line.substring(second + 1)));
        }
        return out;
    }

    static String serialize(List<CustomMap> maps) {
        StringBuilder sb = new StringBuilder();
        for (CustomMap m : maps) {
            if (sb.length() > 0) sb.append(ROW_SEP);
            sb.append(m.app).append(FIELD_SEP).append(m.keyCode).append(FIELD_SEP).append(m.window);
        }
        return sb.toString();
    }
}
