package com.example.myapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * البحث والتشغيل من الإنترنت. مفيش أي API key مطلوب:
 *  - Audius: أغاني كاملة (موسيقيين مستقلين)، تشغيل ستريمنج كامل.
 *  - Deezer: كتالوج ضخم (أغاني مشهورة)، لكن الـ API العام بيدّي معاينة 30 ثانية بس.
 */
public class OnlineApi {

    public static final int SRC_AUDIUS = 0;
    public static final int SRC_DEEZER = 1;

    private static final String APP_NAME = "LORD";
    private static final String AUDIUS = "https://api.audius.co/v1";
    private static final String DEEZER = "https://api.deezer.com";
    private static final String UA = "LORD-Music/1.0 (Android)";
    private static final int LIMIT = 30;

    private OnlineApi() {
    }

    // ---------------------------------------------------------------- public

    public static List<Song> search(int source, String query) throws Exception {
        String q = URLEncoder.encode(query, "UTF-8");
        if (source == SRC_DEEZER) {
            return parseDeezer(httpGet(DEEZER + "/search?limit=" + LIMIT + "&q=" + q));
        }
        return parseAudius(httpGet(AUDIUS + "/tracks/search?app_name=" + APP_NAME
                + "&limit=" + LIMIT + "&query=" + q));
    }

    public static List<Song> trending(int source) throws Exception {
        if (source == SRC_DEEZER) {
            return parseDeezer(httpGet(DEEZER + "/chart/0/tracks?limit=" + LIMIT));
        }
        return parseAudius(httpGet(AUDIUS + "/tracks/trending?app_name=" + APP_NAME
                + "&limit=" + LIMIT + "&time=week"));
    }

    public static long makeId(String source, String rawId) {
        long h = (source + ":" + rawId).hashCode();
        return -Math.abs(h) - 1; // دايمًا سالب عشان ميتعارضش مع أغاني الجهاز
    }

    /** بيمشي ورا الـ redirects ويرجّع الرابط النهائي للملف الصوتي. */
    public static String resolveStream(String url) {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", UA);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.length() == 0) return cur;
                    cur = new URL(new URL(cur), loc).toString();
                } else {
                    return cur;
                }
            } catch (Exception e) {
                return cur;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return cur;
    }

    public static Bitmap fetchBitmap(String url, int maxPx) {
        if (url == null || url.length() == 0) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 6 * 1024 * 1024) return null;
                bo.write(buf, 0, n);
            }
            in.close();
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- http

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- parsers

    private static List<Song> parseAudius(String body) throws Exception {
        List<Song> out = new ArrayList<Song>();
        JSONArray arr = new JSONObject(body).optJSONArray("data");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("id", "");
            if (id.length() == 0) continue;
            if (!t.optBoolean("is_streamable", true)) continue;
            if (t.optBoolean("is_stream_gated", false)) continue;
            if (t.optBoolean("is_delete", false)) continue;

            String title = t.optString("title", "Unknown");
            JSONObject user = t.optJSONObject("user");
            String artist = user != null ? user.optString("name", "Unknown") : "Unknown";
            long durMs = t.optLong("duration", 0) * 1000L;
            String art = null;
            JSONObject aw = t.optJSONObject("artwork");
            if (aw != null) {
                art = aw.optString("480x480", "");
                if (art.length() == 0) art = aw.optString("150x150", "");
                if (art.length() == 0) art = aw.optString("1000x1000", "");
            }
            Uri stream = Uri.parse(AUDIUS + "/tracks/" + id + "/stream?app_name=" + APP_NAME);
            out.add(new Song(makeId("audius", id), title, artist, durMs, stream, art, "Audius"));
        }
        return out;
    }

    private static List<Song> parseDeezer(String body) throws Exception {
        List<Song> out = new ArrayList<Song>();
        JSONArray arr = new JSONObject(body).optJSONArray("data");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String preview = t.optString("preview", "");
            if (preview.length() == 0) continue;
            String id = String.valueOf(t.optLong("id", 0));
            String title = t.optString("title", "Unknown");
            JSONObject ar = t.optJSONObject("artist");
            String artist = ar != null ? ar.optString("name", "Unknown") : "Unknown";
            String art = null;
            JSONObject al = t.optJSONObject("album");
            if (al != null) {
                art = al.optString("cover_big", "");
                if (art.length() == 0) art = al.optString("cover_medium", "");
            }
            // الـ API العام بيدّي معاينة 30 ثانية بس
            out.add(new Song(makeId("deezer", id), title, artist, 30000L, Uri.parse(preview), art, "Deezer"));
        }
        return out;
    }
}
