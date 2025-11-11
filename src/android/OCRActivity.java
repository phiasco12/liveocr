package com.serial.liveocr;

import android.graphics.Rect;
import android.os.Bundle;
import android.util.Size;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import android.content.Intent;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OCRActivity extends AppCompatActivity {
    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private com.google.mlkit.vision.text.TextRecognizer recognizer;

    // Simple stabilizer
    private String last = "";
    private int stableCount = 0;
    private String regex = "^[A-Za-z0-9\\-]{6,}$";
    private int minStable = 3;
    private float boxPercent = 0.33f; // center box

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(previewView);

        try {
            String opt = getIntent().getStringExtra("options");
            if (opt != null) {
                JSONObject o = new JSONObject(opt);
                if (o.has("regex")) regex = o.getString("regex");
                if (o.has("minStableFrames")) minStable = o.getInt("minStableFrames");
                if (o.has("boxPercent")) boxPercent = (float) o.getDouble("boxPercent");
            }
        } catch (Exception ignored) {}

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraExecutor = Executors.newSingleThreadExecutor();
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(1280, 720))
                        .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    @OptIn(markerClass = ExperimentalGetImage.class)
                    ImageProxy proxy = imageProxy;
                    if (proxy.getImage() == null) {
                        proxy.close();
                        return;
                    }
                    InputImage image = InputImage.fromMediaImage(
                            proxy.getImage(), proxy.getImageInfo().getRotationDegrees());

                    recognizer.process(image)
                            .addOnSuccessListener(result -> {
                                String best = pickFromCenter(result);
                                handleCandidate(best);
                                proxy.close();
                            })
                            .addOnFailureListener(e -> proxy.close());
                });

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);
            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private String pickFromCenter(Text result) {
        if (result == null) return "";
        int w = previewView.getWidth();
        int h = previewView.getHeight();
        if (w == 0 || h == 0) return "";

        int bw = (int)(w * boxPercent);
        int bh = (int)(h * boxPercent);
        Rect box = new Rect((w - bw)/2, (h - bh)/2, (w + bw)/2, (h + bh)/2);

        String best = "";
        for (Text.TextBlock b : result.getTextBlocks()) {
            for (Text.Line line : b.getLines()) {
                Rect r = line.getBoundingBox();
                if (r != null && Rect.intersects(r, box)) {
                    String t = line.getText().trim();
                    if (t.length() > best.length()) best = t;
                }
            }
        }
        return best.replaceAll("\\s", "");
    }

    private void handleCandidate(String candidate) {
        if (candidate == null) candidate = "";
        if (!candidate.matches(regex)) {
            last = "";
            stableCount = 0;
            return;
        }
        if (candidate.equals(last)) {
            stableCount++;
        } else {
            last = candidate;
            stableCount = 1;
        }
        if (stableCount >= minStable) {
            Intent data = new Intent();
            data.putExtra("text", candidate);
            data.putExtra("stable", stableCount);
            setResult(RESULT_OK, data);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}

