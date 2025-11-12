package com.example.stablecamera;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import android.content.Intent;

public class StableCamera extends CordovaPlugin {
    private static CallbackContext callback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("start".equals(action)) {
            callback = callbackContext;
            Intent intent = new Intent(cordova.getActivity(), CameraActivity.class);
            cordova.getActivity().startActivity(intent);
            return true;
        }
        return false;
    }

    public static void sendResult(String result) {
        if (callback != null) {
            callback.success(result);
            callback = null;
        }
    }
}
