package com.example.virtualtourar;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import com.example.virtualtourar.data.EggEntry;
import com.example.virtualtourar.data.EggRepository;
import com.example.virtualtourar.geofence.GeofenceManager;
import com.example.virtualtourar.helpers.CameraPermissionHelper;
import com.example.virtualtourar.helpers.DisplayRotationHelper;
import com.example.virtualtourar.helpers.FullScreenHelper;
import com.example.virtualtourar.helpers.LocationPermissionHelper;
import com.example.virtualtourar.helpers.SnackbarHelper;
import com.example.virtualtourar.helpers.TrackingStateHelper;
import com.example.virtualtourar.samplerender.Framebuffer;
import com.example.virtualtourar.samplerender.Mesh;
import com.example.virtualtourar.samplerender.SampleRender;
import com.example.virtualtourar.samplerender.Shader;
import com.example.virtualtourar.samplerender.Texture;
import com.example.virtualtourar.samplerender.arcore.BackgroundRenderer;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Earth;
import com.google.ar.core.Frame;
import com.google.ar.core.GeospatialPose;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.FineLocationPermissionNotGrantedException;
import com.google.ar.core.exceptions.GooglePlayServicesLocationLibraryNotLinkedException;
import com.google.ar.core.exceptions.SessionPausedException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnsupportedConfigurationException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import android.widget.EditText;
import android.text.InputType;








