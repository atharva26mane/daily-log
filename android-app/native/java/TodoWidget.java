package com.atharva.dailylog;

import android.appwidget.AppWidgetManager;
import android.content.Context;

public class TodoWidget extends android.appwidget.AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) WidgetUtil.todo(c, m, id);
    }
}
