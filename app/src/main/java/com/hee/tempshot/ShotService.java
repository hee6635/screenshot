package com.hee.tempshot;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
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
        getAccessibilityButtonController().registerAccessibilityButtonCallback(
                new AccessibilityButtonController.AccessibilityButtonCallback() {
                    @Override public void onClicked(AccessibilityButtonController c) { capture(); }
                });
        handler.post(cleanupLoop);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        handler.removeCallbacks(cleanupLoop);
        hideToolbar();
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
            hideToolbar();
            handler.postDelayed(this::capture, 150);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
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
        handler.post(() -> showToolbar(uri));
    }

    /** Bottom bar shown after a capture: crop / keep / delete / close. Auto-hides. */
    void showToolbar(Uri uri) {
        hideToolbar();
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (8 * dp);
        bar.setPadding(p, p / 2, p, p / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE222222);
        bg.setCornerRadius(28 * dp);
        bar.setBackground(bg);

        TextView info = new TextView(this);
        info.setText(days(this) + "일 후 삭제");
        info.setTextColor(0xFFAAAAAA);
        info.setTextSize(12);
        info.setPadding(p, 0, p, 0);
        bar.addView(info);

        addButton(bar, "자르기", () -> {
            hideToolbar();
            startActivity(new Intent(this, CropActivity.class).setData(uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        addButton(bar, "보관", () -> {
            hideToolbar();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.RELATIVE_PATH, KEEP_FOLDER);
            try {
                getContentResolver().update(uri, v, null, null);
                toast("보관함(스크린샷)으로 옮겼어요");
            } catch (Exception e) {
                toast("보관 실패");
            }
        });
        addButton(bar, "삭제", () -> {
            hideToolbar();
            try { getContentResolver().delete(uri, null, null); toast("삭제했어요"); }
            catch (Exception e) { toast("삭제 실패"); }
        });
        addButton(bar, "✕", this::hideToolbar);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (96 * dp);
        getSystemService(WindowManager.class).addView(bar, lp);
        toolbar = bar;
        handler.postDelayed(hideToolbarRunnable, TOOLBAR_MS);
    }

    private void addButton(LinearLayout bar, String label, Runnable action) {
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(v -> action.run());
        bar.addView(b);
    }

    void hideToolbar() {
        handler.removeCallbacks(hideToolbarRunnable);
        if (toolbar != null) {
            getSystemService(WindowManager.class).removeView(toolbar);
            toolbar = null;
        }
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
