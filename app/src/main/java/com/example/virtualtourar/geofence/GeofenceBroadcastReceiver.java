package com.example.virtualtourar.geofence;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.virtualtourar.GeospatialActivity;
import com.example.virtualtourar.R;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import java.util.List;
import java.util.Map;

/**
 * Receives geofence transitions even when the app is killed.
 * Posts a notification that deep-links into the AR screen.
 */
public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()) return;

        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER
                && transition != Geofence.GEOFENCE_TRANSITION_DWELL) {
            return;
        }

        List<Geofence> triggers = event.getTriggeringGeofences();
        if (triggers == null || triggers.isEmpty()) return;

        // Ensure channel exists (no-op if already there).
        NotificationHelper.ensureChannel(context, GeofenceManager.CHANNEL_ID);

        // Use the first trigger (or loop if you want multiple notifications).
        String id = triggers.get(0).getRequestId();

        // Lookup metadata saved by GeofenceManager.
        Map<String, NearbyEggStore.EggInfo> meta = NearbyEggStore.load(context);
        NearbyEggStore.EggInfo info = meta.get(id);
        String prettyTitle = (info != null && info.title != null && !info.title.isEmpty())
                ? info.title
                : "An egg";

        // Deep link to AR screen with the selected eggId.
        Intent open = new Intent(context, GeospatialActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("openEggId", id);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, id.hashCode(), open, piFlags
        );

        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, GeofenceManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_egg) // make sure this exists
                .setContentTitle("Egg nearby")
                .setContentText(prettyTitle + " is close — tap to view!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setVibrate(new long[]{0, 250, 150, 250});

        // Android 13+ requires POST_NOTIFICATIONS runtime permission.
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        NotificationManagerCompat.from(context).notify(id.hashCode(), nb.build());

        // Optional haptic nudge.
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(250);
                }
            }
        } catch (Throwable ignore) {}
    }
}
