// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Native port of the "FireTV Launcher Loader" animation (originally a
 * browser/React scene, {@code scene.jsx}). It is the loading screen meant to
 * MASK the unavoidable ~5 s Home-button delay on Fire OS 7, where a background
 * activity start is queued behind {@code APP_SWITCH_DELAY_TIME}. Rendering it
 * as plain Canvas drawing (rather than a WebView running React + remote fonts)
 * means it can appear in the very first frame with no cold-start, no network
 * and no extra permission, which is exactly what masking the gap requires.
 *
 * The choreography is taken verbatim from the source scene: every keyframe
 * table, easing and timeline constant matches, so the motion is identical. The
 * whole thing is expressed as pure functions of time, so it ports cleanly to an
 * {@code interpolate(t, times, values, easings)} helper driven off the frame
 * clock.
 *
 * Two deliberate substitutions for the preview build:
 *  - Font: the source uses Baloo 2 (a rounded heavy face) loaded from Google
 *    Fonts. Here the built-in {@code sans-serif-black} stands in so nothing has
 *    to be bundled or fetched; the exact face can be packaged later.
 *  - Glows/shadows: the CSS blur shadows are approximated with cached radial
 *    gradients instead of {@code BlurMaskFilter}/{@code setShadowLayer}, so the
 *    view stays fully hardware-accelerated at 60 fps on Fire OS 7 (API 28).
 *
 * Every current use plays it once ({@code setLooping(false)}): the Fire OS 7
 * masking overlay is torn down on the target's window-state event, and the
 * easter-egg preview fades itself out at the end (via {@link OnEndListener}).
 * Seamless looping, with the seam's dip to black exactly as in the source,
 * stays available via {@code setLooping(true)}.
 */
public class LoaderView extends View {

    // ── Stage geometry (design space; matches scene.jsx) ─────────────────────
    private static final float W = 1920f, H = 1080f;
    private static final float CX = W / 2f, CY = H / 2f;

    // ── Palette ──────────────────────────────────────────────────────────────
    private static final int ORANGE      = 0xFFFF9E3D;
    private static final int ORANGE_DEEP = 0xFFF08A22;
    private static final int INK         = 0xFF100F0D;
    private static final int GLOW_RGB    = 0x00FF8C2A; // rgb(255,140,42), alpha set per use

    // ── Timeline (seconds) ────────────────────────────────────────────────────
    private static final float T_END  = 6.4f;  // full loop
    private static final float T_WAIT = 5.0f;  // the real "bridge": progress fills over this span
    private static final float T_GONE = 4.86f; // the "a" is fully flicked off by here
    private static final float T_HOLD = 6.0f;  // one-shot hold frame ("Ready"), before the loop-fade dips to black

    /** Global playback-speed multiplier. >1 plays the whole animation faster so the punchline
     *  (the "a" flicked off, around T_GONE) lands well before the FOS7 launcher cuts the mask. */
    private static final float SPEED = 1.25f;

    // ── Easing identifiers (only those scene.jsx actually uses) ───────────────
    private static final int LINEAR = 0, OUT_CUBIC = 1, OUT_QUAD = 2,
            INOUT_CUBIC = 3, INOUT_SINE = 4, OUT_BACK = 5, IN_CUBIC = 6;

    // ── Choreography keyframe tables (from scene.jsx) ─────────────────────────
    // Horizontal centre of the "a": barges in from the right, shoved back three
    // times, creeps to its closest point, then the cursor flings it off-screen.
    // Deviation from the source: the fly-in segment (0.55-1.15) is LINEAR, not
    // ease-out, so the "a" arrives at full speed and only brakes in the recoil
    // AFTER the first impact; braking before contact made the collision read late.
    private static final float[] AX_T = {0.00f, 0.55f, 1.15f, 1.55f, 2.10f, 2.45f, 2.95f, 3.25f, 3.80f, 4.30f, 4.58f, T_GONE, T_END};
    private static final float[] AX_V = {2360f, 2360f, 1205f, 1520f, 1070f, 1370f, 1035f, 1245f,  980f,  928f,  928f,  3160f, 3160f};
    private static final int[]   AX_E = {LINEAR, LINEAR, OUT_QUAD, INOUT_CUBIC, OUT_QUAD, INOUT_CUBIC, OUT_QUAD, OUT_CUBIC, OUT_CUBIC, LINEAR, OUT_CUBIC, LINEAR};

