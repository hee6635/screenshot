package com.hee.tempshot;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible activity: lets One Hand Operation+ or a home-screen icon trigger a capture. */
public class CaptureActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (ShotService.instance == null) {
            Toast.makeText(this, "앱에서 접근성 서비스를 먼저 켜 주세요", Toast.LENGTH_LONG).show();
        } else {
            // Wait for this activity and the gesture animation to leave the screen.
            ShotService.instance.captureIn(400);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
