package com.traincheck.widget;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 時刻表の読み込み・ダウンロード（ダウンロード済みがあればそれ、無ければアプリ同梱の assets） */
public final class TimetableStore {
    static final String PREFS = "train_check";
    static final String KEY_URL = "web_app_url";
    static final String KEY_FETCHED_AT = "fetched_at";
    static final String KEY_OFFSET = "route_offset";
    private static final String FILE = "timetable.json";

    private TimetableStore() {}

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(Context c) throws IOException {
        File f = new File(c.getFilesDir(), FILE);
        String text;
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) { text = read(in); }
        } else {
            try (InputStream in = c.getAssets().open(FILE)) { text = read(in); }
        }
        return (Map<String, Object>) MiniJson.parse(text);
    }

    public static boolean isDownloaded(Context c) { return new File(c.getFilesDir(), FILE).exists(); }

    /** WebアプリのURL（設定画面で入力したもの。無ければ timetable.json の webAppUrl） */
    public static String webAppUrl(Context c, Map<String, Object> config) {
        String u = prefs(c).getString(KEY_URL, "");
        if (u.isEmpty() && config != null && config.get("webAppUrl") instanceof String) u = (String) config.get("webAppUrl");
        return u.trim();
    }

    /** {webAppUrl}/timetable.json をダウンロードして保存。別スレッドから呼ぶこと */
    public static void download(Context c, String webAppUrl) throws IOException {
        String base = webAppUrl.endsWith("/") ? webAppUrl : webAppUrl + "/";
        URL url = new URL(base + FILE + "?t=" + System.currentTimeMillis());
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            String text;
            try (InputStream in = con.getInputStream()) { text = read(in); }
            MiniJson.parse(text); // 壊れたファイルは保存しない
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) throw new IOException("保存に失敗しました");
            prefs(c).edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply();
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
