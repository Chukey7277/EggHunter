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

/**
 * Central place to (re)register proximity geofences that keep working
 * when the app is backgrounded or killed.
 */
public class GeofenceManager {

    /** Notification channel used by GeofenceBroadcastReceiver. */
    public static final String CHANNEL_ID = "eggs_proximity_channel";

    /** Safe cap under the Play Services per-app geofence limit (100). */
    private static final int MAX_FENCES = 95;

    /** Enter if within this radius. Keep this modest or you’ll get spam. */
    private static final float RADIUS_METERS = 120f;

    /** How long you must loiter inside the fence before DWELL triggers. */
    private static final int DWELL_MS = 30_000;

    private final Context context;
    private final GeofencingClient client;
    private PendingIntent geofencePendingIntent;

    public GeofenceManager(@NonNull Context ctx) {
        this.context = ctx.getApplicationContext();
        this.client = LocationServices.getGeofencingClient(context);

        // Make sure the channel exists before the first background post.
        NotificationHelper.ensureChannel(context, CHANNEL_ID);
    }

    /**
     * Registers geofences for the provided eggs.
     * Call this after you’ve fetched the list and you have location permissions.
     */
    public void registerForEggs(@NonNull List<EggEntry> eggs) {
        if (eggs.isEmpty()) return;

        // Build fences + side metadata we’ll read in the receiver.
        final List<Geofence> fences = new ArrayList<>();
        final Map<String, NearbyEggStore.EggInfo> meta = new HashMap<>();

        int added = 0;
        for (EggEntry e : eggs) {
            if (e == null || e.id == null || e.geo == null) continue;
            if (added >= MAX_FENCES) break;

            fences.add(new Geofence.Builder()
                    .setRequestId(e.id)
                    .setCircularRegion(
                            e.geo.getLatitude(),
                            e.geo.getLongitude(),
                            RADIUS_METERS
                    )
                    .setTransitionTypes(
                            Geofence.GEOFENCE_TRANSITION_ENTER
                                    | Geofence.GEOFENCE_TRANSITION_DWELL
                    )
                    .setLoiteringDelay(DWELL_MS)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build());

            meta.put(e.id, new NearbyEggStore.EggInfo(
                    e.title,
                    e.geo.getLatitude(),
                    e.geo.getLongitude()
            ));
            added++;
        }

        if (fences.isEmpty()) return;

        // Persist metadata for the broadcast receiver to look up titles, etc.
        NearbyEggStore.save(context, meta);

        final GeofencingRequest request = new GeofencingRequest.Builder()
                .setInitialTrigger(
                        GeofencingRequest.INITIAL_TRIGGER_ENTER
                                | GeofencingRequest.INITIAL_TRIGGER_DWELL
                )
                .addGeofences(fences)
                .build();

        // Runtime permission guard — background delivery still requires
        // ACCESS_BACKGROUND_LOCATION in settings, but addGeofences only checks fine location.
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Remove previous registrations tied to this PendingIntent to avoid duplicates,
        // then add the fresh set.
        client.removeGeofences(getGeofencePendingIntent())
                .addOnCompleteListener(t -> client.addGeofences(request, getGeofencePendingIntent())
                        .addOnSuccessListener(unused -> {
                            // OK
                        })
                        .addOnFailureListener(Throwable::printStackTrace));
    }

    /** Removes all geofences registered through this manager (idempotent). */
    public void clearAll() {
        client.removeGeofences(getGeofencePendingIntent());
    }

    /**
     * Explicit broadcast PendingIntent targeting our receiver.
     * This is what allows transitions to fire while the app is closed.
     */
    private PendingIntent getGeofencePendingIntent() {
        if (geofencePendingIntent != null) return geofencePendingIntent;

        Intent intent = new Intent(context, GeofenceBroadcastReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

        geofencePendingIntent = PendingIntent.getBroadcast(
                context,
                /*requestCode*/ 0,
                intent,
                flags
        );
        return geofencePendingIntent;
    }
}
