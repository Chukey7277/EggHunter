// app/src/main/java/com/example/virtualtourar/geofence/NearbyEggStore.java
package com.example.virtualtourar.geofence;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class NearbyEggStore {
    private static final String PREF = "nearby_egg_store";
    private static final String KEY  = "eggs_json";

    public static void save(Context ctx, Map<String, EggInfo> map) {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, EggInfo> e : map.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("title", e.getValue().title == null ? "" : e.getValue().title);
                o.put("lat", e.getValue().lat);
                o.put("lng", e.getValue().lng);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    public static Map<String, EggInfo> load(Context ctx) {
        String s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]");
        Map<String, EggInfo> out = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.put(o.getString("id"),
                        new EggInfo(o.optString("title",""),
                                o.optDouble("lat", 0), o.optDouble("lng", 0)));
            }
        } catch (Exception ignore) {}
        return out;
    }

    public static class EggInfo {
        public final String title; public final double lat, lng;
        public EggInfo(String t, double la, double ln) { title = t; lat = la; lng = ln; }
    }
}
