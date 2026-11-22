package com.hee.tempshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
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
 * Scroll capture driven by the user: each tap on ⌄⌄ swipes the scrolling list up by ~45%,
 * takes a shot and stitches the new rows; holding it repeats. "완료" saves one tall image.
 * New rows are taken from the middle of the list (above "cut"), never its bottom edge, so input
 * bars, floating buttons and toasts that sit low on screen are not repeated in every strip.
 */
final class ScrollCapture {
    private static final float SWIPE = 0.35f;     // share of the list height per step; leaves room before CUT
    private static final float MIN_MATCH = 0.9f;  // below this the screen changed while capturing
    private static final float CUT = 0.6f;        // strips come from above this share of the list
    private static final int MAX_PAGES = 25;
    private static final int MAX_HEIGHT = 30000;  // px, keeps the final bitmap within memory

    private final ShotService svc;
    private final int[] bars;
    private final Uri original;
    private final int w, h;
    private final ArrayList<Bitmap> pieces = new ArrayList<>();
    private Bitmap prev;               // last full frame
    private int[] prevHash;
    private int regionTop, regionBottom = -1; // rows of the scrolling list; -1 = infer from the first step
    private int cut, offset, taken;           // see Stitcher.strip
    private int pages = 1, height;
    private boolean pressed, busy, ended, finished;

    private GlowFrame pill;
    private TextView label;
    private ImageView thumb;

    ScrollCapture(ShotService svc, Bitmap first, int[] bars, Uri original, Rect list) {
        this.svc = svc;
        this.bars = bars;
        this.original = original;
        this.prev = first;
        w = first.getWidth();
        h = first.getHeight();
        prevHash = hashes(first, null);
        if (list != null) {
            regionTop = Math.max(0, list.top);
            regionBottom = Math.min(h, list.bottom);
        }
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

    /**
     * Runs r and turns any error into a stopped-but-alive session. An uncaught error would kill the
     * process, and Android then switches the accessibility service off.
     */
    private void guard(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            TempShotApp.record(svc, e);
            svc.handler.post(() -> {
                busy = false;
                pressed = false;
                ended = true;
                if (pill != null) pill.setVisibility(View.VISIBLE);
                svc.toast("스크롤 캡처 중 오류가 났어요. '완료'로 지금까지 저장할 수 있어요");
            });
        }
    }

    private void step() {
        guard(this::stepUnsafe);
    }

