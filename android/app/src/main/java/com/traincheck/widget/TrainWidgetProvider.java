package com.traincheck.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ホーム画面ウィジェット
 * - 1分ごとに表示するルートを切り替える（上の行をタップすると次のルートへ）
 * - 到着が早い順に3本表示、「家を出るまで」は秒単位でカウントダウン
 * - 下の部分をタップすると Webアプリ（そのルート）を開く
 */
public class TrainWidgetProvider extends AppWidgetProvider {
    static final String ACTION_TICK = "com.traincheck.widget.TICK";
    static final String ACTION_NEXT = "com.traincheck.widget.NEXT";
    private static final long REFRESH_INTERVAL = 6L * 60 * 60 * 1000; // 時刻表の自動再取得は6時間ごと
    private static final String KEY_TRIED_AT = "tried_at";

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        updateAll(c);
        schedule(c);
        maybeRefresh(c);
    }

    @Override
    public void onEnabled(Context c) { schedule(c); }

    @Override
    public void onDisabled(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(broadcast(c, ACTION_TICK, 1));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        String a = intent.getAction();
        if (ACTION_TICK.equals(a)) {
            updateAll(c);
            maybeRefresh(c);
        } else if (ACTION_NEXT.equals(a)) {
            SharedPreferences p = TimetableStore.prefs(c);
            p.edit().putInt(TimetableStore.KEY_OFFSET, p.getInt(TimetableStore.KEY_OFFSET, 0) + 1).apply();
            updateAll(c);
        }
    }

    static PendingIntent broadcast(Context c, String action, int code) {
        Intent i = new Intent(c, TrainWidgetProvider.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 毎分更新（画面オフ中は止まり、画面がつくとすぐ更新される） */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        long next = (now / 60_000L + 1) * 60_000L;
        am.setRepeating(AlarmManager.RTC, next, 60_000L, broadcast(c, ACTION_TICK, 1));
    }

    /** WebアプリのURLが設定されていれば、ときどき時刻表を取り直す */
    private void maybeRefresh(Context c) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = TimetableStore.prefs(app);
        long now = System.currentTimeMillis();
        if (now - p.getLong(KEY_TRIED_AT, 0) < REFRESH_INTERVAL) return;
        String url;
        try { url = TimetableStore.webAppUrl(app, TimetableStore.load(app)); } catch (Exception e) { url = TimetableStore.webAppUrl(app, null); }
        if (url.isEmpty()) return;
        p.edit().putLong(KEY_TRIED_AT, now).apply();
        final String u = url;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                TimetableStore.download(app, u);
                updateAll(app);
            } catch (Exception ignored) {
                // 次の機会にまた試す
            } finally {
                pr.finish();
            }
        }).start();
    }

    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, TrainWidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        mgr.updateAppWidget(ids, build(c));
    }

    @SuppressWarnings("unchecked")
    static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setOnClickPendingIntent(R.id.header, broadcast(c, ACTION_NEXT, 2));
        v.removeAllViews(R.id.rows);

        Map<String, Object> config;
        try {
            config = TimetableStore.load(c);
        } catch (Exception e) {
            v.setTextViewText(R.id.title, "時刻表を読み込めません");
            v.setTextViewText(R.id.page, "");
            v.setViewVisibility(R.id.leaveBox, View.GONE);
            v.setTextViewText(R.id.footer, String.valueOf(e.getMessage()));
            v.setOnClickPendingIntent(R.id.body, openApp(c, null, null));
            return v;
        }

        List<Map<String, Object>> all = new ArrayList<>();
        for (Object o : (List<Object>) config.get("routes")) all.add((Map<String, Object>) o);
        List<Map<String, Object>> routes = new ArrayList<>();
        if (config.get("widgetRoutes") instanceof List) {
            for (Object id : (List<Object>) config.get("widgetRoutes"))
                for (Map<String, Object> r : all) if (id != null && id.equals(r.get("id"))) routes.add(r);
        }
        if (routes.isEmpty()) routes = all;

        long now = System.currentTimeMillis();
        int offset = TimetableStore.prefs(c).getInt(TimetableStore.KEY_OFFSET, 0);
        int idx = Math.floorMod((int) (now / 60_000L) + offset, routes.size());
        Map<String, Object> route = routes.get(idx);

        TrainLogic.Result res = TrainLogic.nextDepartures(route, config, Instant.ofEpochMilli(now), 3);
        v.setTextViewText(R.id.title, String.valueOf(route.get("label")));
        v.setTextViewText(R.id.page, routes.size() > 1 ? (idx + 1) + "/" + routes.size() + "  ›" : "");

        String from = route.get("from") instanceof String ? (String) route.get("from") : "出発地";
        if (res.departures.isEmpty()) {
            v.setViewVisibility(R.id.leaveBox, View.GONE);
        } else {
            TrainLogic.Departure first = res.departures.get(0);
            v.setViewVisibility(R.id.leaveBox, View.VISIBLE);
            if (first.leaveAtMillis - now <= 0) {
                v.setTextViewText(R.id.leaveLabel, "今すぐ出れば間に合う");
                v.setViewVisibility(R.id.timer, View.GONE);
            } else {
                v.setTextViewText(R.id.leaveLabel, from + "を出るまで ");
                v.setViewVisibility(R.id.timer, View.VISIBLE);
                v.setChronometer(R.id.timer, SystemClock.elapsedRealtime() + (first.leaveAtMillis - now), null, true);
                v.setChronometerCountDown(R.id.timer, true);
            }
        }
        for (TrainLogic.Departure d : res.departures) {
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            row.setTextViewText(R.id.dep, d.time + "発");
            row.setTextViewText(R.id.kind, d.kind == null ? "" : d.kind);
            row.setViewVisibility(R.id.kind, d.kind == null || d.kind.isEmpty() ? View.GONE : View.VISIBLE);
            row.setTextViewText(R.id.arr, d.arrive != null ? d.arrive + "着" : "");
            v.addView(R.id.rows, row);
        }
        v.setTextViewText(R.id.footer, TrainLogic.dayLabel(res.dayType) + "ダイヤ ・ 到着が早い順 ・ " + res.now);
        v.setOnClickPendingIntent(R.id.body, openApp(c, TimetableStore.webAppUrl(c, config), String.valueOf(route.get("id"))));
        return v;
    }

    /** WebアプリのURLがあればそのルートを開く。無ければ設定画面 */
    static PendingIntent openApp(Context c, String webUrl, String routeId) {
        Intent i;
        if (webUrl != null && !webUrl.isEmpty()) {
            String base = webUrl.endsWith("/") ? webUrl : webUrl + "/";
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(base + "?r=" + Uri.encode(routeId == null ? "" : routeId)));
        } else {
            i = new Intent(c, MainActivity.class);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
