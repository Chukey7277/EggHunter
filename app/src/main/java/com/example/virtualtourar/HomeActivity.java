package com.example.virtualtourar;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

public class HomeActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Register a single multi-permission launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (!hasRequiredPermissions()) {
                        Toast.makeText(this, "Please grant all required permissions", Toast.LENGTH_LONG).show();
                    }
                });

        // Ask once at launch if anything is missing
        if (!hasRequiredPermissions()) requestPermissionsNow();

        // Start AR viewer (the Activity that shows eggs from Firestore)
        MaterialButton start = findViewById(R.id.startArTourButton);
        start.setOnClickListener(v -> {
            if (hasRequiredPermissions()) {
                startActivity(new Intent(this, GeospatialActivity.class));
            } else {
                requestPermissionsNow();
            }
        });

        // If you don't have a collectibles screen yet, hide the button in XML,
        // or wire it to your new list Activity (e.g., EggListActivity).
        findViewById(R.id.collectibles_button).setOnClickListener(v -> {
            startActivity(new Intent(this, CollectiblesActivity.class));
        });

    }

    private boolean hasRequiredPermissions() {
        // Essential only
        return granted(Manifest.permission.CAMERA)
                && granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean granted(@NonNull String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_AUDIO
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    // Manifest.permission.READ_EXTERNAL_STORAGE, // enable if your picker requires it
            });
        }
    }
}
