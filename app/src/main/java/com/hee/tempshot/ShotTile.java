package com.hee.tempshot;

import android.service.quicksettings.TileService;
import android.widget.Toast;

public class ShotTile extends TileService {
    @Override
    public void onClick() {
        if (ShotService.instance == null) {
            Toast.makeText(this, "앱에서 접근성 서비스를 먼저 켜 주세요", Toast.LENGTH_LONG).show();
            return;
        }
        ShotService.instance.captureAfterClosingShade();
    }
}
