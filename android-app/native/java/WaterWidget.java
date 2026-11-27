package com.atharva.dailylog;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;

public class WaterWidget extends android.appwidget.AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) WidgetUtil.water(c, m, id);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (WidgetUtil.ADD_WATER.equals(i.getAction())) {
            try {
                SharedPreferences p = c.getSharedPreferences(WidgetUtil.PREFS, Context.MODE_PRIVATE);
                JSONObject d = WidgetUtil.data(c);
                d.put("waterMl", d.optInt("waterMl", 0) + 250);
                p.edit().putString("json", d.toString()).putInt("pendingMl", p.getInt("pendingMl", 0) + 250).apply();
                WidgetUtil.refreshAll(c);
            } catch (Exception e) { }
            return;
        }
        super.onReceive(c, i);
    }
}
