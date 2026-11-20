package com.hee.tempshot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText("임시 스샷 자동 삭제 기간 (일)");
        label.setTextSize(18);

        EditText days = new EditText(this);
        days.setInputType(InputType.TYPE_CLASS_NUMBER);
        days.setText(String.valueOf(ShotService.days(this)));
        days.setGravity(Gravity.CENTER);

        Button save = new Button(this);
        save.setText("저장");
        save.setOnClickListener(v -> {
            int d;
            try { d = Integer.parseInt(days.getText().toString().trim()); } catch (NumberFormatException e) { d = 0; }
            if (d < 1 || d > 3650) {
                Toast.makeText(this, "1~3650 사이로 입력해 주세요", Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).edit().putInt(ShotService.KEY_DAYS, d).apply();
            int n = ShotService.cleanup(this);
            Toast.makeText(this, d + "일로 저장" + (n > 0 ? ", " + n + "개 삭제" : ""), Toast.LENGTH_SHORT).show();
        });

        Button a11y = new Button(this);
        a11y.setText("접근성 설정 열기");
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        TextView help = new TextView(this);
        help.setPadding(0, pad, 0, 0);
        help.setText("사용법\n"
                + "1. [접근성 설정 열기] → 설치된 앱 → '임시 스샷 캡처' 켜기\n"
                + "2. 캡처하는 방법\n"
                + "   · 화면에 뜨는 접근성 버튼 누르기\n"
                + "   · 빠른 설정 편집에서 '임시 스샷' 타일 추가 후 누르기\n"
                + "   · 원핸드 오퍼레이션+ 제스처 → 앱 실행 → '스샷 찍기'\n"
                + "3. 찍으면 아래에 툴바가 떠요: 자르기 / 보관(갤러리 스크린샷 폴더로 이동, 자동 삭제 안 됨) / 삭제\n"
                + "4. 사진은 Pictures/TempShots에 저장되고, 설정한 기간이 지나면 자동으로 삭제돼요.\n\n"
                + "남길 스샷은 갤럭시 기본 캡처로 찍으세요. 기본 캡처는 이 앱이 건드리지 않아요.");

        status = new TextView(this);
        status.setPadding(0, pad, 0, 0);

        root.addView(label);
        root.addView(days);
        root.addView(save);
        root.addView(a11y);
        root.addView(status);
        root.addView(help);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(ShotService.instance != null ? "✅ 서비스 켜짐" : "⚠️ 서비스 꺼짐 — 접근성 설정에서 켜 주세요");
    }
}
