package com.sudokuai.assistant;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class BubbleService extends Service {
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (bubble == null) createBubble();
        return START_STICKY;
    }

    private void createBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
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

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx;
                        float dy = e.getRawY() - ty;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        lp.x = sx + (int) dx;
                        lp.y = sy + (int) dy;
                        wm.updateViewLayout(bubble, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            Toast.makeText(BubbleService.this, "Bubble tap!", Toast.LENGTH_SHORT).show();
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
        if (bubble != null) wm.removeView(bubble);
    }
}
