package com.phiasco.liveocr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Size;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.cordova.*;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class LiveOCR extends CordovaPlugin {
    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private CallbackContext callbackContext;
    private boolean running = false;

    @Override
    public boolean execute(String action, CordovaArgs args, final CallbackContext cb) {
        if ("start".equals(action)) {
            callbackContext = cb;
            cordova.getActivity().runOnUiThread(this::startCamera);
            return true;
        } else if ("stop".equals(action)) {
            cordova.getActivity().runOnUiThread(this::stopCamera);
            return true;
        }
        return false;
    }

    private void startCamera() {
        if (running) return;
        running = true;

        if (ActivityCompat.checkSelfPermission(cordova.getContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(cordova.getActivity(),
                    new String[]{Manifest.permission.CAMERA}, 100);
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        previewView = new PreviewView(cordova.getContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cordova.getActivity().addContentView(previewView, params);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(cordova.getContext());
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreviewAndAnalysis();
            } catch (ExecutionException | InterruptedException ignored) {}
        }, ContextCompat.getMainExecutor(cordova.getContext()));
    }

    private void bindPreviewAndAnalysis() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(
                cordova.getActivity(), cameraSelector, preview, analysis);
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            int width = image.getWidth();
            int height = image.getHeight();

            // Simple brightness sampling
            int step = 12;
            StringBuilder sb = new StringBuilder();
            for (int y = height / 3; y < 2 * height / 3; y += step) {
                int dark = 0;
                for (int x = 0; x < width; x += step) {
                    int lum = bytes[y * width + x] & 0xFF;
                    if (lum < 80) dark++;
                }
                if (dark > width / (3 * step)) sb.append("#");
                else sb.append(" ");
            }

            String candidate = sb.toString().replaceAll("[^A-Z0-9\\-/]", "").trim();
            if (Pattern.compile("(?i)[A-Z0-9\\-/]{6,}").matcher(candidate).find()) {
                sendResult(candidate);
                stopCamera();
            }
        } catch (Exception ignored) {
        } finally {
            image.close();
        }
    }

    private void sendResult(String serial) {
        if (callbackContext == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("text", serial);
            PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
            result.setKeepCallback(false);
            callbackContext.sendPluginResult(result);
        } catch (Exception ignored) {}
    }

    private void stopCamera() {
        if (!running) return;
        running = false;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (previewView != null && previewView.getParent() instanceof ViewGroup) {
            ((ViewGroup) previewView.getParent()).removeView(previewView);
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
