package com.candledetector.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private MediaProjectionManager mpm;
    private static final int REQ_MP = 100;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        Button start = findViewById(R.id.btnStart);
        start.setOnClickListener(v -> {
            // Request overlay permission if NOT already granted
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }

            mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MP);
            } else {
                Toast.makeText(this, "MediaProjection not available", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_MP && res == Activity.RESULT_OK) {
            Intent svc = new Intent(this, OverlayService.class);
            svc.putExtra("code", res);
            svc.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            finish();
        }
    }
}
