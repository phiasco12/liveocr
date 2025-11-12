package com.example.stablecamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;

public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    private static final int REQUEST_CAMERA = 1001;
    private Camera camera;
    private SurfaceView surfaceView;
    private OverlayView overlayView;
    private boolean pictureTaken = false;
    private boolean finishing = false;
    private final Handler handler = new Handler();

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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera(surfaceView.getHolder());
            } else {
                finishSafe();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCamera(holder);
    }

    private void startCamera(SurfaceHolder holder) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;

        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();

            // Highest supported picture size
            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPictureSizes()) {
                if (best == null || (s.width * s.height > best.width * best.height)) {
                    best = s;
                }
            }
            if (best != null) params.setPictureSize(best.width, best.height);

            // Continuous focus for sharper captures
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            params.setJpegQuality(100);
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.startPreview();

            // Wait for focus and exposure to settle
            handler.postDelayed(this::captureAfterFocus, 2500);

        } catch (Exception e) {
            e.printStackTrace();
            finishSafe();
        }
    }

    private void captureAfterFocus() {
        if (camera == null || pictureTaken) return;
        try {
            camera.autoFocus((success, cam) -> handler.postDelayed(this::takePicture, 400));
        } catch (Exception e) {
            takePicture();
        }
    }

    private void takePicture() {
        if (camera == null || pictureTaken) return;
        pictureTaken = true;
        try {
            camera.takePicture(null, null, (data, cam) -> {
                String base64 = encodeToBase64(data);
                StableCamera.sendResult(base64);
                finishSafe();
            });
        } catch (Exception e) {
            e.printStackTrace();
            finishSafe();
        }
    }

    private String encodeToBase64(byte[] jpegData) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (bmp == null) return "";

        // Crop center area (same ratio as overlay)
        int cropWidth = (int) (bmp.getWidth() * 0.6);
        int cropHeight = (int) (bmp.getHeight() * 0.4);
        int left = (bmp.getWidth() - cropWidth) / 2;
        int top = (bmp.getHeight() - cropHeight) / 2;

        Bitmap cropped = Bitmap.createBitmap(bmp, left, top, cropWidth, cropHeight);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        byte[] finalBytes = baos.toByteArray();

        bmp.recycle();
        cropped.recycle();

        return Base64.encodeToString(finalBytes, Base64.NO_WRAP);
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
            paint.setColor(Color.parseColor("#80000000")); // 50% black
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int w = canvas.getWidth();
            int h = canvas.getHeight();

            // Transparent center rectangle (60%x40%)
            int rectW = (int) (w * 0.6);
            int rectH = (int) (h * 0.4);
            int left = (w - rectW) / 2;
            int top = (h - rectH) / 2;
            int right = left + rectW;
            int bottom = top + rectH;

            // Darken the whole screen
            canvas.drawRect(0, 0, w, h, paint);

            // Clear center rectangle
            Paint clearPaint = new Paint();
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            canvas.drawRect(left, top, right, bottom, clearPaint);

            // Draw white border around transparent area
            Paint border = new Paint();
            border.setColor(Color.WHITE);
            border.setStrokeWidth(4);
            border.setStyle(Paint.Style.STROKE);
            canvas.drawRect(left, top, right, bottom, border);
        }
    }
}