    // Vertical centre: small living bob during the tussle, lift up on launch.
    private static final float[] AY_T = {0.00f, 1.15f, 1.85f, 2.55f, 3.25f, 3.80f, 4.30f, 4.58f, T_GONE, T_END};
    private static final float[] AY_V = {CY, CY, CY - 18f, CY + 13f, CY - 15f, CY, CY + 8f, CY + 8f, CY - 300f, CY - 300f};
    private static final int[]   AY_E = {INOUT_SINE, INOUT_SINE, INOUT_SINE, INOUT_SINE, INOUT_SINE, INOUT_SINE, LINEAR, OUT_CUBIC, LINEAR};

    // Rotation (deg): dead level through the fight, then a fast 1.5-turn on exit.
    private static final float[] AR_T = {0.00f, 3.80f, 4.30f, 4.58f, T_GONE, T_END};
    private static final float[] AR_V = {0f, 0f, -4f, -4f, 560f, 560f};
    private static final int[]   AR_E = {LINEAR, LINEAR, OUT_CUBIC, LINEAR, OUT_CUBIC};

    // Scale: pops in on entry, impact-pop on the hit, shrinks into the distance.
    private static final float[] AS_T = {0.00f, 0.55f, 1.15f, 4.30f, 4.58f, 4.66f, T_GONE, T_END};
    private static final float[] AS_V = {0.50f, 0.50f, 1.00f, 1.00f, 1.00f, 1.14f, 0.26f, 0.26f};
    private static final int[]   AS_E = {LINEAR, OUT_BACK, LINEAR, LINEAR, OUT_QUAD, IN_CUBIC, LINEAR};

    // Cursor (FireTV focus ring): rests at screen centre, gets bumped aside by
    // the incoming "a", jabs it back, then swipes up-right to fling it away.
    // Single easing (easeInOutCubic).
    private static final float[] MX_T = {0.00f, 0.55f, 1.02f, 1.22f, 1.70f, 2.02f, 2.20f, 2.70f, 2.90f, 3.08f, 3.55f, 4.18f, 4.50f, 4.62f, 4.78f, 5.20f, T_END};
    private static final float[] MX_V = {980f, 980f, 1010f, 1018f, 855f, 905f, 1012f, 820f, 905f, 988f, 885f, 700f, 690f, 900f, 1460f, 1010f, 965f};
    private static final float[] MY_T = {0.00f, 0.50f, 1.02f, 1.22f, 1.70f, 2.20f, 2.70f, 3.08f, 3.55f, 4.18f, 4.50f, 4.62f, 4.78f, 5.20f, T_END};
    private static final float[] MY_V = {540f, 540f, 534f, 540f, 560f, 534f, 560f, 544f, 560f, 600f, 610f, 548f, 330f, 600f, 615f};
    private static final int[]   M_E  = {INOUT_CUBIC};

    // Impact moments (cursor connects with the "a"): drive ring squash + ripples.
    private static final float[] IMPACTS = {1.18f, 2.06f, 2.94f, 4.58f};
    private final float[] contactX = new float[IMPACTS.length];
    private final float[] contactY = new float[IMPACTS.length];

    // ── Runtime ────────────────────────────────────────────────────────────────
    private boolean running = false;
    /** One-shot by default, matching every current caller; setLooping(true) restores the seamless loop. */
    private boolean loop = false;
    private long startNanos = 0L;
    private OnEndListener onEndListener;
    private boolean endNotified = false;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface heavy;
    private final Typeface captionFace;
    /** Centred baseline for the hero "a", precomputed so onDraw never allocates FontMetrics. */
    private final float glyphBaseline;

    private String captionLoading = "LOADING…";
    private String captionReady = "READY";

    // Cached, design-space shaders (independent of view size).
    private RadialGradient glowShader;   // centre breathing glow
    private RadialGradient vignette;     // edge darkening
    private RadialGradient haloShader;   // halo behind the "a"
    private LinearGradient fillShader;   // progress-bar fill
    private RadialGradient leadShader;   // progress leading dot
    private boolean shadersReady = false;

    public LoaderView(Context c) { this(c, null); }

