package com.example.stablecamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;

public class CameraActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int REQUEST_CAMERA = 1001;

    // --- adjustable stillness settings ---
    private static final double STABILITY_THRESHOLD = 1.0; // lower = more sensitive to movement
    private static final long STABLE_HOLD_MS = 1500;        // how long to hold still before capture

    private Camera camera;
    private SurfaceView surfaceView;
    private OverlayView overlayView;
    private boolean pictureTaken = false;
    private boolean finishing = false;
    private final Handler handler = new Handler();

    private double lastFrameAvg = -1;
    private long stableSince = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        overlayView = new OverlayView(this);

        root.addView(surfaceView);
        root.addView(overlayView);
        setContentView(root);

        surfaceView.getHolder().addCallback(this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCamera(holder);
    }

    private void startCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();

            // Highest available picture size
            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPictureSizes()) {
                if (best == null || (s.width * s.height > best.width * best.height)) {
                    best = s;
                }
            }
            if (best != null) params.setPictureSize(best.width, best.height);

            // Continuous focus
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            params.setJpegQuality(100);
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();

        } catch (Exception e) {
            e.printStackTrace();
            finishSafe();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (pictureTaken) return;

        double avg = computeBrightness(data);
        if (lastFrameAvg < 0) {
            lastFrameAvg = avg;
            return;
        }

        double diff = Math.abs(avg - lastFrameAvg);
        long now = System.currentTimeMillis();

        if (diff < STABILITY_THRESHOLD) {
            if (stableSince == 0) stableSince = now;
            if (now - stableSince > STABLE_HOLD_MS) {
                takePicture();
            }
        } else {
            stableSince = 0;
        }

        lastFrameAvg = avg;
    }

    private double computeBrightness(byte[] frame) {
        int step = 1000;
        long total = 0;
        int count = 0;
        for (int i = 0; i < frame.length; i += step) {
            total += (frame[i] & 0xFF);
            count++;
        }
        return (double) total / count;
    }

    private void takePicture() {
        if (camera == null || pictureTaken) return;
        pictureTaken = true;
        try {
            camera.takePicture(null, null, (data, cam) -> {
                String base64 = encodeToBase64(data, cam);
                StableCamera.sendResult(base64);
                finishSafe();
            });
        } catch (Exception e) {
            e.printStackTrace();
            finishSafe();
        }
    }

    // --- rotation + crop ---
    private String encodeToBase64(byte[] jpegData, Camera camera) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (bmp == null) return "";

        int rotation = getRotationCompensation(camera);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        }

        // Crop center area (same ratio as overlay)
        int cropWidth = (int) (bmp.getWidth() * 0.6);
        int cropHeight = (int) (bmp.getHeight() * 0.4);
        int left = (bmp.getWidth() - cropWidth) / 2;
        int top = (bmp.getHeight() - cropHeight) / 2;
        Bitmap cropped = Bitmap.createBitmap(bmp, left, top, cropWidth, cropHeight);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        byte[] bytes = baos.toByteArray();

        bmp.recycle();
        cropped.recycle();

        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private int getRotationCompensation(Camera camera) {
        int rotation = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRotation();

        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(Camera.CameraInfo.CAMERA_FACING_BACK, info);

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private synchronized void finishSafe() {
        if (finishing) return;
        finishing = true;
        runOnUiThread(() -> {
            try {
                if (camera != null) {
                    camera.stopPreview();
                    camera.release();
                    camera = null;
                }
            } catch (Exception ignored) {}
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        finishSafe();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}

    // --- Overlay View ---
    private static class OverlayView extends View {
        private final Paint paint;

        public OverlayView(Activity ctx) {
            super(ctx);
            paint = new Paint();
            paint.setColor(Color.parseColor("#80000000"));
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int w = canvas.getWidth();
            int h = canvas.getHeight();

            int rectW = (int) (w * 0.6);
            int rectH = (int) (h * 0.4);
            int left = (w - rectW) / 2;
            int top = (h - rectH) / 2;
            int right = left + rectW;
            int bottom = top + rectH;

            canvas.drawRect(0, 0, w, h, paint);

            Paint clearPaint = new Paint();
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawRect(left, top, right, bottom, clearPaint);

            Paint border = new Paint();
            border.setColor(Color.WHITE);
            border.setStrokeWidth(4);
            border.setStyle(Paint.Style.STROKE);
            canvas.drawRect(left, top, right, bottom, border);
        }
    }
}
