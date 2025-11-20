// GeospatialActivity.java
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
import android.view.ScaleGestureDetector;
import android.view.View;
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
import com.example.virtualtourar.geofence.GeofenceManager; // for geofencing
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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Geospatial viewer (SampleRender): Cloud resolve + GEO/TERRAIN + facade-aware orientation + quiz gating + egg-layer digicam zoom. */
public class GeospatialActivity extends AppCompatActivity
        implements SampleRender.Renderer, PrivacyNoticeDialogFragment.NoticeDialogListener {

    private static final String TAG = "GeospatialActivity";

    // Camera + rendering
    private static final float Z_NEAR = 0.10f;
    private static final float Z_FAR  = 60f; // ↑ allow farther targets without confusion

    // Model asset
    private static final String EGG_MODEL    = "models/star.obj";
    private static final String EGG_TEXTURE  = "models/Image_0.png";

    // ---- Model tuning (global defaults) ----
    private static final float MODEL_SCALE_DEFAULT = 0.020f;   // was 0.06f → half the size
    private static final float MODEL_LIFT_M        = 0.01f;

    // Optional: gentle auto-scaling so near objects aren't gigantic
    private static final float MODEL_SCALE_BASE     = 0.008f;  // minimum baseline
    private static final float MODEL_SCALE_K_PER_M  = 0.007f;  // how much scale grows per meter
    private static final float MODEL_SCALE_MIN      = 0.005f;  // clamp small
    private static final float MODEL_SCALE_MAX      = 0.080f;  // clamp large

    // OBJ orientation defaults
    private static final float MODEL_ROT_X_DEG = 0f;
    private static final float MODEL_ROT_Y_DEG = 0f;
    private static final float MODEL_ROT_Z_DEG = 0f;

    // Camera "digicam" zoom via BackgroundRenderer UV crop ONLY
    private float userScaleMultiplier = 1.0f;
    private static final float USER_SCALE_MIN = 0.30f;
    private static final float USER_SCALE_MAX = 3.00f;

    // Placement gating (TIGHTER)
    private static final double MAX_H_ACC_TO_PLACE = 15.0;  // was 12.0
    private static final double MAX_V_ACC_TO_PLACE = 12.0;  // was 10.0
    private static final double HEADING_MAX_ACC_DEG = 20.0; // was 25.0
    private static final double ALT_GLOBAL_OFFSET_M = 0.0;
    private static final long   LOCALIZE_STABLE_MS  = 400L; // was 1500L
    private long lastAccOkayAtMs = 0L;
    // Increase "grace" before force-placing a bit
    private static final long   STUCK_FORCE_PLACE_MS = 4_000L; // was 9000L

    // Smoothed camera geospatial pose (EMA)
    private static final double EMA_ALPHA = 0.35;
    private static final int    MIN_STABLE_SAMPLES = 3; // was 5
    private int emaSamples = 0;
    private double emaLat, emaLng, emaAlt;
    private Double lastGoodYawDeg = null;

    // Re-localize (STRicter)
    private static final double RELOCALIZE_IF_ERROR_M = 1.0; // was 3.0
    private static final long   RELOCALIZE_BACKOFF_MS = 20_000L;
    private final Map<String, Long> lastRelocAttemptAt = new HashMap<>();

    // Tap picking
    private static final float PICK_BASE_RADIUS_M = 0.24f;
    private static final float PICK_RADIUS_PER_M  = 0.040f;
    private static final float PICK_MAX_RADIUS_M  = 0.55f;

    private long lastUiStatusAt = 0L;
    private static final long UI_STATUS_MS = 400L;

    // Proximity nudge (in-session only)
    private static final double NEARBY_RADIUS_M  = 8.0;
    private static final double NEARBY_ALT_TOL_M = 4.0;
    private static final long   NEARBY_VIBRATE_MS = 35L;
    private static final String NOTIF_CHANNEL_ID = "egghunter.nearby";
    private static final int    NOTIF_ID_BASE    = 7000;

    // Rough object-space bounding radius of your star.obj (units before scale)
    // Rough unscaled radii for picking (object-space)
    private static final float STAR_RADIUS_UNSCALED   = 0.50f;
    private static final float PUZZLE_RADIUS_UNSCALED = 0.45f;
    private static final float PICK_MARGIN_M = 0.10f; // keep // tweak if needed
    // Extra margin so you can hit the tips more easily

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
    private Mesh    puzzleMesh;
    private Texture puzzleTexture;
    private Shader  puzzleShader;

    // Anchors
    private final Object anchorsLock = new Object();
    @GuardedBy("anchorsLock") private final Map<Anchor, EggEntry> anchorToEgg = new HashMap<>();
    @GuardedBy("anchorsLock") private final HashSet<String>       placedIds   = new HashSet<>();

    // NEW: single-owner maps to ensure one anchor per egg
    @GuardedBy("anchorsLock") private final Map<String, Anchor> anchorByEggId = new HashMap<>();
    @GuardedBy("anchorsLock") private final Map<String, String> anchorKindByEggId = new HashMap<>(); // "CLOUD" | "GEO"

    // Pending Cloud
    private static class PendingCloud {
        final Anchor a; final EggEntry e; final long startedAt = System.currentTimeMillis();
        PendingCloud(Anchor a, EggEntry e){ this.a=a; this.e=e; }
    }
    private static final long CLOUD_RESOLVE_FALLBACK_MS = 10_000L; // 10s
    private final Map<String, PendingCloud> pendingCloudByEggId = new HashMap<>();

    // GEO fallback
    private final Set<String> allowGeoFallbackIds = new HashSet<>();

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

    // Gestures
    private final Object singleTapLock = new Object();
    @GuardedBy("singleTapLock") private MotionEvent queuedSingleTap;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    // Privacy
    private static final String ALLOW_GEOSPATIAL_ACCESS_KEY = "ALLOW_GEOSPATIAL_ACCESS";
    private SharedPreferences sharedPreferences;
    private boolean installRequested;

    // Retry backoff
    private final Map<String, Long> anchorAttemptAtMs = new HashMap<>();
    private static final long ANCHOR_RETRY_MS = 30_000L;

    // Nearby notifications (in-session)
    private final Set<String> nearbyNotified = new HashSet<>();

    private static final int REQUEST_CODE = 700;
    private static final int REQUEST_BACKGROUND_LOCATION = 701;

    private static final String PUZZLE_MODEL   = "models/magnifying_glass1.obj";
    private static final String PUZZLE_TEXTURE = "models/Image_01.png";
    private static final float STAR_VISUAL_MULT   = 0.48f; // shrink star ~25%
    private static final float PUZZLE_VISUAL_MULT = 5.10f; // enlarge magnifier ~40%

    private static final class PoseLite {
        final double lat, lng, alt, hAcc, vAcc, heading, headingAcc;
        PoseLite(double lat, double lng, double alt,
                 double hAcc, double vAcc, double heading, double headingAcc) {
            this.lat = lat; this.lng = lng; this.alt = alt;
            this.hAcc = hAcc; this.vAcc = vAcc; this.heading = heading; this.headingAcc = headingAcc;
        }
    }

    // ------- user-friendly wait hints -------
    private long resumedAtMs = 0L;
    private boolean waitToastShown = false;
    private boolean anchorLoadingHintShown = false;
    // At top-level fields
    private volatile boolean noInternetDialogShown = false;
    @Nullable private AlertDialog noInternetDialog = null;

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

        // Gestures
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                    if (queuedSingleTap != null) queuedSingleTap.recycle();
                    queuedSingleTap = MotionEvent.obtain(e);
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                userScaleMultiplier = 1.0f;
                if (backgroundRenderer != null) {
                    surfaceView.queueEvent(() -> backgroundRenderer.setCameraZoom(1.0f));
                }
                toast("Zoom reset");
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });
        gestureDetector.setIsLongpressEnabled(false);

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (Float.isNaN(factor) || Float.isInfinite(factor)) return false;
                userScaleMultiplier *= factor;
                if (userScaleMultiplier < USER_SCALE_MIN) userScaleMultiplier = USER_SCALE_MIN;
                if (userScaleMultiplier > USER_SCALE_MAX) userScaleMultiplier = USER_SCALE_MAX;
                if (backgroundRenderer != null) {
                    final float camZoom = userScaleMultiplier;
                    surfaceView.queueEvent(() -> backgroundRenderer.setCameraZoom(camZoom));
                }
                return true;
            }
        });

        surfaceView.setOnTouchListener((v, ev) -> {
            boolean a = scaleGestureDetector.onTouchEvent(ev);
            boolean b = gestureDetector.onTouchEvent(ev);
            return a || b;
        });

        repository = new EggRepository();
        repository.fetchAllEggs()
                .addOnSuccessListener(list -> {
                    eggs.clear();
                    eggs.addAll(list);
                    prewarmAssets(list);
                    synchronized (anchorsLock) {
                        placedIds.clear();
                        anchorByEggId.clear();
                        anchorKindByEggId.clear();
                        anchorToEgg.clear();
                    }
                    nearbyNotified.clear();
                    pendingCloudByEggId.clear();
                    allowGeoFallbackIds.clear();
                    Log.d(TAG, "Fetched eggs: " + eggs.size());
                    toast("Eggs fetched: " + eggs.size());

                    // Register geofences so notifications work when the app is NOT open
                    maybeRequestBackgroundLocation();
                    try {
                        new GeofenceManager(getApplicationContext()).registerForEggs(eggs);
                    } catch (Throwable t) {
                        Log.w(TAG, "Geofence register failed", t);
                    }
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

        // reset wait-hint state for this session
        resumedAtMs = System.currentTimeMillis();
        waitToastShown = false;
        anchorLoadingHintShown = false;

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

        // Handle geofence notification tap
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

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Background location permission is required for full experience.", Toast.LENGTH_LONG).show();
            }
            return;
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
                    .setFloat("u_Opacity", 1.0f)
                    .setDepthTest(true)
                    .setDepthWrite(true)
                    .setTexture("u_Texture", eggTexture);
            // --- Magnifier (puzzle) pipeline ---
            puzzleMesh = Mesh.createFromAsset(render, PUZZLE_MODEL);
            puzzleTexture = Texture.createFromAsset(
                    render, PUZZLE_TEXTURE, Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB);
            puzzleShader = Shader.createFromAssets(
                            render, "shaders/ar_unlit_object.vert", "shaders/ar_unlit_object.frag", null)
                    .setFloat("u_Opacity", 1.0f)
                    .setDepthTest(true)
                    .setDepthWrite(true)
                    .setTexture("u_Texture", puzzleTexture);

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
                EGG_TEXTURE,
                PUZZLE_MODEL,
                PUZZLE_TEXTURE
        };
        boolean ok = true;
        for (String s : required) {
            try (InputStream is = getAssets().open(s)) { /* ok */ }
            catch (Exception e) { ok = false; Log.e(TAG, "❌ Missing asset: " + s, e); }
        }
        return ok;
    }

    // --- Projection zoom helper: keeps virtual FOV in sync with background crop
    // Only scale focal lengths (m00 and m11). Do NOT touch other cells.
    private static void applyZoomToProjection(float[] outProj, float[] inProj, float zoom) {
        if (outProj == null || inProj == null) return;
        System.arraycopy(inProj, 0, outProj, 0, 16);
        outProj[0] *= zoom; // m00
        outProj[5] *= zoom; // m11
    }

    @Override public void onDrawFrame(SampleRender render) {
        if (session == null) return;
        // ---- INTERNET CHECK ----
        if (!hasInternetConnection()) {
            if (!noInternetDialogShown) {
                noInternetDialogShown = true;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (noInternetDialog != null && noInternetDialog.isShowing()) return;

                    noInternetDialog = new AlertDialog.Builder(GeospatialActivity.this)
                            .setTitle("No Internet Connection")
                            .setMessage("This app requires an active internet connection.\n\n" +
                                    "Please check your network and restart the app.")
                            .setCancelable(false)
                            .setPositiveButton("Exit", (d, w) -> {
                                d.dismiss();
                                finish();          // close activity
                            })
                            .create();
                    noInternetDialog.show();
                });
            }
            return; // stop AR loop until user fixes the issue
        } else {
            // Internet came back → clean up dialog, allow rendering to continue
            if (noInternetDialogShown) {
                noInternetDialogShown = false;
                runOnUiThread(() -> {
                    if (noInternetDialog != null && noInternetDialog.isShowing()) {
                        noInternetDialog.dismiss();
                    }
                    noInternetDialog = null;
                });
            }
        }
        if (!backgroundReady || backgroundRenderer == null) return;

        if (!hasSetTextureNames) {
            try {
                if (backgroundRenderer.getCameraColorTexture() != null) {
                    session.setCameraTextureNames(new int[]{backgroundRenderer.getCameraColorTexture().getTextureId()});
                    hasSetTextureNames = true;
                } else {
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "setCameraTextureNames failed", t);
                return;
            }
        }

        try { displayRotationHelper.updateSessionIfNeeded(session); } catch (Throwable ignore) {}

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

        try {
            backgroundRenderer.updateDisplayGeometry(frame);
            backgroundRenderer.setCameraZoom(userScaleMultiplier); // keep background crop
        } catch (Throwable ignore) {}

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

        GeospatialPose rawCamPose = null;
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            try { rawCamPose = earth.getCameraGeospatialPose(); } catch (SecurityException ignored) {}
        }

        PoseLite camPoseLite = null;
        if (rawCamPose != null && !Double.isNaN(rawCamPose.getLatitude()) && !Double.isNaN(rawCamPose.getLongitude())) {
            double lat = rawCamPose.getLatitude();
            double lng = rawCamPose.getLongitude();
            double alt = rawCamPose.getAltitude();
            if (emaSamples == 0) { emaLat = lat; emaLng = lng; emaAlt = alt; emaSamples = 1; }
            else {
                emaLat = EMA_ALPHA * lat + (1.0 - EMA_ALPHA) * emaLat;
                emaLng = EMA_ALPHA * lng + (1.0 - EMA_ALPHA) * emaLng;
                emaAlt = EMA_ALPHA * alt + (1.0 - EMA_ALPHA) * emaAlt;
                emaSamples++;
            }
            if (!Double.isNaN(rawCamPose.getHeading()) && !Double.isNaN(rawCamPose.getHeadingAccuracy())) {
                if (rawCamPose.getHeadingAccuracy() <= HEADING_MAX_ACC_DEG) lastGoodYawDeg = rawCamPose.getHeading();
            }
            camPoseLite = new PoseLite(
                    emaLat, emaLng, emaAlt,
                    rawCamPose.getHorizontalAccuracy(),
                    Double.isNaN(rawCamPose.getVerticalAccuracy()) ? 999 : rawCamPose.getVerticalAccuracy(),
                    rawCamPose.getHeading(),
                    rawCamPose.getHeadingAccuracy()
            );
        }

        updateEarthStatus(earth, camPoseLite, null);

        try { attemptResolveCloudAnchors(); } catch (Throwable t) { Log.w(TAG, "Cloud resolve loop failed", t); }

        if (earth != null && camPoseLite != null) {
            // ---- Combined logic: strict + relaxed + force-after-grace ----
            final double hAcc = camPoseLite.hAcc;
            final double vAcc = camPoseLite.vAcc;

            // Relaxed thresholds
            final double MAX_H_ACC_RELAXED = 25.0; //was 15
            final double MAX_V_ACC_RELAXED = 20.0; //was 12
            final int    MIN_SAMPLES_RELAX = 2;    //was 2
            final long   STABLE_MS_RELAXED = 400L; //was 1000

            final boolean trackingOk =
                    earth.getTrackingState() == TrackingState.TRACKING &&
                            earth.getEarthState()   == Earth.EarthState.ENABLED;

            final boolean goodStrict =
                    hAcc <= MAX_H_ACC_TO_PLACE &&
                            vAcc <= MAX_V_ACC_TO_PLACE &&
                            emaSamples >= MIN_STABLE_SAMPLES &&
                            trackingOk;

            final boolean goodRelaxed =
                    hAcc <= MAX_H_ACC_RELAXED &&
                            vAcc <= MAX_V_ACC_RELAXED &&
                            emaSamples >= MIN_SAMPLES_RELAX &&
                            trackingOk;

            final long nowMs = System.currentTimeMillis();
            final boolean force = (nowMs - resumedAtMs) >= STUCK_FORCE_PLACE_MS;

            if (goodStrict) {
                if (lastAccOkayAtMs == 0L) lastAccOkayAtMs = nowMs;
                if (nowMs - lastAccOkayAtMs >= LOCALIZE_STABLE_MS) {
                    placeGeoAnchorsExactly(earth, camPoseLite);
                    checkNearbyNudges(camPoseLite);
                }
            } else if (goodRelaxed) {
                if (lastAccOkayAtMs == 0L) lastAccOkayAtMs = nowMs;
                if (nowMs - lastAccOkayAtMs >= STABLE_MS_RELAXED) {
                    placeGeoAnchorsExactly(earth, camPoseLite);
                    checkNearbyNudges(camPoseLite);
                }
            } else if (force && hAcc <= 40.0 && vAcc <= 30.0) {
                // Grace period elapsed — try anyway so user sees something.
                placeGeoAnchorsExactly(earth, camPoseLite);
                checkNearbyNudges(camPoseLite);
            } else {
                // Not good yet; reset stability timer and show a brief hint.
                lastAccOkayAtMs = 0L;
                runOnUiThread(() -> statusText.setText(
                        String.format(Locale.US,
                                "Waiting for localization… HAcc=%.1fm VAcc=%.1fm samples=%d",
                                hAcc, vAcc, emaSamples)));
            }
        }

        if (earth != null) { try { maybeRelocalizeDriftedAnchors(earth); } catch (Throwable t) { Log.w(TAG, "relocalize check failed", t); } }

        try { handleTap(frame); } catch (Throwable t) { Log.w(TAG, "handleTap failed", t); }

        // --- Use zoom-adjusted projection for rendering to match the cropped background
        camera.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR);
        camera.getViewMatrix(viewMatrix, 0);

        float[] projZoomed = new float[16];
        applyZoomToProjection(projZoomed, projMatrix, userScaleMultiplier);

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
        synchronized (anchorsLock) {
            for (Map.Entry<Anchor, EggEntry> entry : anchorToEgg.entrySet()) {
                Anchor a = entry.getKey();
                if (a.getTrackingState() != TrackingState.TRACKING) continue;

                Pose p = a.getPose();
                p.toMatrix(modelMatrix, 0);

                float[] eulerDeg = getEggEulerDeg(entry.getValue());
                float rx = eulerDeg[0], ry = eulerDeg[1], rz = eulerDeg[2];

                float[] R = new float[16];
                float[] tmp = new float[16];
                Matrix.setIdentityM(R, 0);

                Matrix.setRotateM(tmp, 0, rz, 0f, 0f, 1f);
                Matrix.multiplyMM(R, 0, tmp, 0, R, 0);
                Matrix.setRotateM(tmp, 0, ry, 0f, 1f, 0f);
                Matrix.multiplyMM(R, 0, tmp, 0, R, 0);
                Matrix.setRotateM(tmp, 0, rx, 1f, 0f, 0f);
                Matrix.multiplyMM(R, 0, tmp, 0, R, 0);

                Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, R, 0);
                Matrix.translateM(modelMatrix, 0, 0f, MODEL_LIFT_M, 0f);

                // Decide which model this is (puzzle vs star) ONCE
                boolean puzzle = isPuzzle(entry.getValue());

                // 1) per-egg override if provided
                float s = getPerEggScaleOrNeg1(entry.getValue());
                if (s < 0f) {
                    // 2) otherwise: auto-scale by current distance-to-camera (gentle, clamped)
                    Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
                    float dx = mvMatrix[12], dy = mvMatrix[13], dz = mvMatrix[14];
                    float distanceM = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                    s = (distanceM > 0f) ? autoScaleForDistance(distanceM) : MODEL_SCALE_DEFAULT;
                }

                // ➜ APPLY visual multipliers BEFORE building S (so size really changes)
                s *= puzzle ? PUZZLE_VISUAL_MULT : STAR_VISUAL_MULT;

                float[] S = new float[16];
                Matrix.setIdentityM(S, 0);
                S[0] = s; S[5] = s; S[10] = s;
                Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, S, 0);

                Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0);
                Matrix.multiplyMM(mvpMatrix, 0, projZoomed, 0, mvMatrix, 0); // <-- use zoomed projection

                Mesh   mesh   = puzzle ? puzzleMesh    : eggMesh;
                Shader shader = puzzle ? puzzleShader  : eggShader;
                Texture tex   = puzzle ? puzzleTexture : eggTexture;

                if (mesh != null && shader != null && tex != null) {
                    shader.setMat4("u_ModelViewProjection", mvpMatrix);
                    shader.setTexture("u_Texture", tex);
                    render.draw(mesh, shader, virtualSceneFramebuffer);
                }
            }
        }
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
    }

    private void updateEarthStatus(@Nullable Earth earth, @Nullable PoseLite pose, @Nullable String override) {
        long now = System.currentTimeMillis();
        if (override == null && now - lastUiStatusAt < UI_STATUS_MS) return;
        lastUiStatusAt = now;

        if (override != null) {
            runOnUiThread(() -> { statusText.setText(override); statusText.setVisibility(View.VISIBLE); });
            return;
        }

        // Friendly guidance + elapsed seconds while localizing
        if (earth == null) {
            String msg = "Preparing AR… getting location, please wait a few seconds";
            runOnUiThread(() -> { statusText.setText(msg); statusText.setVisibility(View.VISIBLE); });
            maybeShowOneTimeWaitToast();
            return;
        }

        final String msg;
        if (earth.getTrackingState() == TrackingState.TRACKING && pose != null) {
            msg = String.format(
                    Locale.US,
                    "Earth: TRACKING ✓  lat=%.6f lon=%.6f  ±H=%.1fm  ±V=%.1fm  ±Head=%.1f°",
                    pose.lat, pose.lng, pose.hAcc, pose.vAcc, pose.headingAcc);
        } else if (earth.getTrackingState() == TrackingState.PAUSED) {
            int elapsed = (int) Math.max(0, (now - resumedAtMs) / 1000L);
            msg = String.format(Locale.US, "Earth: LOCALIZING… please wait (~5–15s). %ds", elapsed);
            maybeShowOneTimeWaitToast();
        } else {
            int elapsed = (int) Math.max(0, (now - resumedAtMs) / 1000L);
            msg = String.format(Locale.US, "Earth: LOCALIZING… please wait (~5–15s). %ds", elapsed);
            maybeShowOneTimeWaitToast();
        }
        runOnUiThread(() -> { statusText.setText(msg); statusText.setVisibility(View.VISIBLE); });
    }

    // ---------- Single-owner helper ----------
    /** Insert/replace the only anchor for an egg id; detaches any previous one and keeps maps in sync. */
    private void putUniqueAnchor(@NonNull String kind, @NonNull Anchor a, @NonNull EggEntry e) {
        synchronized (anchorsLock) {
            Anchor prev = anchorByEggId.get(e.id);
            if (prev != null) {
                try { prev.detach(); } catch (Throwable ignore) {}
                anchorToEgg.remove(prev);
            }
            anchorByEggId.put(e.id, a);
            anchorKindByEggId.put(e.id, kind);
            anchorToEgg.put(a, e);
            placedIds.add(e.id);
        }
    }

    // ---------- CLOUD ----------
    private void attemptResolveCloudAnchors() {
        if (eggs.isEmpty() || session == null) return;

        boolean startedAnyResolveThisTick = false;

        for (EggEntry e : eggs) {
            if (e == null || e.id == null) continue;

            // Magnifier/puzzle anchors are GEO-only: never try Cloud
            if (isPuzzle(e)) continue;

            // If we already have a CLOUD anchor for this egg, skip entirely
            synchronized (anchorsLock) {
                String kind = anchorKindByEggId.get(e.id);
                if ("CLOUD".equals(kind)) continue;
            }

            if (placedIds.contains(e.id) && !allowGeoFallbackIds.contains(e.id)) {
                // If already placed as GEO but cloud preferred, we may still try to upgrade → do not continue here.
                // We'll let it resolve and then replace below.
            }

            if (pendingCloudByEggId.containsKey(e.id)) continue;
            if (!wantsCloud(e)) continue;

            final String cloudId = bestCloudId(e);
            Log.d(TAG, "Egg " + e.id + " CLOUD? cloudId=" + cloudId);

            if (cloudId == null || cloudId.isEmpty()) {
                if (e.geo != null) allowGeoFallbackIds.add(e.id);
                continue;
            }

            if (isLikelyExpired(e.cloudHostedAt, e.cloudTtlDays)) {
                Log.w(TAG, "Cloud likely expired for " + e.id + " — enabling GEO fallback.");
                if (e.geo != null) allowGeoFallbackIds.add(e.id);
                continue;
            }

            try {
                Anchor resolving = session.resolveCloudAnchor(cloudId);
                pendingCloudByEggId.put(e.id, new PendingCloud(resolving, e));
                startedAnyResolveThisTick = true;
                Log.d(TAG, "Resolving Cloud Anchor for " + e.id);
            } catch (Throwable t) {
                Log.w(TAG, "resolveCloudAnchor failed for " + e.id + " — enabling GEO fallback if possible.", t);
                if (e.geo != null) allowGeoFallbackIds.add(e.id);
            }
        }

        // Inform the user once that we’re loading anchors from the cloud
        if (startedAnyResolveThisTick && !anchorLoadingHintShown) {
            anchorLoadingHintShown = true;
            runOnUiThread(() -> {
                toast("Loading nearby anchors… please wait");
                statusText.setText("Loading nearby anchors… please wait");
                statusText.setVisibility(View.VISIBLE);
            });
        }

        List<String> done = new ArrayList<>();
        for (Map.Entry<String, PendingCloud> kv : pendingCloudByEggId.entrySet()) {
            String eggId = kv.getKey();
            PendingCloud pc = kv.getValue();
            Anchor a = pc.a;

            Anchor.CloudAnchorState st = a.getCloudAnchorState();
            switch (st) {
                case SUCCESS: {
                    EggEntry ee = pc.e;
                    putUniqueAnchor("CLOUD", a, ee); // replace any GEO, ensure single-owner
                    done.add(eggId);
                    Log.d(TAG, "Cloud reso" +
                            "lve SUCCESS for " + eggId + " (installed as sole owner)");
                    break;
                }
                case NONE:
                case TASK_IN_PROGRESS:
                    if (System.currentTimeMillis() - pc.startedAt > CLOUD_RESOLVE_FALLBACK_MS && pc.e.geo != null) {
                        allowGeoFallbackIds.add(eggId); // show GEO placeholder now
                    }
                    break;
                default:
                    Log.w(TAG, "Cloud resolve error " + st + " for " + eggId + " — enabling GEO fallback if possible.");
                    try { a.detach(); } catch (Throwable ignore) {}
                    if (pc.e != null && pc.e.geo != null) allowGeoFallbackIds.add(eggId);
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

    // ---------- GEO ----------
    private void placeGeoAnchorsExactly(@Nullable Earth earth, @Nullable PoseLite currentPose) {
        if (earth == null || currentPose == null || eggs.isEmpty()) return;

        final long now = System.currentTimeMillis();

        for (EggEntry e : eggs) {
            if (e == null || e.id == null) continue;

            // If we already have any anchor for this egg, skip creating another
            synchronized (anchorsLock) {
                if (anchorByEggId.containsKey(e.id)) continue;
            }

            if (!allowsGeo(e)) continue;   // must allow GEO
            if (e.geo == null) continue;   // must have coords

            // 🔹1) Cloud-first: don't place GEO for CLOUD eggs unless fallback is allowed
            if (wantsCloud(e) && !allowGeoFallbackIds.contains(e.id)) continue;

            // 🔹2) Distance filter: only place anchors that are "near" the current camera
            double dH = haversineMeters(
                    currentPose.lat, currentPose.lng,
                    e.geo.getLatitude(), e.geo.getLongitude());

            if (dH > 40.0) {   // tweak 40.0 m as you like
                continue;       // too far, skip this egg for now
            }

            // (keep your existing retry/backoff logic below this)
            Long last = anchorAttemptAtMs.get(e.id);
            if (last != null && (now - last) < ANCHOR_RETRY_MS) continue;
            anchorAttemptAtMs.put(e.id, now);

            final double lat = e.geo.getLatitude();
            final double lng = e.geo.getLongitude();

            float[] savedQ = getSavedLocalQuaternion(e);
            float chosenYawDeg;
            Double savedYawMaybe = getSavedLocalSurfaceYawDeg(e);
            if (savedYawMaybe != null) {
                chosenYawDeg = savedYawMaybe.floatValue();
            } else if (e.heading != null) {
                chosenYawDeg = e.heading.floatValue();
            } else if (currentPose.headingAcc <= HEADING_MAX_ACC_DEG && !Double.isNaN(currentPose.heading)) {
                chosenYawDeg = (float) currentPose.heading;
            } else if (lastGoodYawDeg != null) {
                chosenYawDeg = lastGoodYawDeg.floatValue();
            } else {
                chosenYawDeg = 0f;
            }
            final float[] q = (savedQ != null) ? savedQ : yawToQuaternion(chosenYawDeg);

            try {
                if (e.alt != null) {
                    final double alt = e.alt + ALT_GLOBAL_OFFSET_M;
                    Anchor geo = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);

                    // VERIFY before accepting
                    if (!verifyAnchorNearTarget(earth, geo, e, 1.0)) {
                        try { geo.detach(); } catch (Throwable ignore) {}
                        Log.w(TAG, "Rejected GEOSPATIAL (exact alt) for " + e.id + " due to >1m error; will retry");
                        continue;
                    }

                    putUniqueAnchor("GEO", geo, e);
                    Log.d(TAG, "Placed GEOSPATIAL (exact alt) for " + e.id);
                } else {
                    final Double hatMaybe = getHeightAboveTerrain(e);
                    final double latF = lat;
                    final double lngF = lng;
                    final float[] qF  = new float[]{q[0], q[1], q[2], q[3]};
                    final EggEntry eggF = e;

                    earth.resolveAnchorOnTerrainAsync(
                            latF, lngF,
                            (float) (currentPose.alt + ALT_GLOBAL_OFFSET_M),
                            qF[0], qF[1], qF[2], qF[3],
                            (terrainAnchor, state) -> {
                                if (state == Anchor.TerrainAnchorState.SUCCESS) {
                                    try {
                                        double targetAlt;
                                        GeospatialPose tPose = earth.getGeospatialPose(terrainAnchor.getPose());
                                        if (tPose != null) {
                                            double terrainAlt = tPose.getAltitude();
                                            targetAlt = (hatMaybe != null)
                                                    ? terrainAlt + hatMaybe
                                                    : terrainAlt;
                                        } else {
                                            targetAlt = currentPose.alt + (hatMaybe != null ? hatMaybe : 0.0);
                                        }

                                        Anchor earthAnchor = earth.createAnchor(
                                                latF, lngF, targetAlt, qF[0], qF[1], qF[2], qF[3]);

                                        // VERIFY before accepting
                                        if (!verifyAnchorNearTarget(earth, earthAnchor, eggF, 1.0)) {
                                            try { earthAnchor.detach(); } catch (Throwable ignore) {}
                                            Log.w(TAG, "Rejected TERRAIN anchor for " + eggF.id + " due to >1m error; will retry");
                                            return;
                                        }

                                        putUniqueAnchor("GEO", earthAnchor, eggF);
                                        Log.d(TAG, (hatMaybe != null ? "Placed TERRAIN+HAT " : "Placed TERRAIN ")
                                                + "for " + eggF.id + " (alt=" + targetAlt + ")");
                                    } catch (Throwable t) {
                                        Log.w(TAG, "Terrain success but placement failed for " + eggF.id, t);
                                    } finally {
                                        if (terrainAnchor != null) try { terrainAnchor.detach(); } catch (Throwable ignore) {}
                                    }
                                } else {
                                    Log.w(TAG, "Terrain " + state + " for " + eggF.id + "; will retry later.");
                                }
                            });
                }
            } catch (Throwable t) {
                Log.w(TAG, "Anchor create failed for " + e.id, t);
            }
        }
    }

    /** Verify newly created anchor; WARN if far, but do not reject placement. */
    private boolean verifyAnchorNearTarget(Earth earth, Anchor a, EggEntry e, double tolMeters){
        try {
            if (e == null || e.geo == null || a == null) return true; // don't block
            GeospatialPose gp = earth.getGeospatialPose(a.getPose());
            if (gp == null || Double.isNaN(gp.getLatitude()) || Double.isNaN(gp.getLongitude())) return true;
            double err = haversineMeters(gp.getLatitude(), gp.getLongitude(),
                    e.geo.getLatitude(), e.geo.getLongitude());
            if (err > tolMeters) {
                Log.w(TAG, String.format(Locale.US,
                        "Anchor %.2fm from target for %s (tolerating, will monitor/relocalize).",
                        err, e.id));
            }
            return true; // never block placement
        } catch (Throwable t){
            Log.w(TAG, "verifyAnchorNearTarget failed (tolerating)", t);
            return true;
        }
    }

    private void maybeRelocalizeDriftedAnchors(Earth earth) {
        final long now = System.currentTimeMillis();
        List<Anchor> toRemove = new ArrayList<>();
        List<EggEntry> toRecreate = new ArrayList<>();

        synchronized (anchorsLock) {
            for (Map.Entry<Anchor, EggEntry> kv : anchorToEgg.entrySet()) {
                Anchor a = kv.getKey();
                EggEntry e = kv.getValue();
                if (e == null || e.geo == null) continue;

                GeospatialPose ap = earth.getGeospatialPose(a.getPose());
                if (ap == null) continue;

                double err = haversineMeters(ap.getLatitude(), ap.getLongitude(), e.geo.getLatitude(), e.geo.getLongitude());
                if (err > RELOCALIZE_IF_ERROR_M) {
                    long last = lastRelocAttemptAt.getOrDefault(e.id, 0L);
                    if (now - last >= RELOCALIZE_BACKOFF_MS) {
                        lastRelocAttemptAt.put(e.id, now);
                        toRemove.add(a);
                        toRecreate.add(e);
                    }
                }
            }
        }

        // Detach and clean up single-owner maps
        for (Anchor a : toRemove) { try { a.detach(); } catch (Throwable ignore) {} }
        for (EggEntry e : toRecreate) {
            synchronized (anchorsLock) {
                Anchor prev = anchorByEggId.remove(e.id);
                anchorKindByEggId.remove(e.id);
                if (prev != null) anchorToEgg.remove(prev);
                placedIds.remove(e.id);
            }
        }
    }

    // ---------- proximity nudge (in-session) ----------
    private void checkNearbyNudges(PoseLite cam) {
        if (eggs.isEmpty()) return;

        double camLat = cam.lat;
        double camLng = cam.lng;
        double camAlt = cam.alt;

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

        final int w = (surfaceView != null) ? surfaceView.getWidth() : 0;
        final int h = (surfaceView != null) ? surfaceView.getHeight() : 0;
        if (w <= 0 || h <= 0) return;

        cam.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR);
        cam.getViewMatrix(viewMatrix, 0);

        // --- keep pick math consistent with the zoomed background ---
        float[] projZoomed = new float[16];
        applyZoomToProjection(projZoomed, projMatrix, userScaleMultiplier);

        final float sx = tap.getX();
        final float sy = tap.getY();

        final float dp = getResources().getDisplayMetrics().density;
        final float r1 = 12f * dp;
        final float r2 = 24f * dp; // second ring
        final float[][] offsets = new float[][]{
                {0f, 0f},
                { r1, 0f}, {-r1, 0f}, {0f,  r1}, {0f, -r1},
                { r1,  r1}, { r1, -r1}, {-r1,  r1}, {-r1, -r1},
                { r2, 0f}, {-r2, 0f}, {0f,  r2}, {0f, -r2}
        };

        PickResult best = null;
        float bestT = Float.MAX_VALUE;

        for (float[] off : offsets) {
            PickResult pr = pickEggByRay(
                    sx + off[0], sy + off[1],
                    viewMatrix, projZoomed, w, h // <-- use zoomed projection for picking
            );
            if (pr != null && pr.tAlong < bestT) {
                best = pr;
                bestT = pr.tAlong;
            }
        }

        if (best != null) {
            final EggEntry hit = best.egg;
            vibrate(20);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) showStarOrPuzzle(hit);
            });
        }else {
            runOnUiThread(() ->
                    Toast.makeText(this, "No star here — try tapping right on it or pinch-zoom the camera.", Toast.LENGTH_SHORT).show()
            );
        }
    }

    private static class PickResult {
        final Anchor anchor; final EggEntry egg; final float tAlong;
        PickResult(Anchor a, EggEntry e, float t) { anchor = a; egg = e; tAlong = t; }
    }

    @Nullable
    private PickResult pickEggByRay(float sx, float sy, float[] view, float[] proj, int vw, int vh) {
        // Build inverse VP to unproject the touch ray (already using zoomed projection upstream)
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
        for (int i = 0; i < 3; i++) { nearW[i] /= Math.max(1e-6f, nearW[3]); farW[i] /= Math.max(1e-6f, farW[3]); }
        float[] o = {nearW[0], nearW[1], nearW[2]};          // ray origin (world)
        float[] d = {farW[0]-o[0], farW[1]-o[1], farW[2]-o[2]}; // ray dir (world)
        normalize3(d);

        PickResult best = null;
        float bestT = Float.MAX_VALUE;

        synchronized (anchorsLock) {
            if (anchorToEgg.isEmpty()) return null;

            for (Map.Entry<Anchor, EggEntry> entry : anchorToEgg.entrySet()) {
                Anchor a = entry.getKey();
                if (a.getTrackingState() != TrackingState.TRACKING) continue;

                // Anchor center in world
                float[] c = a.getPose().getTranslation();

                // Vector from ray origin to center
                float[] v = {c[0]-o[0], c[1]-o[1], c[2]-o[2]};
                float vd = dot3(v, d);            // distance along ray to closest approach
                if (vd <= 0f) continue;           // behind the camera
                float distanceMeters = len3(v);   // center distance

                /// ---- SCALE-AWARE PICK RADIUS ----
                boolean puzzle = isPuzzle(entry.getValue());

// Compute the rendered scale s for this anchor (same logic as draw)
                float s = getPerEggScaleOrNeg1(entry.getValue());
                if (s < 0f) {
                    s = autoScaleForDistance(distanceMeters);
                }
                s *= puzzle ? PUZZLE_VISUAL_MULT : STAR_VISUAL_MULT;

// Use per-model unscaled radius
                float baseR = puzzle ? PUZZLE_RADIUS_UNSCALED : STAR_RADIUS_UNSCALED;
                float objectRadiusM = baseR * s + PICK_MARGIN_M;

// Assist radius (keep your distance-based assist)
                float assistRadius = Math.min(
                        PICK_MAX_RADIUS_M,
                        PICK_BASE_RADIUS_M + PICK_RADIUS_PER_M * Math.min(40f, distanceMeters)
                );

// Final radius = max(object radius, assist)
                float radius = Math.max(objectRadiusM, assistRadius);

                // Closest distance from ray to center
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
        if (s.startsWith("https://firebasestorage.googleapis.com/v0/") && !s.contains("alt=media")) {
            s = s + (s.contains("?") ? "&" : "?") + "alt=media";
        }
        return s;
    }

    private void prewarmAssets(List<EggEntry> list) {
        for (EggEntry e : list) {
            if (e == null || e.id == null) continue;

            String img = normalizeUrlOrPath(e.firstImageOrUrl());
            if (img != null) {
                if (img.startsWith("http")) {
                    Uri uri = Uri.parse(img);
                    imageUrlCache.put(e.id, uri);
                    Glide.with(getApplicationContext())
                            .load(uri)
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
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
                                    .load(uri) // ensure load precedes diskCacheStrategy
                                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                    .timeout(20_000)
                                    .preload();
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
                                                .addOnFailureListener(e2 -> Log.w(TAG, "Audio resolve retry failed: " + s, e2))
                                , 1000);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Audio resolve error: " + s, t);
        }
    }

    private static String safe(@Nullable String s){ return s == null ? "" : s; }

    private static boolean typeHas(@Nullable String t, String key) {
        return t != null && t.toUpperCase(Locale.US).contains(key);
    }
    private boolean isPuzzle(@Nullable EggEntry e) {
        if (e == null) return false;
        if (typeHas(e.anchorType, "GEO_PUZZLE")) return true;

        try {
            Map<?,?> m = tryReadMetaMap(e);
            Object v = (m != null) ? m.get("model") : null;
            if (v instanceof String && "puzzle".equalsIgnoreCase((String) v)) return true;
        } catch (Throwable ignore) {}

        return false;
    }
    private static boolean wantsCloud(EggEntry e) {
        return typeHas(e.anchorType, "CLOUD");
    }
    private static boolean allowsGeo(EggEntry e) {
        // allow GEO if the type mentions GEO, or if we have coords at all
        return typeHas(e.anchorType, "GEO") || e.geo != null;
    }

    /** Return a per-egg scale if present and sane, else -1 to indicate "no override". */
    private float getPerEggScaleOrNeg1(EggEntry e) {
        Float eggScaleOverride = getEggScale(e);
        if (eggScaleOverride != null && eggScaleOverride > 0f) {
            return Math.min(eggScaleOverride, MODEL_SCALE_MAX);
        }
        return -1f;
    }

    /** Compute a nice-looking scale based on distance (meters), clamped to sane limits. */
    private float autoScaleForDistance(float distanceM) {
        // Simple linear model: base + k * distance, then clamp.
        float s = MODEL_SCALE_BASE + MODEL_SCALE_K_PER_M * Math.max(0f, distanceM);
        if (s < MODEL_SCALE_MIN) s = MODEL_SCALE_MIN;
        if (s > MODEL_SCALE_MAX) s = MODEL_SCALE_MAX;
        return s;
    }

    private static float[] yawToQuaternion(float yawDeg) {
        float r = (float) Math.toRadians(yawDeg);
        float s = (float) Math.sin(r * 0.5f), c = (float) Math.cos(r * 0.5f);
        return new float[]{0f, s, 0f, c};
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
                    NOTIF_CHANNEL_ID, "Nearby Eggs (in app)", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Alerts when you are near a saved egg while the AR view is open");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ---------- dialogs ----------
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

        String img = egg.firstImageOrUrl();
        final Uri[] imageToShow = { null };

        if (img != null && !img.trim().isEmpty()) {
            btnViewImage.setVisibility(View.VISIBLE);
            Uri cached = (egg.id != null) ? imageUrlCache.get(egg.id) : null;
            if (cached != null) imageToShow[0] = cached;
            else {
                String s = normalizeUrlOrPath(img);
                if (s != null) {
                    if (s.startsWith("http")) imageToShow[0] = Uri.parse(s);
                    else {
                        try {
                            StorageReference ref = s.startsWith("gs://")
                                    ? FirebaseStorage.getInstance().getReferenceFromUrl(s)
                                    : FirebaseStorage.getInstance().getReference().child(s);
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
                }
            });
        } else {
            btnViewImage.setVisibility(View.GONE);
        }

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

                audioSec.findViewById(R.id.audioLoading).setVisibility(View.VISIBLE);
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
                            audioSec.findViewById(R.id.audioLoading).setVisibility(View.GONE);
                            btnPlayPause.setEnabled(true);
                            ((TextView) audioSec.findViewById(R.id.txtDuration)).setText(fmtTime(player.getDuration()));
                            player.start();
                            btnPlayPause.setText("Pause");
                            btnPlayPause.setIconResource(R.drawable.ic_outline_pause_24);
                            handler.post(tick);
                        });
                        p.setOnCompletionListener(player -> {
                            try { player.seekTo(0); } catch (Exception ignore) {}
                            ((android.widget.SeekBar) audioSec.findViewById(R.id.seekAudio)).setProgress(0);
                            ((TextView) audioSec.findViewById(R.id.txtElapsed)).setText("0:00");
                            btnPlayPause.setText("Play");
                            btnPlayPause.setIconResource(R.drawable.ic_outline_play_arrow_24);
                        });
                        p.setOnErrorListener((player, what, extra) -> {
                            Toast.makeText(this, "Audio error", Toast.LENGTH_SHORT).show();
                            audioSec.findViewById(R.id.audioLoading).setVisibility(View.GONE);
                            btnPlayPause.setEnabled(true);
                            return true;
                        });
                        mp[0] = p;
                        p.prepareAsync();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Audio error", Toast.LENGTH_SHORT).show();
                        audioSec.findViewById(R.id.audioLoading).setVisibility(View.GONE);
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
    private void showStarOrPuzzle(EggEntry egg) {
        if (isPuzzle(egg)) {
            showPuzzleClueDialog(egg);
            return;
        }
        String kind;
        synchronized (anchorsLock) { kind = anchorKindByEggId.get(egg.id); }
        if ("CLOUD".equals(kind)) {
            // Cloud is accurately placed → skip quiz
            showEggDialog(egg);
        } else {
            // GEO placeholder → keep your existing quiz gate (if any)
            showEggOrQuiz(egg);
        }
    }

    private void showPuzzleClueDialog(EggEntry egg) {
        // Simple programmatic layout; no new XML required
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        ImageView img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setMinimumHeight(dp(180));
        img.setBackgroundColor(0x11000000);
        root.addView(img, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView hint = new TextView(this);
        hint.setText("Go and find this place.");
        hint.setTextSize(16f);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

        // Load the first image that was made mandatory in Marker app
        loadIntoImageView(egg.firstImageOrUrl(), img, egg.id);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(egg.title != null && !egg.title.isEmpty() ? egg.title : "Puzzle")
                .setView(root)
                .setPositiveButton("Got It", (d, w) -> {
                    // Reveal full details (title + desc + media)
                    showEggDialog(egg);
                })
                .setNegativeButton("I Give Up", (d, w) -> {
                    // Take back: just close the dialog, return to AR view
                    d.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void showEggOrQuiz(EggEntry egg) {
        if (egg == null) return;
        if (egg.hasQuiz()) {
            EggEntry.QuizQuestion q = egg.quiz.get(0);
            showQuizDialog(q, () -> refreshEggThenShow(egg));
        } else {
            refreshEggThenShow(egg);
        }
    }

    private void refreshEggThenShow(EggEntry stale) {
        if (stale == null || stale.id == null) { return; }
        repository.fetchEggById(stale.id, /*forceServer=*/true)
                .addOnSuccessListener(updated -> {
                    EggEntry toUse = (updated != null ? updated : stale);
                    java.util.List<EggEntry> one = new java.util.ArrayList<>();
                    one.add(toUse);
                    prewarmAssets(one);
                    showEggDialog(toUse);
                })
                .addOnFailureListener(err -> showEggDialog(stale));
    }

    private void postNearbyNotification(EggEntry e) {
        try {
            Intent intent = new Intent(this, GeospatialActivity.class);
            intent.putExtra("openEggId", e.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    e.id != null ? e.id.hashCode() : 0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Nearby egg")
                    .setContentText(e.title != null && !e.title.isEmpty() ? e.title : "Open details")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            if (Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED) {
                nm.notify(NOTIF_ID_BASE + (e.id != null ? (e.id.hashCode() & 0x0FFF) : 0x123), b.build());
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "postNearbyNotification failed.", t);
        }
    }

    // ---------- facade orientation & per-egg overrides ----------
    @Nullable
    private float[] getSavedLocalQuaternion(EggEntry e) {
        try {
            float[] q = tryReadQuaternionXYZW(e);
            if (q == null) {
                Map<?,?> m = tryReadMetaMap(e);
                if (m != null) q = tryReadQuaternionXYZW(m);
            }
            return q;
        } catch (Throwable t) { Log.w(TAG, "getSavedLocalQuaternion: reflect read failed", t); return null; }
    }

    @Nullable
    private Double getSavedLocalSurfaceYawDeg(EggEntry e) {
        try {
            float[] q = tryReadQuaternionXYZW(e);
            if (q == null) {
                Map<?,?> m = tryReadMetaMap(e);
                if (m != null) q = tryReadQuaternionXYZW(m);
            }
            if (q == null) return null;
            return (double) yawDegFromQuaternionYUp(q[0], q[1], q[2], q[3]);
        } catch (Throwable t) { Log.w(TAG, "getSavedLocalSurfaceYawDeg: reflect read failed", t); return null; }
    }

    // UPDATED: clamp/validate heightAboveTerrain to avoid absurd values (e.g., –80 m)
    @Nullable
    private Double getHeightAboveTerrain(EggEntry e) {
        try {
            Float f = tryReadFloat(e, "heightAboveTerrain", "hat", "heightOverTerrain");
            if (f == null) {
                Map<?,?> m = tryReadMetaMap(e);
                if (m != null) f = tryReadFloat(m, "heightAboveTerrain", "hat", "heightOverTerrain");
            }
            if (f == null) return null;
            double val = f.doubleValue();
            // Accept small offsets only; ignore if out of a sane range.
            if (val < -5.0 || val > 15.0) {
                Log.w(TAG, "Ignoring HAT=" + val + "m (out of range)");
                return null;
            }
            return val;
        } catch (Throwable t) {
            Log.w(TAG, "getHeightAboveTerrain: reflect read failed", t);
            return null;
        }
    }

    @Nullable
    private Float getEggScale(EggEntry e) {
        try {
            Float f = tryReadFloat(e, "scale", "modelScale");
            if (f == null) {
                Map<?,?> m = tryReadMetaMap(e);
                if (m != null) f = tryReadFloat(m, "scale", "modelScale");
            }
            return f;
        } catch (Throwable t) { Log.w(TAG, "getScale read failed", t); return null; }
    }

    private float[] getEggEulerDeg(EggEntry e) {
        float rx = MODEL_ROT_X_DEG, ry = MODEL_ROT_Y_DEG, rz = MODEL_ROT_Z_DEG;
        try {
            Map<?,?> m = tryReadMetaMap(e);
            if (m != null) {
                Float _rx = tryReadFloat(m, "rotX", "modelRotX");
                Float _ry = tryReadFloat(m, "rotY", "modelRotY");
                Float _rz = tryReadFloat(m, "modelRotZ", "rotZ");
                if (_rx != null) rx = _rx;
                if (_ry != null) ry = _ry;
                if (_rz != null) rz = _rz;
            }
        } catch (Throwable t) { Log.w(TAG, "getEulerDeg read failed", t); }
        return new float[]{rx, ry, rz};
    }

    @Nullable
    private Map<?,?> tryReadMetaMap(Object e) {
        try {
            Field f1 = e.getClass().getDeclaredField("meta");
            f1.setAccessible(true);
            Object v = f1.get(e);
            if (v instanceof Map) return (Map<?, ?>) v;
        } catch (Throwable ignore) {}
        try {
            Field f2 = e.getClass().getDeclaredField("extras");
            f2.setAccessible(true);
            Object v = f2.get(e);
            if (v instanceof Map) return (Map<?, ?>) v;
        } catch (Throwable ignore) {}
        return null;
    }

    @Nullable
    private float[] tryReadQuaternionXYZW(Object source) {
        Float qx = tryReadFloat(source, "localQx", "localHitQx", "hitQx");
        Float qy = tryReadFloat(source, "localQy", "localHitQy", "hitQy");
        Float qz = tryReadFloat(source, "localQz", "localHitQz", "hitQz");
        Float qw = tryReadFloat(source, "localQw", "localHitQw", "hitQw");
        if (qx == null || qy == null || qz == null || qw == null) return null;
        float x = qx, y = qy, z = qz, w = qw;
        float len = (float) Math.sqrt(x*x + y*y + z*z + w*w);
        if (len > 1e-6f) { x/=len; y/=len; z/=len; w/=len; }
        return new float[]{x,y,z,w};
    }

    @Nullable
    private Float tryReadFloat(Object src, String... names) {
        if (src == null) return null;
        if (src instanceof Map) {
            Map<?,?> m = (Map<?,?>) src;
            for (String n : names) {
                Object v = m.get(n);
                Float f = castToFloat(v);
                if (f != null) return f;
            }
            return null;
        }
        for (String n : names) {
            try {
                Field f = src.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(src);
                Float fv = castToFloat(v);
                if (fv != null) return fv;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    @Nullable
    private Float castToFloat(Object v) {
        if (v == null) return null;
        if (v instanceof Float)  return (Float) v;
        if (v instanceof Double) return ((Double) v).floatValue();
        if (v instanceof Number) return ((Number) v).floatValue();
        if (v instanceof String) {
            try { return Float.parseFloat(((String) v).trim()); } catch (Exception ignore) {}
        }
        return null;
    }

    private static float yawDegFromQuaternionYUp(float x, float y, float z, float w) {
        double siny_cosp = 2.0 * (w * y + x * z);
        double cosy_cosp = 1.0 - 2.0 * (y * y + z * z);
        double yawRad = Math.atan2(siny_cosp, cosy_cosp);
        return (float) Math.toDegrees(yawRad);
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
                Log.e(TAG, "createSession", exception);
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
            config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
            try { config.setFocusMode(Config.FocusMode.AUTO); } catch (Throwable ignore) {}
            try { config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE); } catch (Throwable ignore) {}
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.setDepthMode(Config.DepthMode.AUTOMATIC);
            } else {
                config.setDepthMode(Config.DepthMode.DISABLED);
            }
            session.configure(config);
            session.resume();
        } catch (CameraNotAvailableException e) { message = "Camera not available"; exception = e; }
        catch (GooglePlayServicesLocationLibraryNotLinkedException e) { message = "Location library not linked"; exception = e; }
        catch (FineLocationPermissionNotGrantedException e) { message = "Location permission required"; exception = e; }
        catch (UnsupportedConfigurationException e) { message = "Unsupported AR configuration"; exception = e; }
        catch (Exception e) { message = "Failed to resume session: " + e.getMessage(); exception = e; }

        if (message != null) {
            messageSnackbarHelper.showError(this, message);
            Log.e(TAG, "resumeSession", exception);
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

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    private int dp(int d) { return Math.round(d * getResources().getDisplayMetrics().density); }

    private void showQuizDialog(EggEntry.QuizQuestion q, @Nullable Runnable onPassed) {
        if (q == null || q.options == null || q.options.isEmpty()) {
            if (onPassed != null) onPassed.run();
            return;
        }

        final String question = q.getPrompt() == null || q.getPrompt().isEmpty()
                ? "Answer the question" : q.getPrompt();
        final CharSequence[] items = q.options.toArray(new CharSequence[0]);
        final int correctIndex = q.getCorrectIndex();
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
                        Toast.makeText(this, "Please pick an answer.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (chosen[0] == correctIndex) {
                        Toast.makeText(this, "Correct! Unlocked.", Toast.LENGTH_SHORT).show();
                        if (onPassed != null) onPassed.run();
                    } else {
                        Toast.makeText(this, "Not quite. Try again.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Nullable
    private String bestCloudId(@Nullable EggEntry e) {
        if (e == null) return null;
        try {
            String viaMethod = e.bestCloudId();
            if (viaMethod != null && !viaMethod.isEmpty()) return viaMethod;
        } catch (Throwable ignore) { }
        return (e.cloudId != null && !e.cloudId.isEmpty()) ? e.cloudId : null;
    }

    // ---------- permission helper for background geofencing ----------
    private void maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) return;

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        REQUEST_BACKGROUND_LOCATION
                );
            }
        }
    }
    private boolean hasInternetConnection() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network nw = cm.getActiveNetwork();
            if (nw == null) return false;

            NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
            return caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    // ------- helper to show the toast once per resume while localizing -------
    private void maybeShowOneTimeWaitToast() {
        long now = System.currentTimeMillis();
        boolean withinFirst45s = (now - resumedAtMs) <= 45_000L; // was 30s → give it a bit longer
        if (!waitToastShown && withinFirst45s) {
            waitToastShown = true;
            toast("Getting your position… this can take a little longer depending on network/GNSS");
        }
    }
}