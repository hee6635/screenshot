package com.hee.tempshot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Pill with a cyan/blue/purple gradient border and soft glow (Galaxy AI bar mood).
 * The glow sweeps around on appear, then settles to a calm static border unless spinForever.
 * Padding leaves room for the glow outside the pill. Optional swipe-down to dismiss.
 */
final class GlowFrame extends FrameLayout {
    private static final int[] COLORS = {0xFF22D3EE, 0xFF3E82F7, 0xFF8B5CF6, 0xFFC084FC, 0xFF22D3EE};
    private static final float SETTLED_GLOW = 0.35f;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Matrix rotate = new Matrix();
    private final boolean spinForever;
    private final float slop, dismissDistance;
    private SweepGradient sweep;
    private float angle, glowAlpha = 1f, downY;
    private boolean dragging;
    private ValueAnimator spin, settle;
    Runnable onSwipeDown;

    GlowFrame(Context c, int fillColor, int margin, boolean spinForever) {
        super(c);
        this.spinForever = spinForever;
        setWillNotDraw(false);
        setClipChildren(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter needs a software layer
        setPadding(margin, margin, margin, margin);
        float dp = c.getResources().getDisplayMetrics().density;
        slop = 8 * dp;
        dismissDistance = 40 * dp;
        fill.setColor(fillColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.5f * dp);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(7 * dp);
        glow.setMaskFilter(new BlurMaskFilter(9 * dp, BlurMaskFilter.Blur.NORMAL));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        pill.set(getPaddingLeft(), getPaddingTop(), w - getPaddingRight(), h - getPaddingBottom());
        sweep = new SweepGradient(w / 2f, h / 2f, COLORS, null);
        stroke.setShader(sweep);
        glow.setShader(sweep);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (sweep == null) return;
        float r = pill.height() / 2;
        rotate.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
        sweep.setLocalMatrix(rotate);
        glow.setAlpha((int) (glowAlpha * 230));
        c.drawRoundRect(pill, r, r, glow);
        c.drawRoundRect(pill, r, r, fill);
        c.drawRoundRect(pill, r, r, stroke);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        spin = ValueAnimator.ofFloat(0, 360);
        spin.setDuration(1600);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(null); // linear
        spin.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidate(); });
        spin.start();
        if (spinForever) return;
        settle = ValueAnimator.ofFloat(1f, SETTLED_GLOW);
        settle.setStartDelay(1600);
        settle.setDuration(700);
        settle.addUpdateListener(a -> {
            glowAlpha = (float) a.getAnimatedValue();
            if (a.getAnimatedFraction() >= 1f) spin.cancel(); // keep the border, stop rotating
            invalidate();
        });
        settle.start(); // with system animations off this jumps straight to the settled look
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spin != null) spin.cancel();
        if (settle != null) settle.cancel();
        super.onDetachedFromWindow();
    }

    // --- swipe down to dismiss ---

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (onSwipeDown == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downY = e.getRawY();
            dragging = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && e.getRawY() - downY > slop) {
            dragging = true;
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
                dragging = false;
                if (getTranslationY() > dismissDistance) onSwipeDown.run();
                else animate().translationY(0).setDuration(150).start();
                return true;
        }
        return false;
    }
}
