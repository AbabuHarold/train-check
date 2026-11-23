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
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ホーム画面ウィジェット
 *
 * 表示モード（ウィジェットごとに保存。右上のボタンで切り替え）
 * - 1ルートずつ（既定）：1分ごとに表示するルートを切り替える（上の行をタップすると次のルートへ）。
 *   到着が早い順に3本表示、「家を出るまで」は秒単位でカウントダウン
 * - 全ルート：widgetRoutes の全ルート（4ルート）を同時に表示。各ルートの次の電車とカウントダウン。
 *   ウィジェットの高さに余裕があれば1ルート2行（種別つき）、低ければ1ルート1行で表示
 * - 下の部分（全ルートでは各ルートの行）をタップすると Webアプリ（そのルート）を開く
 */
public class TrainWidgetProvider extends AppWidgetProvider {
    static final String ACTION_TICK = "com.traincheck.widget.TICK";
    static final String ACTION_NEXT = "com.traincheck.widget.NEXT";
    static final String ACTION_MODE = "com.traincheck.widget.MODE";
    static final String ACTION_PINNED = "com.traincheck.widget.PINNED";
    static final String EXTRA_MODE = "com.traincheck.widget.EXTRA_MODE";

    static final String MODE_ROTATE = "rotate";
    static final String MODE_ALL = "all";
    private static final String KEY_MODE_PREFIX = "mode_";

    /** 全ルート表示を2行（種別つき）にする高さ。これより低いウィジェットは1行で表示する */
    private static final int DETAILED_MIN_HEIGHT_DP = 240;

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

