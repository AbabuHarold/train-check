package com.traincheck.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 設定画面：WebアプリのURL入力、時刻表の取得、ウィジェットの追加 */
public class MainActivity extends Activity {
    private TextView status;
    private EditText url;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        url = findViewById(R.id.url);
        Button fetch = findViewById(R.id.fetch);
        Button pin = findViewById(R.id.pin);

        Map<String, Object> config = null;
        try { config = TimetableStore.load(this); } catch (Exception ignored) { }
        url.setText(TimetableStore.webAppUrl(this, config));

        fetch.setOnClickListener(x -> {
            final String u = url.getText().toString().trim();
            TimetableStore.prefs(this).edit().putString(TimetableStore.KEY_URL, u).apply();
            if (u.isEmpty()) { showStatus("URLを入力してください"); return; }
            fetch.setEnabled(false);
            status.setText("取得中…");
            new Thread(() -> {
                String msg;
                try {
                    TimetableStore.download(this, u);
                    TrainWidgetProvider.updateAll(this);
                    msg = null;
                } catch (Exception e) {
                    msg = "取得できませんでした: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> { fetch.setEnabled(true); showStatus(m); });
            }).start();
        });

        AppWidgetManager mgr = getSystemService(AppWidgetManager.class);
        if (mgr != null && mgr.isRequestPinAppWidgetSupported()) {
            pin.setOnClickListener(x -> mgr.requestPinAppWidget(new ComponentName(this, TrainWidgetProvider.class), null, null));
        } else {
            pin.setText("ホーム画面を長押し →「ウィジェット」から追加してください");
            pin.setEnabled(false);
        }
        TrainWidgetProvider.schedule(this);
        showStatus(null);
    }

    @SuppressWarnings("unchecked")
    private void showStatus(String error) {
        StringBuilder s = new StringBuilder();
        if (error != null) s.append(error).append("\n\n");
        try {
            Map<String, Object> cfg = TimetableStore.load(this);
            List<Object> routes = (List<Object>) cfg.get("routes");
            s.append("時刻表: ").append(TimetableStore.isDownloaded(this) ? "ダウンロード済み" : "アプリ同梱").append("\n");
            long at = TimetableStore.prefs(this).getLong(TimetableStore.KEY_FETCHED_AT, 0);
            if (at > 0) s.append("最終取得: ").append(DateFormat.getDateTimeInstance().format(new Date(at))).append("\n");
            s.append("ルート:");
            for (Object o : routes) s.append("\n ・").append(((Map<String, Object>) o).get("label"));
        } catch (Exception e) {
            s.append("時刻表を読み込めません: ").append(e.getMessage());
        }
        status.setText(s);
    }
}
