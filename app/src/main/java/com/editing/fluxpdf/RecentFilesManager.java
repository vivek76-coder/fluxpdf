package com.editing.fluxpdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecentFilesManager {

    private static final String PREFS_NAME = "fluxpdf_recent_files";
    private static final String KEY_FILES = "recent_files";
    private static final int MAX_RECENT = 30;

    public static class RecentFile {
        public String uri;
        public String name;
        public long timestamp;

        public RecentFile(String uri, String name, long timestamp) {
            this.uri = uri;
            this.name = name;
            this.timestamp = timestamp;
        }

        public String getFormattedDate() {
            long now = System.currentTimeMillis();
            long diff = now - timestamp;
            long mins = diff / 60000;
            long hours = diff / 3600000;
            long days = diff / 86400000;

            if (mins < 1) return "Just now";
            if (mins < 60) return mins + " min ago";
            if (hours < 24) return hours + " hr ago";
            if (days == 1) return "Yesterday";
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }
    }

    public static void addFile(Context ctx, Uri uri, String name) {
        List<RecentFile> files = getFiles(ctx);

        // Remove existing entry for this URI to avoid duplicates
        files.removeIf(f -> f.uri.equals(uri.toString()));

        // Add to front
        files.add(0, new RecentFile(uri.toString(), name, System.currentTimeMillis()));

        // Trim to max
        if (files.size() > MAX_RECENT) {
            files = files.subList(0, MAX_RECENT);
        }

        save(ctx, files);
    }

    public static List<RecentFile> getFiles(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_FILES, "[]");
        List<RecentFile> files = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                files.add(new RecentFile(
                        obj.getString("uri"),
                        obj.getString("name"),
                        obj.getLong("timestamp")
                ));
            }
        } catch (JSONException e) {
            // return empty list on error
        }
        return files;
    }

    public static void clearAll(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_FILES).apply();
    }

    private static void save(Context ctx, List<RecentFile> files) {
        JSONArray arr = new JSONArray();
        for (RecentFile f : files) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("uri", f.uri);
                obj.put("name", f.name);
                obj.put("timestamp", f.timestamp);
                arr.put(obj);
            } catch (JSONException ignored) {}
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_FILES, arr.toString()).apply();
    }
}
