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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.OutputStream;

public class ShotService extends AccessibilityService {
    static final String FOLDER = "Pictures/TempShots/";
    static final String KEEP_FOLDER = "DCIM/Screenshots/"; // same folder as Galaxy's own screenshots
    private static final long TOOLBAR_MS = 6000;
    static final String PREFS = "prefs";
    static final String KEY_DAYS = "days";
    static final int DEFAULT_DAYS = 7;
    private static final long HOUR = 60 * 60 * 1000L;

    static ShotService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable cleanupLoop = new Runnable() {
        @Override public void run() {
            cleanup(ShotService.this);
            handler.postDelayed(this, HOUR);
        }
    };
    private View toolbar;
    private final Runnable hideToolbarRunnable = this::hideToolbar;

    static int days(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_DAYS, DEFAULT_DAYS);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        handler.post(cleanupLoop);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        handler.removeCallbacks(cleanupLoop);
        hideToolbarNow();
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
        if (toolbar != null) {
            // Remove the previous toolbar and give the screen a frame to redraw without it.
            hideToolbarNow();
            handler.postDelayed(this::capture, 150);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                flash(); // after the shot, so the flash itself isn't captured
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                r.getHardwareBuffer().close();
                if (hw == null) { toast("캡처 실패"); return; }
                Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
                new Thread(() -> save(bmp)).start();
            }
            @Override public void onFailure(int code) {
                toast("캡처 실패 (" + code + ")");
            }
        });
    }

    private void save(Bitmap bmp) {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "TempShot_" + System.currentTimeMillis() + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) { toast("저장 실패"); return; }
        Bitmap thumb = thumbnail(this, bmp);
        try (OutputStream out = cr.openOutputStream(uri)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            cr.delete(uri, null, null); // don't leave a half-written file behind
            toast("저장 실패");
            return;
        } finally {
            bmp.recycle();
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        handler.post(() -> {
            copy(this, uri);
            showToolbar(uri, thumb);
        });
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

    /** Galaxy-style bottom bar shown after a capture: preview, crop / keep / delete / close. Auto-hides. */
    void showToolbar(Uri uri, Bitmap thumb) {
        hideToolbarNow();
        Ui ui = new Ui(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        bar.setBackground(ui.round((ui.card & 0x00FFFFFF) | 0xF2000000, 28));
        bar.setElevation(ui.dp(8));

        ImageView preview = new ImageView(this);
        preview.setImageBitmap(thumb);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(ui.round(ui.chip, 12));
        preview.setClipToOutline(true);
        preview.setContentDescription("미리보기 열기");
        preview.setOnClickListener(v -> {
            hideToolbarNow();
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        });
        int ph = ui.dp(72);
        bar.addView(preview, new LinearLayout.LayoutParams(Math.min(ph, thumb.getWidth()), ph));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        info.addView(ui.text("복사됨", 14, ui.text));
        info.addView(ui.text(days(this) + "일 후 삭제", 12, ui.subText));
        bar.addView(info);

        bar.addView(ui.action(R.drawable.ic_crop, "자르기", ui.text, v -> {
            hideToolbarNow();
            startActivity(new Intent(this, CropActivity.class).setData(uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }));
        bar.addView(ui.action(R.drawable.ic_keep, "보관", Ui.ACCENT, v -> {
            hideToolbar();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, KEEP_FOLDER);
            try {
                getContentResolver().update(uri, cv, null, null);
                toast("스크린샷 폴더에 보관했어요");
            } catch (Exception e) {
                toast("보관 실패");
            }
        }));
        bar.addView(ui.action(R.drawable.ic_delete, "삭제", Ui.DANGER, v -> {
            hideToolbar();
            try {
                getContentResolver().delete(uri, null, null);
                getSystemService(ClipboardManager.class).clearPrimaryClip();
                toast("삭제했어요");
            } catch (Exception e) {
                toast("삭제 실패");
            }
        }));
        bar.addView(ui.action(R.drawable.ic_close, "닫기", ui.subText, v -> hideToolbar()));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = ui.dp(72);
        getSystemService(WindowManager.class).addView(bar, lp);
        toolbar = bar;
        bar.setAlpha(0f);
        bar.setTranslationY(ui.dp(40));
        bar.animate().alpha(1f).translationY(0).setDuration(220).start();
        handler.postDelayed(hideToolbarRunnable, TOOLBAR_MS);
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
        white.animate().alpha(0f).setDuration(250).withEndAction(() -> removeBar(white)).start();
    }

    /** Slides the toolbar out. */
    void hideToolbar() {
        handler.removeCallbacks(hideToolbarRunnable);
        View bar = toolbar;
        if (bar == null) return;
        toolbar = null;
        bar.animate().alpha(0f).translationY(bar.getHeight() / 2f).setDuration(180)
                .withEndAction(() -> removeBar(bar)).start();
    }

    /** Removes the toolbar immediately (before a capture or opening another screen). */
    void hideToolbarNow() {
        handler.removeCallbacks(hideToolbarRunnable);
        if (toolbar != null) {
            removeBar(toolbar);
            toolbar = null;
        }
    }

    private void removeBar(View bar) {
        if (bar.isAttachedToWindow()) getSystemService(WindowManager.class).removeView(bar);
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

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
