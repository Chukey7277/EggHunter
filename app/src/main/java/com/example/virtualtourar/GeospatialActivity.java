package com.example.virtualtourar;

import android.Manifest;
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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
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
import com.google.firebase.Timestamp;
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

/** Geospatial viewer (SampleRender): now supports Cloud Anchor resolution + Geo/Terrain anchors. */
public class GeospatialActivity extends AppCompatActivity
        implements SampleRender.Renderer, PrivacyNoticeDialogFragment.NoticeDialogListener {

    private static final String TAG = "GeospatialActivity";

    // Camera + rendering
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR  = 1000f;

    // Model
    private static final String EGG_MODEL    = "models/egg_2.obj";
    private static final String EGG_TEXTURE  = "models/egg_2_colour.jpg";
    private static final float  EGG_SCALE    = 0.002f;
    private static final float  MODEL_LIFT_M = 0.01f; // tiny lift; avoids “floating” look

    // Geospatial placement gating
    private static final double MAX_H_ACC_TO_PLACE = 100.0; // meters prev :60
    private static final double MAX_V_ACC_TO_PLACE = 80.0; // meters  prev: 40
    private static final double ALT_GLOBAL_OFFSET_M = 0.0;

    // Tap picking thresholds
    private static final float PICK_BASE_RADIUS_M = 0.24f;
    private static final float PICK_RADIUS_PER_M  = 0.040f;
    private static final float PICK_MAX_RADIUS_M  = 0.55f;

    private long lastUiStatusAt = 0L;
    private static final long UI_STATUS_MS = 400L;

    // Proximity heads-up
    private static final double NEARBY_RADIUS_M  = 8.0;
    private static final double NEARBY_ALT_TOL_M = 4.0;
    private static final long   NEARBY_VIBRATE_MS = 35L;
    private static final String NOTIF_CHANNEL_ID = "egghunter.nearby";
    private static final int    NOTIF_ID_BASE    = 7000;

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

    // PENDING Cloud resolutions (id -> pending record)
    private static class PendingCloud { final Anchor a; final EggEntry e; PendingCloud(Anchor a, EggEntry e){ this.a=a; this.e=e; } }
    private final Map<String, PendingCloud> pendingCloudByEggId = new HashMap<>();

    // Data
    private EggRepository repository;
    private final List<EggEntry> eggs = new ArrayList<>();

    // Media URL caches
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

    // Backoff for placement attempts (geo)
    private final Map<String, Long> anchorAttemptAtMs = new HashMap<>();
    private static final long ANCHOR_RETRY_MS = 30_000L;

    // Optional micro-stabilizer
    private static final float SMOOTHING_ALPHA = 0.65f;
    private final Map<String, float[]> lastStableT = new HashMap<>();

    // Nearby notification gating
    private final Set<String> nearbyNotified = new HashSet<>();

    private static final int REQUEST_CODE = 700;
    private static final int REQUEST_BACKGROUND_LOCATION = 701;

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("GeospatialActivity", Context.MODE_PRIVATE);
        surfaceView = findViewById(R.id.surfaceview);
        statusText  = findViewById(R.id.status_text_view);

        statusText.setClickable(false);
        statusText.setFocusable(false);

        displayRotationHelper = new DisplayRotationHelper(this);
        render = new SampleRender(surfaceView, this, getAssets());
        installRequested = false;

        try {
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnFailureListener(e -> Log.w(TAG, "Firebase anonymous auth failed", e));
        } catch (Throwable t) {
            Log.w(TAG, "FirebaseAuth init skipped", t);
        }

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                    if (queuedSingleTap != null) queuedSingleTap.recycle();
                    queuedSingleTap = MotionEvent.obtain(e);
                }
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });
        gestureDetector.setIsLongpressEnabled(false);
        surfaceView.setOnTouchListener((v, ev) -> { gestureDetector.onTouchEvent(ev); return true; });

        repository = new EggRepository();
        repository.fetchAllEggs()
                .addOnSuccessListener(list -> {
                    eggs.clear();
                    eggs.addAll(list);
                    prewarmAssets(list);
                    placedIds.clear();
                    nearbyNotified.clear();
                    pendingCloudByEggId.clear();
                    Log.d(TAG, "Fetched eggs: " + eggs.size());
                    toast("Eggs fetched: " + eggs.size());
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

        String openId = getIntent() != null ? getIntent().getStringExtra("openEggId") : null;
        if (openId != null) {
            EggEntry toOpen = null;
            for (EggEntry e : eggs) { if (openId.equals(e.id)) { toOpen = e; break; } }
            if (toOpen != null) showEggDialog(toOpen);
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

    // ---------- permissions / notifications ----------

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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                nm.notify(NOTIF_ID_BASE + (e.id != null ? e.id.hashCode() & 0x0FFF : 0x123), b.build());
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "postNearbyNotification failed.", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Background location permission is required for full experience.", Toast.LENGTH_LONG).show();
            }
        }

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
            return;
        }

        if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions)
                && !LocationPermissionHelper.hasFineLocationPermission(this)) {
            Toast.makeText(this, "Precise location permission is required", Toast.LENGTH_LONG).show();
            if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                LocationPermissionHelper.launchPermissionSettings(this);
            }
            finish();
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
            config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED); // needed for resolve
            try { config.setFocusMode(Config.FocusMode.AUTO); } catch (Throwable ignore) {}
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
            } else { return; }
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
        }

        GeospatialPose camPose = null;
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            try { camPose = earth.getCameraGeospatialPose(); } catch (SecurityException ignored) {}
            updateEarthStatus(earth, camPose, null);
        } else if (earth != null) {
            updateEarthStatus(earth, null, "Earth: LOCALIZING… walk and look around");
        }

        // 1) Always resolve Cloud anchors (no GPS accuracy gating).
        try { attemptResolveCloudAnchors(); } catch (Throwable t) { Log.w(TAG, "Cloud resolve loop failed", t); }

        // 2) Place GEOSPATIAL/TERRAIN anchors once accuracy is reasonable.
        if (earth != null && camPose != null) {
            double hAcc = camPose.getHorizontalAccuracy();
            double vAcc = Double.isNaN(camPose.getVerticalAccuracy()) ? 999 : camPose.getVerticalAccuracy();
            if (hAcc <= MAX_H_ACC_TO_PLACE && vAcc <= MAX_V_ACC_TO_PLACE) {
                placeGeoAnchorsExactly(earth, camPose);
                checkNearbyNudges(camPose);
            }
        }

        // 3) Taps
        try { handleTap(frame); } catch (Throwable t) { Log.w(TAG, "handleTap failed", t); }

        // 4) Draw eggs
        camera.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR);
        camera.getViewMatrix(viewMatrix, 0);

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
        synchronized (anchorsLock) {
            for (Map.Entry<Anchor, EggEntry> entry : anchorToEgg.entrySet()) {
                Anchor a = entry.getKey();
                if (a.getTrackingState() != TrackingState.TRACKING) continue;

                Pose p = a.getPose();

                // Optional micro-stabilize translation
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

                // OBJ Z-up → Y-up
                float[] Rx = new float[16];
                Matrix.setRotateM(Rx, 0, -90f, 1f, 0f, 0f);
                Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, Rx, 0);

                Matrix.translateM(modelMatrix, 0, 0f, MODEL_LIFT_M, 0f);
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
        long now = System.currentTimeMillis();
        if (override == null && now - lastUiStatusAt < UI_STATUS_MS) return;
        lastUiStatusAt = now;
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

    // ---------- CLOUD: resolve & track ----------

    private void attemptResolveCloudAnchors() {
        if (eggs.isEmpty() || session == null) return;
        Log.d(TAG, "Attempting to resolve cloud anchors. Total eggs: " + eggs.size());

        // Kick off resolves for eggs that want CLOUD and aren't already pending/placed.
        for (EggEntry e : eggs) {
            Log.d(TAG, "Egg " + e.id + ": anchorType=" + e.anchorType + ", cloudId=" + e.cloudId);
            if (e == null || e.id == null) continue;
            if (placedIds.contains(e.id)) continue;
            if (pendingCloudByEggId.containsKey(e.id)) continue;

            if ("CLOUD".equalsIgnoreCase(safe(e.anchorType))
                    && e.cloudId != null && !e.cloudId.trim().isEmpty()) {
                if (isLikelyExpired(e.cloudHostedAt, e.cloudTtlDays)) {
                    Log.w(TAG, "Cloud anchor likely expired for " + e.id + " — skipping resolve, will fallback to GEO if available.");
                    continue;
                }
                try {
                    Anchor resolving = session.resolveCloudAnchor(e.cloudId.trim());
                    pendingCloudByEggId.put(e.id, new PendingCloud(resolving, e));
                    Log.d(TAG, "Resolving Cloud Anchor for " + e.id);
                } catch (Throwable t) {
                    Log.w(TAG, "resolveCloudAnchor failed for " + e.id, t);
                }
            }
        }

        // Poll states for all pending resolutions
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, PendingCloud> kv : pendingCloudByEggId.entrySet()) {
            String eggId = kv.getKey();
            PendingCloud pc = kv.getValue();
            Anchor a = pc.a;

            Anchor.CloudAnchorState st = a.getCloudAnchorState();
            switch (st) {
                case SUCCESS:
                    synchronized (anchorsLock) {
                        anchorToEgg.put(a, pc.e);
                        placedIds.add(eggId);
                    }
                    done.add(eggId);
                    Log.d(TAG, "Cloud resolve SUCCESS for " + eggId);
                    break;
                case NONE:
                case TASK_IN_PROGRESS:
                    // still resolving
                    break;
                default:
                    // Some error — detach and fall back (if GEO available)
                    Log.w(TAG, "Cloud resolve error " + st + " for " + eggId);
                    try { a.detach(); } catch (Throwable ignore) {}
                    done.add(eggId);
                    break;
            }
        }
        for (String id : done) pendingCloudByEggId.remove(id);
    }

    private static boolean isLikelyExpired(@Nullable Timestamp hostedAt, @Nullable Long ttlDays) {
        if (hostedAt == null || ttlDays == null) return false;
        long start = hostedAt.toDate().getTime();
        long ttlMs = ttlDays * 24L * 60L * 60L * 1000L;
        return System.currentTimeMillis() > (start + ttlMs);
    }

    // ---------- GEO: exact/terrain anchor placement ----------

    private void placeGeoAnchorsExactly(@Nullable Earth earth, @Nullable GeospatialPose currentPose) {
        Log.d(TAG, "Placing geo anchors. Accuracy: H=" + currentPose.getHorizontalAccuracy() +
                ", V=" + currentPose.getVerticalAccuracy());
        if (earth == null || currentPose == null || eggs.isEmpty()) return;

        final long now = System.currentTimeMillis();

        for (EggEntry e : eggs) {
            if (e == null || e.id == null) continue;
            if (placedIds.contains(e.id)) continue;

            // Skip eggs that demand CLOUD (we resolve them separately)
            if ("CLOUD".equalsIgnoreCase(safe(e.anchorType))) continue;

            // Need geospatial coordinates
            if (e.geo == null) continue;

            Long last = anchorAttemptAtMs.get(e.id);
            if (last != null && (now - last) < ANCHOR_RETRY_MS) continue;
            anchorAttemptAtMs.put(e.id, now);

            final double lat = e.geo.getLatitude();
            final double lng = e.geo.getLongitude();
            final double yawDeg = (e.heading != null) ? e.heading : safeHeadingDeg(currentPose);
            final float[] q = yawToQuaternion((float) yawDeg);

            try {
                if (e.alt != null) {
                    final double alt = e.alt + ALT_GLOBAL_OFFSET_M;
                    Anchor geo = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);
                    synchronized (anchorsLock) {
                        anchorToEgg.put(geo, e);
                        placedIds.add(e.id);
                    }
                    Log.d(TAG, "Placed GEOSPATIAL exact-alt for " + e.id);
                } else {
                    // Try terrain (good outdoors). If not success, DO NOT fall back to camera-alt (prevents floating).
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
                                    Log.w(TAG, "Terrain " + state + " for " + e.id + "; skipping camera-alt fallback to avoid float.");
                                    // We’ll try again later when Earth improves.
                                }
                            });
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
                postNearbyNotification(e);
            }
        }
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R*c;
    }

    // ---------- tap → ray picking ----------

    private void handleTap(Frame frame) {
        final MotionEvent tap;
        synchronized (singleTapLock) { tap = queuedSingleTap; queuedSingleTap = null; }
        if (tap == null) return;

        final Camera cam = frame.getCamera();
        if (cam.getTrackingState() != TrackingState.TRACKING) return;

        cam.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR);
        cam.getViewMatrix(viewMatrix, 0);

        PickResult pr = pickEggByRay(tap.getX(), tap.getY(), viewMatrix, projMatrix,
                surfaceView.getWidth(), surfaceView.getHeight());

        if (pr != null) {
            final EggEntry hit = pr.egg;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) showEggOrQuiz(hit);
            });
        } else {
            runOnUiThread(() ->
                    Toast.makeText(this, "Tap directly on an egg", Toast.LENGTH_SHORT).show());
        }
    }

    private static class PickResult {
        final Anchor anchor; final EggEntry egg; final float tAlong;
        PickResult(Anchor a, EggEntry e, float t) { anchor = a; egg = e; tAlong = t; }
    }

    @Nullable
    private PickResult pickEggByRay(float sx, float sy, float[] view, float[] proj, int vw, int vh) {
        float[] vp = new float[16];
        float[] invVP = new float[16];
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        if (!Matrix.invertM(invVP, 0, vp, 0)) return null;

        float x = (2f * sx) / vw - 1f;
        float y = 1f - (2f * sy) / vh;

        float[] near = {x, y, -1f, 1f};
        float[] far  = {x, y,  1f, 1f};
        float[] nearW = new float[4];
        float[] farW  = new float[4];
        Matrix.multiplyMV(nearW, 0, invVP, 0, near, 0);
        Matrix.multiplyMV(farW , 0, invVP, 0, far, 0);
        for (int i=0;i<3;i++){ nearW[i] /= nearW[3]; farW[i] /= farW[3]; }
        float[] o = {nearW[0], nearW[1], nearW[2]};
        float[] d = {farW[0]-o[0], farW[1]-o[1], farW[2]-o[2]};
        normalize3(d);

        PickResult best = null;
        float bestT = Float.MAX_VALUE;

        synchronized (anchorsLock) {
            if (anchorToEgg.isEmpty()) return null;

            for (Map.Entry<Anchor, EggEntry> entry : anchorToEgg.entrySet()) {
                Anchor a = entry.getKey();
                if (a.getTrackingState() != TrackingState.TRACKING) continue;

                float[] c = a.getPose().getTranslation();

                float[] v = {c[0]-o[0], c[1]-o[1], c[2]-o[2]};
                float vd = dot3(v, d);
                if (vd <= 0f) continue;

                float distanceMeters = len3(v);
                float radius = Math.min(PICK_MAX_RADIUS_M,
                        PICK_BASE_RADIUS_M + PICK_RADIUS_PER_M * Math.min(40f, distanceMeters));

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

            String img = normalizeUrlOrPath(
                    (e.cardImageUrl != null && !e.cardImageUrl.isEmpty())
                            ? e.cardImageUrl
                            : (e.photoPaths != null && !e.photoPaths.isEmpty() ? e.photoPaths.get(0) : null));

            if (img != null) {
                if (img.startsWith("http")) {
                    Uri uri = Uri.parse(img);
                    imageUrlCache.put(e.id, uri);
                    Glide.with(getApplicationContext())
                            .load(uri).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                            .timeout(20_000)
                            .preload();
                } else {
                    try {
                        StorageReference ref = img.startsWith("gs://")
                                ? FirebaseStorage.getInstance().getReferenceFromUrl(img)
                                : FirebaseStorage.getInstance().getReference().child(img);
                        ref.getDownloadUrl().addOnSuccessListener(uri -> {
                            imageUrlCache.put(e.id, uri);
                            Glide.with(getApplicationContext())
                                    .load(uri).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                    .timeout(20_000).preload();
                        });
                    } catch (Throwable t) {
                        Log.w(TAG, "Image prewarm error for " + e.id + " (" + img + ")", t);
                    }
                }
            }

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

        Uri cached = (eggId != null) ? imageUrlCache.get(eggId) : null;
        if (cached != null) {
            Glide.with(this)
                    .load(cached)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .timeout(20_000)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_menu_report_image)
                    .dontAnimate()
                    .into(target);
            return;
        }

        String s = normalizeUrlOrPath(urlOrPath);
        if (s == null) { target.setImageResource(android.R.color.transparent); return; }

        if (s.startsWith("http")) {
            Uri uri = Uri.parse(s);
            if (eggId != null) imageUrlCache.put(eggId, uri);
            Glide.with(this)
                    .load(uri)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .timeout(20_000)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_menu_report_image)
                    .dontAnimate()
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
                                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                .timeout(20_000)
                                .placeholder(android.R.drawable.ic_menu_report_image)
                                .error(android.R.drawable.ic_menu_report_image)
                                .dontAnimate()
                                .into(target);
                    })
                    .addOnFailureListener(err -> target.setImageResource(android.R.color.transparent));
        } catch (Throwable t) {
            Log.w(TAG, "Image resolve failed: " + s, t);
            target.setImageResource(android.R.color.transparent);
        }
    }

    private interface UriCallback { void accept(Uri uri); }

    private void resolveToStreamUri(@Nullable String urlOrPath,
                                    @Nullable String eggId,
                                    UriCallback callback) {
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
                    .addOnFailureListener(err -> {
                        Log.w(TAG, "Audio URL resolve failed: " + s, err);
                        new android.os.Handler(getMainLooper()).postDelayed(() ->
                                        ref.getDownloadUrl()
                                                .addOnSuccessListener(uri -> {
                                                    if (eggId != null) audioUrlCache.put(eggId, uri);
                                                    callback.accept(uri);
                                                })
                                                .addOnFailureListener(e2 ->
                                                        Log.w(TAG, "Audio resolve retry failed: " + s, e2))
                                , 1000);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Audio resolve error: " + s, t);
        }
    }

    // ---------- utils ----------

    private static String safe(@Nullable String s){ return s == null ? "" : s; }

    /** heading (deg cw from North) → quaternion about +Y in EUS. */
    private static float[] yawToQuaternion(float yawDeg) {
        float r = (float) Math.toRadians(yawDeg);
        float s = (float) Math.sin(r * 0.5f), c = (float) Math.cos(r * 0.5f);
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

    private void showEggDialog(EggEntry egg) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_egg, null);
        builder.setView(v);

        TextView title   = v.findViewById(R.id.eggTitle);
        TextView desc    = v.findViewById(R.id.eggDesc);
        View audioSec    = v.findViewById(R.id.audioSection);
        com.google.android.material.button.MaterialButton btnViewImage = v.findViewById(R.id.btnViewImage);
        com.google.android.material.button.MaterialButton btnPlayPause = v.findViewById(R.id.btnPlayPause);
        android.widget.ProgressBar audioLoading = v.findViewById(R.id.audioLoading);
        android.widget.SeekBar seek = v.findViewById(R.id.seekAudio);
        TextView txtElapsed = v.findViewById(R.id.txtElapsed);
        TextView txtDuration = v.findViewById(R.id.txtDuration);

        title.setText(egg.title != null && !egg.title.isEmpty() ? egg.title : "Egg");
        desc.setText(egg.description != null ? egg.description : "");

        // ---- Image: show "View image" only if present ----
        String img = (egg.cardImageUrl != null && !egg.cardImageUrl.isEmpty())
                ? egg.cardImageUrl
                : (egg.photoPaths != null && !egg.photoPaths.isEmpty() ? egg.photoPaths.get(0) : null);

        final Uri[] imageToShow = { null };

        if (img != null && !img.trim().isEmpty()) {
            btnViewImage.setVisibility(View.VISIBLE);

            // Try cache first
            Uri cached = (egg.id != null) ? imageUrlCache.get(egg.id) : null;
            if (cached != null) {
                imageToShow[0] = cached;
            } else {
                // Resolve (supports http, gs://, or storage path)
                String s = normalizeUrlOrPath(img);
                if (s != null) {
                    if (s.startsWith("http")) {
                        imageToShow[0] = Uri.parse(s);
                    } else {
                        try {
                            com.google.firebase.storage.StorageReference ref = s.startsWith("gs://")
                                    ? com.google.firebase.storage.FirebaseStorage.getInstance().getReferenceFromUrl(s)
                                    : com.google.firebase.storage.FirebaseStorage.getInstance().getReference().child(s);
                            ref.getDownloadUrl().addOnSuccessListener(uri -> {
                                if (egg.id != null) imageUrlCache.put(egg.id, uri);
                                imageToShow[0] = uri;
                            });
                        } catch (Throwable ignore) {}
                    }
                }
            }

            btnViewImage.setOnClickListener(vw -> {
                if (imageToShow[0] != null) {
                    ImageViewerDialogFragment.newInstance(imageToShow[0])
                            .show(getSupportFragmentManager(), "image_viewer");
                } else {
                    Toast.makeText(this, "Loading image…", Toast.LENGTH_SHORT).show();
                    // Best effort resolve again if not ready
                    String s2 = normalizeUrlOrPath(img);
                    if (s2 != null) {
                        if (s2.startsWith("http")) {
                            imageToShow[0] = Uri.parse(s2);
                            ImageViewerDialogFragment.newInstance(imageToShow[0])
                                    .show(getSupportFragmentManager(), "image_viewer");
                        } else {
                            try {
                                com.google.firebase.storage.StorageReference ref = s2.startsWith("gs://")
                                        ? com.google.firebase.storage.FirebaseStorage.getInstance().getReferenceFromUrl(s2)
                                        : com.google.firebase.storage.FirebaseStorage.getInstance().getReference().child(s2);
                                ref.getDownloadUrl().addOnSuccessListener(uri -> {
                                    if (egg.id != null) imageUrlCache.put(egg.id, uri);
                                    imageToShow[0] = uri;
                                    ImageViewerDialogFragment.newInstance(uri)
                                            .show(getSupportFragmentManager(), "image_viewer");
                                }).addOnFailureListener(err ->
                                        Toast.makeText(this, "Image unavailable", Toast.LENGTH_SHORT).show());
                            } catch (Throwable t) {
                                Toast.makeText(this, "Image unavailable", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
            });
        } else {
            btnViewImage.setVisibility(View.GONE);
        }

        // ---- Audio ----
        String audio = (egg.audioUrl != null && !egg.audioUrl.isEmpty()) ? egg.audioUrl : egg.audioPath;
        final MediaPlayer[] mp = new MediaPlayer[1];
        final boolean[] userSeeking = { false };
        final android.os.Handler handler = new android.os.Handler(getMainLooper());

        Runnable tick = new Runnable() {
            @Override public void run() {
                if (mp[0] != null && mp[0].isPlaying() && !userSeeking[0]) {
                    int pos = mp[0].getCurrentPosition();
                    int dur = mp[0].getDuration();
                    seek.setProgress(dur > 0 ? (int) (1000L * pos / Math.max(dur,1)) : 0);
                    txtElapsed.setText(fmtTime(pos));
                    txtDuration.setText(fmtTime(dur));
                    handler.postDelayed(this, 500);
                }
            }
        };

        if (audio != null && !audio.isEmpty()) {
            audioSec.setVisibility(View.VISIBLE);
            btnPlayPause.setText("Play");
            btnPlayPause.setIconResource(R.drawable.ic_outline_play_arrow_24);

            // Seekbar interactions
            seek.setMax(1000);
            seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && mp[0] != null) {
                        int dur = mp[0].getDuration();
                        int newPos = (int) ((progress / 1000f) * dur);
                        txtElapsed.setText(fmtTime(newPos));
                    }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { userSeeking[0] = true; }
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    if (mp[0] != null) {
                        int dur = mp[0].getDuration();
                        int newPos = (int) ((sb.getProgress() / 1000f) * dur);
                        mp[0].seekTo(newPos);
                    }
                    userSeeking[0] = false;
                }
            });

            btnPlayPause.setOnClickListener(vv -> {
                if (mp[0] != null && mp[0].isPlaying()) {
                    try { mp[0].pause(); } catch (Exception ignore) {}
                    btnPlayPause.setText("Play");
                    btnPlayPause.setIconResource(R.drawable.ic_outline_play_arrow_24);
                    return;
                }

                if (mp[0] != null) {
                    try { mp[0].start(); } catch (Exception ignore) {}
                    btnPlayPause.setText("Pause");
                    btnPlayPause.setIconResource(R.drawable.ic_outline_pause_24);
                    handler.post(tick);
                    return;
                }

                // First time: resolve + prepare
                audioLoading.setVisibility(View.VISIBLE);
                btnPlayPause.setEnabled(false);

                resolveToStreamUri(audio, egg.id, uri -> runOnUiThread(() -> {
                    try {
                        MediaPlayer p = new MediaPlayer();
                        if (Build.VERSION.SDK_INT >= 21) {
                            p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .build());
                        }
                        p.setDataSource(this, uri);
                        p.setOnPreparedListener(player -> {
                            audioLoading.setVisibility(View.GONE);
                            btnPlayPause.setEnabled(true);
                            txtDuration.setText(fmtTime(player.getDuration()));
                            player.start();
                            btnPlayPause.setText("Pause");
                            btnPlayPause.setIconResource(R.drawable.ic_outline_pause_24);
                            handler.post(tick);
                        });
                        p.setOnCompletionListener(player -> {
                            try { player.seekTo(0); } catch (Exception ignore) {}
                            seek.setProgress(0);
                            txtElapsed.setText("0:00");
                            btnPlayPause.setText("Play");
                            btnPlayPause.setIconResource(R.drawable.ic_outline_play_arrow_24);
                        });
                        p.setOnErrorListener((player, what, extra) -> {
                            Toast.makeText(this, "Audio error", Toast.LENGTH_SHORT).show();
                            audioLoading.setVisibility(View.GONE);
                            btnPlayPause.setEnabled(true);
                            return true;
                        });
                        mp[0] = p;
                        p.prepareAsync();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Audio error", Toast.LENGTH_SHORT).show();
                        audioLoading.setVisibility(View.GONE);
                        btnPlayPause.setEnabled(true);
                    }
                }));
            });
        } else {
            audioSec.setVisibility(View.GONE);
        }

        builder.setPositiveButton("Close", (d, w) -> {
            try { if (mp[0] != null) { mp[0].stop(); mp[0].release(); mp[0] = null; } } catch (Exception ignore) {}
            handler.removeCallbacksAndMessages(null);
        });

        builder.setOnDismissListener(d -> {
            try { if (mp[0] != null) { mp[0].stop(); mp[0].release(); mp[0] = null; } } catch (Exception ignore) {}
            handler.removeCallbacksAndMessages(null);
        });

        builder.show();
    }

    private static String fmtTime(int ms) {
        if (ms < 0) ms = 0;
        int totalSec = ms / 1000;
        int m = totalSec / 60;
        int s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }


    private void showEggOrQuiz(EggEntry egg) {
        if (egg != null && egg.hasQuiz()) {
            EggEntry.QuizQuestion q = egg.quiz.get((int)(Math.random() * egg.quiz.size()));
            showQuizDialog(q, () -> showEggDialog(egg));
        } else {
            showEggDialog(egg);
        }
    }

    private void showQuizDialog(EggEntry.QuizQuestion q, Runnable onPassed) {
        if (q == null || q.options == null || q.options.isEmpty()) {
            if (onPassed != null) onPassed.run();
            return;
        }

        final String question = (q.q != null && !q.q.isEmpty()) ? q.q : "Answer the question";
        final CharSequence[] items = q.options.toArray(new CharSequence[0]);
        final int correctIndex = (q.answer != null) ? q.answer.intValue() : 0;
        final int[] chosen = {-1};

        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(16);
        header.setPadding(pad, pad, pad, pad);

        android.widget.TextView titleTv = new android.widget.TextView(this);
        titleTv.setText("Quick quiz to unlock");
        titleTv.setTextSize(18f);
        titleTv.setTypeface(titleTv.getTypeface(), android.graphics.Typeface.BOLD);

        android.widget.TextView questionTv = new android.widget.TextView(this);
        questionTv.setText(question);
        questionTv.setTextSize(16f);
        questionTv.setPadding(0, dp(6), 0, 0);
        questionTv.setLineSpacing(0f, 1.1f);

        header.addView(titleTv);
        header.addView(questionTv);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setSingleChoiceItems(items, -1, (dlg, which) -> chosen[0] = which)
                .setPositiveButton("Submit", (dlg, w) -> {
                    if (chosen[0] == -1) {
                        android.widget.Toast.makeText(this, "Please pick an answer.", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (chosen[0] == correctIndex) {
                        android.widget.Toast.makeText(this, "Correct! Unlocked.", android.widget.Toast.LENGTH_SHORT).show();
                        if (onPassed != null) onPassed.run();
                    } else {
                        android.widget.Toast.makeText(this, "Not quite. Try again.", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
}
