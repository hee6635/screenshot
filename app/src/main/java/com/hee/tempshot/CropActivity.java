package com.hee.tempshot;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class CropActivity extends Activity {
    static final String EXTRA_CANDIDATES = "candidates"; // ArrayList<Rect> of on-screen elements, image coords
    static final String EXTRA_INITIAL = "initial";       // Rect to start the crop with
    private Uri uri;
    private Bitmap bitmap;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = getIntent().getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            bitmap = null;
        }
        if (bitmap == null) {
            Toast.makeText(this, "이미지를 열 수 없어요", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        Ui ui = new Ui(this);
        @SuppressWarnings("deprecation") // the typed overloads need API 33
        ArrayList<Rect> cands = getIntent().getParcelableArrayListExtra(EXTRA_CANDIDATES);
        @SuppressWarnings("deprecation")
        Rect initial = getIntent().getParcelableExtra(EXTRA_INITIAL);
        CropView crop = new CropView(this, bitmap, initial, cands != null ? cands : new ArrayList<>());

        android.widget.TextView hint = ui.text(cands != null && !cands.isEmpty()
                ? "대각선으로 그어서 범위를 잡거나, 영역을 탭하면 자동으로 맞춰요"
                : "대각선으로 그어서 범위를 잡거나, 모서리를 끌어서 다듬으세요", 13, 0xFFB0B0B5);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, ui.dp(12), 0, ui.dp(4));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.addView(ui.action(R.drawable.ic_close, "취소", Color.WHITE, v -> finish()),
                new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.action(R.drawable.ic_check, "완료", Ui.ACCENT, v -> save(crop.cropInBitmap())),
                new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(hint);
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(72)));
        Ui.padForSystemBars(root, 0);
        setContentView(root);
    }

    private void save(Rect r) {
        Bitmap out = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height());
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        boolean ok = false;
        try {
            // Pending on → write → pending off makes MediaStore rescan the new size.
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            cr.update(uri, v, null, null);
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                // keep the file's own format (scroll captures are JPEG)
                if ("image/jpeg".equals(ShotService.mime(this, uri))) out.compress(Bitmap.CompressFormat.JPEG, 95, os);
                else out.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            ok = true;
        } catch (Exception e) {
            Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show();
        } finally {
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
        }
        finish();
        if (!ok) return;
        ShotService.copy(this, uri);
        if (ShotService.instance != null) ShotService.instance.showToolbar(uri, ShotService.thumbnail(this, out), false);
    }

    /**
     * Shows the image fit to the view with a crop rectangle. Drag an edge or corner to resize; drag
     * anywhere else to draw a new box (Galaxy-style "swipe to select"), which snaps to the on-screen
     * element it best covers; tap to snap to the element under the finger.
     */
    static class CropView extends View {
        private static final int L = 1, T = 2, R = 4, B = 8, DRAW = 32;
        private static final float SNAP_IOU = 0.6f; // how closely a drawn box must cover an element to snap
        private final Bitmap bmp;
        private final RectF img = new RectF();   // where the bitmap is drawn
        private final RectF crop = new RectF();  // crop rect in view coords
        private final Paint dim = new Paint(), line = new Paint(), handle = new Paint();
        private final float touch, minSize;
        private final Paint grid = new Paint();
        private final Rect initial;
        private final ArrayList<Rect> candidates;
        private final float tapSlop;
        private int mode;
        private float lastX, lastY, downX, downY;
        private boolean moved;
        private final RectF before = new RectF(); // crop before a draw, restored if the box is too small

        CropView(Context c, Bitmap bmp, Rect initial, ArrayList<Rect> candidates) {
            super(c);
            this.bmp = bmp;
            this.initial = initial;
            this.candidates = candidates;
            float dp = c.getResources().getDisplayMetrics().density;
            tapSlop = 8 * dp;
            touch = 32 * dp;
            minSize = 48 * dp;
            dim.setColor(0x99000000);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * dp);
            handle.setColor(Color.WHITE);
            handle.setStrokeWidth(5 * dp);
            handle.setStrokeCap(Paint.Cap.ROUND);
            grid.setColor(0x80FFFFFF);
            grid.setStrokeWidth(1 * dp);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = touch / 2;
            float s = Math.min((w - 2 * pad) / bmp.getWidth(), (h - 2 * pad) / bmp.getHeight());
            float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
            img.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            if (initial != null) toView(initial, crop);
            else crop.set(img);
        }

        private void toView(Rect r, RectF out) {
            float s = img.width() / bmp.getWidth();
            out.set(img.left + r.left * s, img.top + r.top * s, img.left + r.right * s, img.top + r.bottom * s);
            out.intersect(img);
        }

        /** Snaps the crop to the smallest on-screen element under the tap. */
        private void snapTo(float x, float y) {
            float s = bmp.getWidth() / img.width();
            int bx = Math.round((x - img.left) * s), by = Math.round((y - img.top) * s);
            Rect best = null;
            for (Rect r : candidates) {
                if (r.contains(bx, by) && (best == null || (long) r.width() * r.height() < (long) best.width() * best.height())) {
                    best = r;
                }
            }
            if (best != null) {
                toView(best, crop);
                invalidate();
            }
        }

        /** Snaps a drawn box to the element it overlaps best, if that overlap is close enough. */
        private void snapToBox() {
            Rect r = cropInBitmap();
            Rect best = null;
            float bestIou = SNAP_IOU;
            for (Rect e : candidates) {
                float v = iou(r.left, r.top, r.right, r.bottom, e.left, e.top, e.right, e.bottom);
                if (v >= bestIou) {
                    bestIou = v;
                    best = e;
                }
            }
            if (best != null) toView(best, crop);
        }

        /** Intersection over union of two boxes: 1 = identical, 0 = no overlap. */
        static float iou(int al, int at, int ar, int ab, int bl, int bt, int br, int bb) {
            long iw = Math.max(0, Math.min(ar, br) - Math.max(al, bl));
            long ih = Math.max(0, Math.min(ab, bb) - Math.max(at, bt));
            long inter = iw * ih;
            long union = (long) (ar - al) * (ab - at) + (long) (br - bl) * (bb - bt) - inter;
            return union <= 0 ? 0f : (float) inter / union;
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawBitmap(bmp, null, img, null);
            c.drawRect(img.left, img.top, img.right, crop.top, dim);
            c.drawRect(img.left, crop.bottom, img.right, img.bottom, dim);
            c.drawRect(img.left, crop.top, crop.left, crop.bottom, dim);
            c.drawRect(crop.right, crop.top, img.right, crop.bottom, dim);
            c.drawRect(crop, line);
            if (mode != 0) { // rule-of-thirds guide while dragging
                for (int i = 1; i < 3; i++) {
                    float gx = crop.left + crop.width() * i / 3, gy = crop.top + crop.height() * i / 3;
                    c.drawLine(gx, crop.top, gx, crop.bottom, grid);
                    c.drawLine(crop.left, gy, crop.right, gy, grid);
                }
            }
            float k = touch * 0.6f;
            for (float x : new float[]{crop.left, crop.right}) {
                for (float y : new float[]{crop.top, crop.bottom}) {
                    float sx = x == crop.left ? k : -k, sy = y == crop.top ? k : -k;
                    c.drawLine(x, y, x + sx, y, handle);
                    c.drawLine(x, y, x, y + sy, handle);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = 0;
                    if (Math.abs(x - crop.left) < touch) mode |= L;
                    else if (Math.abs(x - crop.right) < touch) mode |= R;
                    if (Math.abs(y - crop.top) < touch) mode |= T;
                    else if (Math.abs(y - crop.bottom) < touch) mode |= B;
                    if (mode == 0 && img.contains(x, y)) mode = DRAW;
                    before.set(crop);
                    lastX = downX = x;
                    lastY = downY = y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.hypot(x - downX, y - downY) < tapSlop) return true;
                    moved = true;
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (mode == DRAW) { // box from where the finger went down to where it is now
                        float cx = clamp(x, img.left, img.right), cy = clamp(y, img.top, img.bottom);
                        crop.set(Math.min(downX, cx), Math.min(downY, cy), Math.max(downX, cx), Math.max(downY, cy));
                    } else {
                        if ((mode & L) != 0) crop.left = clamp(crop.left + dx, img.left, crop.right - minSize);
                        if ((mode & R) != 0) crop.right = clamp(crop.right + dx, crop.left + minSize, img.right);
                        if ((mode & T) != 0) crop.top = clamp(crop.top + dy, img.top, crop.bottom - minSize);
                        if ((mode & B) != 0) crop.bottom = clamp(crop.bottom + dy, crop.top + minSize, img.bottom);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) snapTo(x, y);
                    else if (mode == DRAW) {
                        if (crop.width() < minSize || crop.height() < minSize) crop.set(before); // a slip, not a box
                        else snapToBox();
                    }
                    mode = 0;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mode = 0;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        /** Crop rect mapped to bitmap pixels, kept inside the bitmap. */
        Rect cropInBitmap() {
            float s = bmp.getWidth() / img.width();
            int l = Math.max(0, Math.round((crop.left - img.left) * s));
            int t = Math.max(0, Math.round((crop.top - img.top) * s));
            int r = Math.min(bmp.getWidth(), Math.round((crop.right - img.left) * s));
            int b = Math.min(bmp.getHeight(), Math.round((crop.bottom - img.top) * s));
            return new Rect(l, t, Math.max(r, l + 1), Math.max(b, t + 1));
        }
    }
}