public class GeospatialActivity extends AppCompatActivity
        implements SampleRender.Renderer, PrivacyNoticeDialogFragment.NoticeDialogListener {

    private static final String TAG = "GeospatialActivity";

    // Camera + rendering
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR  = 1000f;

    // Model
    private static final String EGG_MODEL   = "models/egg_2.obj";
    private static final String EGG_TEXTURE = "models/egg_2_colour.jpg";
    private static final float  EGG_SCALE   = 0.01f;
    private static final float  MODEL_LIFT_M = 0.05f; // tiny lift to avoid ground clipping

    // Placement gating (stricter → fewer bad placements)
    private static final double MAX_H_ACC_TO_PLACE = 60.0; // meters
    private static final double MAX_V_ACC_TO_PLACE = 40.0; // meters

    // Optional altitude bias if your building’s alt is systemically off (keep 0 unless needed)
    private static final double ALT_GLOBAL_OFFSET_M = 0.0;

    // Tap picking thresholds
    private static final float PICK_BASE_RADIUS_M   = 0.18f;  // sphere radius at 0 m
    private static final float PICK_RADIUS_PER_M    = 0.030f; // growth per meter distance
    private static final float PICK_MAX_RADIUS_M    = 0.55f;  // clamp
    private static final float PICK_MAX_SCREEN_PX   = 64f;    // extra screen test

    // Proximity nudge (vibration/heads-up when near an egg)
    private static final double NEARBY_RADIUS_M     = 8.0;    // horizontal distance
    private static final double NEARBY_ALT_TOL_M    = 4.0;    // vertical tolerance
    private static final long   NEARBY_VIBRATE_MS   = 35L;
    private static final String NOTIF_CHANNEL_ID    = "egghunter.nearby";
    private static final int    NOTIF_ID_BASE       = 7000;

    // UI
    private GLSurfaceView surfaceView;
    private TextView statusText;

    // ARCore
    private volatile Session session;
    private DisplayRotationHelper displayRotationHelper;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
    private SampleRender render;
    private BackgroundRenderer backgroundRenderer;
    private Framebuffer virtualSceneFramebuffer;
    private boolean hasSetTextureNames = false;
    private boolean backgroundReady = false;

    // Content
    private Mesh   eggMesh;
    private Texture eggTexture;
    private Shader  eggShader;

    // Anchors
    private final Object anchorsLock = new Object();
    @GuardedBy("anchorsLock") private final Map<Anchor, EggEntry> anchorToEgg = new HashMap<>();
    @GuardedBy("anchorsLock") private final HashSet<String>       placedIds   = new HashSet<>();

    // Data
    private EggRepository repository;
    private final List<EggEntry> eggs = new ArrayList<>();

    // Media URL caches (per-egg)
    private final Map<String, Uri> imageUrlCache = new HashMap<>();
    private final Map<String, Uri> audioUrlCache = new HashMap<>();

    // Matrices
    private final float[] modelMatrix = new float[16];
    private final float[] viewMatrix  = new float[16];
    private final float[] projMatrix  = new float[16];
    private final float[] mvMatrix    = new float[16];
    private final float[] mvpMatrix   = new float[16];

    // Tap
    private final Object singleTapLock = new Object();
    @GuardedBy("singleTapLock") private MotionEvent queuedSingleTap;
    private GestureDetector gestureDetector;

    // Privacy
    private static final String ALLOW_GEOSPATIAL_ACCESS_KEY = "ALLOW_GEOSPATIAL_ACCESS";
    private SharedPreferences sharedPreferences;
    private boolean installRequested;

    // Backoff for placement attempts
    private final Map<String, Long> anchorAttemptAtMs = new HashMap<>();
    private static final long ANCHOR_RETRY_MS = 30_000L;

    // Optional micro-stabilizer (translation only)
    private static final float SMOOTHING_ALPHA = 0.65f;
    private final Map<String, float[]> lastStableT = new HashMap<>();

    // Nearby notification gating
    private final Set<String> nearbyNotified = new HashSet<>();

    private static final int REQUEST_CODE = 1;  // Or any unique integer for your permission request

    private static final int REQUEST_BACKGROUND_LOCATION = 101;


    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // must contain @id/surfaceview & @id/status_text_view

        sharedPreferences = getSharedPreferences("GeospatialActivity", Context.MODE_PRIVATE);
        surfaceView = findViewById(R.id.surfaceview);
        statusText  = findViewById(R.id.status_text_view);

        statusText.setClickable(false);
        statusText.setFocusable(false);

        displayRotationHelper = new DisplayRotationHelper(this);
        render = new SampleRender(surfaceView, this, getAssets());
        installRequested = false;

        // Firebase anonymous sign-in (helps Storage getDownloadUrl())
        try {
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnFailureListener(e -> Log.w(TAG, "Firebase anonymous auth failed", e));
        } catch (Throwable t) {
            Log.w(TAG, "FirebaseAuth init skipped", t);
        }

        // Gestures
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) { queuedSingleTap = e; }
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });
        surfaceView.setOnTouchListener((v, ev) -> gestureDetector.onTouchEvent(ev));

        // Fetch eggs
        repository = new EggRepository();
        repository.fetchAllEggs()
                .addOnSuccessListener(list -> {
                    eggs.clear();
                    eggs.addAll(list);
                    prewarmAssets(list);
                    placedIds.clear();
                    nearbyNotified.clear();
                    Log.d(TAG, "Fetched eggs: " + eggs.size());
                    toast("Eggs fetched: " + eggs.size());

                    // for geofence
                    new GeofenceManager(this).registerForEggs(list);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch eggs", e);
                    toast("Failed to load eggs");
                });

        createNotifChannel();
    }

    @Override protected void onResume() {
        super.onResume();
        surfaceView.onResume();
        displayRotationHelper.onResume();

        if (sharedPreferences.getBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, false)) {
            createSession();
        } else {
            showPrivacyNoticeDialog();
        }

        int gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (gms != ConnectionResult.SUCCESS) {
            android.app.Dialog dlg = GoogleApiAvailability.getInstance().getErrorDialog(this, gms, 1001);
            if (dlg != null) dlg.show();
        }

        // Deep-link: open egg details if requested
        String openId = getIntent() != null ? getIntent().getStringExtra("openEggId") : null;
        if (openId != null) {
            EggEntry toOpen = null;
            for (EggEntry e : eggs) { if (openId.equals(e.id)) { toOpen = e; break; } }
            if (toOpen != null) {
                final EggEntry finalToOpen = toOpen;
                runOnUiThread(() -> showEggDialog(finalToOpen));
            }
            getIntent().removeExtra("openEggId");
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (session != null) { try { session.pause(); } catch (Exception ignore) {} }
        surfaceView.onPause();
        displayRotationHelper.onPause();
    }

    @Override protected void onDestroy() {
        if (session != null) {
            surfaceView.queueEvent(() -> { try { session.close(); } catch (Exception ignore) {} });
        }
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    // ---------- permissions ----------

    private void postNearbyNotification(EggEntry e) {
        try {
            Intent intent = new Intent(this, GeospatialActivity.class);
            intent.putExtra("openEggId", e.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, e.id != null ? e.id.hashCode() : 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Nearby egg")
                    .setContentText(e.title != null && !e.title.isEmpty() ? e.title : "Open details")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            // Check if notification permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // If permission is granted, send the notification
                nm.notify(NOTIF_ID_BASE + (e.id != null ? e.id.hashCode() & 0x0FFF : 0x123), b.build());
            } else {
                // If permission is not granted, request permission
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
            }

        } catch (Throwable t) {
            Log.w(TAG, "postNearbyNotification failed (likely missing POST_NOTIFICATIONS on Android 13+).", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        //for background notification
        if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Background location granted — proceed with geofence registration
                // (If needed, re-register geofences here)
            } else {
                Toast.makeText(this, "Background location permission is required for full experience.", Toast.LENGTH_LONG).show();
                // Consider showing rationale or guiding user to Settings
            }
        }
        // Check if camera permission is granted
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
            return;
        }

        // Check if location permission is granted
        if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions)
                && !LocationPermissionHelper.hasFineLocationPermission(this)) {
            Toast.makeText(this, "Precise location permission is required", Toast.LENGTH_LONG).show();
            if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                LocationPermissionHelper.launchPermissionSettings(this);
            }
            finish();
            return;
        }

        // Handle notification permission result (Android 13+)
        if (requestCode == REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        // Permission just granted! If you want to send a notification now,
                        // call your notification logic here, e.g.:
                        // postNearbyNotification(pendingEggEntry);
                        // Only works if you stored which EggEntry to notify about when requesting.
                    } else {
                        Toast.makeText(this, "Notification permission is required to send notifications", Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    }


    // ---------- privacy dialog ----------

    @Override public void onDialogPositiveClick(DialogFragment dialog) {
        if (!sharedPreferences.edit().putBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, true).commit()) {
            throw new AssertionError("Could not save preference");
        }
        createSession();
    }

    private void showPrivacyNoticeDialog() {
        DialogFragment dialog = PrivacyNoticeDialogFragment.createDialog();
        dialog.show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
    }

    // ---------- session ----------

    private void createSession() {
        Exception exception = null; String message = null;

        if (session == null) {
            if (!checkSensorAvailability()) { showSensorUnavailableDialog(); return; }
            try {
                ArCoreApk.InstallStatus status =
                        ArCoreApk.getInstance().requestInstall(this, !installRequested);
                if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true; return;
                }
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this); return;
                }
                if (!LocationPermissionHelper.hasFineLocationPermission(this)) {
                    LocationPermissionHelper.requestFineLocationPermission(this); return;
                }
                session = new Session(this);
            } catch (UnavailableArcoreNotInstalledException e) { message = "Please install ARCore"; exception = e; }
            catch (UnavailableDeviceNotCompatibleException e) { message = "Device not compatible"; exception = e; }
            catch (Exception e) { message = "Failed to create AR session: " + e.getMessage(); exception = e; }
            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, message, exception);
                return;
            }
        }

        try {
            if (!session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                messageSnackbarHelper.showError(this, "Geospatial not supported on this device");
                return;
            }
            Config config = session.getConfig();
            config.setGeospatialMode(Config.GeospatialMode.ENABLED);
            // Faster focus acquisition
            try { config.setFocusMode(Config.FocusMode.AUTO); } catch (Throwable ignore) {}
            // Keep Streetscape/Depth disabled for hunter (we only need camera + geospatial anchors)
            session.configure(config);
            session.resume();
        } catch (CameraNotAvailableException e) { message = "Camera not available"; exception = e; }
        catch (GooglePlayServicesLocationLibraryNotLinkedException e) { message = "Location library not linked"; exception = e; }
        catch (FineLocationPermissionNotGrantedException e) { message = "Location permission required"; exception = e; }
        catch (UnsupportedConfigurationException e) { message = "Unsupported AR configuration"; exception = e; }
        catch (Exception e) { message = "Failed to resume session: " + e.getMessage(); exception = e; }

        if (message != null) {
            messageSnackbarHelper.showError(this, message);
            Log.e(TAG, message, exception);
            session = null;
        }
    }

    private boolean checkSensorAvailability() {
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return false;
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        return acc != null && gyr != null;
    }

    private void showSensorUnavailableDialog() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Sensors Unavailable")
                .setMessage("This application requires an accelerometer and a gyroscope.")
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show());
    }

    // ---------- renderer ----------

    @Override public void onSurfaceCreated(SampleRender render) {
        try {
            if (!verifyAssets()) {
                backgroundReady = false;
                messageSnackbarHelper.showError(this, "Missing shader/model assets. See Logcat.");
                return;
            }

            backgroundRenderer = new BackgroundRenderer(render);
            try { backgroundRenderer.setUseDepthVisualization(render, false); } catch (Throwable ignore) {}
            try { backgroundRenderer.setUseOcclusion(render, false); } catch (Throwable ignore) {}

            virtualSceneFramebuffer = new Framebuffer(render, 1, 1);

            eggMesh = Mesh.createFromAsset(render, EGG_MODEL);
            eggTexture = Texture.createFromAsset(
                    render, EGG_TEXTURE, Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB);
            eggShader = Shader.createFromAssets(
                            render, "shaders/ar_unlit_object.vert", "shaders/ar_unlit_object.frag", null)
                    .setFloat("u_Opacity", 1.0f);

            backgroundReady = true;
        } catch (IOException e) {
            backgroundReady = false;
            Log.e(TAG, "Asset load failed", e);
            messageSnackbarHelper.showError(this, "Missing assets");
        } catch (Throwable t) {
            backgroundReady = false;
            Log.e(TAG, "onSurfaceCreated fatal", t);
        }
    }

    @Override public void onSurfaceChanged(SampleRender render, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        if (virtualSceneFramebuffer != null) virtualSceneFramebuffer.resize(width, height);
    }

    private boolean verifyAssets() {
        String[] required = new String[] {
                "shaders/background_show_camera.vert",
                "shaders/background_show_camera.frag",
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",
                EGG_MODEL,
                EGG_TEXTURE
        };
        boolean ok = true;
        for (String s : required) {
            try (InputStream is = getAssets().open(s)) { /* ok */ }
            catch (Exception e) { ok = false; Log.e(TAG, "❌ Missing asset: " + s, e); }
        }
        return ok;
    }

    @Override public void onDrawFrame(SampleRender render) {
        if (session == null) return;
        if (!backgroundReady || backgroundRenderer == null) return;

        if (!hasSetTextureNames) {
            if (backgroundRenderer.getCameraColorTexture() != null) {
                session.setCameraTextureNames(new int[]{backgroundRenderer.getCameraColorTexture().getTextureId()});
                hasSetTextureNames = true;
            } else {
                return;
            }
        }

        displayRotationHelper.updateSessionIfNeeded(session);

        final Frame frame;
        try {
            frame = session.update();
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available during onDrawFrame", e);
            messageSnackbarHelper.showError(this, "Camera not available.");
            return;
        } catch (SessionPausedException e) {
            return;
        } catch (Throwable t) {
            Log.e(TAG, "session.update failed", t);
            return;
        }

        try { backgroundRenderer.updateDisplayGeometry(frame); } catch (Throwable ignore) {}
        if (frame.getTimestamp() != 0) backgroundRenderer.drawBackground(render);

        Camera camera = frame.getCamera();
        trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());
        if (camera.getTrackingState() != TrackingState.TRACKING) {
            updateEarthStatus(null, null, "Camera not tracking");
            return;
        }

        Earth earth = session.getEarth();
        if (earth == null || earth.getEarthState() != Earth.EarthState.ENABLED) {
            updateEarthStatus(null, null, "Earth: NOT ENABLED (check API key/Cloud)");
            return;
        }
        if (earth.getTrackingState() != TrackingState.TRACKING) {
            updateEarthStatus(earth, null, "Earth: LOCALIZING… walk and look around");
            return;
        }

        GeospatialPose camPose;
        try {
            camPose = earth.getCameraGeospatialPose();
        } catch (SecurityException se) {
            updateEarthStatus(earth, null, "Earth: tracking, but location permission missing");
            return;
        }
        updateEarthStatus(earth, camPose, null);

        // Accuracy gating to avoid wrong floors / stacking
        double hAcc = camPose.getHorizontalAccuracy();
        double vAcc = Double.isNaN(camPose.getVerticalAccuracy()) ? 999 : camPose.getVerticalAccuracy();
        if (hAcc <= MAX_H_ACC_TO_PLACE && vAcc <= MAX_V_ACC_TO_PLACE) {
            placeEggAnchorsExactly(earth, camPose);
            // one-shot proximity nudge
            checkNearbyNudges(camPose);
        } else {
//            updateEarthStatus(earth, camPose,
//                    String.format(Locale.US, "Waiting accuracy… ±H=%.1fm ±V=%.1fm", hAcc, vAcc));
        }

        // Handle tap
        try { handleTap(frame); } catch (Throwable t) { Log.w(TAG, "handleTap failed", t); }

        // Draw
        camera.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR);
        camera.getViewMatrix(viewMatrix, 0);

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
        synchronized (anchorsLock) {
            for (Map.Entry<Anchor, EggEntry> entry : anchorToEgg.entrySet()) {
                Anchor a = entry.getKey();
                if (a.getTrackingState() != TrackingState.TRACKING) continue;

                Pose p = a.getPose();

                // Optional: micro-stabilize translation only (won't “pull” eggs)
                float[] t = p.getTranslation();
                float[] last = lastStableT.get(entry.getValue().id);
                if (last != null && SMOOTHING_ALPHA < 1f) {
                    for (int i = 0; i < 3; i++) t[i] = SMOOTHING_ALPHA * last[i] + (1 - SMOOTHING_ALPHA) * t[i];
                    lastStableT.put(entry.getValue().id, t.clone());
                    p = new Pose(t, new float[]{p.qx(), p.qy(), p.qz(), p.qw()});
                } else {
                    lastStableT.put(entry.getValue().id, t.clone());
                }

                p.toMatrix(modelMatrix, 0);

                // OBJ is Z-up → rotate -90° about X to make it Y-up
                float[] Rx = new float[16];
                Matrix.setRotateM(Rx, 0, -90f, 1f, 0f, 0f);
                Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, Rx, 0);

                // small lift to reduce ground clipping
                Matrix.translateM(modelMatrix, 0, 0f, MODEL_LIFT_M, 0f);

                // scale
                float[] S = new float[16];
                Matrix.setIdentityM(S, 0);
                S[0] = EGG_SCALE; S[5] = EGG_SCALE; S[10] = EGG_SCALE;
                Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, S, 0);

                Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
                Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0);

                if (eggMesh != null && eggTexture != null && eggShader != null) {
                    eggShader.setMat4("u_ModelViewProjection", mvpMatrix);
                    eggShader.setTexture("u_Texture", eggTexture);
                    render.draw(eggMesh, eggShader, virtualSceneFramebuffer);
                }
            }
        }
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
    }

    private void updateEarthStatus(@Nullable Earth earth, @Nullable GeospatialPose pose, @Nullable String override) {
        if (override != null) {
            runOnUiThread(() -> { statusText.setText(override); statusText.setVisibility(View.VISIBLE); });
            return;
        }
        if (earth == null) {
            runOnUiThread(() -> { statusText.setText("Earth: NOT AVAILABLE"); statusText.setVisibility(View.VISIBLE); });
            return;
        }
        final String msg;
        if (earth.getTrackingState() == TrackingState.TRACKING && pose != null) {
            msg = String.format(
                    Locale.US,
                    "Earth: TRACKING ✓  lat=%.6f lon=%.6f  ±H=%.1fm  ±V=%.1fm  ±Head=%.1f°",
                    pose.getLatitude(), pose.getLongitude(),
                    pose.getHorizontalAccuracy(),
                    Double.isNaN(pose.getVerticalAccuracy()) ? -1 : pose.getVerticalAccuracy(),
                    pose.getHeadingAccuracy());
        } else if (earth.getTrackingState() == TrackingState.PAUSED) {
            msg = "Earth: PAUSED";
        } else {
            msg = "Earth: LOCALIZING…";
        }
        runOnUiThread(() -> { statusText.setText(msg); statusText.setVisibility(View.VISIBLE); });
    }

    // ---------- exact anchor placement ----------

    private void placeEggAnchorsExactly(Earth earth, GeospatialPose currentPose) {
        if (eggs.isEmpty()) return;

        final long now = System.currentTimeMillis();

        for (EggEntry e : eggs) {
            if (e == null || e.id == null || e.geo == null) continue;
            if (placedIds.contains(e.id)) continue;

            Long last = anchorAttemptAtMs.get(e.id);
            if (last != null && (now - last) < ANCHOR_RETRY_MS) continue;
            anchorAttemptAtMs.put(e.id, now);

            final double lat = e.geo.getLatitude();
            final double lng = e.geo.getLongitude();

            // Heading → quaternion about +Y (EUS frame uses +Y up)
            final double yawDeg = (e.heading != null) ? e.heading : safeHeadingDeg(currentPose);
            final float[] q = yawToQuaternion((float) yawDeg);

            try {
                if (e.alt != null) {
                    // Saved WGS84 altitude — place exactly at that level (+ optional global tweak).
                    final double alt = e.alt + ALT_GLOBAL_OFFSET_M;
                    Anchor geo = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);
                    synchronized (anchorsLock) {
                        anchorToEgg.put(geo, e);
                        placedIds.add(e.id);
                    }
                    Log.d(TAG, "Placed GEOSPATIAL exact-alt for " + e.id + "  lat=" + lat + " lon=" + lng + " alt=" + alt);
                } else {
                    // No altitude saved — prefer Terrain; fallback to camera altitude.
                    try {
                        earth.resolveAnchorOnTerrainAsync(
                                lat, lng,
                                (float) (currentPose.getAltitude() + ALT_GLOBAL_OFFSET_M),
                                q[0], q[1], q[2], q[3],
                                (anchor, state) -> {
                                    if (state == Anchor.TerrainAnchorState.SUCCESS) {
                                        synchronized (anchorsLock) {
                                            anchorToEgg.put(anchor, e);
                                            placedIds.add(e.id);
                                        }
                                        Log.d(TAG, "Placed TERRAIN for " + e.id);
                                    } else {
                                        try {
                                            double alt = currentPose.getAltitude() + ALT_GLOBAL_OFFSET_M;
                                            Anchor geo = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);
                                            synchronized (anchorsLock) {
                                                anchorToEgg.put(geo, e);
                                                placedIds.add(e.id);
                                            }
                                            Log.w(TAG, "Terrain " + state + "; used GEOSPATIAL(cam alt) for " + e.id);
                                        } catch (Throwable t2) {
                                            Log.w(TAG, "Geospatial fallback failed for " + e.id, t2);
                                        }
                                    }
                                });
                    } catch (Throwable callShapeDiffers) {
                        double alt = currentPose.getAltitude() + ALT_GLOBAL_OFFSET_M;
                        Anchor geo = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);
                        synchronized (anchorsLock) {
                            anchorToEgg.put(geo, e);
                            placedIds.add(e.id);
                        }
                        Log.w(TAG, "resolveAnchorOnTerrainAsync mismatch; used GEOSPATIAL(cam alt) for " + e.id);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Anchor create failed for " + e.id, t);
            }
        }
    }

    // ---------- proximity nudge ----------

    private void checkNearbyNudges(GeospatialPose cam) {
        if (eggs.isEmpty()) return;

        double camLat = cam.getLatitude();
        double camLng = cam.getLongitude();
        double camAlt = cam.getAltitude();

        for (EggEntry e : eggs) {
            if (e == null || e.id == null || e.geo == null) continue;
            if (nearbyNotified.contains(e.id)) continue;

            double dH = haversineMeters(camLat, camLng, e.geo.getLatitude(), e.geo.getLongitude());
            double dV = (e.alt != null) ? Math.abs(camAlt - (e.alt + ALT_GLOBAL_OFFSET_M)) : 0.0;

            if (dH <= NEARBY_RADIUS_M && dV <= NEARBY_ALT_TOL_M) {
                nearbyNotified.add(e.id);
                vibrate(NEARBY_VIBRATE_MS);
                toast("Nearby: " + (e.title != null && !e.title.isEmpty() ? e.title : "an egg"));

                // Optional heads-up notification (no-op if POST_NOTIFICATIONS not granted on Android 13+)
                postNearbyNotification(e);
            }
        }
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        // fast-enough for short distances
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R*c;
    }

    // ---------- tap → ray picking on egg (strict) ----------

    private void handleTap(Frame frame) {
        final MotionEvent tap;
        synchronized (singleTapLock) { tap = queuedSingleTap; queuedSingleTap = null; }
        if (tap == null) return;

        Log.d(TAG, "Handling tap at (" + tap.getX() + ", " + tap.getY() + ")");

        final Camera cam = frame.getCamera();
        if (cam.getTrackingState() != TrackingState.TRACKING) return;

        cam.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR);
        cam.getViewMatrix(viewMatrix, 0);

        // 1) precise ray/sphere pick
        PickResult pr = pickEggByRay(tap.getX(), tap.getY(), viewMatrix, projMatrix,
                surfaceView.getWidth(), surfaceView.getHeight());

        // 2) extra screen-space proximity test to avoid “random” taps
        if (pr != null) {
            float[] screen = new float[2];
            if (projectAnchorToScreen(pr.anchor, viewMatrix, projMatrix, screen)) {
                float dx = tap.getX() - screen[0];
                float dy = tap.getY() - screen[1];
                float distPx = (float) Math.hypot(dx, dy);
//                if (distPx > PICK_MAX_SCREEN_PX) {
//                    pr = null;
//                }
            }
        }

        if (pr != null) {
            final EggEntry hit = pr.egg;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed())
                    showEggDialog(hit);
                Log.d(TAG, "Egg tapped: " + pr.egg.id);
            });
        } else {
            runOnUiThread(() ->
                    Toast.makeText(this, "Tap directly on an egg", Toast.LENGTH_SHORT).show());
            Log.d(TAG, "No egg tapped at location");
        }
    }

    private boolean projectAnchorToScreen(Anchor a, float[] view, float[] proj, float[] out2) {
        if (a == null || a.getTrackingState() != TrackingState.TRACKING) return false;
        Pose pose = a.getPose();
        float[] model = new float[16];
        pose.toMatrix(model, 0);

        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, mvp, 0);

        float[] clip = new float[4];
        Matrix.multiplyMV(clip, 0, mvp, 0, new float[]{0, 0, 0, 1}, 0);

        float w = clip[3];
        if (w == 0f) return false;

        float ndcX = clip[0] / w;
        float ndcY = clip[1] / w;

        if (ndcX < -1.2f || ndcX > 1.2f || ndcY < -1.2f || ndcY > 1.2f) return false;

        int vw = surfaceView.getWidth();
        int vh = surfaceView.getHeight();
        out2[0] = (ndcX + 1f) * 0.5f * vw;
        out2[1] = (1f - ndcY) * 0.5f * vh;
        return true;
    }

    private static class PickResult {
        final Anchor anchor; final EggEntry egg; final float tAlong;
        PickResult(Anchor a, EggEntry e, float t) { anchor = a; egg = e; tAlong = t; }
    }

    /** Ray-sphere pick in world space. Sphere radius ≈ physical model size (very tight). */
    @Nullable
    private PickResult pickEggByRay(float sx, float sy, float[] view, float[] proj, int vw, int vh) {
        // Build inverse VP
        float[] vp = new float[16];
        float[] invVP = new float[16];
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        if (!Matrix.invertM(invVP, 0, vp, 0)) return null;

        // NDC coordinates
        float x = (2f * sx) / vw - 1f;
        float y = 1f - (2f * sy) / vh;

        // Unproject near & far
        float[] near = {x, y, -1f, 1f};
        float[] far  = {x, y,  1f, 1f};
        float[] nearW = new float[4];
        float[] farW  = new float[4];
        Matrix.multiplyMV(nearW, 0, invVP, 0, near, 0);
        Matrix.multiplyMV(farW , 0, invVP, 0, far, 0);
        for (int i=0;i<3;i++){ nearW[i] /= nearW[3]; farW[i] /= farW[3]; }
        float[] o = {nearW[0], nearW[1], nearW[2]};                 // ray origin
        float[] d = {farW[0]-o[0], farW[1]-o[1], farW[2]-o[2]};     // ray dir
        normalize3(d);

        PickResult best = null;
        float bestT = Float.MAX_VALUE;

        synchronized (anchorsLock) {
            if (anchorToEgg.isEmpty()) return null;

            for (Map.Entry<Anchor, EggEntry> entry : anchorToEgg.entrySet()) {
                Anchor a = entry.getKey();
                if (a.getTrackingState() != TrackingState.TRACKING) continue;

                float[] c = a.getPose().getTranslation();

                // ensure in front of camera
                float[] v = {c[0]-o[0], c[1]-o[1], c[2]-o[2]};
                float vd = dot3(v, d);
                if (vd <= 0f) continue;

                float distanceMeters = len3(v);
                float radius = Math.min(PICK_MAX_RADIUS_M,
                        PICK_BASE_RADIUS_M + PICK_RADIUS_PER_M * Math.min(40f, distanceMeters));

                // compute shortest distance from ray to center
                float[] projV = {d[0]*vd, d[1]*vd, d[2]*vd};
                float[] perp  = {v[0]-projV[0], v[1]-projV[1], v[2]-projV[2]};
                float dist = len3(perp);
                if (dist <= radius && vd < bestT) {
                    bestT = vd;
                    best = new PickResult(a, entry.getValue(), vd);
                }
            }
        }
        return best;
    }

    private static void normalize3(float[] v) {
        float l = (float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
        if (l > 1e-6f) { v[0]/=l; v[1]/=l; v[2]/=l; }
    }
    private static float dot3(float[] a, float[] b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static float len3(float[] a){ return (float)Math.sqrt(dot3(a,a)); }

    // ---------- media helpers ----------

    /** Normalize various path styles (quoted, gs://, REST without alt=media). */
    @Nullable
    private String normalizeUrlOrPath(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("https://firebasestorage.googleapis.com/v0/")
                && !s.contains("alt=media")) {
            s = s + (s.contains("?") ? "&" : "?") + "alt=media";
        }
        return s;
    }

    private void prewarmAssets(List<EggEntry> list) {
        for (EggEntry e : list) {
            if (e == null || e.id == null) continue;

            // IMAGE: prefer cardImageUrl, else first photoPaths
            String img = normalizeUrlOrPath(e.cardImageUrl);
            if ((img == null || img.isEmpty()) && e.photoPaths != null && !e.photoPaths.isEmpty()) {
                img = normalizeUrlOrPath(e.photoPaths.get(0));
            }
            if (img != null) {
                if (img.startsWith("http")) {
                    imageUrlCache.put(e.id, Uri.parse(img));
                } else {
                    try {
                        StorageReference ref = img.startsWith("gs://")
                                ? FirebaseStorage.getInstance().getReferenceFromUrl(img)
                                : FirebaseStorage.getInstance().getReference().child(img);
                        ref.getDownloadUrl().addOnSuccessListener(uri -> imageUrlCache.put(e.id, uri));
                    } catch (Throwable t) {
                        Log.w(TAG, "Image prewarm error for " + e.id + " (" + img + ")", t);
                    }
                }
            }

            // AUDIO
            String au = normalizeUrlOrPath(e.audioUrl != null ? e.audioUrl : e.audioPath);
            if (au != null) {
                if (au.startsWith("http")) {
                    audioUrlCache.put(e.id, Uri.parse(au));
                } else {
                    try {
                        StorageReference ref = au.startsWith("gs://")
                                ? FirebaseStorage.getInstance().getReferenceFromUrl(au)
                                : FirebaseStorage.getInstance().getReference().child(au);
                        ref.getDownloadUrl().addOnSuccessListener(uri -> audioUrlCache.put(e.id, uri));
                    } catch (Throwable t) {
                        Log.w(TAG, "Audio prewarm error for " + e.id + " (" + au + ")", t);
                    }
                }
            }
        }
    }

    private void loadIntoImageView(@Nullable String urlOrPath, ImageView target, @Nullable String eggId) {
        if (target == null) return;

        // cache hit
        if (eggId != null) {
            Uri cached = imageUrlCache.get(eggId);
            if (cached != null) {
                Glide.with(this)
                        .load(cached)
                        .placeholder(android.R.drawable.ic_menu_report_image)
                        .error(android.R.drawable.ic_menu_report_image)
                        .into(target);
                return;
            }
        }

        String s = normalizeUrlOrPath(urlOrPath);
        if (s == null) { target.setImageResource(android.R.color.transparent); return; }

        if (s.startsWith("http")) {
            Uri uri = Uri.parse(s);
            if (eggId != null) imageUrlCache.put(eggId, uri);
            Glide.with(this)
                    .load(uri)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(target);
            return;
        }

        try {
            StorageReference ref = s.startsWith("gs://")
                    ? FirebaseStorage.getInstance().getReferenceFromUrl(s)
                    : FirebaseStorage.getInstance().getReference().child(s);
            ref.getDownloadUrl()
                    .addOnSuccessListener(uri -> {
                        if (eggId != null) imageUrlCache.put(eggId, uri);
                        Glide.with(this)
                                .load(uri)
                                .placeholder(android.R.drawable.ic_menu_report_image)
                                .error(android.R.drawable.ic_menu_report_image)
                                .into(target);
                    })
                    .addOnFailureListener(err -> target.setImageResource(android.R.color.transparent));
        } catch (Throwable t) {
            Log.w(TAG, "Image resolve failed: " + s, t);
            target.setImageResource(android.R.color.transparent);
        }
    }

    private interface UriCallback { void accept(Uri uri); }

    private void resolveToStreamUri(@Nullable String urlOrPath, @Nullable String eggId, UriCallback callback) {
        if (callback == null) return;

        if (eggId != null) {
            Uri cached = audioUrlCache.get(eggId);
            if (cached != null) { callback.accept(cached); return; }
        }

        String s = normalizeUrlOrPath(urlOrPath);
        if (s == null) { Log.w(TAG, "resolveToStreamUri: empty/null"); return; }

        if (s.startsWith("http")) {
            Uri uri = Uri.parse(s);
            if (eggId != null) audioUrlCache.put(eggId, uri);
            callback.accept(uri);
            return;
        }

        try {
            StorageReference ref = s.startsWith("gs://")
                    ? FirebaseStorage.getInstance().getReferenceFromUrl(s)
                    : FirebaseStorage.getInstance().getReference().child(s);
            ref.getDownloadUrl()
                    .addOnSuccessListener(uri -> {
                        if (eggId != null) audioUrlCache.put(eggId, uri);
                        callback.accept(uri);
                    })
                    .addOnFailureListener(err -> Log.w(TAG, "Audio URL resolve failed: " + s, err));
        } catch (Throwable t) {
            Log.w(TAG, "Audio resolve error: " + s, t);
        }
    }

    // ---------- utils ----------

    /** heading (deg cw from North) → quaternion about +Y in EUS. */
    private static float[] yawToQuaternion(float yawDeg) {
        float r = (float) Math.toRadians(yawDeg);
        float s = (float) Math.sin(r * 0.5f), c = (float) Math.cos(r * 0.5f);
        // (x,y,z,w); yaw about +Y (up); Earth frame is East(+X), Up(+Y), South(+Z)
        return new float[]{0f, s, 0f, c};
    }

    private static double safeHeadingDeg(@Nullable GeospatialPose gp) {
        if (gp == null) return 0.0;
        double h = gp.getHeading();
        double acc = gp.getHeadingAccuracy();
        if (Double.isNaN(h) || acc <= 0 || acc >= 45.0) return 0.0;
        return h;
    }

    private void vibrate(long ms) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= 26)
                        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                    else v.vibrate(ms);
                }
            }
        } catch (Throwable ignore) {}
    }

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Nearby Eggs", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Alerts when you are near a saved egg");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ---------- dialog ----------

    private void showEggDialog(EggEntry egg) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_egg, null);
        builder.setView(v);

        TextView title = v.findViewById(R.id.eggTitle);
        TextView desc  = v.findViewById(R.id.eggDesc);
        ImageView photo = v.findViewById(R.id.eggPhoto);
        Button playAudio = v.findViewById(R.id.playAudio);

        title.setText(egg.title != null && !egg.title.isEmpty() ? egg.title : "Egg");
        desc.setText(egg.description != null ? egg.description : "");

        // Image: prefer cardImageUrl, else first photoPaths
        String img = (egg.cardImageUrl != null && !egg.cardImageUrl.isEmpty())
                ? egg.cardImageUrl
                : (egg.photoPaths != null && !egg.photoPaths.isEmpty() ? egg.photoPaths.get(0) : null);
        loadIntoImageView(img, photo, egg.id);

        // Audio
        String audio = (egg.audioUrl != null && !egg.audioUrl.isEmpty())
                ? egg.audioUrl
                : egg.audioPath;

        final MediaPlayer[] mp = new MediaPlayer[1];
        if (audio != null && !audio.isEmpty()) {
            playAudio.setVisibility(View.VISIBLE);
            playAudio.setOnClickListener(v1 -> resolveToStreamUri(audio, egg.id, uri -> {
                try {
                    if (mp[0] != null) { try { mp[0].stop(); mp[0].release(); } catch (Exception ignore) {} }
                    mp[0] = new MediaPlayer();
                    mp[0].setDataSource(this, uri);
                    mp[0].setOnPreparedListener(MediaPlayer::start);
                    mp[0].setOnCompletionListener(p -> { try { p.release(); } catch (Exception ignore) {} mp[0] = null; });
                    mp[0].prepareAsync();
                } catch (Exception ex) {
                    Log.e(TAG, "Audio play failed", ex);
                    Toast.makeText(this, "Audio error", Toast.LENGTH_SHORT).show();
                }
            }));
        } else {
            playAudio.setVisibility(View.GONE);
        }

        builder.setPositiveButton("Close", (d, w) -> {
            try { if (mp[0] != null) { mp[0].stop(); mp[0].release(); mp[0] = null; } } catch (Exception ignore) {}
        });
        builder.setOnDismissListener(d -> {
            try { if (mp[0] != null) { mp[0].stop(); mp[0].release(); mp[0] = null; } } catch (Exception ignore) {}
        });
        builder.show();
    }

//    private void showQuizDialog(EggEntry egg) {
////        String question = (egg.quizQuestion != null && !egg.quizQuestion.isEmpty())
////                ? egg.quizQuestion
////                : "Answer this to unlock hidden content:"; // default fallback
////        String correctAnswer = (egg.quizAnswer != null) ? egg.quizAnswer.trim() : "";
//
//        AlertDialog.Builder builder = new AlertDialog.Builder(this);
//        builder.setTitle("Quiz Time!");
//        builder.setMessage(question);
//
//        final EditText input = new EditText(this);
//        input.setInputType(InputType.TYPE_CLASS_TEXT);
//        builder.setView(input);
//
//        builder.setPositiveButton("Submit", null);
//
//        AlertDialog dialog = builder.create();
//        dialog.setOnShowListener(d -> {
//            Button btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
//            btn.setOnClickListener(v -> {
//                String userAnswer = input.getText().toString().trim();
//                if (userAnswer.equalsIgnoreCase(correctAnswer)) {
//                    dialog.dismiss();
//                    showEggDialog(egg);
//                } else {
//                    input.setError("Incorrect! Try again or use the hint.");
//                }
//            });
//        });
//
//        dialog.show();
//    }



    // ---------- helpers ----------

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ---------- EOF ----------
}
