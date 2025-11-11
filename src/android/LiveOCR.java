package com.serial.liveocr;

import android.content.Intent;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class LiveOCR extends CordovaPlugin {
    private static final int REQ_OCR = 4242;
    private CallbackContext cb;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if (!"start".equals(action)) return false;

        this.cb = callbackContext;
        JSONObject opts = args.optJSONObject(0);

        Intent i = new Intent(cordova.getActivity(), OCRActivity.class);
        if (opts != null) i.putExtra("options", opts.toString());

        cordova.setActivityResultCallback(this);
        cordova.getActivity().startActivityForResult(i, REQ_OCR);
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_OCR || cb == null) return;

        if (resultCode == CordovaActivity.RESULT_OK && data != null) {
            String text = data.getStringExtra("text");
            int stable = data.getIntExtra("stable", 0);
            JSONObject res = new JSONObject();
            try {
                res.put("text", text);
                res.put("stableFrames", stable);
            } catch (Exception ignored) {}
            cb.success(res);
        } else {
            cb.error(data != null ? data.getStringExtra("error") : "cancelled");
        }
        cb = null;
    }
}

