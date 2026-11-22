package com.hee.tempshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Scroll capture driven by the user: each tap on ⌄⌄ swipes the scrolling list up by ~45%,
 * takes a shot and stitches the new rows; holding it repeats. "완료" saves one tall image.
 * New rows are taken from the middle of the list (above "cut"), never its bottom edge, so input
 * bars, floating buttons and toasts that sit low on screen are not repeated in every strip.
 *
 * Manual mode ("직접 내리기") is for apps that ignore injected swipes: the user scrolls by hand. A line
 * is stuck to an on-screen element at the cut line (its position is polled through accessibility);
 * when the user lets it rest anywhere inside a green band near the top of the list, the next page is
 * captured. Stitching itself is the same image matching as in auto mode; the line only guides.
 */
final class ScrollCapture {
    private static final float SWIPE = 0.35f;     // share of the list height per step; leaves room before CUT
    private static final float MIN_MATCH = 0.9f;  // below this the screen changed while capturing
    private static final float CUT = 0.6f;        // strips come from above this share of the list
    // Rows below this share of the list are ignored when matching: floating input panels, "scroll to
    // bottom" buttons and toasts sit there and never move with the content (Claude app composer).
    private static final float MATCH_BAND = 0.8f;
    private static final int MAX_PAGES = 25;
    private static final int MAX_HEIGHT = 30000;  // px, keeps the final bitmap within memory
    private static final float BAND_TOP = 0.05f, BAND_BOTTOM = 0.30f; // manual drop zone, share of the list
    private static final long POLL_MS = 80, SETTLE_MS = 300;

    private final ShotService svc;
    private final int[] bars;
    private final Uri original;
    private final int w, h;
    private final ArrayList<Bitmap> pieces = new ArrayList<>();
    private Bitmap prev;               // last full frame
    private int[] prevHash;
    private int[] prevSeg;             // per-row segment hashes of prev, for matchRatio
    private int regionTop, regionBottom = -1; // rows of the scrolling list; -1 = infer from the first step
    private int cut, offset, taken;           // see Stitcher.strip
    private int pages = 1, height;
    private boolean pressed, busy, ended, finished, full;

    private GlowFrame pill;
    private TextView label;
    private ImageView thumb;
    private View directBtn, shootBtn;

    // manual mode
    private boolean manual, waitForMove;
    private GuideView guide;
    private AccessibilityNodeInfo anchor;     // element the line is stuck to; null = fixed line + [찍기]
    private float anchorOffset, lineY, lineStartY, lastLineY;
    private long lastMoveAt;
    private int lineState;
    private final Runnable poll = this::pollAnchor;

