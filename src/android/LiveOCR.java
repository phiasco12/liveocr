package com.serial.liveocr;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiveOCR extends CordovaPlugin implements LifecycleOwner {
    private static final int REQ_CAMERA = 2025;

    private Activity activity;
    private PreviewView previewView;
    private FrameLayout overlay;
    private LifecycleRegistry lifecycleRegistry;
    private ExecutorService executor;

    private ProcessCameraProvider cameraProvider;
    private TextRecognizer recognizer;

    private CallbackContext activeCallback;

    // Stabilization
    private String lastCandidate = "";
    private int stableCount = 0;
    private String jsRegex = "^[A-Za-z0-9\\-]{6,}$"; // sensible default
    private int minStableFrames = 3;
    private float boxPercent = 0.33f; // center 1/3 box

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.activity = cordova.getActivity();
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        this.executor = Executors.newSingleThreadExecutor();
        this.recognizer = TextRecognition.getClient(new TextRecognizerOptions.Builder().build());
    }

    @Override
    public boolean execute(String action, org.json.JSONArray args, CallbackContext callbackContext) {
        if ("start".equals(action)) {
            this.activeCallback = callbackContext;
            readOptions(args);
            cordova.getThreadPool().execute(this::startInternal);
            return true;
        } else if ("stop".equals(action)) {
            cordova.getThreadPool().execute(() -> stopInternal(true));
            callbackContext.success();
            return true;
        }
        return false;
    }

    private void readOptions(org.json.JSONArray args) {
        if (args != null && args.length() > 0) {
            JSONObject o = args.optJSONObject(0);
            if (o != null) {
                String r = o.optString("regex", null);
                if (r != null && !r.isEmpty()) jsRegex = r;
                int msf = o.optInt("minStableFrames", -1);
                if (msf > 0) minStableFrames = msf;
                double bp = o.optDouble("boxPercent", Double.NaN);
                if (!Double.isNaN(bp) && bp > 0.1 && bp <= 1.0) boxPercent = (float) bp;
            }
        }
    }

    private void startInternal() {
        if (!hasCameraPermission()) {
            requestCameraPermission();
            return;
        }
        activity.runOnUiThread(() -> {
            if (overlay == null) {
                overlay = new FrameLayout(activity);
                overlay.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                ));
            }

            if (previewView == null) {
                previewView = new PreviewView(activity);
                previewView.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));
                overlay.addView(previewView);
                ((ViewGroup) webView.getView().getParent()).addView(overlay);
            }

            lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
            lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

            ProcessCameraProvider.getInstance(activity).addListener(() -> {
                try {
                    cameraProvider = ProcessCameraProvider.getInstance(activity).get();
                    bindUseCases();
                } catch (Exception e) {
                    if (activeCallback != null) activeCallback.error(e.getMessage());
                }
            }, ContextCompat.getMainExecutor(activity));
        });
    }

    private void bindUseCases() {
        if (cameraProvider == null) return;

        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(executor, this::analyze);

        cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
        );
    }

    private void analyze(@NonNull ImageProxy imageProxy) {
        try {
            InputImage img = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );
            recognizer.process(img)
                    .addOnSuccessListener(new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text text) {
                            handleText(text, imageProxy.getWidth(), imageProxy.getHeight());
                            imageProxy.close();
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            imageProxy.close();
                        }
                    });
        } catch (Exception e) {
            imageProxy.close();
        }
    }

    private void handleText(Text result, int w, int h) {
        if (result == null) return;

        // center box
        int cx = w / 2;
        int cy = h / 2;
        int bw = (int) (w * boxPercent);
        int bh = (int) (h * boxPercent);

        String bestLine = null;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                android.graphics.Rect r = line.getBoundingBox();
                if (r == null) continue;
                if (Math.abs(r.centerX() - cx) <= bw / 2 && Math.abs(r.centerY() - cy) <= bh / 2) {
                    String candidate = sanitize(line.getText());
                    if (candidate.matches(jsRegex)) {
                        bestLine = candidate;
                        break;
                    }
                }
            }
            if (bestLine != null) break;
        }

        if (bestLine == null) {
            // decay stability if nothing matches
            stableCount = Math.max(0, stableCount - 1);
            return;
        }

        if (!bestLine.equalsIgnoreCase(lastCandidate)) {
            lastCandidate = bestLine;
            stableCount = 1;
        } else {
            stableCount++;
        }

        if (stableCount >= minStableFrames) {
            // return and stop
            if (activeCallback != null) {
                try {
                    JSONObject out = new JSONObject();
                    out.put("text", lastCandidate);
                    out.put("stableFrames", stableCount);
                    activeCallback.success(out);
                } catch (JSONException e) {
                    activeCallback.error(e.getMessage());
                }
            }
            stopInternal(false);
        }
    }

    private String sanitize(String s) {
        if (s == null) return "";
        // trim and collapse spaces; keep hyphens
        s = s.trim().replace('\n', ' ').replace('\r', ' ');
        s = s.replaceAll("\\s+", "");
        return s;
    }

    private void stopInternal(boolean fromStopAction) {
        activity.runOnUiThread(() -> {
            try {
                if (cameraProvider != null) {
                    cameraProvider.unbindAll();
                    cameraProvider = null;
                }
                if (overlay != null) {
                    ViewGroup parent = (ViewGroup) overlay.getParent();
                    if (parent != null) parent.removeView(overlay);
                    overlay.removeAllViews();
                    overlay = null;
                }
                previewView = null;
                lastCandidate = "";
                stableCount = 0;
                lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
                lifecycleRegistry = new LifecycleRegistry(this);
                lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
                if (fromStopAction && activeCallback != null) {
                    // if the JS called stop explicitly and we haven't sent success, send NO_RESULT
                    activeCallback = null;
                }
            } catch (Exception ignored) { }
        });
    }

    // ---- Permissions ----
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        cordova.requestPermission(this, REQ_CAMERA, Manifest.permission.CAMERA);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startInternal();
            } else {
                if (activeCallback != null) activeCallback.error("CAMERA permission denied");
            }
        } else {
            super.onRequestPermissionResult(requestCode, permissions, grantResults);
        }
    }

    // ---- LifecycleOwner ----
    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onReset() {
        stopInternal(true);
        super.onReset();
    }

    @Override
    public void onDestroy() {
        stopInternal(true);
        if (executor != null) executor.shutdown();
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }
}
