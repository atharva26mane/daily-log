package com.atharva.dailylog;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class WidgetUtil {
    public static final String PREFS = "dl_widget";
    public static final String ADD_WATER = "com.atharva.dailylog.ADD_WATER";

    static JSONObject data(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "");
            JSONObject o = s.length() > 0 ? new JSONObject(s) : new JSONObject();
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!today.equals(o.optString("day", ""))) {
                // a new day started and the app has not been opened yet
                o.put("day", today); o.put("waterMl", 0); o.put("done", 0); o.put("snippets", o.optInt("snippets", 0));
                o.put("total", o.optJSONArray("todos") != null ? o.optJSONArray("todos").length() : 0);
            }
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    static PendingIntent open(Context c, int code) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent();
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void water(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        int ml = d.optInt("waterMl", 0), goal = Math.max(500, d.optInt("goal", 2500));
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_water);
        v.setTextViewText(R.id.w_ml, String.format(Locale.US, "%,d", ml));
        v.setTextViewText(R.id.w_goal, "of " + String.format(Locale.US, "%,d", goal) + " ml");
        v.setProgressBar(R.id.w_bar, 100, Math.min(100, ml * 100 / goal), false);
        v.setOnClickPendingIntent(R.id.w_root, open(c, 1));
        Intent add = new Intent(c, WaterWidget.class); add.setAction(ADD_WATER);
        v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getBroadcast(c, 2, add, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(id, v);
    }

    static void todo(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_todo);
        int done = d.optInt("done", 0), total = d.optInt("total", 0);
        v.setTextViewText(R.id.t_head, total > 0 ? "To-do  " + done + "/" + total : "To-do");
        JSONArray a = d.optJSONArray("todos");
        int[] rows = {R.id.t_1, R.id.t_2, R.id.t_3};
        for (int i = 0; i < 3; i++) {
            String t = a != null && i < a.length() ? a.optString(i) : "";
            if (i == 0 && t.length() == 0) t = total > 0 && done >= total ? "All done for today" : "Nothing planned";
            v.setTextViewText(rows[i], t);
            v.setViewVisibility(rows[i], t.length() > 0 ? View.VISIBLE : View.GONE);
        }
        v.setOnClickPendingIntent(R.id.t_root, open(c, 3));
        m.updateAppWidget(id, v);
    }

    static void snippets(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        int n = d.optInt("snippets", 0);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_snippets);
        v.setTextViewText(R.id.s_n, String.valueOf(n));
        v.setTextViewText(R.id.s_label, n == 0 ? "No new snippets" : n == 1 ? "new snippet" : "new snippets");
        v.setOnClickPendingIntent(R.id.s_root, open(c, 4));
        m.updateAppWidget(id, v);
    }

    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, WaterWidget.class))) water(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TodoWidget.class))) todo(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, SnippetsWidget.class))) snippets(c, m, id);
    }
}
