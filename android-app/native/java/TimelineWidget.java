package com.atharva.dailylog;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class TimelineWidget extends android.appwidget.AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) WidgetUtil.timeline(c, m, id);
        WidgetUtil.tick(c, true);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        WidgetUtil.timeline(c, m, id);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (WidgetUtil.handle(c, i)) return;
        super.onReceive(c, i);
    }
}
