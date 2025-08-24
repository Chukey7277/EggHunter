// app/src/main/java/com/example/virtualtourar/geofence/BootCompletedReceiver.java
package com.example.virtualtourar.geofence;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.example.virtualtourar.data.EggEntry;
import com.example.virtualtourar.data.EggRepository;

import java.util.List;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) return;
        // Recreate geofences using last known list from Firestore
        // (simple: fetch once; you can also cache locally)
        new EggRepository().fetchAllEggs()
                .addOnSuccessListener((List<EggEntry> eggs) -> new GeofenceManager(context).registerForEggs(eggs));
    }
}
