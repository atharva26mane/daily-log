package com.atharva.dailylog;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;

public class WaterWidget extends android.appwidget.AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) WidgetUtil.water(c, m, id);
        WidgetUtil.tick(c, true);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (WidgetUtil.handle(c, i)) return;
        super.onReceive(c, i);
    }
}
