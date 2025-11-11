package com.serial.liveocr;

import android.view.TextureView;
import android.widget.FrameLayout;
import android.app.Activity;

import org.apache.cordova.*;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.common.InputImage;

import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

public class LiveOCR extends CordovaPlugin {

    private PreviewView previewView;
    private TextRecognizer recognizer;

    @Override
    public boolean execute(String action, CallbackContext callbackContext) {
        if (action.equals("start")) {
            startCamera(callbackContext);
            return true;
        }
        if (action.equals("stop")) {
            stopCamera();
            return true;
        }
        return false;
    }

    private void startCamera(CallbackContext callbackContext) {
        Activity activity = cordova.getActivity();
        previewView = new PreviewView(activity);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        FrameLayout layout = (FrameLayout) activity.findViewById(android.R.id.content);
        layout.addView(previewView);

        ProcessCameraProvider.getInstance(activity).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(activity).get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(ContextCompat.getMainExecutor(activity), image -> {
                    InputImage img = InputImage.fromMediaImage(image.getImage(), image.getImageInfo().getRotationDegrees());
                    recognizer.process(img)
                            .addOnSuccessListener(result -> {
                                if (result != null && result.getText() != null && !result.getText().isEmpty()) {
                                    callbackContext.success(result.getText());
                                    stopCamera();
                                }
                            });
                    image.close();
                });

                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

            } catch (Exception e) {
                callbackContext.error(e.getMessage());
            }

        }, ContextCompat.getMainExecutor(activity));
    }

    private void stopCamera() {
        Activity activity = cordova.getActivity();
        FrameLayout layout = (FrameLayout) activity.findViewById(android.R.id.content);
        layout.removeView(previewView);
    }
}
