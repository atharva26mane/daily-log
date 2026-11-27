package com.atharva.dailylog;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.WindowManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {
    @PluginMethod
    public void update(PluginCall call) {
        String json = call.getString("json", "");
        SharedPreferences p = getContext().getSharedPreferences(WidgetUtil.PREFS, Context.MODE_PRIVATE);
        p.edit().putString("json", json).apply();
        WidgetUtil.refreshAll(getContext());
        call.resolve();
    }

    @PluginMethod
    public void takePending(PluginCall call) {
        SharedPreferences p = getContext().getSharedPreferences(WidgetUtil.PREFS, Context.MODE_PRIVATE);
        int ml = p.getInt("pendingMl", 0);
        if (ml > 0) p.edit().putInt("pendingMl", 0).apply();
        JSObject o = new JSObject();
        o.put("ml", ml);
        call.resolve(o);
    }

    @PluginMethod
    public void secure(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        });
        call.resolve();
    }
}
