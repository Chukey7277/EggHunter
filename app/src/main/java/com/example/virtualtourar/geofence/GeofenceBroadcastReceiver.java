// app/src/main/java/com/example/virtualtourar/geofence/GeofenceBroadcastReceiver.java
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

public class GeofenceBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null || event.hasError()) return;

        int type = event.getGeofenceTransition();
        if (type != Geofence.GEOFENCE_TRANSITION_ENTER
                && type != Geofence.GEOFENCE_TRANSITION_DWELL) return;

        List<Geofence> triggers = event.getTriggeringGeofences();
        if (triggers == null || triggers.isEmpty()) return;

        // Metadata lookup
        Map<String, NearbyEggStore.EggInfo> meta = NearbyEggStore.load(context);

        // Notify for the first triggered id (or you could loop)
        String id = triggers.get(0).getRequestId();
        NearbyEggStore.EggInfo info = meta.get(id);
        String title = (info != null && info.title != null && !info.title.isEmpty())
                ? info.title : "An egg";

        // Tap -> open the AR screen (and we pass the eggId so UI can show details)
        Intent open = new Intent(context, GeospatialActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("openEggId", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, id.hashCode(), open, flags);

        // Build notification (channel created in NotificationHelper)
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, GeofenceManager.CHANNEL_ID)
                // TODO: Replace with your own small icon in res/drawable
                .setSmallIcon(R.drawable.ic_stat_egg)
                .setContentTitle("Egg nearby")
                .setContentText(title + " is close — tap to view!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(content)
                .setVibrate(new long[]{0, 250, 150, 250}); // vibrate pattern

        // Check runtime permission for notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return; // Permission not granted, don't show notification
            }
        }

        NotificationManagerCompat.from(context).notify(id.hashCode(), nb.build());

        // Extra haptic punch (optional)
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
