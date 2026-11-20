package com.hee.tempshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Scroll capture driven by the user: each tap on ⌄⌄ swipes the app up by ~45% of the screen,
 * takes a shot and stitches the new rows; holding it repeats. "완료" saves one tall image.
 */
final class ScrollCapture {
    private static final float SWIPE = 0.45f;     // share of screen height per step
    private static final int MAX_PAGES = 25;
    private static final int MAX_HEIGHT = 30000;  // px, keeps the final bitmap within memory

    private final ShotService svc;
    private final int[] bars;
    private final Uri original;
    private final int w, h;
    private final ArrayList<Bitmap> pieces = new ArrayList<>();
    private Bitmap prev;               // last full frame
    private int[] prevHash;
    private int footer = -1;           // fixed bottom rows, measured on the first step
    private int pages = 1, height;
    private boolean pressed, busy, ended, finished;

    private GlowFrame pill;
    private TextView label;
    private ImageView thumb;

    ScrollCapture(ShotService svc, Bitmap first, int[] bars, Uri original) {
        this.svc = svc;
        this.bars = bars;
        this.original = original;
        this.prev = first;
        w = first.getWidth();
        h = first.getHeight();
        prevHash = hashes(first, null);
    }

    void start() {
        showPill();
        step(); // the toolbar's ⌄⌄ tap is the first step
    }

    void cancel() {
        finished = true;
        if (pill != null) svc.removeView(pill);
        recycleAll();
    }

    // --- capture loop ---

    private void step() {
        if (busy || ended || finished) return;
        busy = true;
        float x = w / 2f, y0 = h * 0.70f, y1 = y0 - h * SWIPE;
        Path drag = new Path();
        drag.moveTo(x, y0);
        drag.lineTo(x, y1);
        GestureDescription.StrokeDescription move = new GestureDescription.StrokeDescription(drag, 0, 500, true);
        dispatch(move, () -> {
            // Hold the finger still before lifting so the list doesn't fling past the measured distance.
            Path hold = new Path();
            hold.moveTo(x, y1);
            hold.lineTo(x, y1 + 1);
            dispatch(move.continueStroke(hold, 0, 200, false), () -> {
                pill.setVisibility(View.INVISIBLE); // keep our own bar out of the shot
                svc.handler.postDelayed(this::shootStep, 250);
            });
        });
    }

