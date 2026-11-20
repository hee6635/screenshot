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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

public class CropActivity extends Activity {
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

        CropView crop = new CropView(this, bitmap);
        Button cancel = new Button(this);
        cancel.setText("취소");
        cancel.setOnClickListener(v -> finish());
        Button save = new Button(this);
        save.setText("자르기 저장");
        save.setOnClickListener(v -> save(crop.cropInBitmap()));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(save, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int barH = (int) (72 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams cropLp = new FrameLayout.LayoutParams(-1, -1);
        cropLp.bottomMargin = barH;
        root.addView(crop, cropLp);
        root.addView(bar, new FrameLayout.LayoutParams(-1, barH, Gravity.BOTTOM));
        setContentView(root);
    }

    private void save(Rect r) {
        Bitmap out = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height());
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        try {
            // Pending on → write → pending off makes MediaStore rescan the new size.
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            cr.update(uri, v, null, null);
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                out.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
        } catch (Exception e) {
            Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show();
        } finally {
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
        }
        finish();
        if (ShotService.instance != null) ShotService.instance.showToolbar(uri);
    }

    /** Shows the image fit to the view with a draggable crop rectangle (edges, corners, or move). */
    static class CropView extends View {
        private static final int L = 1, T = 2, R = 4, B = 8, MOVE = 16;
        private final Bitmap bmp;
        private final RectF img = new RectF();   // where the bitmap is drawn
        private final RectF crop = new RectF();  // crop rect in view coords
        private final Paint dim = new Paint(), line = new Paint(), handle = new Paint();
        private final float touch, minSize;
        private int mode;
        private float lastX, lastY;

        CropView(Context c, Bitmap bmp) {
            super(c);
            this.bmp = bmp;
            float dp = c.getResources().getDisplayMetrics().density;
            touch = 32 * dp;
            minSize = 48 * dp;
            dim.setColor(0x99000000);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * dp);
            handle.setColor(Color.WHITE);
            handle.setStrokeWidth(5 * dp);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = touch / 2;
            float s = Math.min((w - 2 * pad) / bmp.getWidth(), (h - 2 * pad) / bmp.getHeight());
            float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
            img.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            crop.set(img);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawBitmap(bmp, null, img, null);
            c.drawRect(img.left, img.top, img.right, crop.top, dim);
            c.drawRect(img.left, crop.bottom, img.right, img.bottom, dim);
            c.drawRect(img.left, crop.top, crop.left, crop.bottom, dim);
            c.drawRect(crop.right, crop.top, img.right, crop.bottom, dim);
            c.drawRect(crop, line);
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
                    if (mode == 0 && crop.contains(x, y)) mode = MOVE;
                    lastX = x;
                    lastY = y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (mode == MOVE) {
                        dx = clamp(dx, img.left - crop.left, img.right - crop.right);
                        dy = clamp(dy, img.top - crop.top, img.bottom - crop.bottom);
                        crop.offset(dx, dy);
                    } else {
                        if ((mode & L) != 0) crop.left = clamp(crop.left + dx, img.left, crop.right - minSize);
                        if ((mode & R) != 0) crop.right = clamp(crop.right + dx, crop.left + minSize, img.right);
                        if ((mode & T) != 0) crop.top = clamp(crop.top + dy, img.top, crop.bottom - minSize);
                        if ((mode & B) != 0) crop.bottom = clamp(crop.bottom + dy, crop.top + minSize, img.bottom);
                    }
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
