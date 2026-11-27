package com.hee.tempshot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** The app icon: captures right away (One Hand Operation+ launches this too). Settings are on long-press. */
public class CaptureActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (ShotService.instance == null) {
            // Can't capture without the service: open settings, where it can be switched on.
            startActivity(new Intent(this, MainActivity.class));
        } else {
            // Wait for this activity and the gesture animation to leave the screen.
            ShotService.instance.captureIn(400);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
