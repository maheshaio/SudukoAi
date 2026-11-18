package com.sudokuai.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class BubbleService extends Service {
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private Bitmap lastBmp;
    private int sw, sh;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || bubble != null) return START_NOT_STICKY;

        NotificationChannel ch = new NotificationChannel(
                "sudoku", "Sudoku AI", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "sudoku")
                .setContentTitle("Sudoku AI chal raha hai")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager m =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(code, data);
        projection.registerCallback(new MediaProjection.Callback() {}, null);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        vd = projection.createVirtualDisplay("cap", sw, sh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        createBubble();
        return START_NOT_STICKY;
    }

    private void capture() {
        Image img = reader.acquireLatestImage();
        if (img != null) {
            Image.Plane p = img.getPlanes()[0];
            int rowPad = p.getRowStride() / p.getPixelStride();
            Bitmap b = Bitmap.createBitmap(rowPad, sh, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(p.getBuffer());
            img.close();
            lastBmp = b;
        }
        if (lastBmp != null) {
            Toast.makeText(this, "Screenshot mila: " + sw + "x" + sh,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Screenshot nahi mila, dobara tap karo",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void createBubble() {
        bubble = new TextView(this);
        bubble.setText("S");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(22);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF6200EE);
        bubble.setBackground(bg);

        lp = new WindowManager.LayoutParams(
                150, 150,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 50;
        lp.y = 300;

        bubble.setOnTouchListener(new android.view.View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;
            long downTime;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx;
                        float dy = e.getRawY() - ty;
                        if (Math.abs(dx) > 40 || Math.abs(dy) > 40) moved = true;
                        if (moved) {
                            lp.x = sx + (int) dx;
                            lp.y = sy + (int) dy;
                            wm.updateViewLayout(bubble, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            long held = System.currentTimeMillis() - downTime;
                            if (held > 1000) stopSelf();
                            else capture();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, lp);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bubble != null) { wm.removeView(bubble); bubble = null; }
        if (vd != null) vd.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
    }
}
