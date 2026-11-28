package com.atharva.dailylog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class WidgetUtil {
    public static final String PREFS = "dl_widget";
    public static final String ADD_WATER = "com.atharva.dailylog.ADD_WATER";
    public static final String TICK = "com.atharva.dailylog.WIDGET_TICK";
    /** Raise this when the phone-side code changes, and NATIVE_NEEDED in index.html with it. */
    public static final int LEVEL = 3;

    static final int FOREST = Color.rgb(64, 81, 59);

    // the app's day starts at 4 AM
    static String dayKey(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms - 4L * 3600000L)); }
    static String hm(long ms) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms)); }
    static String fmt(int n) { return String.format(Locale.US, "%,d", n); }
    static int r10(double x) { return x <= 0 ? 0 : (int) Math.max(10, Math.round(x / 10.0) * 10); }

    static String sipText(int ml) {
        if (ml <= 0) return ""; if (ml <= 20) return "A sip or two"; if (ml <= 45) return "2 to 3 sips"; if (ml <= 80) return "2 to 3 mouthfuls";
        if (ml <= 140) return "Half a glass"; if (ml <= 210) return "A small glass"; if (ml <= 300) return "A glass"; return "A big glass";
    }
    static String inText(double mins) {
        long m = Math.max(0, Math.round(mins)); if (m < 1) return "now"; if (m < 60) return "in " + m + " min";
        long h = m / 60, r = m % 60; return "in " + h + " h" + (r > 0 ? " " + r + " min" : "");
    }

    static JSONArray pending(Context c) {
        try { return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pendingList", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** Water logged on a widget. Kept with its real time until the app opens and saves it. */
    static void addWater(Context c, int ml) {
        if (ml <= 0) return;
        try {
            JSONArray a = pending(c), it = new JSONArray();
            it.put(System.currentTimeMillis()); it.put(ml); a.put(it);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pendingList", a.toString()).apply();
            Toast.makeText(c, "Added " + ml + " ml", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { }
        refreshAll(c);
    }

    /** Shared handling for widget buttons and the 15-minute refresh. */
    static boolean handle(Context c, Intent i) {
        String a = i.getAction();
        if (ADD_WATER.equals(a)) { addWater(c, i.getIntExtra("ml", 0)); return true; }
        if (TICK.equals(a)) { refreshAll(c); return true; }
        return false;
    }

    static JSONObject data(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = p.getString("json", "");
            JSONObject o = s.length() > 0 ? new JSONObject(s) : new JSONObject();
            long now = System.currentTimeMillis();
            String today = dayKey(now);
            if (!today.equals(o.optString("day", ""))) {
                // a new day started and the app has not been opened yet
                o.put("day", today); o.put("waterMl", 0); o.put("done", 0);
                o.put("total", o.optJSONArray("todos") != null ? o.optJSONArray("todos").length() : 0);
                JSONObject pl = o.optJSONObject("plan");
                if (pl != null) {
                    JSONObject tm = pl.optJSONObject("tomorrow");
                    if (tm != null && today.equals(tm.optString("key"))) { pl.put("today", tm); pl.remove("tomorrow"); }
                    else o.remove("plan");
                }
            }
            JSONObject pl = o.optJSONObject("plan");
            JSONObject td = pl != null ? pl.optJSONObject("today") : null;
            JSONArray drinks = null;
            if (td != null) { drinks = td.optJSONArray("drinks"); if (drinks == null) { drinks = new JSONArray(); td.put("drinks", drinks); } }
            int add = p.getInt("pendingMl", 0);
            JSONArray pend = pending(c);
            for (int i = 0; i < pend.length(); i++) {
                JSONArray it = pend.optJSONArray(i); if (it == null) continue;
                long at = it.optLong(0, 0); if (!today.equals(dayKey(at))) continue;
                add += it.optInt(1, 0);
                if (drinks != null) drinks.put((at - td.optLong("start", 0)) / 60000.0);
            }
            o.put("waterMl", o.optInt("waterMl", 0) + add);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    /** The water plan right now. Same rules as the app (index.html, live()). */
    static class Live {
        JSONObject p; double now; int goal, drunk, remaining, diff, nextMl; long nextAt; boolean due; String status;
        double[] at; int[] base; int[] state; boolean[] key; // state: 0 upcoming, 1 done, 2 missed, 3 next, 4 optional
    }
    static Live live(JSONObject o) {
        try {
            JSONObject pl = o.optJSONObject("plan"); if (pl == null) return null;
            JSONObject p = pl.optJSONObject("today"); if (p == null) return null;
            JSONArray sl = p.optJSONArray("slots"); if (sl == null || sl.length() == 0) return null;
            Live L = new Live(); L.p = p;
            int n = sl.length(); L.at = new double[n]; L.base = new int[n]; L.state = new int[n]; L.key = new boolean[n];
            for (int i = 0; i < n; i++) { JSONArray s = sl.getJSONArray(i); L.at[i] = s.optDouble(0); L.base[i] = s.optInt(1); L.key[i] = s.optInt(2) == 1; }
            long start = p.optLong("start", 0);
            double gap = p.optDouble("gap", 20), cover = Math.max(5, Math.min(20, gap / 2));
            double now = (System.currentTimeMillis() - start) / 60000.0; L.now = now;
            L.goal = p.optInt("goal", 2500); L.drunk = o.optInt("waterMl", 0);
            JSONArray dr = p.optJSONArray("drinks");
            boolean[] done = new boolean[n];
            double planned = 0; int due = -1;
            for (int i = 0; i < n; i++) {
                double nextAt = i < n - 1 ? L.at[i + 1] : L.at[i] + gap;
                if (dr != null) for (int j = 0; j < dr.length(); j++) { double w = dr.optDouble(j); if (w >= L.at[i] - cover && w < nextAt) { done[i] = true; break; } }
                if (L.at[i] <= now) planned += L.base[i];
                if (L.at[i] <= now && now < nextAt && !done[i]) due = i;
            }
            L.remaining = Math.max(0, L.goal - L.drunk);
            ArrayList<Integer> U = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) if (!done[i] && (L.at[i] > now || i == due)) U.add(i);
            double dark = p.optDouble("dark", -1e9);
            boolean boost = now - dark <= 180 && now >= dark;
            double[] w = new double[U.size()]; double B = 0;
            for (int j = 0; j < U.size(); j++) { int i = U.get(j); w[j] = L.base[i] * (boost && j < 3 && L.at[i] <= now + 90 ? 1.4 : 1); B += w[j]; }
            int[] amt = new int[n]; boolean[] skip = new boolean[n];
            if (L.remaining > 0 && U.size() > 0 && B > 0) {
                double avg = (double) L.remaining / U.size();
                if (avg < 30) {
                    int step = (int) Math.ceil(30 / avg); B = 0;
                    for (int j = 0; j < U.size(); j++) { int i = U.get(j); if (j % step == 0) { w[j] = L.base[i]; B += w[j]; } else skip[i] = true; }
                }
                double f = L.remaining / B;
                for (int j = 0; j < U.size(); j++) { int i = U.get(j); if (skip[i]) continue; double cap = Math.min(350, Math.max(60, L.base[i] * 2.5)); amt[i] = r10(Math.min(cap, w[j] * f)); }
            }
            int nx = -1;
            for (int j = 0; j < U.size(); j++) if (amt[U.get(j)] > 0) { nx = U.get(j); break; }
            L.diff = (int) Math.round(L.drunk - planned);
            double end = p.optDouble("end", 1320), wake = p.optDouble("wake", 390);
            L.status = L.drunk >= L.goal ? "done" : now < wake ? "before" : (now > end && due < 0) ? "over" : (L.diff < -150 ? "behind" : L.diff > 300 ? "ahead" : "ok");
            if (nx >= 0) { L.nextMl = amt[nx]; L.nextAt = start + Math.round(L.at[nx] * 60000); L.due = nx == due; }
            for (int i = 0; i < n; i++) L.state[i] = done[i] ? 1 : i == nx ? 3 : (L.at[i] <= now ? 2 : (skip[i] || amt[i] == 0 ? 4 : 0));
            return L;
        } catch (Exception e) { return null; }
    }
    static String statusText(Live L) {
        if ("done".equals(L.status)) return "Goal reached";
        if ("behind".equals(L.status)) return "Behind by " + fmt(-L.diff) + " ml";
        if ("ahead".equals(L.status)) return "Ahead by " + fmt(L.diff) + " ml";
        if ("ok".equals(L.status)) return "On track";
        return "";
    }

    /** The day strip: meal windows, every drink as a tick, the next one marked, and now. */
    static Bitmap strip(Live L, int wpx, int hpx, float dens) {
        Bitmap b = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
        JSONObject p = L.p;
        double a = p.optDouble("wake", 390), e = p.optDouble("to", p.optDouble("end", 1320)), span = Math.max(60, e - a);
        float pad = 6 * dens, top = hpx * 0.22f, bot = hpx * 0.62f, rr = (bot - top) / 2;
        float W = wpx - 2 * pad;
        pa.setColor(Color.argb(26, 64, 81, 59));
        cv.drawRoundRect(new RectF(pad, top, wpx - pad, bot), rr, rr, pa);
        JSONArray q = p.optJSONArray("quiet");
        pa.setColor(Color.argb(70, 185, 148, 112));
        if (q != null) for (int i = 0; i < q.length(); i++) { JSONArray x = q.optJSONArray(i); if (x == null) continue; float x1 = X(x.optDouble(0), a, span, pad, W), x2 = X(x.optDouble(1), a, span, pad, W); cv.drawRoundRect(new RectF(x1, top, x2, bot), 4 * dens, 4 * dens, pa); }
        JSONArray bz = p.optJSONArray("busy");
        pa.setColor(Color.argb(44, 64, 81, 59));
        if (bz != null) for (int i = 0; i < bz.length(); i++) { JSONArray x = bz.optJSONArray(i); if (x == null) continue; float x1 = X(x.optDouble(0), a, span, pad, W), x2 = X(x.optDouble(1), a, span, pad, W); cv.drawRoundRect(new RectF(x1, top, x2, bot), 4 * dens, 4 * dens, pa); }
        float h = bot - top;
        int nxi = -1;
        for (int i = 0; i < L.at.length; i++) {
            if (L.state[i] == 3) { nxi = i; continue; }
            float x = X(L.at[i], a, span, pad, W), tw = (L.key[i] ? 3f : 2f) * dens;
            float t = L.key[i] ? top + h * 0.12f : top + h * 0.28f, bo = L.key[i] ? bot - h * 0.12f : bot - h * 0.28f;
            int col = L.state[i] == 1 ? Color.rgb(96, 153, 102) : L.state[i] == 2 ? Color.argb(46, 64, 81, 59) : L.state[i] == 4 ? Color.argb(40, 64, 81, 59) : Color.argb(110, 64, 81, 59);
            pa.setColor(col);
            cv.drawRoundRect(new RectF(x - tw / 2, t, x + tw / 2, bo), tw / 2, tw / 2, pa);
        }
        JSONArray ms = p.optJSONArray("meals");
        pa.setColor(Color.rgb(185, 148, 112));
        if (ms != null) for (int i = 0; i < ms.length(); i++) { float x = X(ms.optDouble(i), a, span, pad, W); cv.drawCircle(x, bot + (hpx - bot) * 0.55f, 3.2f * dens, pa); }
        if (L.now >= a && L.now <= e) {
            float x = X(L.now, a, span, pad, W);
            pa.setColor(Color.rgb(180, 83, 42));
            cv.drawRoundRect(new RectF(x - 1 * dens, top - 4 * dens, x + 1 * dens, bot + 4 * dens), dens, dens, pa);
        }
        if (nxi >= 0) {
            float x = X(L.at[nxi], a, span, pad, W);
            pa.setColor(Color.argb(150, 150, 182, 197));
            cv.drawRoundRect(new RectF(x - 5 * dens, top - 6 * dens, x + 5 * dens, bot + 6 * dens), 4 * dens, 4 * dens, pa);
            pa.setColor(FOREST);
            cv.drawRoundRect(new RectF(x - 2 * dens, top - 4 * dens, x + 2 * dens, bot + 4 * dens), 2 * dens, 2 * dens, pa);
        }
        return b;
    }
    static float X(double m, double a, double span, float pad, float W) { double f = (m - a) / span; if (f < 0) f = 0; if (f > 1) f = 1; return (float) (pad + f * W); }

    static PendingIntent open(Context c, int code, String target) {
        Intent i;
        if (target != null) { i = new Intent(Intent.ACTION_VIEW, Uri.parse("com.atharva.dailylog://go/" + target)); i.setPackage(c.getPackageName()); }
        else { i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName()); if (i == null) i = new Intent(); }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent addIntent(Context c, Class<?> cls, int code, int ml) {
        Intent i = new Intent(c, cls); i.setAction(ADD_WATER); i.putExtra("ml", ml);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void water(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        int ml = d.optInt("waterMl", 0), goal = Math.max(500, d.optInt("goal", 2500));
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_water);
        v.setTextViewText(R.id.w_ml, fmt(ml));
        v.setTextViewText(R.id.w_goal, "of " + fmt(goal) + " ml");
        v.setProgressBar(R.id.w_bar, 100, Math.min(100, ml * 100 / goal), false);
        String nx = "";
        Live L = live(d);
        if (L != null && L.nextMl > 0 && !"done".equals(L.status)) nx = L.due ? "Now: " + L.nextMl + " ml" : "Next " + L.nextMl + " ml at " + hm(L.nextAt);
        v.setTextViewText(R.id.w_next, nx);
        v.setViewVisibility(R.id.w_next, nx.length() > 0 ? View.VISIBLE : View.GONE);
        v.setOnClickPendingIntent(R.id.w_root, open(c, 1, "water"));
        v.setOnClickPendingIntent(R.id.w_add, addIntent(c, WaterWidget.class, 2, 250));
        m.updateAppWidget(id, v);
    }

    static void timeline(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        Live L = live(d);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_timeline);
        v.setOnClickPendingIntent(R.id.tl_root, open(c, 10, "waterplan"));
        v.setOnClickPendingIntent(R.id.tl_p100, addIntent(c, TimelineWidget.class, 11, 100));
        v.setOnClickPendingIntent(R.id.tl_p250, addIntent(c, TimelineWidget.class, 12, 250));
        if (L == null) {
            v.setTextViewText(R.id.tl_title, "Make your water plan");
            v.setTextViewText(R.id.tl_sub, "Tap to set your wake, meal and sleep times");
            v.setViewVisibility(R.id.tl_chip, View.GONE);
            v.setViewVisibility(R.id.tl_strip, View.GONE);
            v.setViewVisibility(R.id.tl_times, View.GONE);
            v.setViewVisibility(R.id.tl_drink, View.GONE);
            m.updateAppWidget(id, v);
            return;
        }
        String title, sub; boolean btn = false;
        if ("done".equals(L.status)) { title = "Goal reached"; sub = "Sip only when you feel thirsty"; }
        else if ("over".equals(L.status) || L.nextMl <= 0) { title = "Drinking time is over"; sub = L.remaining > 0 ? fmt(L.remaining) + " ml left, no need to force it" : "Sleep well"; }
        else if (L.due) { title = "Drink " + L.nextMl + " ml now"; sub = sipText(L.nextMl) + ", due at " + hm(L.nextAt); btn = true; }
        else { title = "Next " + L.nextMl + " ml at " + hm(L.nextAt); sub = sipText(L.nextMl) + ", " + inText((L.nextAt - System.currentTimeMillis()) / 60000.0); btn = true; }
        v.setTextViewText(R.id.tl_title, title);
        v.setTextViewText(R.id.tl_sub, sub + "  |  " + fmt(L.drunk) + " of " + fmt(L.goal) + " ml");
        String st = statusText(L);
        if (st.length() == 0) v.setViewVisibility(R.id.tl_chip, View.GONE);
        else {
            v.setTextViewText(R.id.tl_chip, st);
            boolean warn = "behind".equals(L.status);
            v.setInt(R.id.tl_chip, "setBackgroundResource", warn ? R.drawable.widget_chip_warn : R.drawable.widget_chip);
            v.setTextColor(R.id.tl_chip, warn ? Color.rgb(107, 68, 35) : FOREST);
        }
        if (btn) {
            v.setTextViewText(R.id.tl_drink, "Drank " + L.nextMl + " ml");
            v.setOnClickPendingIntent(R.id.tl_drink, addIntent(c, TimelineWidget.class, 13, L.nextMl));
        } else v.setViewVisibility(R.id.tl_drink, View.GONE);
        float dens = c.getResources().getDisplayMetrics().density;
        int wdp = 300;
        try { Bundle op = m.getAppWidgetOptions(id); if (op != null) wdp = op.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300); } catch (Exception e) { }
        if (wdp < 150) wdp = 300;
        int wpx = Math.max(200, Math.min(1600, Math.round((wdp - 28) * dens))), hpx = Math.round(30 * dens);
        v.setImageViewBitmap(R.id.tl_strip, strip(L, wpx, hpx, dens));
        v.setTextViewText(R.id.tl_a, hm(L.p.optLong("start", 0) + Math.round(L.p.optDouble("wake", 390) * 60000)));
        v.setTextViewText(R.id.tl_b, hm(L.p.optLong("start", 0) + Math.round(L.p.optDouble("to", L.p.optDouble("end", 1320)) * 60000)));
        m.updateAppWidget(id, v);
    }

    static void quick(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        int ml = d.optInt("waterMl", 0), goal = Math.max(500, d.optInt("goal", 2500));
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
        v.setTextViewText(R.id.q_ml, fmt(ml));
        v.setTextViewText(R.id.q_goal, "of " + fmt(goal));
        v.setOnClickPendingIntent(R.id.q_info, open(c, 20, "water"));
        int[] ids = {R.id.q_100, R.id.q_200, R.id.q_250, R.id.q_500}; int[] mls = {100, 200, 250, 500};
        for (int i = 0; i < 4; i++) v.setOnClickPendingIntent(ids[i], addIntent(c, QuickWidget.class, 21 + i, mls[i]));
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
        v.setOnClickPendingIntent(R.id.t_root, open(c, 3, "todo"));
        m.updateAppWidget(id, v);
    }

    static void snippets(Context c, AppWidgetManager m, int id) {
        JSONObject d = data(c);
        int n = d.optInt("snippets", 0);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_snippets);
        v.setTextViewText(R.id.s_n, String.valueOf(n));
        v.setTextViewText(R.id.s_label, n == 0 ? "No new snippets" : n == 1 ? "new snippet" : "new snippets");
        v.setOnClickPendingIntent(R.id.s_root, open(c, 4, "snippets"));
        m.updateAppWidget(id, v);
    }

    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] wa = m.getAppWidgetIds(new ComponentName(c, WaterWidget.class));
        int[] tl = m.getAppWidgetIds(new ComponentName(c, TimelineWidget.class));
        int[] qk = m.getAppWidgetIds(new ComponentName(c, QuickWidget.class));
        for (int id : wa) water(c, m, id);
        for (int id : tl) timeline(c, m, id);
        for (int id : qk) quick(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TodoWidget.class))) todo(c, m, id);
        for (int id : m.getAppWidgetIds(new ComponentName(c, SnippetsWidget.class))) snippets(c, m, id);
        tick(c, wa.length + tl.length > 0);
    }

    /** Keeps "next drink" fresh about every 15 minutes while the screen is on. Does not wake the phone. */
    static void tick(Context c, boolean on) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, TimelineWidget.class); i.setAction(TICK);
            PendingIntent pi = PendingIntent.getBroadcast(c, 99, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (on) am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
            else am.cancel(pi);
        } catch (Exception e) { }
    }
}
