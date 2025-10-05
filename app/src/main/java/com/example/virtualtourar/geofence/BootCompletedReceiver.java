package com.example.virtualtourar.geofence;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import com.example.virtualtourar.data.EggEntry;
import com.example.virtualtourar.data.EggRepository;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Re-registers geofences after device reboot or app update.
 * Works offline from local cache, then refreshes from Firestore if possible.
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        final String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        // Keep the receiver alive while we do async work.
        final PendingResult pr = goAsync();

        // Must already have foreground location permission (can’t prompt at boot).
        boolean hasFine =
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (!hasFine) {
            pr.finish();
            return;
        }

        // 1) Re-register immediately from local cache (offline-friendly).
        tryReRegisterFromCache(context);

        // 2) Refresh from server so the set stays current.
        new EggRepository().fetchAllEggs()
                .addOnSuccessListener(eggs -> {
                    try {
                        new GeofenceManager(context).registerForEggs(eggs);
                    } finally {
                        pr.finish();
                    }
                })
                .addOnFailureListener(err -> {
                    // Silent fail; we’ll refresh next time the app runs.
                    pr.finish();
                });
    }

    private void tryReRegisterFromCache(Context context) {
        Map<String, NearbyEggStore.EggInfo> meta = NearbyEggStore.load(context);
        if (meta == null || meta.isEmpty()) return;

        List<Geofence> fences = new ArrayList<>();
        for (Map.Entry<String, NearbyEggStore.EggInfo> e : meta.entrySet()) {
            NearbyEggStore.EggInfo i = e.getValue();
            fences.add(new Geofence.Builder()
                    .setRequestId(e.getKey())
                    .setCircularRegion(i.lat, i.lng, 120f) // keep in sync with GeofenceManager
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER
                            | Geofence.GEOFENCE_TRANSITION_DWELL)
                    .setLoiteringDelay(30_000)             // keep in sync with GeofenceManager
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build());
        }
        if (fences.isEmpty()) return;

        GeofencingRequest req = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER
                        | GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(fences)
                .build();

        GeofencingClient gc = LocationServices.getGeofencingClient(context);
        PendingIntent pi = buildGeofencePendingIntent(context); // same PI the app uses

        // Make sure we don’t duplicate: remove current set, then add.
        gc.removeGeofences(pi).addOnCompleteListener(t ->
                gc.addGeofences(req, pi)
        );
    }

    /** Builds the same PendingIntent your GeofenceManager uses (must match requestCode & flags). */
    private static PendingIntent buildGeofencePendingIntent(Context context) {
        Intent i = new Intent(context, GeofenceBroadcastReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, 1001, i, flags);
    }
}
