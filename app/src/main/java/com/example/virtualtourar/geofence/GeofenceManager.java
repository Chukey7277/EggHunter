// app/src/main/java/com/example/virtualtourar/geofence/GeofenceManager.java
package com.example.virtualtourar.geofence;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.example.virtualtourar.data.EggEntry;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeofenceManager {
    public static final String CHANNEL_ID = "eggs_proximity_channel";
    private static final float  RADIUS_METERS = 120f;   // trigger distance
    private static final int    DWELL_MS     = 30_000;  // 30s loitering
    private static final int    MAX = 95;               // margin under API limit (100)

    private final Context context;
    private final GeofencingClient client;
    private PendingIntent pendingIntent;

    public GeofenceManager(@NonNull Context ctx) {
        context = ctx.getApplicationContext();
        client = LocationServices.getGeofencingClient(context);
        // Ensure notification channel exists for this geofence system
        NotificationHelper.ensureChannel(context, CHANNEL_ID);
    }

    public void registerForEggs(@NonNull List<EggEntry> eggs) {
        // Build geofence list (cap to MAX, prefer unique coordinates)
        List<Geofence> list = new ArrayList<>();
        Map<String, NearbyEggStore.EggInfo> meta = new HashMap<>();

        int count = 0;
        for (EggEntry e : eggs) {
            if (e == null || e.id == null || e.geo == null) continue;
            if (count >= MAX) break;

            list.add(new Geofence.Builder()
                    .setRequestId(e.id)
                    .setCircularRegion(e.geo.getLatitude(), e.geo.getLongitude(), RADIUS_METERS)
                    .setTransitionTypes(
                            Geofence.GEOFENCE_TRANSITION_ENTER |
                                    Geofence.GEOFENCE_TRANSITION_DWELL
                    )
                    .setLoiteringDelay(DWELL_MS)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build());

            meta.put(e.id, new NearbyEggStore.EggInfo(
                    e.title,
                    e.geo.getLatitude(),
                    e.geo.getLongitude()
            ));
            count++;
        }

        // Save metadata for lookups in the BroadcastReceiver
        NearbyEggStore.save(context, meta);

        if (list.isEmpty()) return;

        GeofencingRequest req = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(list)
                .build();

        // Check location permission before adding geofences
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Permissions not granted, don't crash
            return;
        }

        client.addGeofences(req, getPendingIntent())
                .addOnSuccessListener(v -> {
                    // Successfully registered geofences
                })
                .addOnFailureListener(err -> {
                    // Log error if needed
                    err.printStackTrace();
                });
    }

    public void clearAll() {
        client.removeGeofences(getPendingIntent());
    }

    private PendingIntent getPendingIntent() {
        if (pendingIntent != null) return pendingIntent;
        Intent i = new Intent(context, GeofenceBroadcastReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        pendingIntent = PendingIntent.getBroadcast(context, 1001, i, flags);
        return pendingIntent;
    }
}
