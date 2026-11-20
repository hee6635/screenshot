package com.hee.tempshot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One UI-like colors and shapes shared by the toolbar, crop screen and settings. */
final class Ui {
    static final int ACCENT = 0xFF3E82F7;
    static final int DANGER = 0xFFE5484D;

    final Context c;
    final boolean dark;
    final int bg, card, text, subText, chip;

    Ui(Context c) {
        this.c = c;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF000000 : 0xFFF2F2F7;
        card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        text = dark ? 0xFFFFFFFF : 0xFF111111;
        subText = dark ? 0xFF9A9AA0 : 0xFF6B6B70;
        chip = dark ? 0xFF2C2C2E : 0xFFEDEDF2;
    }

    int dp(float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Rounded background with a touch ripple. */
    RippleDrawable pressable(int color, float radiusDp) {
        int ripple = dark ? 0x33FFFFFF : 0x22000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), round(color, radiusDp), round(0xFFFFFFFF, radiusDp));
    }

    TextView text(String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Icon above a small label, like Galaxy's bottom action buttons. */
    LinearLayout action(int icon, String label, int fg, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(pressable(0x00000000, 16));
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(fg));
        b.addView(i, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(label, 11, fg);
        t.setSingleLine(true); // "자르기" was wrapping to "자르" on large font settings
        t.setPadding(0, dp(3), 0, 0);
        b.addView(t);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        return b;
    }

    // --- floating bar (after a capture / during scroll capture) ---

    static final int KEEP_BLUE = 0xFF2563EB;
    // 20dp outer vs 12dp thumbnail: ~ inner radius + padding (6dp vertical, 10dp left), so the curves nest.
    static final float BAR_RADIUS = 20;

    int barBg() { return dark ? 0xF2181A1F : 0xFAFFFFFF; } // near-opaque: system "복사했어요." toast barely shows through
    int barHairline() { return dark ? 0x24FFFFFF : 0x18000000; }
    int barText() { return dark ? 0xFFF2F4F8 : 0xFF191F28; }
    int barSubText() { return dark ? 0xFFA3A8B3 : 0xFF6B7280; }
    int barIcon() { return dark ? 0xFFECEFF4 : 0xFF222730; }
    int barDanger() { return dark ? 0xFFFF6B6B : 0xFFE5484D; }
    int barChip() { return dark ? 0x18FFFFFF : 0x0E000000; }
    int barRipple() { return dark ? 0x26FFFFFF : 0x1A000000; }

    /** The bar's card: compact wrap-content row, rounded 20dp, soft 10dp shadow, padding L10 R10 T6 B6. */
    LinearLayout barCard() {
        LinearLayout card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(6));
        card.setBackground(round(barBg(), BAR_RADIUS));
        card.setElevation(dp(10));
        return card;
    }

    /** 36x48dp portrait thumbnail, clipped to 12dp corners, with a 1dp hairline so bright shots don't blend in. */
    ImageView barThumb(android.graphics.Bitmap bmp) {
        ImageView i = new ImageView(c);
        i.setImageBitmap(bmp);
        i.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int r = dp(12);
        i.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        i.setClipToOutline(true);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(r);
        border.setStroke(dp(1), dark ? 0x28FFFFFF : 0x1F000000);
        i.setForeground(border);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(48)));
        return i;
    }

    /** Icon-only 38dp button: transparent, oval ripple only. The label is kept for TalkBack. */
    ImageView iconButton(int icon, String label, int fg, View.OnClickListener click) {
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(fg));
        i.setPadding(dp(9), dp(9), dp(9), dp(9)); // 38dp touch target around a 20dp icon
        android.graphics.drawable.ShapeDrawable mask =
                new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
        i.setBackground(new RippleDrawable(ColorStateList.valueOf(barRipple()), null, mask));
        i.setContentDescription(label);
        i.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.leftMargin = dp(4); // the first button after the text gets 16dp from the caller
        lp.gravity = Gravity.CENTER_VERTICAL;
        i.setLayoutParams(lp);
        return i;
    }

    /** Window params for a bar: wrap-content, bottom center, clear of the home-screen dock. */
    android.view.WindowManager.LayoutParams barWindow(int padV) {
        android.view.WindowManager.LayoutParams lp = ShotService.overlayParams(); // WRAP_CONTENT, BOTTOM|CENTER_HORIZONTAL
        lp.y = dp(96 + 12 + 6) - padV;
        return lp;
    }

    /** Makes v the first icon after the text block: a fixed 16dp gap instead of a stretchy spacer. */
    static <V extends View> V afterText(V v, int gapPx) {
        ((LinearLayout.LayoutParams) v.getLayoutParams()).leftMargin = gapPx;
        return v;
    }

    /** Keeps content clear of the status and navigation bars (edge-to-edge on Android 15). */
    static void padForSystemBars(View v, int extraBottom) {
        int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom + extraBottom);
            return insets;
        });
    }
}
