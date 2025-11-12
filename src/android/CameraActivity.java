package com.example.stablecamera;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.hardware.Camera;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
import java.util.Queue;

public class CameraActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private Camera camera;
    private SurfaceView surfaceView;
    private Queue<Bitmap> frameQueue = new LinkedList<>();
    private final int FRAME_LIMIT = 15;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        surfaceView = new SurfaceView(this);
        setContentView(surfaceView);
        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        camera = Camera.open();
        try {
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Camera.Size size = camera.getParameters().getPreviewSize();
        Bitmap bmp = decodeToBitmap(data);
        if (bmp == null) return;

        if (frameQueue.size() >= FRAME_LIMIT) frameQueue.poll();
        frameQueue.add(bmp);

        if (frameQueue.size() == FRAME_LIMIT && isStable()) {
            Bitmap lastFrame = frameQueue.peek();
            String base64 = encodeToBase64(lastFrame);
            StableCamera.sendResult(base64);
            finish();
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
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return null;
        }
    }

    private String encodeToBase64(Bitmap bmp) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}
}
