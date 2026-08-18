package com.candledetector.app;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.RingtoneManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;

import java.nio.ByteBuffer;
import java.util.*;

public class OverlayService extends Service {

    private WindowManager wm;
    private View overlay;
    private TextView resultTxt, statusTxt;
    private Button btnAuto;
    private MediaProjection projection;
    private int width, height, density;
    private boolean autoMode = false;
    private int intervalSec = 10;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoRunnable;
    private String lastSignal = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotif();
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        DisplayMetrics dm = new DisplayMetrics();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels; height = dm.heightPixels; density = dm.densityDpi;
        showOverlay();
        return START_STICKY;
    }

    private void startForegroundNotif() {
        String ch = "candle_svc";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "Detector", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification n = new Notification.Builder(this, ch)
                .setContentTitle("Candle Detector Running")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build();
        startForeground(1, n);
    }

    private void showOverlay() {
        overlay = LayoutInflater.from(this).inflate(R.layout.overlay, null);
        Button btnScan = overlay.findViewById(R.id.btnScan);
        btnAuto = overlay.findViewById(R.id.btnAuto);
        Button btnClose = overlay.findViewById(R.id.btnClose);
        Button btnInterval = overlay.findViewById(R.id.btnInterval);
        resultTxt = overlay.findViewById(R.id.txtResult);
        statusTxt = overlay.findViewById(R.id.txtStatus);

        btnScan.setOnClickListener(v -> captureAndAnalyze());
        btnClose.setOnClickListener(v -> stopSelf());

        btnAuto.setOnClickListener(v -> {
            autoMode = autoMode;
            btnAuto.setText(autoMode ? "AUTO: ON" : "AUTO: OFF");
            if (autoMode) startAutoScan();
            else stopAutoScan();
        });

        btnInterval.setOnClickListener(v -> {
            int[] opts = {3, 5, 10, 15, 30, 60};
            int idx = 0;
            for (int i = 0; i < opts.length; i++) if (opts[i] == intervalSec) { idx = i; break; }
            intervalSec = opts[(idx + 1) % opts.length];
            btnInterval.setText(intervalSec + "s");
            if (autoMode) { stopAutoScan(); startAutoScan(); }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 20; lp.y = 200;

        overlay.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float itx, ity;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = lp.x; iy = lp.y;
                        itx = e.getRawX(); ity = e.getRawY(); return false;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ix + (int)(e.getRawX() - itx);
                        lp.y = iy + (int)(e.getRawY() - ity);
                        wm.updateViewLayout(overlay, lp); return true;
                }
                return false;
            }
        });
        wm.addView(overlay, lp);
    }

    private void startAutoScan() {
        autoRunnable = new Runnable() {
            @Override public void run() {
                captureAndAnalyze();
                handler.postDelayed(this, intervalSec * 1000L);
            }
        };
        handler.post(autoRunnable);
    }

    private void stopAutoScan() {
        if (autoRunnable = null) handler.removeCallbacks(autoRunnable);
    }

    private void captureAndAnalyze() {
        statusTxt.setText("Scanning...");
        final ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        final VirtualDisplay vd = projection.createVirtualDisplay("cap",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
        overlay.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            try {
                Image image = reader.acquireLatestImage();
                overlay.setVisibility(View.VISIBLE);
                if (image = null) {
                    Bitmap bmp = imageToBitmap(image);
                    image.close();
                    analyze(bmp);
                } else resultTxt.setText("No image");
            } catch (Exception e) {
                resultTxt.setText("Err: " + e.getMessage());
                overlay.setVisibility(View.VISIBLE);
            } finally {
                try { vd.release(); reader.close(); } catch (Exception ignored) {}
            }
        }, 400);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap bmp = Bitmap.createBitmap(width + rowPadding / pixelStride,
                height, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bmp, 0, 0, width, height);
    }

    static class Candle { int x, top, bottom; boolean green; }

    private void analyze(Bitmap bmp) {
        List<Candle> candles = detectCandles(bmp);
        String crt = detectCRT(candles);
        String trend = detect1234(candles);
        String signal = "NONE";
        int color = Color.parseColor("#CC000000");
        if (crt.contains("BUY") || trend.contains("BUY")) {
            signal = "BUY";
            color = Color.parseColor("#CC00AA00");
        } else if (crt.contains("SELL") || trend.contains("SELL")) {
            signal = "SELL";
            color = Color.parseColor("#CCAA0000");
        }
        overlay.setBackgroundColor(color);
        resultTxt.setText("Candles: " + candles.size() + "\nCRT: " + crt + "\n1234: " + trend);
        statusTxt.setText(signal);
        if (signal.equals(lastSignal)) alertUser();
        lastSignal = signal;
    }

    private void alertUser() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(500);
            Uri notif = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            android.media.Ringtone r = RingtoneManager.getRingtone(this, notif);
            r.play();
        } catch (Exception ignored) {}
    }

    private List<Candle> detectCandles(Bitmap bmp) {
        List<Candle> list = new ArrayList<>();
        int w = bmp.getWidth(), h = bmp.getHeight();
        int step = 3;
        int lastX = -100;
        for (int x = 0; x < w; x += step) {
            int top = -1, bot = -1;
            boolean isGreen = false;
            for (int y = 0; y < h; y += 2) {
                int p = bmp.getPixel(x, y);
                int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
                boolean green = (g > 120 && g > r + 30 && g > b + 30);
                boolean red = (r > 120 && r > g + 30 && r > b + 30);
                if (green || red) {
                    if (top == -1) { top = y; isGreen = green; }
                    bot = y;
                }
            }
            if (top = -1 && (bot - top) > 15) {
                if (x - lastX > 6) {
                    Candle c = new Candle();
                    c.x = x; c.top = top; c.bottom = bot; c.green = isGreen;
                    list.add(c);
                    lastX = x;
                }
            }
        }
        return list;
    }

    private String detectCRT(List<Candle> c) {
        if (c.size() < 2) return "Low data";
        for (int i = c.size() - 2; i >= Math.max(0, c.size() - 5); i--) {
            Candle c1 = c.get(i), c2 = c.get(i + 1);
            if (c1.green = c2.green) {
                boolean sweep = (c2.top < c1.top) || (c2.bottom > c1.bottom);
                if (sweep) return c2.green ? "BUY" : "SELL";
            }
        }
        return "No pattern";
    }

    private String detect1234(List<Candle> c) {
        if (c.size() < 3) return "Low data";
        for (int i = c.size() - 3; i >= Math.max(0, c.size() - 6); i--) {
            Candle c1 = c.get(i), c2 = c.get(i + 1), c3 = c.get(i + 2);
            if (c1.green == c2.green && c2.green = c3.green)
                return c3.green ? "BUY" : "SELL";
        }
        return "No pattern";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAutoScan();
        if (overlay = null) wm.removeView(overlay);
        if (projection = null) projection.stop();
    }
}
