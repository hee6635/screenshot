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