    public LoaderView(Context c, AttributeSet a) {
        super(c, a);
        Typeface t = Typeface.create("sans-serif-black", Typeface.BOLD);
        heavy = (t != null) ? t : Typeface.DEFAULT_BOLD;
        Typeface m = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        captionFace = (m != null) ? m : Typeface.DEFAULT;
        // glyphPaint draws only the hero "a": configure it once here and cache the
        // centred baseline instead of allocating FontMetrics on every frame (the
        // overlay runs ~5s at 60 fps on the low-RAM Fire OS 7 devices).
        glyphPaint.setTypeface(heavy);
        glyphPaint.setTextSize(460f);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = glyphPaint.getFontMetrics();
        glyphBaseline = -(fm.ascent + fm.descent) / 2f;
        // Precompute the cursor contact points (where ripples originate).
        for (int i = 0; i < IMPACTS.length; i++) {
            contactX[i] = interp(IMPACTS[i], AX_T, AX_V, AX_E) - 142f;
            contactY[i] = interp(IMPACTS[i], AY_T, AY_V, AY_E);
        }
    }

    /** Sets the two progress captions (shown upper-cased, as in the source). */
    public void setCaptions(String loading, String ready) {
        if (loading != null) captionLoading = loading.toUpperCase(Locale.ROOT);
        if (ready != null) captionReady = ready.toUpperCase(Locale.ROOT);
    }

    public void setLooping(boolean l) { loop = l; }

    /** Notified once when a one-shot (non-looping) run reaches its final hold frame. */
    public interface OnEndListener { void onEnd(); }

    public void setOnEndListener(OnEndListener l) { onEndListener = l; }

    public void start() {
        if (running) return;
        running = true;
        endNotified = false;
        startNanos = System.nanoTime();
        postInvalidateOnAnimation();
    }