    /** ウィジェットの大きさが変わったら、そのウィジェットだけ描き直す（全ルート表示の1行/2行の切り替え） */
    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager mgr, int id, Bundle options) {
        update(c, mgr, id);
    }

    /** 削除されたウィジェットの表示モードの記録を消す */
    @Override
    public void onDeleted(Context c, int[] ids) {
        SharedPreferences.Editor e = TimetableStore.prefs(c).edit();
        for (int id : ids) e.remove(KEY_MODE_PREFIX + id);
        e.apply();
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
        } else if (ACTION_MODE.equals(a)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
            setMode(c, id, MODE_ALL.equals(mode(c, id)) ? MODE_ROTATE : MODE_ALL);
            update(c, AppWidgetManager.getInstance(c), id);
        } else if (ACTION_PINNED.equals(a)) {
            // 設定画面の「全ルート表示のウィジェットを追加」から置かれたウィジェット
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            String m = intent.getStringExtra(EXTRA_MODE);
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID || m == null) return;
            setMode(c, id, m);
            update(c, AppWidgetManager.getInstance(c), id);
        }
    }

    // ───────────── 表示モード（ウィジェットごと） ─────────────

    static String mode(Context c, int id) {
        String m = TimetableStore.prefs(c).getString(KEY_MODE_PREFIX + id, MODE_ROTATE);
        return MODE_ALL.equals(m) ? MODE_ALL : MODE_ROTATE;
    }

    static void setMode(Context c, int id, String m) {
        TimetableStore.prefs(c).edit().putString(KEY_MODE_PREFIX + id, MODE_ALL.equals(m) ? MODE_ALL : MODE_ROTATE).apply();
    }

    // ───────────── タップ・定期更新 ─────────────

    static PendingIntent broadcast(Context c, String action, int code) {
        Intent i = new Intent(c, TrainWidgetProvider.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** ウィジェットごとの表示モード切り替え（ウィジェットごとに別の PendingIntent にするため data に ID を入れる） */
    static PendingIntent modeToggle(Context c, int id) {
        Intent i = new Intent(c, TrainWidgetProvider.class)
            .setAction(ACTION_MODE)
            .setData(Uri.parse("traincheck://widget/" + id + "/mode"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(c, 1000 + id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * ウィジェットを置いたあとに呼ばれる（設定画面の「全ルート表示のウィジェットを追加」用）。
     * 置かれたウィジェットの ID をシステムが付け加えるため FLAG_MUTABLE にする。
     */
    static PendingIntent pinnedCallback(Context c, String mode) {
        Intent i = new Intent(c, TrainWidgetProvider.class)
            .setAction(ACTION_PINNED)
            .setData(Uri.parse("traincheck://pinned/" + mode))
            .putExtra(EXTRA_MODE, mode);
        return PendingIntent.getBroadcast(c, 4, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
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
        if (ids == null) return;
        for (int id : ids) update(c, mgr, id);
    }

    static void update(Context c, AppWidgetManager mgr, int id) {
        mgr.updateAppWidget(id, build(c, id, mgr.getAppWidgetOptions(id)));
    }

    // ───────────── 描画 ─────────────

    @SuppressWarnings("unchecked")
    static RemoteViews build(Context c, int id, Bundle options) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.removeAllViews(R.id.rows);
        boolean all = MODE_ALL.equals(mode(c, id));
        v.setTextViewText(R.id.mode, all ? "1ルートずつ" : "全ルート");
        v.setOnClickPendingIntent(R.id.mode, modeToggle(c, id));

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

        List<Map<String, Object>> allRoutes = new ArrayList<>();
        for (Object o : (List<Object>) config.get("routes")) allRoutes.add((Map<String, Object>) o);
        List<Map<String, Object>> routes = new ArrayList<>();
        if (config.get("widgetRoutes") instanceof List) {
            for (Object rid : (List<Object>) config.get("widgetRoutes"))
                for (Map<String, Object> r : allRoutes) if (rid != null && rid.equals(r.get("id"))) routes.add(r);
        }
        if (routes.isEmpty()) routes = allRoutes;

        long now = System.currentTimeMillis();
        if (all) buildAll(c, v, config, routes, now, isDetailed(options));
        else buildRotate(c, v, config, routes, now);
        return v;
    }

    /** 1ルートずつ（これまでの表示） */
    private static void buildRotate(Context c, RemoteViews v, Map<String, Object> config, List<Map<String, Object>> routes, long now) {
        v.setOnClickPendingIntent(R.id.header, broadcast(c, ACTION_NEXT, 2));
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
    }

    /** 全ルート：各ルートの次の電車（到着が一番早いもの）と、出発地を出るまでのカウントダウン */
    private static void buildAll(Context c, RemoteViews v, Map<String, Object> config, List<Map<String, Object>> routes, long now, boolean detailed) {
        // 全ルート表示では上の行のタップに何も割り当てない（モード切り替えボタンだけ反応する）
        v.setTextViewText(R.id.title, "全ルート");
        v.setTextViewText(R.id.page, "");
        v.setViewVisibility(R.id.leaveBox, View.GONE);

        String webUrl = TimetableStore.webAppUrl(c, config);
        String dayType = null;
        String nowText = "";
        for (Map<String, Object> route : routes) {
            TrainLogic.Result res = TrainLogic.nextDepartures(route, config, Instant.ofEpochMilli(now), 1);
            if (dayType == null) { dayType = res.dayType; nowText = res.now; }
            RemoteViews row = new RemoteViews(c.getPackageName(), detailed ? R.layout.widget_route : R.layout.widget_route_compact);
            row.setTextViewText(R.id.rLabel, String.valueOf(route.get("label")));

            if (res.departures.isEmpty()) {
                row.setTextViewText(R.id.rDep, detailed ? "この先の電車はありません" : "電車なし");
                row.setTextViewText(R.id.rArr, "");
                row.setTextViewText(R.id.rLeaveLabel, "");
                row.setViewVisibility(R.id.rTimer, View.GONE);
                if (detailed) row.setViewVisibility(R.id.rKind, View.GONE);
            } else {
                TrainLogic.Departure d = res.departures.get(0);
                if (detailed) {
                    row.setTextViewText(R.id.rDep, d.time + "発");
                    row.setTextViewText(R.id.rKind, d.kind == null ? "" : d.kind);
                    row.setViewVisibility(R.id.rKind, d.kind == null || d.kind.isEmpty() ? View.GONE : View.VISIBLE);
                    row.setTextViewText(R.id.rArr, d.arrive != null ? d.arrive + "着" : "");
                } else {
                    row.setTextViewText(R.id.rDep, d.time);
                    row.setTextViewText(R.id.rArr, d.arrive != null ? "→" + d.arrive : "");
                }
                long leaveIn = d.leaveAtMillis - now;
                if (leaveIn <= 0) {
                    row.setTextViewText(R.id.rLeaveLabel, "今すぐ");
                    row.setViewVisibility(R.id.rTimer, View.GONE);
                } else {
                    row.setTextViewText(R.id.rLeaveLabel, detailed ? "出るまで " : "");
                    row.setViewVisibility(R.id.rTimer, View.VISIBLE);
                    row.setChronometer(R.id.rTimer, SystemClock.elapsedRealtime() + leaveIn, null, true);
                    row.setChronometerCountDown(R.id.rTimer, true);
                }
            }
            row.setOnClickPendingIntent(R.id.routeRoot, openApp(c, webUrl, String.valueOf(route.get("id"))));
            v.addView(R.id.rows, row);
        }
        v.setTextViewText(R.id.footer,
            (dayType == null ? "" : TrainLogic.dayLabel(dayType) + "ダイヤ ・ ") + "各ルートの到着が一番早い電車 ・ " + nowText);
        v.setOnClickPendingIntent(R.id.body, openApp(c, webUrl, null));
    }

    /** ウィジェットの高さ（縦向きの画面での高さ）に余裕があれば、全ルート表示を2行にする */
    static boolean isDetailed(Bundle options) {
        if (options == null) return false;
        int portraitHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return portraitHeight >= DETAILED_MIN_HEIGHT_DP;
    }

    /** WebアプリのURLがあればそのルート（routeId が null なら全体）を開く。無ければ設定画面 */
    static PendingIntent openApp(Context c, String webUrl, String routeId) {
        Intent i;
        if (webUrl != null && !webUrl.isEmpty()) {
            String base = webUrl.endsWith("/") ? webUrl : webUrl + "/";
            String url = routeId == null ? base : base + "?r=" + Uri.encode(routeId);
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } else {
            i = new Intent(c, MainActivity.class);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
