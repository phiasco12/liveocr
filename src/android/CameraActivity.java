package com.example.stablecamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
import java.util.Queue;

public class CameraActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int REQUEST_CAMERA = 1001;
    private Camera camera;
    private SurfaceView surfaceView;
    private Queue<Bitmap> frameQueue = new LinkedList<>();
    private final int FRAME_LIMIT = 15;
    private boolean finishing = false;

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
        // decode frames off the UI thread
        new FrameTask().execute(data);
    }

    private class FrameTask extends AsyncTask<byte[], Void, Bitmap> {
        @Override
        protected Bitmap doInBackground(byte[]... params) {
            return decodeToBitmap(params[0]);
        }

        @Override
        protected void onPostExecute(Bitmap bmp) {
            if (bmp == null || finishing) return;

            if (frameQueue.size() >= FRAME_LIMIT) frameQueue.poll();
            frameQueue.add(bmp);

            if (frameQueue.size() == FRAME_LIMIT && isStable()) {
                Bitmap lastFrame = frameQueue.peek();
                String base64 = encodeToBase64(lastFrame);
                StableCamera.sendResult(base64);
                finishSafe();
            }
        }
    }

    private boolean isStable() {
        Bitmap first = null;
        for (Bitmap bmp : frameQueue) {
            if (first == null) { first = bmp; continue; }
            if (!isSimilar(first, bmp)) return false;
        }
        return true;
    }

    private boolean isSimilar(Bitmap a, Bitmap b) {
        int sample = 100;
        int diff = 0;
        for (int i = 0; i < sample; i++) {
            int x = (int)(Math.random() * a.getWidth());
            int y = (int)(Math.random() * a.getHeight());
            if (x < b.getWidth() && y < b.getHeight()) {
                int colorA = a.getPixel(x, y);
                int colorB = b.getPixel(x, y);
                diff += Math.abs((colorA & 0xFF) - (colorB & 0xFF));
            }
        }
        double avgDiff = diff / (double) sample;
        return avgDiff < 10;
    }

    private Bitmap decodeToBitmap(byte[] data) {
        try {
            if (camera == null) return null;
            Camera.Size size = camera.getParameters().getPreviewSize();
            android.graphics.YuvImage yuv = new android.graphics.YuvImage(data,
                    android.graphics.ImageFormat.NV21, size.width, size.height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, size.width, size.height), 80, out);
            byte[] jpegData = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String encodeToBase64(Bitmap bmp) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
    }

    private synchronized void finishSafe() {
        if (finishing) return;
        finishing = true;

        runOnUiThread(() -> {
            try {
                if (camera != null) {
                    camera.setPreviewCallback(null);
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
