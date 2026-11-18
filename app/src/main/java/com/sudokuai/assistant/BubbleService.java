package com.sudokuai.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BubbleService extends Service {
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private Bitmap lastBmp;
    private View overlay;
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- capture + OCR ----------
    private void capture() {
        if (overlay != null) {
            wm.removeView(overlay);
            overlay = null;
            return;
        }
        Image img = reader.acquireLatestImage();
        if (img != null) {
            Image.Plane p = img.getPlanes()[0];
            int rowPad = p.getRowStride() / p.getPixelStride();
            Bitmap b = Bitmap.createBitmap(rowPad, sh, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(p.getBuffer());
            img.close();
            lastBmp = Bitmap.createBitmap(b, 0, 0, sw, sh);
        }
        if (lastBmp == null) {
            toast("Screenshot nahi mila, dobara tap karo");
            return;
        }
        toast("Padh raha hu...");
        InputImage in = InputImage.fromBitmap(lastBmp, 0);
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(in)
                .addOnSuccessListener(t -> handle(t))
                .addOnFailureListener(ex -> toast("OCR fail hua"));
    }

    private float gapMin(List<float[]> f, int axis, float thr) {
        float[] v = new float[f.size()];
        for (int i = 0; i < v.length; i++) v[i] = f.get(i)[axis];
        Arrays.sort(v);
        float best = 1e9f;
        for (int i = 1; i < v.length; i++) {
            float g = v[i] - v[i - 1];
            if (g > thr && g < best) best = g;
        }
        return best;
    }

    private void handle(Text t) {
        List<float[]> d = new ArrayList<>();
        for (Text.TextBlock b : t.getTextBlocks())
            for (Text.Line l : b.getLines())
                for (Text.Element el : l.getElements()) {
                    String s = el.getText().trim();
                    Rect r = el.getBoundingBox();
                    if (r == null || s.length() != 1) continue;
                    char ch = s.charAt(0);
                    if (ch < '1' || ch > '9') continue;
                    d.add(new float[]{r.centerX(), r.centerY(), r.height(), ch - '0'});
                }
        if (d.size() < 5) { toast("Grid nahi mila. Game screen par tap karo"); return; }

        float[] hs = new float[d.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = d.get(i)[2];
        Arrays.sort(hs);
        float med = hs[hs.length / 2];
        List<float[]> f = new ArrayList<>();
        for (float[] e : d) if (e[2] > 0.7f * med && e[2] < 1.4f * med) f.add(e);
        if (f.size() < 5) { toast("Grid nahi mila"); return; }

        float thr = 0.6f * med;
        float s0 = Math.min(gapMin(f, 0, thr), gapMin(f, 1, thr));
        if (s0 > 1e8f) { toast("Grid nahi mila"); return; }

        float minX = 1e9f, minY = 1e9f, maxX = -1f, maxY = -1f;
        for (float[] e : f) {
            minX = Math.min(minX, e[0]); maxX = Math.max(maxX, e[0]);
            minY = Math.min(minY, e[1]); maxY = Math.max(maxY, e[1]);
        }
        int kx = Math.round((maxX - minX) / s0);
        int ky = Math.round((maxY - minY) / s0);
        float sx = kx > 0 ? (maxX - minX) / kx : s0;
        float sy = ky > 0 ? (maxY - minY) / ky : s0;
        float s = (sx + sy) / 2f;

        int[][] g = new int[9][9];
        float x0 = 0, y0 = 0;
        int n = 0;
        for (float[] e : f) {
            int c = Math.round((e[0] - minX) / s);
            int r = Math.round((e[1] - minY) / s);
            if (c > 8 || r > 8) { toast("Grid galat detect hua"); return; }
            g[r][c] = (int) e[3];
            x0 += e[0] - c * s;
            y0 += e[1] - r * s;
            n++;
        }
        x0 /= n;
        y0 /= n;

        if (!valid(g)) { toast("Numbers galat padhe gaye, dobara try karo"); return; }
        int[][] sol = new int[9][9];
        for (int i = 0; i < 9; i++) sol[i] = g[i].clone();
        if (!solve(sol)) { toast("Solve nahi hua, dobara try karo"); return; }

        showOverlay(g, sol, x0, y0, s);
    }

    // ---------- solver ----------
    private int used(int[][] g, int r, int c) {
        int m = 0;
        for (int i = 0; i < 9; i++) {
            m |= 1 << g[r][i];
            m |= 1 << g[i][c];
        }
        int br = r / 3 * 3, bc = c / 3 * 3;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) m |= 1 << g[br + i][bc + j];
        return m;
    }

    private boolean valid(int[][] g) {
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                int v = g[r][c];
                if (v == 0) continue;
                g[r][c] = 0;
                boolean bad = (used(g, r, c) & (1 << v)) != 0;
                g[r][c] = v;
                if (bad) return false;
            }
        return true;
    }

    private boolean solve(int[][] g) {
        int br = -1, bc = -1, bestCnt = 10, bestCand = 0;
        for (int r = 0; r < 9; r++)
            for (int c = 0; c < 9; c++) {
                if (g[r][c] != 0) continue;
                int cand = ~used(g, r, c) & 0x3FE;
                int cnt = Integer.bitCount(cand);
                if (cnt == 0) return false;
                if (cnt < bestCnt) { bestCnt = cnt; br = r; bc = c; bestCand = cand; }
            }
        if (br == -1) return true;
        for (int v = 1; v <= 9; v++) {
            if ((bestCand & (1 << v)) == 0) continue;
            g[br][bc] = v;
            if (solve(g)) return true;
        }
        g[br][bc] = 0;
        return false;
    }

    // ---------- overlay ----------
    private void showOverlay(final int[][] orig, final int[][] sol,
                             final float x0, final float y0, final float s) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF00C853);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(s * 0.55f);

        overlay = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                canvas.translate(-loc[0], -loc[1]);
                for (int r = 0; r < 9; r++)
                    for (int c = 0; c < 9; c++) {
                        if (orig[r][c] != 0) continue;
                        float cx = x0 + c * s;
                        float cy = y0 + r * s + p.getTextSize() * 0.35f;
                        canvas.drawText(String.valueOf(sol[r][c]), cx, cy, p);
                    }
            }
        };
        WindowManager.LayoutParams olp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        olp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        wm.addView(overlay, olp);
        toast("Answers hide karne ke liye bubble tap karo");
    }

    // ---------- bubble ----------
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

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;
            long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
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
        if (overlay != null) { wm.removeView(overlay); overlay = null; }
        if (bubble != null) { wm.removeView(bubble); bubble = null; }
        if (vd != null) vd.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
    }
            }