    private void dispatch(GestureDescription.StrokeDescription s, Runnable done) {
        GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { done.run(); }
            @Override public void onCancelled(GestureDescription d) { stepFailed("스크롤하지 못했어요"); }
        }, svc.handler);
        if (!ok) stepFailed("스크롤하지 못했어요");
    }

    private void shootStep() {
        if (finished) return;
        svc.shoot(frame -> {
            pill.setVisibility(View.VISIBLE);
            new Thread(() -> {
                String stop = add(frame);
                svc.handler.post(() -> {
                    busy = false;
                    if (finished) return;
                    label.setText("캡처 중 · " + pages + "페이지");
                    if (stop != null) {
                        ended = true;
                        svc.toast(stop);
                    } else if (pressed) {
                        step();
                    }
                });
            }).start();
        }, () -> stepFailed("캡처 실패"));
    }

    private void stepFailed(String msg) {
        if (pill != null) pill.setVisibility(View.VISIBLE);
        busy = false;
        pressed = false;
        svc.toast(msg);
    }

    /** Stitches frame b onto the result. Returns a reason to stop, or null to keep going. Background thread. */
    private synchronized String add(Bitmap b) {
        if (finished) { b.recycle(); return null; }
        boolean[] info = new boolean[h];
        int[] hash = hashes(b, info);
        int top = Stitcher.sameTop(prevHash, hash);
        if (top >= h) { b.recycle(); return "끝까지 캡처했어요"; }
        int bottom = Stitcher.sameBottom(prevHash, hash, h - top);
        if (footer < 0) {
            footer = bottom;
            pieces.add(Bitmap.createBitmap(prev, 0, 0, w, h - footer)); // first screen down to the footer
            height = h - footer;
        }
        int contentBottom = h - footer;
        int expected = Math.round(h * SWIPE);
        int d = Stitcher.findShift(prevHash, hash, info, top, contentBottom, expected / 2, expected * 8 / 5);
        if (d <= 0) { b.recycle(); return "더 이어 붙일 수 없는 화면이에요"; }
        // shortcut: assumes the sticky footer height stays the same during the capture; apps that hide
        // their bottom bar while scrolling can leave a small seam.
        pieces.add(Bitmap.createBitmap(b, 0, contentBottom - d, w, d));
        height += d;
        pages++;
        prev.recycle();
        prev = b;
        prevHash = hash;
        Bitmap t = ShotService.thumbnail(svc, b);
        svc.handler.post(() -> thumb.setImageBitmap(t));
        if (pages >= MAX_PAGES || height >= MAX_HEIGHT) return "최대 길이까지 캡처했어요";
        return null;
    }

    private int[] hashes(Bitmap b, boolean[] info) {
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] hash = new int[h];
        Stitcher.rowHashes(px, w, h, hash, info != null ? info : new boolean[h]);
        return hash;
    }

    // --- finish ---

    private void finish() {
        if (finished) return;
        finished = true;
        pressed = false;
        svc.removeView(pill);
        new Thread(() -> {
            Bitmap out = null;
            synchronized (this) {
                if (pieces.size() > 1) {
                    try {
                        out = Bitmap.createBitmap(w, height + footer, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(out);
                        int y = 0;
                        for (Bitmap p : pieces) {
                            c.drawBitmap(p, 0, y, null);
                            y += p.getHeight();
                        }
                        // the footer (navigation bar, sticky bottom) from the last frame
                        c.drawBitmap(prev, new android.graphics.Rect(0, h - footer, w, h),
                                new android.graphics.Rect(0, y, w, y + footer), null);
                    } catch (OutOfMemoryError e) {
                        out = null;
                        svc.toast("이미지가 너무 길어서 저장하지 못했어요");
                    }
                }
                recycleAll();
            }
            Bitmap result = out;
            svc.handler.post(() -> svc.onScrollFinished(result, original, bars));
        }).start();
    }

    private void recycleAll() {
        for (Bitmap p : pieces) p.recycle();
        pieces.clear();
        if (prev != null) prev.recycle();
        prev = null;
    }

    // --- UI ---

    @SuppressLint("ClickableViewAccessibility")
    private void showPill() {
        Ui ui = new Ui(svc);
        int margin = ui.dp(14);
        pill = new GlowFrame(svc, ui.dark ? 0xF21C1C20 : 0xF7FFFFFF, margin, true);
        LinearLayout row = new LinearLayout(svc);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8));
        pill.addView(row);

        // Tall preview that rises out of the bar, like the page growing.
        thumb = new ImageView(svc);
        thumb.setImageBitmap(ShotService.thumbnail(svc, prev));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(ui.round(ui.chip, 10));
        thumb.setClipToOutline(true);
        thumb.setElevation(ui.dp(4));
        thumb.setTranslationY(-ui.dp(14));
        row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(64)));

        label = ui.text("캡처 중 · 1페이지", 16, ui.text);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        row.addView(label);

        View more = ui.iconButton(R.drawable.ic_scroll, "더 내리기 (누르고 있으면 계속)", ui.text, ui.buttonBg(), null);
        more.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    v.setPressed(true);
                    step();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
        // TalkBack users get a single step per activation.
        more.setOnClickListener(v -> step());
        row.addView(more, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));

        TextView done = ui.text("완료", 16, ui.dark ? 0xFF111111 : 0xFFFFFFFF);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(ui.dp(20), 0, ui.dp(20), 0);
        done.setBackground(ui.pressable(ui.dark ? 0xFFFFFFFF : 0xFF111111, 23));
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, ui.dp(46));
        dl.leftMargin = ui.dp(6);
        row.addView(done, dl);

        WindowManager.LayoutParams lp = ShotService.overlayParams();
        lp.y = ui.dp(96) - margin;
        svc.getSystemService(WindowManager.class).addView(pill, lp);
    }
}
