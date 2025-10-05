package com.example.virtualtourar.geofence;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

class NotificationHelper {

    /**
     * Creates/updates a channel with sane defaults for proximity alerts.
     * Safe to call repeatedly (no-ops if unchanged).
     */
    static void ensureChannel(@NonNull Context ctx, @NonNull String channelId) {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // If channel already exists, you can early-return or update mutable fields (desc).
        NotificationChannel existing = nm.getNotificationChannel(channelId);
        if (existing != null) {
            existing.setDescription("Alerts when you’re near a saved egg");
            nm.createNotificationChannel(existing);
            return;
        }

        NotificationChannel ch = new NotificationChannel(
                channelId,
                "Nearby Eggs",
                NotificationManager.IMPORTANCE_HIGH
        );
        ch.setDescription("Alerts when you’re near a saved egg");
        ch.enableVibration(true);
        ch.enableLights(true);
        ch.setShowBadge(false);           // no launcher badge spam
        ch.setVibrationPattern(new long[]{0, 250, 150, 250});
        nm.createNotificationChannel(ch);
    }
}
