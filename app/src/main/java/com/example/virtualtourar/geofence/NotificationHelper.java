// app/src/main/java/com/example/virtualtourar/geofence/NotificationHelper.java
package com.example.virtualtourar.geofence;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

class NotificationHelper {
    static void ensureChannel(Context ctx, String channelId) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                channelId, "Egg proximity", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }
}
