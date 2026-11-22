package com.hee.tempshot;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;

public class ShotService extends AccessibilityService {
    static final String FOLDER = "Pictures/TempShots/";
    static final String KEEP_FOLDER = "DCIM/Screenshots/"; // same folder as Galaxy's own screenshots
    static final String PREFS = "prefs";
    static final String KEY_DAYS = "days";
    static final String KEY_TRIM = "trim_bars";
    static final int DEFAULT_DAYS = 7;
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long TOOLBAR_MS = 6000;

    static ShotService instance;
    final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable cleanupLoop = new Runnable() {
        @Override public void run() {
            cleanup(ShotService.this);
            handler.postDelayed(this, HOUR);
        }
    };
    private View toolbar;
    private final Runnable hideToolbarRunnable = this::hideToolbar;

    // The latest single capture, kept so a scroll capture can continue from it.
    private Bitmap lastFull;
    private Uri lastUri;
    private int[] lastBars = {0, 0};
    private volatile ArrayList<Rect> lastCandidates = new ArrayList<>();
    private ScrollCapture scroll;
    private volatile boolean saving; // a capture is still being written; lastFull must stay alive

    static int days(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_DAYS, DEFAULT_DAYS);
    }

    static boolean trimBars(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TRIM, true);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        handler.post(cleanupLoop);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacks(cleanupLoop);
        hideToolbarNow();
        if (scroll != null) scroll.cancel();
        releaseLast();
        return super.onUnbind(intent);
    }

    /** Closes the notification shade first so the tile path doesn't capture it. */
    void captureAfterClosingShade() {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        captureIn(600);
    }

    void captureIn(long ms) {
        handler.postDelayed(this::capture, ms);
    }

    void capture() {
        if (scroll != null || saving) return; // a scroll capture or save is running
        if (toolbar != null) {
            // Remove the previous toolbar and give the screen a frame to redraw without it.
            hideToolbarNow();
            handler.postDelayed(this::capture, 150);
            return;
        }
        saving = true;
        shoot(bmp -> {
            flash(); // after the shot, so the flash itself isn't captured
            releaseLast();
            lastFull = bmp;
            lastUri = null;
            lastBars = systemBars(bmp.getWidth(), bmp.getHeight());
            lastCandidates = new ArrayList<>();
            int[] bars = lastBars;
            int w = bmp.getWidth(), h = bmp.getHeight();
            new Thread(() -> {
              try {
                Uri uri = save(trim(bmp, bars));
                if (uri == null) { saving = false; return; }
                Bitmap thumb = thumbnail(this, bmp);
                handler.post(() -> {
                    saving = false;
                    lastUri = uri;
                    copy(this, uri);
                    showToolbar(uri, thumb, true);
                });
                try {
                    lastCandidates = candidates(w, h, bars);
                } catch (RuntimeException e) {
                    // Best effort: windows can vanish mid-walk (e.g. another screen opening); crop still works by hand.
                    lastCandidates = new ArrayList<>();
                }
              } catch (Throwable e) { // a crash here would switch the accessibility service off
                TempShotApp.record(this, e);
                saving = false;
                toast("캡처 저장 중 오류가 났어요");
              }
            }).start();
        }, () -> { saving = false; toast("캡처 실패"); });
    }

    /** Takes a screenshot as a software bitmap; retries when Android's 1-per-second limit hits. */
    void shoot(Consumer<Bitmap> ok, Runnable fail) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                r.getHardwareBuffer().close();
                Bitmap bmp = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                if (hw != null) hw.recycle();
                if (bmp == null) fail.run();
                else ok.accept(bmp);
            }
            @Override public void onFailure(int code) {
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) handler.postDelayed(() -> shoot(ok, fail), 400);
                else fail.run();
            }
        });
    }

    /** Heights of the status bar (top) and navigation bar (bottom) currently on screen; 0 if hidden. */
    int[] systemBars(int w, int h) {
        int top = 0, bottom = 0;
        Rect r = new Rect();
        for (AccessibilityWindowInfo win : getWindows()) {
            if (win.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            win.getBoundsInScreen(r);
            if (r.width() < w * 0.9f || r.height() > h / 6) continue; // shade, edge handles, etc.
            if (r.top <= 0) top = Math.max(top, r.bottom);
            else if (r.bottom >= h) bottom = Math.max(bottom, h - r.top);
        }
        return new int[]{top, bottom};
    }

    /** Drops the status/navigation bars when the setting is on. May return the same bitmap. */
    Bitmap trim(Bitmap full, int[] bars) {
        int h = full.getHeight() - bars[0] - bars[1];
        if (!trimBars(this) || (bars[0] == 0 && bars[1] == 0) || h <= 0) return full;
        return Bitmap.createBitmap(full, 0, bars[0], full.getWidth(), h);
    }

    /**
     * Bounds of on-screen elements (images, cards, text blocks) for crop auto-select,
     * in the saved image's coordinates.
     */
    ArrayList<Rect> candidates(int w, int h, int[] bars) {
        ArrayList<Rect> out = new ArrayList<>();
        long screen = (long) w * h;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        for (AccessibilityWindowInfo win : getWindows()) {
            if (win.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = win.getRoot();
            if (root != null) q.add(root);
        }
        boolean trimmed = trimBars(this);
        Rect r = new Rect();
        int visited = 0;
        while (!q.isEmpty() && visited++ < 800) { // shortcut: huge trees are cut at 800 nodes, fine for typical screens
            AccessibilityNodeInfo n = q.poll();
            if (!n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (r.intersect(0, 0, w, h)) {
                long area = (long) r.width() * r.height();
                if (area > screen * 0.02 && area < screen * 0.92 && r.width() > w * 0.15f) {
                    Rect c = new Rect(r);
                    if (trimmed) {
                        c.offset(0, -bars[0]);
                        c.intersect(0, 0, w, h - bars[0] - bars[1]);
                    }
                    if (!c.isEmpty() && !out.contains(c)) out.add(c);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return out;
    }

    /** Bounds of the biggest scrollable element on screen (a chat or feed list), or null. */
    Rect scrollingList(int w, int h) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        for (AccessibilityWindowInfo win : getWindows()) {
            if (win.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = win.getRoot();
            if (root != null) q.add(root);
        }
        Rect best = null, r = new Rect();
        int visited = 0;
        while (!q.isEmpty() && visited++ < 800) {
            AccessibilityNodeInfo n = q.poll();
            if (!n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (n.isScrollable() && r.intersect(0, 0, w, h) && r.height() > h * 0.3f
                    && (best == null || (long) r.width() * r.height() > (long) best.width() * best.height())) {
                best = new Rect(r);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return best;
    }

    /** Saves a PNG into the TempShots album. Recycles img unless it is the kept full capture. */
    Uri save(Bitmap img) {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "TempShot_" + System.currentTimeMillis() + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try {
            if (uri == null) { toast("저장 실패"); return null; }
            try (OutputStream out = cr.openOutputStream(uri)) {
                img.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                cr.delete(uri, null, null); // don't leave a half-written file behind
                toast("저장 실패");
                return null;
            }
        } finally {
            if (img != lastFull) img.recycle();
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    private void releaseLast() {
        if (lastFull != null) lastFull.recycle();
        lastFull = null;
    }

    static Bitmap thumbnail(Context c, Bitmap src) {
        int h = Math.round(72 * c.getResources().getDisplayMetrics().density);
        int w = Math.max(1, Math.round((float) src.getWidth() * h / src.getHeight()));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    /** Puts the image on the clipboard so it can be pasted right away. */
    static void copy(Context c, Uri uri) {
        c.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newUri(c.getContentResolver(), "스크린샷", uri));
    }

    // --- scroll capture ---

    private void startScroll() {
        if (lastFull == null || lastUri == null) { toast("이 이미지는 스크롤 캡처를 이어갈 수 없어요"); return; }
        hideToolbarNow();
        Rect list = null;
        try {
            list = scrollingList(lastFull.getWidth(), lastFull.getHeight());
        } catch (RuntimeException e) {
            // Best effort: without it the header/footer are inferred from the first step.
        }
        scroll = new ScrollCapture(this, lastFull, lastBars, lastUri, list);
        lastFull = null; // ownership moves to the scroll capture
        scroll.start();
    }

    /** Called by ScrollCapture when done. stitched is null when nothing was added. */
    void onScrollFinished(Bitmap stitched, Uri original, int[] bars) {
        scroll = null;
        if (stitched == null) {
            toast("추가로 캡처된 내용이 없어요");
            return;
        }
        new Thread(() -> {
          try {
            Bitmap thumb = thumbnail(this, stitched);
            Bitmap out = trim(stitched, bars);
            if (out != stitched) stitched.recycle();
            Uri uri = save(out);
            if (uri == null) return;
            try { getContentResolver().delete(original, null, null); } catch (Exception ignored) { }
            handler.post(() -> {
                copy(this, uri);
                showToolbar(uri, thumb, false);
            });
          } catch (Throwable e) {
            TempShotApp.record(this, e);
            toast("스크롤 캡처를 저장하지 못했어요");
          }
        }).start();
    }

    // --- toolbar ---

    /** Floating bar shown after a capture: thumbnail, "복사됨" + delete-period chip, scroll / crop / keep / delete. */
    void showToolbar(Uri uri, Bitmap thumb, boolean canScroll) {
        hideToolbarNow();
        Ui ui = new Ui(this);
        LinearLayout card = ui.barCard();
        int padH = ui.dp(6), padV = ui.dp(12); // glow bleed; a little more vertically for the shadow
        GlowFrame bar = new GlowFrame(this, card, ui.barHairline(), Ui.BAR_RADIUS, padH, padV, false);
        bar.onSwipeDown = this::hideToolbar;

        card.addView(shareThumb(ui, uri, thumb));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text("복사됨", 14, ui.barText());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        info.addView(title);
        info.addView(periodChip(ui));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.leftMargin = ui.dp(10) - ui.dp(5); // keep 10dp from the thumbnail; its slot is 5dp wider
        infoLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(info, infoLp);

        int gap = ui.dp(16); // text block → first icon
        if (canScroll) {
            card.addView(Ui.afterText(ui.iconButton(R.drawable.ic_bar_scroll, "스크롤 캡처", ui.barIcon(), v -> startScroll()), gap));
        }
        ImageView crop = ui.iconButton(R.drawable.ic_bar_crop, "자르기", ui.barIcon(), v -> {
            Intent cropIntent = new Intent(this, CropActivity.class).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (canScroll && lastFull != null) { // element bounds only match the single-screen capture
                cropIntent.putParcelableArrayListExtra(CropActivity.EXTRA_CANDIDATES, lastCandidates);
                if (!trimBars(this)) {
                    cropIntent.putExtra(CropActivity.EXTRA_INITIAL,
                            new Rect(0, lastBars[0], lastFull.getWidth(), lastFull.getHeight() - lastBars[1]));
                }
            }
            startActivity(cropIntent);
            hideToolbarNow();
        });
        card.addView(canScroll ? crop : Ui.afterText(crop, gap));
        card.addView(ui.iconButton(R.drawable.ic_bar_keep, "보관", ui.barIcon(), v -> {
            ImageView keep = (ImageView) v;
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, KEEP_FOLDER);
            try {
                getContentResolver().update(uri, cv, null, null);
                // Only an active keep turns the bookmark blue; leave it on screen a moment to be seen.
                keep.setImageResource(R.drawable.ic_bar_keep_filled);
                keep.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.KEEP_BLUE));
                keep.setEnabled(false);
                handler.removeCallbacks(hideToolbarRunnable);
                handler.postDelayed(hideToolbarRunnable, 700);
                toast("스크린샷 앨범에 보관했어요");
            } catch (Exception e) {
                toast("보관 실패");
            }
        }));
        card.addView(ui.iconButton(R.drawable.ic_bar_delete, "휴지통으로 이동", ui.barDanger(), v -> {
            hideToolbar();
            try {
                trash(this, uri);
                getSystemService(ClipboardManager.class).clearPrimaryClip();
                toast("휴지통으로 옮겼어요");
            } catch (Exception e) {
                toast("삭제 실패");
            }
        }));

        bar.onOutsideTouch = this::hideToolbarNow; // tapping anywhere else dismisses it at once
        WindowManager.LayoutParams lp = ui.barWindow(padV);
        lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        getSystemService(WindowManager.class).addView(bar, lp);
        toolbar = bar;
        bar.setAlpha(0f);
        bar.setTranslationY(ui.dp(40));
        bar.animate().alpha(1f).translationY(0).setDuration(220).start();
        handler.postDelayed(hideToolbarRunnable, TOOLBAR_MS);
    }

    /**
     * Thumbnail that shares the shot (tap) or opens it (long press), with a small static share badge
     * so it reads as "tap to share".
     */
    private View shareThumb(Ui ui, Uri uri, Bitmap thumb) {
        ImageView preview = ui.barThumb(thumb);
        // The slot is 6dp wider than the 36x48dp thumbnail so the badge sits fully inside it, straddling
        // the thumbnail's corner. Overhanging the slot got it clipped into a half circle.
        int extra = ui.dp(5);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(36) + extra, ui.dp(48)));
        box.addView(preview, new android.widget.FrameLayout.LayoutParams(ui.dp(36), ui.dp(48), Gravity.START));

        ImageView badge = new ImageView(this);
        badge.setImageResource(R.drawable.ic_share);
        badge.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        badge.setPadding(ui.dp(3.5f), ui.dp(3.5f), ui.dp(3.5f), ui.dp(3.5f));
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Ui.KEEP_BLUE);
        dot.setStroke(ui.dp(1.5f), ui.barBg() | 0xFF000000); // ring in the bar color separates it from the photo
        badge.setBackground(dot);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.widget.FrameLayout.LayoutParams b = new android.widget.FrameLayout.LayoutParams(
                ui.dp(15), ui.dp(15), Gravity.BOTTOM | Gravity.END);
        b.bottomMargin = ui.dp(2);
        box.addView(badge, b);

        box.setContentDescription("공유하기 (길게 누르면 열기)");
        box.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(null, uri)); // shows the preview in the share sheet
            startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            hideToolbarNow();
        });
        box.setOnLongClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            hideToolbarNow();
            return true;
        });
        // Press feedback: the thumbnail dips slightly.
        box.setOnTouchListener((v, e) -> {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start();
            else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false; // let click / long click run
        });

        return box;
    }

    /** "7일 후 휴지통 ⚙": text and gear are separate views in a wrap-content row, so the gear is never cut. Opens settings. */
    private View periodChip(Ui ui) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        chip.setBackground(ui.pressable(ui.barChip(), 6));
        TextView t = ui.text(days(this) + "일 후 휴지통", 11.5f, ui.barSubText());
        t.setSingleLine(true);
        chip.addView(t);
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setImageTintList(android.content.res.ColorStateList.valueOf(ui.barSubText()));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(ui.dp(11), ui.dp(11));
        g.leftMargin = ui.dp(4);
        chip.addView(gear, g);
        chip.setContentDescription(days(this) + "일 후 휴지통으로 이동, 설정 열기");
        chip.setOnClickListener(v -> {
            // Launch while our toolbar window is still visible; Android trusts launches from visible apps.
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            hideToolbarNow();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(3);
        chip.setLayoutParams(lp);
        return chip;
    }

    static WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /** Brief white flash like a camera shutter. Skipped when the user turned animations off. */
    private void flash() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        View white = new View(this);
        white.setBackgroundColor(0xFFFFFFFF);
        white.setAlpha(0.7f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        getSystemService(WindowManager.class).addView(white, lp);
        white.animate().alpha(0f).setDuration(250).withEndAction(() -> removeView(white)).start();
    }

    /** Slides the toolbar out. */
    void hideToolbar() {
        handler.removeCallbacks(hideToolbarRunnable);
        View bar = toolbar;
        if (bar == null) return;
        toolbar = null;
        bar.animate().alpha(0f).translationY(bar.getTranslationY() + bar.getHeight() / 2f).setDuration(180)
                .withEndAction(() -> removeView(bar)).start();
    }

    /** Removes the toolbar immediately (before a capture or opening another screen). */
    void hideToolbarNow() {
        handler.removeCallbacks(hideToolbarRunnable);
        if (toolbar != null) {
            removeView(toolbar);
            toolbar = null;
        }
    }

    void removeView(View v) {
        if (v.isAttachedToWindow()) getSystemService(WindowManager.class).removeView(v);
    }

    /**
     * Moves an image to the gallery's trash (restorable for 30 days) instead of deleting it.
     * Apps may trash their own media without asking. Returns 1 if moved.
     */
    static int trash(Context c, Uri uri) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_TRASHED, 1);
        return c.getContentResolver().update(uri, v, null, null);
    }

    /** Moves TempShots older than the configured days to the trash. Already-trashed ones aren't listed. */
    static int cleanup(Context c) {
        long cutoffSec = System.currentTimeMillis() / 1000 - days(c) * 86400L;
        ContentResolver cr = c.getContentResolver();
        int deleted = 0;
        try (Cursor cur = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.RELATIVE_PATH + "=? AND " + MediaStore.Images.Media.DATE_ADDED + "<?",
                new String[]{FOLDER, String.valueOf(cutoffSec)}, null)) {
            if (cur == null) return 0;
            while (cur.moveToNext()) {
                Uri u = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getString(0));
                try {
                    deleted += trash(c, u);
                } catch (SecurityException e) {
                    // shortcut: files from before an app reinstall aren't ours anymore and are skipped; delete them by hand in Gallery.
                }
            }
        }
        return deleted;
    }

    void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