    private void stepUnsafe() {
        if (busy || ended || finished) return;
        busy = true;
        int top = regionBottom > 0 ? regionTop : 0, listH = (regionBottom > 0 ? regionBottom : h) - top;
        // Swipe through the middle of the list (65% → 20%): starting low lands on floating input panels
        // (e.g. the Claude app's composer covers the bottom of its list) and nothing scrolls.
        float x = w / 2f, y0 = top + listH * 0.65f, y1 = y0 - listH * SWIPE;
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
                svc.handler.postDelayed(() -> guard(this::shootStep), 250);
            });
        });
    }

    private void dispatch(GestureDescription.StrokeDescription s, Runnable done) {
        GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { guard(done); }
            @Override public void onCancelled(GestureDescription d) { stepFailed("스크롤하지 못했어요"); }
        }, svc.handler);
        if (!ok) stepFailed("스크롤하지 못했어요");
    }

    private void shootStep() {
        if (finished) return;
        svc.shoot(frame -> guard(() -> {
            pill.setVisibility(View.VISIBLE);
            new Thread(() -> guard(() -> {
                String stop = add(frame);
                svc.handler.post(() -> guard(() -> {
                    busy = false;
                    if (finished) return;
                    label.setText("캡처 중 · " + pages + "페이지");
                    if (stop != null) {
                        ended = true;
                        svc.toast(stop);
                    } else if (pressed) {
                        step();
                    }
                }));
            })).start();
        }), () -> stepFailed("캡처 실패"));
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
        if (regionBottom < 0) { // no scrolling list found: rows that stayed put are header / footer
            int top = Stitcher.sameTop(prevHash, hash);
            if (top >= h) { b.recycle(); return "끝까지 캡처했어요"; }
            regionTop = top;
            regionBottom = h - Stitcher.sameBottom(prevHash, hash, h - top);
        }
        boolean moved = false;
        for (int y = regionTop; y < regionBottom && !moved; y++) moved = prevHash[y] != hash[y];
        if (!moved) { b.recycle(); return "끝까지 캡처했어요"; }
        int listH = regionBottom - regionTop;
        if (pieces.isEmpty()) {
            cut = regionTop + Math.round(listH * CUT);
            pieces.add(rows(prev, 0, cut)); // header + first part of the list
            taken = cut;
            height = cut;
        }
        int expected = Math.round(listH * SWIPE);
        int d = Stitcher.findShift(prevHash, hash, info, regionTop, regionBottom, expected / 2, expected * 8 / 5);
        if (d <= 0) { b.recycle(); return "더 이어 붙일 수 없는 화면이에요"; }
        // Safety: never glue a doubtful seam. Stop and keep what's already stitched instead.
        if (Stitcher.matchRatio(prevHash, hash, info, regionTop, regionBottom, d) < MIN_MATCH
                || taken - (offset + d) < regionTop) { // moved past the trusted band: rows would be missing
            b.recycle();
            return "화면이 바뀌어서 여기까지만 이어 붙였어요. '완료'를 눌러 저장하세요";
        }
        offset += d;
        int[] strip = Stitcher.strip(taken, offset, cut, regionTop);
        if (strip[1] > strip[0]) {
            pieces.add(rows(b, strip[0], strip[1]));
            height += strip[1] - strip[0];
        }
        taken = offset + cut;
        pages++;
        prev.recycle();
        prev = b;
        prevHash = hash;
        Bitmap t = ShotService.thumbnail(svc, b);
        svc.handler.post(() -> thumb.setImageBitmap(t));
        if (pages >= MAX_PAGES || height >= MAX_HEIGHT) return "최대 길이까지 캡처했어요";
        return null;
    }

    /** Copy of rows [y0, y1). createBitmap() returns the source itself for a full-frame cut, which we recycle later. */
    private Bitmap rows(Bitmap src, int y0, int y1) {
        Bitmap r = Bitmap.createBitmap(src, 0, y0, w, y1 - y0);
        return r == src ? src.copy(Bitmap.Config.ARGB_8888, false) : r;
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
            try {
            synchronized (this) {
                if (pieces.size() > 1) {
                    try {
                        out = Bitmap.createBitmap(w, height + h - cut, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(out);
                        int y = 0;
                        for (Bitmap p : pieces) {
                            c.drawBitmap(p, 0, y, null);
                            y += p.getHeight();
                        }
                        // rest of the last frame: bottom of the list, input bar, navigation bar
                        c.drawBitmap(prev, new Rect(0, cut, w, h), new Rect(0, y, w, y + h - cut), null);
                    } catch (OutOfMemoryError e) {
                        out = null;
                        svc.toast("이미지가 너무 길어서 저장하지 못했어요");
                    }
                }
                recycleAll();
            }
            } catch (Throwable e) {
                TempShotApp.record(svc, e);
                svc.toast("스크롤 캡처를 저장하지 못했어요");
                out = null;
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
        LinearLayout card = ui.barCard();
        int padH = ui.dp(6), padV = ui.dp(12);
        pill = new GlowFrame(svc, card, ui.barHairline(), Ui.BAR_RADIUS, padH, padV, true);

        thumb = ui.barThumb(ShotService.thumbnail(svc, prev));
        card.addView(thumb);

        label = ui.text("캡처 중 · 1페이지", 14, ui.barText());
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = ui.dp(10);
        labelLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(label, labelLp);

        View more = ui.iconButton(R.drawable.ic_bar_scroll, "더 내리기 (누르고 있으면 계속)", ui.barIcon(), null);
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
        card.addView(Ui.afterText(more, ui.dp(16)));

        TextView done = ui.text("완료", 13, ui.dark ? 0xFF111318 : 0xFFFFFFFF);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        done.setBackground(ui.pressable(ui.barText(), 12));
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(34));
        dl.leftMargin = ui.dp(4);
        dl.gravity = Gravity.CENTER_VERTICAL;
        card.addView(done, dl);

        svc.getSystemService(WindowManager.class).addView(pill, ui.barWindow(padV));
    }
}
