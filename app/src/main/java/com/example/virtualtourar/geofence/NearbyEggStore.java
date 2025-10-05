package com.example.virtualtourar.geofence;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny cache of geofence metadata (title/lat/lng) used by the BroadcastReceiver
 * and by BootCompletedReceiver to re-register fences after reboot/app-update.
 *
 * Backed by SharedPreferences (JSON). Safe to call from any process.
 */
public final class NearbyEggStore {
    private static final String PREF = "nearby_store";
    private static final String KEY_JSON = "anchor_json";
    private static final String KEY_META = "meta_json";     // version + updatedAt

    private static final int VERSION = 1;

    private NearbyEggStore() {}

    /** Persist the full map (overwrites old set). */
    public static void save(Context ctx, Map<String, EggInfo> map) {
        if (ctx == null || map == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, EggInfo> e : map.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) continue;
                EggInfo info = e.getValue();
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("title", info.title == null ? "" : info.title);
                o.put("lat", info.lat);
                o.put("lng", info.lng);
                arr.put(o);
            }

            JSONObject meta = new JSONObject();
            meta.put("version", VERSION);
            meta.put("updatedAt", System.currentTimeMillis());

            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_JSON, arr.toString())
                    .putString(KEY_META, meta.toString())
                    .apply();
        } catch (Throwable ignore) {
            // best-effort cache; never crash
        }
    }

    /** Load the last saved set (empty map if none). */
    public static Map<String, EggInfo> load(Context ctx) {
        if (ctx == null) return Collections.emptyMap();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_JSON, "[]");

        Map<String, EggInfo> out = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", null);
                if (id == null || id.isEmpty()) continue;
                out.put(id, new EggInfo(
                        o.optString("title", ""),
                        o.optDouble("lat", 0d),
                        o.optDouble("lng", 0d)
                ));
            }
        } catch (Throwable ignore) { /* empty map fallback */ }
        return out;
    }

    /** Optional helper: clear the cache. */
    public static void clear(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_JSON)
                .remove(KEY_META)
                .apply();
    }

    /** Optional: when this cache was last updated (ms since epoch); -1 if unknown. */
    public static long lastUpdatedAt(Context ctx) {
        if (ctx == null) return -1L;
        try {
            String meta = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_META, null);
            if (meta == null) return -1L;
            JSONObject m = new JSONObject(meta);
            return m.optLong("updatedAt", -1L);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** POJO stored for each geofence. */
    public static class EggInfo {
        public final String title;
        public final double lat, lng;

        public EggInfo(String title, double lat, double lng) {
            this.title = title;
            this.lat = lat;
            this.lng = lng;
        }
    }
}
