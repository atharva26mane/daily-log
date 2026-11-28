package com.atharva.dailylog;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;

public class QuickWidget extends android.appwidget.AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) WidgetUtil.quick(c, m, id);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (WidgetUtil.handle(c, i)) return;
        super.onReceive(c, i);
    }
}
