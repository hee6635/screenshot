package com.hee.tempshot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int[] PRESETS = {1, 3, 7, 30};
    private Ui ui;
    private TextView headerStatus, serviceState;
    private LinearLayout chips;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.bg);
        if (!ui.dark) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light, light);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24));

        // One UI-style large header that takes the top of the screen.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.addView(ui.text("임시 스샷", 34, ui.text));
        headerStatus = ui.text("", 14, ui.subText);
        headerStatus.setPadding(0, ui.dp(8), 0, 0);
        header.addView(headerStatus);
        col.addView(header, new LinearLayout.LayoutParams(-1,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.28f)));

        // Service card
        LinearLayout service = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        row.setBackground(ui.pressable(ui.card, 24));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(ui.text("접근성 서비스", 17, ui.text));
        TextView hint = ui.text("눌러서 '설치된 앱 → 임시 스샷 캡처' 켜기", 13, ui.subText);
        texts.addView(hint);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        serviceState = ui.text("", 15, Ui.ACCENT);
        row.addView(serviceState);
        row.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        service.addView(row);
        col.addView(service, cardLp());

        // Delete period card
        col.addView(sectionTitle("자동 삭제"));
        LinearLayout period = card();
        period.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        period.addView(ui.text("찍은 뒤 이 기간이 지나면 지워져요", 14, ui.subText));
        chips = new LinearLayout(this);
        chips.setPadding(0, ui.dp(12), 0, 0);
        period.addView(chips);
        col.addView(period, cardLp());
        renderChips();

        // Capture options card
        col.addView(sectionTitle("캡처"));
        LinearLayout opts = card();
        LinearLayout trimRow = new LinearLayout(this);
        trimRow.setGravity(Gravity.CENTER_VERTICAL);
        trimRow.setPadding(ui.dp(20), ui.dp(16), ui.dp(16), ui.dp(16));
        LinearLayout trimTexts = new LinearLayout(this);
        trimTexts.setOrientation(LinearLayout.VERTICAL);
        trimTexts.addView(ui.text("상태바·하단바 숨기기", 17, ui.text));
        trimTexts.addView(ui.text("시계·배터리 줄과 하단 내비게이션 바를 빼고 저장해요 (스크롤 캡처 포함)", 13, ui.subText));
        trimRow.addView(trimTexts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch trim = new Switch(this);
        trim.setChecked(ShotService.trimBars(this));
        trim.setOnCheckedChangeListener((v, on) -> getSharedPreferences(ShotService.PREFS, MODE_PRIVATE)
                .edit().putBoolean(ShotService.KEY_TRIM, on).apply());
        trimRow.addView(trim);
        trimRow.setBackground(ui.pressable(ui.card, 24));
        trimRow.setOnClickListener(v -> trim.toggle());
        opts.addView(trimRow);
        col.addView(opts, cardLp());

        // How-to card
        col.addView(sectionTitle("사용법"));
        LinearLayout help = card();
        help.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        String[][] steps = {
                {"제스처로 찍기", "원핸드 오퍼레이션+ → 제스처 동작 → 앱 실행 → '스샷 찍기'"},
                {"타일로 찍기", "빠른 설정 편집에서 '임시 스샷' 타일 추가"},
                {"찍은 뒤", "자동으로 복사돼요. 아래 툴바에서 스크롤 캡처 · 자르기 · 보관 · 삭제. 툴바는 아래로 쓸어서 닫아요"},
                {"스크롤 캡처", "⌄⌄를 누를 때마다 한 번 내려서 이어 붙여요. 누르고 있으면 계속 내려가요. 다 되면 '완료'"},
                {"자르기", "사진이나 카드 같은 영역을 탭하면 범위가 자동으로 맞춰져요"},
                {"앨범", "갤러리의 'TempShots' 앨범에 모여요. 보관하면 스크린샷 앨범으로 옮겨져 지워지지 않아요"},
                {"남길 스샷", "갤럭시 기본 캡처로 찍은 건 이 앱이 건드리지 않아요"},
        };
        for (int i = 0; i < steps.length; i++) {
            TextView t = ui.text(steps[i][0], 15, ui.text);
            if (i > 0) t.setPadding(0, ui.dp(14), 0, 0);
            help.addView(t);
            help.addView(ui.text(steps[i][1], 13, ui.subText));
        }
        col.addView(help, cardLp());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.addView(col);
        Ui.padForSystemBars(scroll, 0);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean on = ShotService.instance != null;
        serviceState.setText(on ? "켜짐" : "꺼짐");
        serviceState.setTextColor(on ? Ui.ACCENT : Ui.DANGER);
        headerStatus.setText(on ? ShotService.days(this) + "일 뒤 자동 삭제 중" : "접근성 서비스를 켜 주세요");
    }

    private void renderChips() {
        chips.removeAllViews();
        int cur = ShotService.days(this);
        boolean custom = true;
        for (int d : PRESETS) {
            chips.addView(chip(d + "일", d == cur, v -> setDays(d)), chipLp());
            if (d == cur) custom = false;
        }
        chips.addView(chip(custom ? cur + "일" : "직접", custom, v -> askCustom()), chipLp());
    }

    private void askCustom() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1~3650");
        input.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("며칠 뒤에 지울까요?")
                .setView(input)
                .setPositiveButton("확인", (dlg, w) -> {
                    int d;
                    try { d = Integer.parseInt(input.getText().toString().trim()); } catch (NumberFormatException e) { d = 0; }
                    if (d < 1 || d > 3650) {
                        Toast.makeText(this, "1~3650 사이로 입력해 주세요", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    setDays(d);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void setDays(int d) {
        getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).edit().putInt(ShotService.KEY_DAYS, d).apply();
        int n = ShotService.cleanup(this);
        if (n > 0) Toast.makeText(this, "지난 스샷 " + n + "개를 지웠어요", Toast.LENGTH_SHORT).show();
        renderChips();
        refreshStatus();
    }

    private TextView chip(String label, boolean selected, View.OnClickListener click) {
        TextView t = ui.text(label, 14, selected ? 0xFFFFFFFF : ui.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(10), 0, ui.dp(10));
        t.setBackground(ui.pressable(selected ? Ui.ACCENT : ui.chip, 20));
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(ui.dp(3), 0, ui.dp(3), 0);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ui.round(ui.card, 24));
        c.setClipToOutline(true);
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.dp(8);
        return lp;
    }

    private TextView sectionTitle(String s) {
        TextView t = ui.text(s, 13, ui.subText);
        t.setPadding(ui.dp(20), ui.dp(16), 0, ui.dp(8));
        return t;
    }
}
