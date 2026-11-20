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
            new Thread(() -> {
                Uri uri = save(trim(bmp, bars));
                if (uri == null) { saving = false; return; }
                Bitmap thumb = thumbnail(this, bmp);
                handler.post(() -> {
                    saving = false;
                    lastUri = uri;
                    copy(this, uri);
                    showToolbar(uri, thumb, true);
                });
                lastCandidates = candidates(bmp.getWidth(), bmp.getHeight(), bars);
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
        scroll = new ScrollCapture(this, lastFull, lastBars, lastUri);
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
        }).start();
    }

    // --- toolbar ---

    /** Galaxy AI-style glowing bar shown after a capture: preview, delete period, scroll / crop / keep / delete. */
    void showToolbar(Uri uri, Bitmap thumb, boolean canScroll) {
        hideToolbarNow();
        Ui ui = new Ui(this);
        int margin = ui.dp(14); // room for the glow around the pill
        GlowFrame bar = new GlowFrame(this, ui.dark ? 0xF21C1C20 : 0xF7FFFFFF, margin, false);
        bar.onSwipeDown = this::hideToolbar;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(7), ui.dp(7), ui.dp(9), ui.dp(7));
        bar.addView(row);

        ImageView preview = new ImageView(this);
        preview.setImageBitmap(thumb);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(ui.round(ui.chip, 28));
        preview.setClipToOutline(true);
        preview.setContentDescription("미리보기 열기");
        preview.setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
            hideToolbarNow();
        });
        row.addView(preview, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(ui.dp(12), 0, ui.dp(10), 0);
        TextView title = ui.text("복사됨", 17, ui.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        info.addView(title);
        info.addView(periodChip(ui));
        row.addView(info);

        LinearLayout.LayoutParams btn = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46));
        btn.leftMargin = ui.dp(5);
        if (canScroll) {
            row.addView(ui.iconButton(R.drawable.ic_scroll, "스크롤 캡처", ui.text, ui.buttonBg(), v -> startScroll()), btn);
        }
        row.addView(ui.iconButton(R.drawable.ic_crop, "자르기", ui.text, ui.buttonBg(), v -> {
            Intent crop = new Intent(this, CropActivity.class).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (canScroll && lastFull != null) { // element bounds only match the single-screen capture
                crop.putParcelableArrayListExtra(CropActivity.EXTRA_CANDIDATES, lastCandidates);
                if (!trimBars(this)) {
                    crop.putExtra(CropActivity.EXTRA_INITIAL,
                            new Rect(0, lastBars[0], lastFull.getWidth(), lastFull.getHeight() - lastBars[1]));
                }
            }
            startActivity(crop);
            hideToolbarNow();
        }), btn);
        row.addView(ui.iconButton(R.drawable.ic_keep_outline, "보관", ui.text, ui.buttonBg(), v -> {
            hideToolbar();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, KEEP_FOLDER);
            try {
                getContentResolver().update(uri, cv, null, null);
                toast("스크린샷 앨범에 보관했어요");
            } catch (Exception e) {
                toast("보관 실패");
            }
        }), btn);
        row.addView(ui.iconButton(R.drawable.ic_delete, "삭제", ui.dark ? 0xFFB91C1C : Ui.DANGER,
                ui.dark ? 0xFFF07575 : 0x1FE5484D, v -> {
            hideToolbar();
            try {
                getContentResolver().delete(uri, null, null);
                getSystemService(ClipboardManager.class).clearPrimaryClip();
                toast("삭제했어요");
            } catch (Exception e) {
                toast("삭제 실패");
            }
        }), btn);

        WindowManager.LayoutParams lp = overlayParams();
        lp.y = ui.dp(96) - margin;
        getSystemService(WindowManager.class).addView(bar, lp);
        toolbar = bar;
        bar.setAlpha(0f);
        bar.setTranslationY(ui.dp(40));
        bar.animate().alpha(1f).translationY(0).setDuration(220).start();
        handler.postDelayed(hideToolbarRunnable, TOOLBAR_MS);
    }

    /** "7일 삭제 ⚙" chip; opens settings. */
    private View periodChip(Ui ui) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(ui.dp(8), ui.dp(3), ui.dp(7), ui.dp(3));
        chip.setBackground(ui.pressable(ui.buttonBg(), 12));
        TextView t = ui.text(days(this) + "일 삭제", 12, ui.subText);
        t.setSingleLine(true);
        chip.addView(t);
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setImageTintList(android.content.res.ColorStateList.valueOf(ui.subText));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(ui.dp(13), ui.dp(13));
        g.leftMargin = ui.dp(4);
        chip.addView(gear, g);
        chip.setContentDescription(days(this) + "일 후 삭제, 설정 열기");
        chip.setOnClickListener(v -> {
            // Launch while our toolbar window is still visible; Android trusts launches from visible apps.
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            hideToolbarNow();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
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

    /** Deletes TempShots older than the configured days. Returns how many were deleted. */
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
                    deleted += cr.delete(u, null, null);
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
