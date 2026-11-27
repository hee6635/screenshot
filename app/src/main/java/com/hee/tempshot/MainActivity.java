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
    private static final int[] PRESETS = {1, 6, 24, 72, 168, 720}; // hours
    private Ui ui;
    private TextView headerStatus, serviceState;
    private LinearLayout chips;
    private LinearLayout stuckCard;
    private TextView stuckText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            build();
        } catch (RuntimeException e) {
            // Show the error instead of closing, so it can be copied and reported.
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView t = new TextView(this);
            t.setTextIsSelectable(true);
            t.setPadding(40, 160, 40, 40);
            t.setText("설정 화면 오류 (길게 눌러 복사해 보내 주세요)\n\n" + sw);
            ScrollView sv = new ScrollView(this);
            sv.addView(t);
            setContentView(sv);
        }
    }

    private void build() {
        ui = new Ui(this);
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.bg);

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
        col.addView(sectionTitle("자동 정리"));
        LinearLayout period = card();
        period.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        period.addView(ui.text("찍은 뒤 이 기간이 지나면 휴지통으로 가요 (30일 안에 복구 가능)", 14, ui.subText));
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        chips.setPadding(0, ui.dp(12), 0, 0);
        period.addView(chips);
        col.addView(period, cardLp());
        renderChips();

        // Old shots the cleanup couldn't move (they belonged to a previous install of the app)
        stuckCard = card();
        LinearLayout stuckRow = new LinearLayout(this);
        stuckRow.setGravity(Gravity.CENTER_VERTICAL);
        stuckRow.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        stuckRow.setBackground(ui.pressable(ui.card, 24));
        stuckText = ui.text("", 15, ui.text);
        stuckRow.addView(stuckText, new LinearLayout.LayoutParams(0, -2, 1));
        stuckRow.addView(ui.text("정리", 15, Ui.ACCENT));
        stuckRow.setOnClickListener(v -> trashStuck());
        stuckCard.addView(stuckRow);
        stuckCard.setVisibility(View.GONE);
        col.addView(stuckCard, cardLp());

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

        // Last crash, so it can be copied and reported
        String crash = getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).getString(TempShotApp.KEY_CRASH, null);
        if (crash != null) {
            col.addView(sectionTitle("최근 오류"));
            LinearLayout err = card();
            err.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
            err.setBackground(ui.pressable(ui.card, 24));
            err.addView(ui.text("앱이 비정상 종료됐어요. 눌러서 오류 내용을 복사한 뒤 보내 주세요.", 14, Ui.DANGER));
            TextView detail = ui.text(crash.length() > 600 ? crash.substring(0, 600) + "…" : crash, 11, ui.subText);
            detail.setPadding(0, ui.dp(8), 0, 0);
            err.addView(detail);
            err.setOnClickListener(v -> {
                getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("오류", crash));
                getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).edit().remove(TempShotApp.KEY_CRASH).apply();
                Toast.makeText(this, "오류 내용을 복사했어요", Toast.LENGTH_SHORT).show();
            });
            col.addView(err, cardLp());
        }

        // How-to card
        col.addView(sectionTitle("사용법"));
        LinearLayout help = card();
        help.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16));
        String[][] steps = {
                {"아이콘으로 찍기", "'임시 스샷' 아이콘을 누르면 바로 찍혀요. 이 설정 화면은 아이콘을 길게 눌러 '설정'"},
                {"제스처로 찍기", "원핸드 오퍼레이션+ → 제스처 동작 → 앱 실행 → '임시 스샷'"},
                {"타일로 찍기", "빠른 설정 편집에서 '임시 스샷' 타일 추가"},
                {"찍은 뒤", "자동으로 복사돼요. 썸네일을 누르면 공유, 길게 누르면 열기. 아래 툴바에서 스크롤 캡처 · 자르기 · 보관 · 휴지통. 툴바는 바깥을 누르거나 아래로 쓸어서 닫아요"},
                {"스크롤 캡처", "⌄⌄를 누를 때마다 한 번 내려서 이어 붙여요. 누르고 있으면 계속 내려가요. 다 되면 '완료'"},
                {"수동 스크롤", "자동으로 안 내려가는 앱에서는 [수동]: 손으로 내려서 파란 선을 초록 띠 안에 놓으면 찍혀요"},
                {"자르기", "사진이나 카드 같은 영역을 탭하면 범위가 자동으로 맞춰져요"},
                {"앨범", "갤러리의 'TempShots' 앨범에 모여요. 기간이 지나면 휴지통으로 가고, 30일 안에는 휴지통에서 복구할 수 있어요. 보관하면 스크린샷 앨범으로 옮겨져 정리되지 않아요"},
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
        // Only after setContentView: before it the window's insets controller can be null and crash.
        WindowInsetsController bars = getWindow().getDecorView().getWindowInsetsController();
        if (!ui.dark && bars != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(light, light);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        CleanupJob.schedule(this);
        if (stuckCard != null) {
            new Thread(() -> {
                java.util.ArrayList<android.net.Uri> stuck = new java.util.ArrayList<>();
                try { ShotService.cleanup(this, stuck); } catch (RuntimeException ignored) { }
                runOnUiThread(() -> showStuck(stuck.size()));
            }).start();
        }
    }

    private void showStuck(int n) {
        if (stuckCard == null) return;
        stuckCard.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        stuckText.setText("정리 못 한 지난 스샷 " + n + "개");
    }

    /** Asks once to trash old shots that aren't this install's anymore (one system dialog for all). */
    private void trashStuck() {
        new Thread(() -> {
            java.util.ArrayList<android.net.Uri> stuck = new java.util.ArrayList<>();
            try { ShotService.cleanup(this, stuck); } catch (RuntimeException ignored) { }
            if (stuck.isEmpty()) { runOnUiThread(() -> showStuck(0)); return; }
            android.app.PendingIntent pi = android.provider.MediaStore.createTrashRequest(getContentResolver(), stuck, true);
            runOnUiThread(() -> {
                try {
                    startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
                } catch (android.content.IntentSender.SendIntentException e) {
                    Toast.makeText(this, "정리 실패", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static final int REQ_TRASH = 7;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TRASH && res == RESULT_OK) {
            showStuck(0);
            Toast.makeText(this, "휴지통으로 옮겼어요", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshStatus() {
        if (serviceState == null) return; // build() failed; the error screen is showing
        boolean on = ShotService.instance != null;
        serviceState.setText(on ? "켜짐" : "꺼짐");
        serviceState.setTextColor(on ? Ui.ACCENT : Ui.DANGER);
        headerStatus.setText(on ? ShotService.period(this) + " 뒤 휴지통으로 정리 중" : "접근성 서비스를 켜 주세요");
    }

    /** Two rows of four: 1시간 6시간 1일 3일 / 7일 30일 직접. */
    private void renderChips() {
        chips.removeAllViews();
        int cur = ShotService.hours(this);
        boolean custom = true;
        for (int h : PRESETS) if (h == cur) custom = false;
        LinearLayout row = null;
        for (int i = 0; i <= PRESETS.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rl.topMargin = ui.dp(6);
                chips.addView(row, rl);
            }
            if (i < PRESETS.length) {
                int h = PRESETS[i];
                row.addView(chip(ShotService.periodLabel(h), h == cur, v -> setHours(h)), chipLp());
            } else {
                row.addView(chip(custom ? ShotService.periodLabel(cur) : "직접", custom, v -> askCustom()), chipLp());
                row.addView(new View(this), chipLp()); // keeps the last row's chips the same width
            }
        }
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
                    setHours(d * 24);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void setHours(int h) {
        getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).edit().putInt(ShotService.KEY_HOURS, h).apply();
        int n = ShotService.cleanup(this);
        if (n > 0) Toast.makeText(this, "지난 스샷 " + n + "개를 휴지통으로 옮겼어요", Toast.LENGTH_SHORT).show();
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
