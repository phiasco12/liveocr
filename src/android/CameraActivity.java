package com.example.stablecamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;

public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    private static final int REQUEST_CAMERA = 1001;
    private Camera camera;
    private SurfaceView surfaceView;
    private boolean pictureTaken = false;
    private boolean finishing = false;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        surfaceView = new SurfaceView(this);
        setContentView(surfaceView);
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

            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.startPreview();

            // Delay autofocus and capture slightly
            handler.postDelayed(this::autoFocusAndCapture, 1000);

        } catch (Exception e) {
            e.printStackTrace();
            finishSafe();
        }
    }

    private void autoFocusAndCapture() {
        if (camera == null || pictureTaken) return;
        try {
            camera.autoFocus((success, cam) -> takePicture());
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        byte[] finalBytes = baos.toByteArray();
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
}