    ScrollCapture(ShotService svc, Bitmap first, int[] bars, Uri original, Rect list) {
        this.svc = svc;
        this.bars = bars;
        this.original = original;
        this.prev = first;
        w = first.getWidth();
        h = first.getHeight();
        prevSeg = new int[h * Stitcher.SEGMENTS];
        prevHash = hashes(first, null, prevSeg);
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
        stopManual();
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
                say("오류가 났어요 · '완료'로 저장");
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
                String stop = add(frame, 0);
                svc.handler.post(() -> guard(() -> {
                    busy = false;
                    if (finished) return;
                    label.setText(pages + "페이지");
                    if (stop != null) {
                        ended = true;
                        full = stop.equals(STOP_FULL);
                        // Nothing moved on the very first step: the app likely ignores injected swipes.
                        say(stop.equals(STOP_END) && pages == 1 ? "자동으로 안 내려가요 · [직접 내리기]" : stop);
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
        say(msg);
    }

    /**
     * Shows a short message in the bar's label for a few seconds. Toasts from the accessibility service
     * don't appear on the user's Galaxy, so messages go in the bar, which is always on screen.
     */
    private void say(String msg) {
        svc.handler.post(() -> {
            if (label == null || finished) return;
            label.setText(msg);
            svc.handler.removeCallbacks(restoreLabel);
            svc.handler.postDelayed(restoreLabel, 3000);
        });
    }

    private final Runnable restoreLabel = () -> {
        if (label != null) label.setText(manual ? "직접 · " + pages + "페이지" : pages + "페이지");
    };

    private static final String STOP_END = "끝까지 캡처했어요";
    private static final String STOP_FULL = "최대 길이까지 캡처했어요";

    /** Stitches frame b onto the result. Returns a reason to stop, or null to keep going. Background thread. */
    private synchronized String add(Bitmap b, int expectedShift) {
        if (finished) { b.recycle(); return null; }
        boolean[] info = new boolean[h];
        int[] seg = new int[h * Stitcher.SEGMENTS];
        int[] hash = hashes(b, info, seg);
        if (regionBottom < 0) { // no scrolling list found: rows that stayed put are header / footer
            int top = Stitcher.sameTop(prevHash, hash);
            if (top >= h) { b.recycle(); return STOP_END; }
            regionTop = top;
            regionBottom = h - Stitcher.sameBottom(prevHash, hash, h - top);
        }
        int listH = regionBottom - regionTop;
        int band = regionTop + Math.round(listH * MATCH_BAND); // rows [regionTop, band) are compared
        boolean moved = false;
        for (int y = regionTop; y < band && !moved; y++) moved = prevHash[y] != hash[y];
        if (!moved) { b.recycle(); return STOP_END; }
        if (pieces.isEmpty()) {
            cut = regionTop + Math.round(listH * CUT);
            pieces.add(rows(prev, 0, cut)); // header + first part of the list
            taken = cut;
            height = cut;
        }
        int expected = expectedShift > 0 ? expectedShift : Math.round(listH * SWIPE);
        int d = Stitcher.findShift(prevHash, hash, info, regionTop, band, expected / 2, expected * 8 / 5);
        if (d <= 0) { b.recycle(); return "더 이어 붙일 수 없는 화면이에요"; }
        // Safety: never glue a doubtful seam. Stop and keep what's already stitched instead.
        if (Stitcher.matchRatio(prevSeg, seg, info, regionTop, band, d) < MIN_MATCH
                || taken - (offset + d) < regionTop) { // moved past the trusted band: rows would be missing
            b.recycle();
            return "화면이 바뀌어 멈췄어요 · '완료'로 저장";
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
        prevSeg = seg;
        Bitmap t = ShotService.thumbnail(svc, b);
        svc.handler.post(() -> thumb.setImageBitmap(t));
        if (pages >= MAX_PAGES || height >= MAX_HEIGHT) return STOP_FULL;
        return null;
    }

    /** Copy of rows [y0, y1). createBitmap() returns the source itself for a full-frame cut, which we recycle later. */
    private Bitmap rows(Bitmap src, int y0, int y1) {
        Bitmap r = Bitmap.createBitmap(src, 0, y0, w, y1 - y0);
        return r == src ? src.copy(Bitmap.Config.ARGB_8888, false) : r;
    }

    private int[] hashes(Bitmap b, boolean[] info, int[] seg) {
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] hash = new int[h];
        Stitcher.rowHashes(px, w, h, hash, info != null ? info : new boolean[h]);
        Stitcher.segmentHashes(px, w, h, seg);
        return hash;
    }

    // --- finish ---

    private void finish() {
        if (finished) return;
        finished = true;
        pressed = false;
        stopManual();
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

    // --- manual mode ---

    private int listTop() { return regionBottom > 0 ? regionTop : 0; }
    private int listHeight() { return (regionBottom > 0 ? regionBottom : h) - listTop(); }
    private float bandTop() { return listTop() + listHeight() * BAND_TOP; }
    private float bandBottom() { return listTop() + listHeight() * BAND_BOTTOM; }
    private float cutLine() { return pieces.isEmpty() ? listTop() + listHeight() * CUT : cut; }

    private void startManual() {
        if (manual || finished || full || busy) return;
        manual = true;
        ended = false; // auto mode may have stopped because nothing moved; manual takes over from here
        pressed = false;
        directBtn.setVisibility(View.GONE);
        label.setText("직접 · " + pages + "페이지");
        guide = new GuideView(svc);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        svc.getSystemService(WindowManager.class).addView(guide, lp);
        reanchor();
        svc.handler.post(poll);
    }

    private void stopManual() {
        svc.handler.removeCallbacks(poll);
        if (guide != null) svc.removeView(guide);
        guide = null;
        anchor = null;
        if (!manual) return;
        manual = false;
        if (finished) return;
        directBtn.setVisibility(View.VISIBLE);
        shootBtn.setVisibility(View.GONE);
        label.setText(pages + "페이지");
    }

    /** Puts the line back on the cut row and sticks it to whatever element sits there now. */
    private void reanchor() {
        lineStartY = lineY = lastLineY = cutLine();
        lastMoveAt = SystemClock.uptimeMillis();
        lineState = GuideView.NORMAL;
        waitForMove = false;
        anchor = null;
        try {
            anchor = elementAt(lineY);
        } catch (RuntimeException e) {
            // Best effort: windows can change mid-walk; fall back to the fixed line.
            lastScan = "검색 오류: " + e.getClass().getSimpleName();
        }
        if (anchor != null) {
            Rect r = new Rect();
            anchor.getBoundsInScreen(r);
            anchorOffset = lineY - r.top;
        }
        shootBtn.setVisibility(anchor == null ? View.VISIBLE : View.GONE);
        drawGuide();
    }

    private String lastScan = "";

    /**
     * Smallest visible on-screen element crossing row y (a message bubble, a card, a text line). If row y
     * falls in the gap between messages, the element whose top edge is nearest to y (within 20% of the
     * screen) is used; the line keeps its offset from that element either way.
     */
    private AccessibilityNodeInfo elementAt(float y) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        for (AccessibilityWindowInfo win : svc.getWindows()) {
            if (win.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = win.getRoot();
            if (root != null) q.add(root);
        }
        int windows = 0, apps = q.size(), visited0 = 0, visible = 0;
        for (AccessibilityWindowInfo win : svc.getWindows()) windows++;
        AccessibilityNodeInfo best = null, nearest = null;
        long bestArea = Long.MAX_VALUE;
        float nearestDist = h * 0.2f;
        Rect r = new Rect();
        int visited = 0;
        while (!q.isEmpty() && visited++ < 1500) { // shortcut: very large trees are cut off; the fixed line covers that
            AccessibilityNodeInfo n = q.poll();
            visited0++;
            if (!n.isVisibleToUser()) continue;
            visible++;
            n.getBoundsInScreen(r);
            boolean sized = r.height() > 0 && r.height() < h * 0.45f && r.width() > w * 0.15f;
            if (sized && r.top <= y && r.bottom > y) {
                long area = (long) r.width() * r.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = n;
                }
            } else if (sized && Math.abs(r.top - y) < nearestDist) {
                nearestDist = Math.abs(r.top - y);
                nearest = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.add(child);
            }
        }
        lastScan = "창 " + windows + " · 앱 " + apps + " · 요소 " + visited0 + " · 보이는 요소 " + visible;
        return best != null ? best : nearest;
    }

    /** Follows the anchored element; captures once the line rests inside the band. Main thread, every 80ms. */
    private void pollAnchor() {
        if (!manual || finished) return;
        guard(() -> {
            if (anchor == null || busy) return;
            Rect r = new Rect();
            boolean alive = anchor.refresh() && anchor.isVisibleToUser();
            if (alive) anchor.getBoundsInScreen(r);
            float y = r.top + anchorOffset;
            if (!alive || Math.abs(y - lineY) > h * 0.4f) {
                // Scrolled off screen or the view got reused for other content: switch to the fixed line.
                anchor = null;
                lineY = lineStartY;
                lineState = GuideView.NORMAL;
                shootBtn.setVisibility(View.VISIBLE);
                say("선을 놓쳤어요 · 맞추고 [찍기]");
                drawGuide();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (Math.abs(y - lastLineY) > 2) {
                lastMoveAt = now;
                lastLineY = y;
                waitForMove = false;
            }
            lineY = y;
            int state = y < bandTop() ? GuideView.OVER : y <= bandBottom() ? GuideView.IN : GuideView.NORMAL;
            if (state != lineState) {
                if (state == GuideView.OVER) pill.performHapticFeedback(HapticFeedbackConstants.REJECT);
                lineState = state;
            }
            drawGuide();
            if (state == GuideView.IN && !waitForMove && now - lastMoveAt >= SETTLE_MS) {
                manualShoot(Math.round(lineStartY - y));
            }
        });
        svc.handler.postDelayed(poll, POLL_MS);
    }

    private void drawGuide() {
        // shortcut: lastScan is a diagnostic shown next to the fixed line (KakaoTalk test); drop once understood
        if (guide != null) guide.set(bandTop(), bandBottom(), lineY, lineState, anchor == null, anchor == null ? lastScan : "");
    }

    /** Captures the hand-scrolled screen and stitches it. expectedShift: how far the line moved (0 = unknown). */
    private void manualShoot(int expectedShift) {
        if (busy || finished || !manual) return;
        busy = true;
        guide.setVisibility(View.INVISIBLE); // keep the guide and our bar out of the shot
        pill.setVisibility(View.INVISIBLE);
        svc.handler.postDelayed(() -> guard(() -> svc.shoot(frame -> guard(() -> {
            showOverlays();
            new Thread(() -> guard(() -> {
                String stop = add(frame, expectedShift);
                svc.handler.post(() -> guard(() -> {
                    busy = false;
                    if (finished || !manual) return;
                    label.setText("직접 · " + pages + "페이지");
                    if (stop == null) {
                        pill.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        reanchor();
                    } else if (stop.equals(STOP_FULL)) {
                        full = true;
                        say(stop);
                        stopManual();
                    } else {
                        // Not glued (moved too far, or the screen changed): stay, let the user adjust.
                        waitForMove = true;
                        pill.performHapticFeedback(HapticFeedbackConstants.REJECT);
                        say("못 이어 붙였어요 · 다시 멈춰 주세요");
                    }
                }));
            })).start();
        }), () -> {
            showOverlays();
            busy = false;
            say("캡처 실패");
        })), 80);
    }

    private void showOverlays() {
        if (guide != null) guide.setVisibility(View.VISIBLE);
        pill.setVisibility(View.VISIBLE);
    }

    // --- UI ---

    @SuppressLint("ClickableViewAccessibility")
    private void showPill() {
        Ui ui = new Ui(svc);
        LinearLayout card = ui.barCard();
        int padH = ui.barBleedH(), padV = ui.barBleedV();
        pill = new GlowFrame(svc, card, ui.barHairline(), Ui.BAR_RADIUS, padH, padV, true);

        thumb = ui.barThumb(ShotService.thumbnail(svc, prev));
        card.addView(thumb);

        label = ui.text("1페이지", 14, ui.barText());
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setMaxWidth(ui.dp(170)); // messages share this spot; keep the buttons on screen
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = ui.dp(10);
        labelLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(label, labelLp);

        View more = ui.iconButton(R.drawable.ic_bar_scroll, "더 내리기 (누르고 있으면 계속)", ui.barIcon(), null);
        more.setOnTouchListener((v, e) -> {
            if (manual) { // in manual mode ⌄⌄ goes back to automatic scrolling
                if (e.getActionMasked() == MotionEvent.ACTION_UP) stopManual();
                return true;
            }
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
        more.setOnClickListener(v -> { if (manual) stopManual(); else step(); });
        card.addView(Ui.afterText(more, ui.dp(16)));

        // Shown from the start for now (testing); otherwise it matters once auto scrolling doesn't move.
        TextView direct = ui.text("직접 내리기", 12, Ui.KEEP_BLUE);
        direct.setTypeface(Typeface.DEFAULT_BOLD);
        direct.setSingleLine(true);
        direct.setGravity(Gravity.CENTER);
        direct.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setCornerRadius(ui.dp(12));
        outline.setStroke(ui.dp(1.5f), Ui.KEEP_BLUE);
        direct.setBackground(outline);
        direct.setOnClickListener(v -> startManual());
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(34));
        xl.leftMargin = ui.dp(4);
        xl.gravity = Gravity.CENTER_VERTICAL;
        card.addView(direct, xl);
        directBtn = direct;

        // Manual mode without element info: the line can't follow the content, so the user taps.
        TextView shoot = ui.text("찍기", 13, 0xFFFFFFFF);
        shoot.setTypeface(Typeface.DEFAULT_BOLD);
        shoot.setSingleLine(true);
        shoot.setGravity(Gravity.CENTER);
        shoot.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        shoot.setBackground(ui.pressable(Ui.KEEP_BLUE, 12));
        shoot.setOnClickListener(v -> manualShoot(0));
        shoot.setVisibility(View.GONE);
        card.addView(shoot, new LinearLayout.LayoutParams(xl));
        shootBtn = shoot;

        TextView done = ui.text("완료", 13, ui.dark ? 0xFF111318 : 0xFFFFFFFF);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setSingleLine(true); // it was squeezed into "완/료" when the bar ran out of width
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
