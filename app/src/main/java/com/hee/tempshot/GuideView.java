package com.hee.tempshot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Manual scroll guide drawn on a full-screen, touch-through overlay: a green "drop zone" band near the
 * top of the list and a line stuck to the content. Moving the line into the band and letting go
 * captures the next page. Coordinates are screen coordinates.
 */
final class GuideView extends View {
    static final int NORMAL = 0, IN = 1, OVER = 2;
    private static final int BLUE = 0xFF2563EB, RED = 0xFFE5484D, GREEN = 0xFF16A34A;

    private final float dp;
    private final Paint fill = new Paint(), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private float bandTop, bandBottom, lineY;
    private int state;
    private boolean fixed;
    private String note = "";

    GuideView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * dp);
        line.setStrokeWidth(3 * dp);
        text.setTextSize(11.5f * dp * c.getResources().getConfiguration().fontScale);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** fixed = the line can't follow the content (no element info); it stays put and [찍기] is used. */
    void set(float bandTop, float bandBottom, float lineY, int state, boolean fixed, String note) {
        this.note = note;
        this.bandTop = bandTop;
        this.bandBottom = bandBottom;
        this.lineY = lineY;
        this.state = state;
        this.fixed = fixed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        c.translate(-loc[0], -loc[1]); // draw in screen coordinates
        float right = loc[0] + getWidth();

        boolean in = state == IN;
        fill.setColor(in ? 0x4222C55E : 0x2222C55E);
        c.drawRect(0, bandTop, right, bandBottom, fill);
        edge.setColor(in ? GREEN : 0x8C16A34A);
        c.drawLine(0, bandTop, right, bandTop, edge);
        c.drawLine(0, bandBottom, right, bandBottom, edge);
        label(c, "여기 안에 놓으면 찍혀요", right - 10 * dp, bandTop + 14 * dp, 0xFFDCFCE7, 0xFF166534, true);

        int color = state == OVER ? RED : BLUE;
        line.setColor(color);
        line.setPathEffect(fixed ? new DashPathEffect(new float[]{8 * dp, 6 * dp}, 0) : null);
        c.drawLine(0, lineY, right, lineY, line);
        String msg = state == OVER ? "조금 내려 주세요"
                : in ? "✓ 놓으면 찍어요"
                : fixed ? "이 줄을 띠 안으로 올리고 [찍기]" : "이 선을 위로";
        label(c, msg, 10 * dp, lineY, color, 0xFFFFFFFF, false);
        if (!note.isEmpty()) label(c, note, 10 * dp, lineY + 24 * dp, 0xE6191F28, 0xFFFFFFFF, false);
    }

    private void label(Canvas c, String s, float x, float cy, int bg, int fg, boolean alignRight) {
        Paint.FontMetrics fm = text.getFontMetrics();
        float tw = text.measureText(s), padH = 9 * dp, hgt = (fm.descent - fm.ascent) + 8 * dp;
        float left = alignRight ? x - tw - 2 * padH : x;
        r.set(left, cy - hgt / 2, left + tw + 2 * padH, cy + hgt / 2);
        chip.setColor(bg);
        c.drawRoundRect(r, hgt / 2, hgt / 2, chip);
        text.setColor(fg);
        c.drawText(s, left + padH, cy - (fm.ascent + fm.descent) / 2, text);
    }
}