    public void stop() { running = false; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    // ── Easing + interpolation ────────────────────────────────────────────────
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float ease(int k, float t) {
        switch (k) {
            case OUT_CUBIC:   { float u = t - 1f; return u * u * u + 1f; }
            case OUT_QUAD:    return t * (2f - t);
            case INOUT_CUBIC: return t < 0.5f ? 4f * t * t * t : (t - 1f) * (2f * t - 2f) * (2f * t - 2f) + 1f;
            case INOUT_SINE:  return (float) (-(Math.cos(Math.PI * t) - 1d) / 2d);
            case OUT_BACK:    { float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f; return 1f + c3 * u * u * u + c1 * u * u; }
            case IN_CUBIC:    return t * t * t;
            case LINEAR:
            default:          return t;
        }
    }

    /**
     * Piecewise tween. {@code easings} is either one entry per segment
     * (length = times.length - 1) or a single entry applied to every segment.
     */
    private static float interp(float t, float[] times, float[] vals, int[] easings) {
        int n = times.length;
        if (t <= times[0]) return vals[0];
        if (t >= times[n - 1]) return vals[n - 1];
        for (int i = 0; i < n - 1; i++) {
            if (t >= times[i] && t <= times[i + 1]) {
                float span = times[i + 1] - times[i];
                float local = span <= 0f ? 0f : (t - times[i]) / span;
                int k = (easings.length == 1) ? easings[0] : easings[i];
                float e = ease(k, clamp(local, 0f, 1f));
                return vals[i] + (vals[i + 1] - vals[i]) * e;
            }
        }
        return vals[n - 1];
    }

    private static float bump(float t, float c, float w) {
        float z = (t - c) / w;
        return (float) Math.exp(-(z * z));
    }

    /** Combined impact "squash" envelope (0..1) at time t. */
    private static float squash(float t) {
        float s = 0f;
        for (float it : IMPACTS) s += bump(t, it, 0.085f);
        return Math.min(1f, s);
    }

    private static int withAlpha(int color, float op) {
        int a = (int) (clamp(op, 0f, 1f) * 255f + 0.5f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ── Shaders ────────────────────────────────────────────────────────────────
    private void ensureShaders() {
        if (shadersReady) return;
        glowShader = new RadialGradient(0.5f * W, 0.47f * H, 0.62f * W,
                (0xFF000000 | (GLOW_RGB & 0x00FFFFFF)), (GLOW_RGB & 0x00FFFFFF),
                Shader.TileMode.CLAMP);
        vignette = new RadialGradient(CX, CY, 0.72f * W,
                new int[]{0x00000000, 0x00000000, 0x8C000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        haloShader = new RadialGradient(0f, 0f, 330f,
                0x59FF962D, 0x00FF962D, Shader.TileMode.CLAMP);
        fillShader = new LinearGradient(0f, 0f, W, 0f,
                ORANGE_DEEP, ORANGE, Shader.TileMode.CLAMP);
        leadShader = new RadialGradient(0f, 0f, 13f,
                0xE6FFAA46, 0x00FFAA46, Shader.TileMode.CLAMP);
        shadersReady = true;
    }

    // ── Frame ──────────────────────────────────────────────────────────────────
    @Override
    protected void onDraw(Canvas canvas) {
        ensureShaders();
        float t = currentTime();

        canvas.drawColor(INK);

        int vw = getWidth(), vh = getHeight();
        float s = Math.max(vw / W, vh / H); // cover: stage always fills the view
        float ox = (vw - W * s) / 2f, oy = (vh - H * s) / 2f;

        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(s, s);

        drawBackdrop(canvas, t);
        drawProgress(canvas, t);
        drawHeroA(canvas, t);
        drawCursor(canvas, t);
        drawLoopFade(canvas, t);

        canvas.restore();

        // Keep animating while looping; in one-shot mode stop re-posting once
        // the hold frame is reached so a static "Ready" frame doesn't spin the
        // CPU at 60 fps.
        if (running && (loop || t < T_HOLD)) {
            postInvalidateOnAnimation();
        } else if (running && !loop && !endNotified) {
            endNotified = true;
            if (onEndListener != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (onEndListener != null) onEndListener.onEnd();
                    }
                });
            }
        }
    }

    /**
     * Looping (easter-egg) mode wraps the clock at T_END through the loop-fade
     * seam. One-shot (masking) mode instead clamps at {@link #T_HOLD}, holding on
     * the clean "Ready" frame before the loop-fade dips to black, so the mask can
     * sit on a finished frame until the target launcher's window finally appears.
     */
    private float currentTime() {
        float t = (System.nanoTime() - startNanos) / 1_000_000_000f * SPEED;
        if (loop) {
            t = t % T_END;
            if (t < 0) t += T_END;
        } else if (t > T_HOLD) {
            t = T_HOLD;
        }
        return t;
    }

    // Warm backdrop: breathing centre glow + vignette.
    private void drawBackdrop(Canvas c, float t) {
        float breathe = 0.5f + 0.5f * (float) Math.sin((t / T_END) * Math.PI * 2d);
        paint.setShader(glowShader);
        paint.setAlpha((int) ((0.085f + 0.05f * breathe) * 255f));
        c.drawRect(0f, 0f, W, H, paint);
        paint.setShader(vignette);
        paint.setAlpha(255);
        c.drawRect(0f, 0f, W, H, paint);
        paint.setShader(null);
    }

    // Progress bar + caption (caption near top, bar pinned to the bottom edge).
    private void drawProgress(Canvas c, float t) {
        float p = clamp(t / T_WAIT, 0f, 1f);
        float capA = clamp(1f - Math.max(0f, t - 4.6f) / 0.28f, 0f, 1f);
        float capB = clamp((t - 4.82f) / 0.3f, 0f, 1f);

        capPaint.setTypeface(captionFace);
        capPaint.setTextSize(30f);
        capPaint.setLetterSpacing(0.16f);
        capPaint.setTextAlign(Paint.Align.CENTER);
        float baseline = 88f; // CSS bottom:H-96 → bottom edge ~96px from the top
        if (capA > 0f) {
            capPaint.setColor(withAlpha(0xFFFFFFFF, 0.40f * capA));
            c.drawText(captionLoading, CX, baseline, capPaint);
        }
        if (capB > 0f) {
            capPaint.setColor(withAlpha(ORANGE, capB));
            c.drawText(captionReady, CX, baseline, capPaint);
        }

        // track
        paint.setColor(0x12FFFFFF);
        c.drawRect(0f, H - 6f, W, H, paint);
        // fill
        paint.setShader(fillShader);
        paint.setAlpha(255);
        c.drawRect(0f, H - 6f, W * p, H, paint);
        paint.setShader(null);
        // leading glow
        if (p > 0f && p < 1f) {
            c.save();
            c.translate(W * p, H - 3f);
            paint.setShader(leadShader);
            c.drawCircle(0f, 0f, 13f, paint);
            paint.setShader(null);
            c.restore();
        }
    }

    // The hero "a" (+ a short motion streak while it is flung off).
    private void drawHeroA(Canvas c, float t) {
        float op = 1f;
        if (t < 0.50f) op = 0f;
        else if (t < 0.62f) op = (t - 0.50f) / 0.12f;
        else if (t > 4.80f) op = clamp(1f - (t - 4.80f) / 0.16f, 0f, 1f);

        if (t > 4.58f && t < 5.0f) {
            for (int i = 4; i >= 1; i--) {
                float tt = t - i * 0.028f;
                if (tt < 4.50f) continue;
                drawGlyphA(c, interp(tt, AX_T, AX_V, AX_E), interp(tt, AY_T, AY_V, AY_E),
                        interp(tt, AR_T, AR_V, AR_E), interp(tt, AS_T, AS_V, AS_E),
                        op * (0.05f + 0.05f * (4 - i)));
            }
        }
        drawGlyphA(c, interp(t, AX_T, AX_V, AX_E), interp(t, AY_T, AY_V, AY_E),
                interp(t, AR_T, AR_V, AR_E), interp(t, AS_T, AS_V, AS_E), op);
    }

    private void drawGlyphA(Canvas c, float x, float y, float rot, float sc, float op) {
        if (op <= 0f) return;
        c.save();
        c.translate(x, y);
        c.rotate(rot);
        c.scale(sc, sc);
        // glow halo behind the glyph
        paint.setShader(haloShader);
        paint.setAlpha((int) (op * 255f));
        c.drawCircle(0f, 0f, 330f, paint);
        paint.setShader(null);
        // the glyph itself (paint preconfigured in the constructor)
        glyphPaint.setColor(withAlpha(ORANGE, op));
        c.drawText("a", 0f, glyphBaseline, glyphPaint);
        c.restore();
    }

    // The cursor (focus ring) + impact ripples.
    private void drawCursor(Canvas c, float t) {
        // ripples
        for (int i = 0; i < IMPACTS.length; i++) {
            float dt = t - IMPACTS[i];
            if (dt < 0f || dt > 0.55f) continue;
            float k = dt / 0.55f;
            float r = 26f + k * 230f;
            float o = (1f - k) * 0.45f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(withAlpha(0xFFFFFFFF, 0.9f * o));
            c.drawCircle(contactX[i], contactY[i], r / 2f, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        float x = interp(t, MX_T, MX_V, M_E);
        float y = interp(t, MY_T, MY_V, M_E);
        float sq = squash(t);
        float sx = 1f - 0.30f * sq;
        float sy = 1f + 0.24f * sq;
        float tilt = sq * -10f;
        float op = 1f;
        if (t < 0.42f) op = clamp((t - 0.18f) / 0.24f, 0f, 1f);
        else if (t > 5.55f) op = clamp(1f - (t - 5.55f) / 0.4f, 0f, 1f);
        if (op <= 0f) return;

        c.save();
        c.translate(x, y);
        c.rotate(tilt);
        c.scale(sx, sy);
        // soft outer glow
        paint.setColor(withAlpha(0xFFFFFFFF, op * 0.18f));
        c.drawCircle(0f, 0f, 48f, paint);
        // ring
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(withAlpha(0xFFFFFFFF, op * 0.95f));
        c.drawCircle(0f, 0f, 38f, paint);
        paint.setStyle(Paint.Style.FILL);
        // centre dot
        paint.setColor(withAlpha(0xFFFFFFFF, op));
        c.drawCircle(0f, 0f, 5.5f, paint);
        c.restore();
    }

    // Loop seam: soft dip to black at the very end / start.
    private void drawLoopFade(Canvas c, float t) {
        float op = 0f;
        if (t > T_END - 0.3f) op = (t - (T_END - 0.3f)) / 0.3f;
        else if (t < 0.28f) op = 1f - t / 0.28f;
        if (op <= 0f) return;
        paint.setColor(withAlpha(INK, op));
        c.drawRect(0f, 0f, W, H, paint);
    }
}
