package com.serial.liveocr;

import android.content.Intent;
import android.app.Activity;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class LiveOCR extends CordovaPlugin {

    private static final int REQ_OCR = 9917;
    private CallbackContext callback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) {
        if ("start".equals(action)) {
            this.callback = cb;
            JSONObject opts = (args != null && args.length() > 0) ? args.optJSONObject(0) : new JSONObject();
            Intent i = new Intent(cordova.getActivity(), OCRActivity.class);
            i.putExtra("regex", opts.optString("regex", "^[A-Za-z0-9\\-]{6,}$"));
            i.putExtra("minStable", opts.optInt("minStableFrames", 3));
            i.putExtra("boxPct", (float) opts.optDouble("boxPercent", 0.33));
            cordova.setActivityResultCallback(this);
            cordova.getActivity().startActivityForResult(i, REQ_OCR);
            return true;
        }
        if ("stop".equals(action)) {
            // OCRActivity finishes itself; nothing to do.
            cb.success();
            return true;
        }
        return false;
    }

    @Override
    public void onActivityResult(int reqCode, int resCode, Intent data) {
        if (reqCode == REQ_OCR && callback != null) {
            if (resCode == Activity.RESULT_OK && data != null) {
                String text = data.getStringExtra("text");
                int stable = data.getIntExtra("stable", 0);
                try {
                    JSONObject out = new JSONObject();
                    out.put("text", text);
                    out.put("stableFrames", stable);
                    callback.success(out);
                } catch (Exception e) {
                    callback.error(e.getMessage());
                }
            } else {
                callback.error("cancelled");
            }
            callback = null;
        }
        super.onActivityResult(reqCode, resCode, data);
    }
}
