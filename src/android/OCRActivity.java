package com.serial.liveocr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Size;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class OCRActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private Pattern accept;
    private int minStable, stableCount = 0;
    private String lastAccepted = "";
    private float boxPct;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else finish(); // no permission
            });

    @Override
    protected void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);
        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(previewView);

        String regex = getIntent().getStringExtra("regex");
        if (regex == null || regex.isEmpty()) regex = "^[A-Za-z0-9\\-]{6,}$";
        accept = Pattern.compile(regex);
        minStable = getIntent().getIntExtra("minStable", 3);
        boxPct = getIntent().getFloatExtra("boxPct", 0.33f);

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this)
                .addListener(() -> {
                    try {
                        ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                        provider.unbindAll();

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());

                        ImageAnalysis analysis = new ImageAnalysis.Builder()
                                .setTargetResolution(new Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                        analysis.setAnalyzer(cameraExecutor, this::analyze);

                        provider.bindToLifecycle(this,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis);
                    } catch (Exception e) {
                        finish();
                    }
                }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(@NonNull ImageProxy proxy) {
        try {
            ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
            if (planes == null || proxy.getImage() == null) {
                proxy.close(); return;
            }
            InputImage img = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());

            Task<Text> task = TextRecognition.getClient().process(img);
            task.addOnSuccessListener(text -> {
                // center crop box
                int w = proxy.getWidth(), h = proxy.getHeight();
                int boxW = Math.round(w * boxPct), boxH = Math.round(h * boxPct);
                Rect box = new Rect((w - boxW)/2, (h - boxH)/2, (w + boxW)/2, (h + boxH)/2);

                String best = null;
                for (Text.TextBlock b : text.getTextBlocks()) {
                    Rect r = b.getBoundingBox();
                    if (r != null && box.contains(r)) {
                        String cand = b.getText().replaceAll("\\s", "");
                        if (accept.matcher(cand).matches()) { best = cand; break; }
                    }
                }

                if (best != null) {
                    if (best.equals(lastAccepted)) {
                        stableCount++;
                    } else {
                        lastAccepted = best;
                        stableCount = 1;
                    }
                    if (stableCount >= minStable) {
                        Intent out = new Intent();
                        out.putExtra("text", best);
                        out.putExtra("stable", stableCount);
                        setResult(RESULT_OK, out);
                        finish();
                    }
                }
                proxy.close();
            }).addOnFailureListener(e -> {
                proxy.close();
            });
        } catch (Exception e) {
            proxy.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
