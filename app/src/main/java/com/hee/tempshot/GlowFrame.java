package com.hee.tempshot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Hosts the floating bar's card and draws its edge: a calm 1dp hairline, plus a pastel glow with a
 * transparent tail that sweeps around once after a capture (1.6s) and fades to nothing.
 * The glow is three plain strokes (no blur), so the view stays hardware-rendered and the card's
 * elevation shadow still draws. Padding is the bleed that keeps glow and shadow from being clipped.
 */
final class GlowFrame extends FrameLayout {
    private static final int[] COLORS = {0x0038BDF8, 0xFF38BDF8, 0xFF818CF8, 0xFFC084FC, 0x00C084FC};
    private static final float[] STOPS = {0f, 0.3f, 0.55f, 0.8f, 1f};
    private static final float[] WIDTH_DP = {7f, 3.5f, 1.5f};
    private static final float[] ALPHA = {0.12f, 0.30f, 0.95f};
    private static final long GLOW_MS = 1600;

    private final View card;
    private final boolean spinForever;
    private final float dp, radius, slop, dismissDistance;
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF edge = new RectF();
    private final Matrix rotate = new Matrix();
    private SweepGradient sweep;
    private float sweepCx = -1, sweepCy = -1;
    private float angle, glowAlpha = 1f, downY;
    private ValueAnimator anim;
    Runnable onSwipeDown;
    Runnable onOutsideTouch; // needs FLAG_WATCH_OUTSIDE_TOUCH on the window

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (onOutsideTouch != null) onOutsideTouch.run();
            return true; // the touch itself still reaches the app underneath
        }
        return super.dispatchTouchEvent(e);
    }

    GlowFrame(Context c, View card, int hairlineColor, float radiusDp, int padH, int padV, boolean spinForever) {
        super(c);
        this.card = card;
        this.spinForever = spinForever;
        dp = c.getResources().getDisplayMetrics().density;
        radius = radiusDp * dp;
        slop = 8 * dp;
        dismissDistance = 40 * dp;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(padH, padV, padH, padV);
        addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        hairline.setStyle(Paint.Style.STROKE);
        hairline.setStrokeWidth(1 * dp);
        hairline.setColor(hairlineColor);
        glow.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        float inset = 0.5f * dp; // keep the 1dp hairline on the card's edge, not half outside it
        edge.set(card.getLeft() + inset, card.getTop() + inset, card.getRight() - inset, card.getBottom() - inset);
        c.drawRoundRect(edge, radius, radius, hairline);
        if (glowAlpha <= 0) return;
        if (sweep == null || sweepCx != edge.centerX() || sweepCy != edge.centerY()) {
            sweepCx = edge.centerX();
            sweepCy = edge.centerY();
            sweep = new SweepGradient(sweepCx, sweepCy, COLORS, STOPS);
            glow.setShader(sweep);
        }
        rotate.setRotate(angle, sweepCx, sweepCy);
        sweep.setLocalMatrix(rotate);
        for (int i = 0; i < WIDTH_DP.length; i++) {
            glow.setStrokeWidth(WIDTH_DP[i] * dp);
            glow.setAlpha(Math.round(255 * ALPHA[i] * glowAlpha));
            c.drawRoundRect(edge, radius, radius, glow);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(GLOW_MS);
        anim.setInterpolator(null); // linear rotation
        if (spinForever) anim.setRepeatCount(ValueAnimator.INFINITE); // scroll capture: glow while working
        anim.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            angle = 360f * t;
            // Full strength for the first 55%, then ease out to 0 by the end of 1.6s.
            glowAlpha = spinForever || t < 0.55f ? 1f : smooth(1f - (t - 0.55f) / 0.45f);
            invalidate();
        });
        anim.start(); // with system animations off this jumps to the end: hairline only
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3 - 2 * x);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    // --- swipe down to dismiss ---

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (onSwipeDown == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downY = e.getRawY();
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && e.getRawY() - downY > slop) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (onSwipeDown == null) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                setTranslationY(Math.max(0, e.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getTranslationY() > dismissDistance) onSwipeDown.run();
                else animate().translationY(0).setDuration(150).start();
                return true;
        }
        return false;
    }
}
