package com.example.virtualtourar;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

/** Bottom sheet showing an egg’s details (title, desc, image, audio). */
public class EggDetailBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG      = "EggDetailSheet";
    private static final String ARG_TITLE = "t";
    private static final String ARG_DESC  = "d";
    private static final String ARG_IMG   = "i";
    private static final String ARG_AUDIO = "a";

    private MediaPlayer player;
    private ProgressBar audioProgress;
    private MaterialButton playPause;

    // Optional image progress bar in your layout. Safe if missing.
    private @Nullable ProgressBar imageProgress;

    public static void show(androidx.fragment.app.FragmentManager fm,
                            String title, String desc,
                            @Nullable String imageUrlOrPath,
                            @Nullable String audioUrlOrPath) {
        EggDetailBottomSheet f = new EggDetailBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_DESC,  desc);
        b.putString(ARG_IMG,   imageUrlOrPath);
        b.putString(ARG_AUDIO, audioUrlOrPath);
        f.setArguments(b);
        f.show(fm, "starDetail");
    }

    @Nullable @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_egg_detail, container, false);

        TextView title = v.findViewById(R.id.title);
        TextView desc  = v.findViewById(R.id.desc);
        ImageView img  = v.findViewById(R.id.image);
        audioProgress  = v.findViewById(R.id.audio_progress);
        playPause      = v.findViewById(R.id.play_pause);
//        imageProgress  = v.findViewById(R.id.image_progress); // optional; may be null

        String t  = (getArguments() != null) ? getArguments().getString(ARG_TITLE, "Egg") : "Star";
        String d  = (getArguments() != null) ? getArguments().getString(ARG_DESC, "") : "";
        String iu = (getArguments() != null) ? getArguments().getString(ARG_IMG) : null;
        String au = (getArguments() != null) ? getArguments().getString(ARG_AUDIO) : null;

        title.setText(!TextUtils.isEmpty(t) ? t : "Star");
        desc.setText(d != null ? d : "");

        // IMAGE
        if (!TextUtils.isEmpty(iu)) {
            String norm = normalizeUrlOrPath(iu);
            loadImage(norm, img);
        } else {
            img.setVisibility(View.GONE);
            if (imageProgress != null) imageProgress.setVisibility(View.GONE);
        }

        // AUDIO
        if (!TextUtils.isEmpty(au)) {
            final String auNorm = normalizeUrlOrPath(au);
            playPause.setOnClickListener(btn -> prepareAndToggle(auNorm));
        } else {
            playPause.setVisibility(View.GONE);
            if (audioProgress != null) audioProgress.setVisibility(View.GONE);
        }

        return v;
    }

    // ---------- IMAGE HELPERS ----------

    private void loadImage(String urlOrPath, ImageView iv) {
        if (iv == null) return;

        if (imageProgress != null) imageProgress.setVisibility(View.VISIBLE);
        iv.setVisibility(View.VISIBLE);

        try {
            if (TextUtils.isEmpty(urlOrPath)) {
                hideImage(iv, "empty url");
                return;
            }

            // Local uris
            if (urlOrPath.startsWith("content://") || urlOrPath.startsWith("file://")) {
                Glide.with(this)
                        .load(Uri.parse(urlOrPath))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .into(iv);
                if (imageProgress != null) imageProgress.setVisibility(View.GONE);
                return;
            }

            // Direct HTTP(S)
            if (looksHttp(urlOrPath)) {
                Glide.with(this)
                        .load(urlOrPath)
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .timeout(20_000)
                        .into(iv);
                if (imageProgress != null) imageProgress.setVisibility(View.GONE);
                return;
            }

            // Firebase Storage: gs://… or bucket path
            StorageReference ref = storageRefFrom(urlOrPath);
            if (ref == null) {
                hideImage(iv, "bad storage ref: " + urlOrPath);
                return;
            }
            ref.getDownloadUrl()
                    .addOnSuccessListener(uri -> {
                        if (!isAdded()) return; // fragment gone
                        Glide.with(this)
                                .load(uri)
                                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                .timeout(20_000)
                                .into(iv);
                        if (imageProgress != null) imageProgress.setVisibility(View.GONE);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "getDownloadUrl failed", e);
                        hideImage(iv, "download url fail");
                    });

        } catch (Throwable t) {
            Log.w(TAG, "loadImage failed: " + urlOrPath, t);
            hideImage(iv, "exception");
        }
    }

    private void hideImage(ImageView iv, String reason) {
        if (imageProgress != null) imageProgress.setVisibility(View.GONE);
        iv.setVisibility(View.GONE);
        Log.d(TAG, "Hiding image: " + reason);
    }

    private static boolean looksHttp(String s) {
        return s.startsWith("https://") || s.startsWith("http://")
                || s.startsWith("https%3A%2F%2F") || s.startsWith("http%3A%2F%2F");
    }

    // ---------- AUDIO HELPERS ----------

    private void prepareAndToggle(String urlOrPath) {
        if (player != null && player.isPlaying()) {
            try { player.pause(); } catch (Exception ignore) {}
            playPause.setText("Play");
            return;
        }

        // If we already have a prepared player (paused), resume
        if (player != null) {
            try { player.start(); } catch (Exception ignore) {}
            playPause.setText("Pause");
            return;
        }

        if (audioProgress != null) audioProgress.setVisibility(View.VISIBLE);
        playPause.setEnabled(false);

        try {
            if (looksHttp(urlOrPath) || urlOrPath.startsWith("content://") || urlOrPath.startsWith("file://")) {
                startPlayer(Uri.parse(urlOrPath));
            } else {
                StorageReference ref = storageRefFrom(urlOrPath);
                if (ref == null) {
                    if (audioProgress != null) audioProgress.setVisibility(View.GONE);
                    playPause.setEnabled(true);
                    return;
                }
                ref.getDownloadUrl()
                        .addOnSuccessListener(this::startPlayer)
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "Audio getDownloadUrl failed", e);
                            if (audioProgress != null) audioProgress.setVisibility(View.GONE);
                            playPause.setEnabled(true);
                        });
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepareAndToggle failed", t);
            if (audioProgress != null) audioProgress.setVisibility(View.GONE);
            playPause.setEnabled(true);
        }
    }

    private void startPlayer(Uri uri) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build());
            }
            player.setDataSource(requireContext(), uri);
            player.setOnPreparedListener(mp -> {
                if (audioProgress != null) audioProgress.setVisibility(View.GONE);
                playPause.setEnabled(true);
                mp.start();
                playPause.setText("Pause");
            });
            player.setOnCompletionListener(mp -> playPause.setText("Play"));
            player.setOnErrorListener((mp, what, extra) -> {
                if (audioProgress != null) audioProgress.setVisibility(View.GONE);
                playPause.setEnabled(true);
                playPause.setText("Play");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            if (audioProgress != null) audioProgress.setVisibility(View.GONE);
            playPause.setEnabled(true);
        }
    }

    private void releasePlayer() {
        try {
            if (player != null) {
                player.reset();
                player.release();
            }
        } catch (Exception ignore) {}
        player = null;
    }

    private @Nullable StorageReference storageRefFrom(String urlOrPath) {
        try {
            if (TextUtils.isEmpty(urlOrPath)) return null;
            if (urlOrPath.startsWith("gs://")) {
                return FirebaseStorage.getInstance().getReferenceFromUrl(urlOrPath);
            }
            // treat as bucket path (allow leading '/')
            String p = urlOrPath.startsWith("/") ? urlOrPath.substring(1) : urlOrPath;
            return FirebaseStorage.getInstance().getReference().child(p);
        } catch (Exception e) {
            Log.w(TAG, "storageRefFrom failed for: " + urlOrPath, e);
            return null;
        }
    }

    /**
     * Normalize Firestore/Storage strings:
     *  - trim quotes
     *  - append alt=media to REST storage URLs (if those happen to be stored)
     */
    private static String normalizeUrlOrPath(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return s;

        // Trim accidental surrounding quotes
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }

        // If it’s a Firebase REST URL, ensure direct binary download
        if (s.startsWith("https://firebasestorage.googleapis.com/v0/")
                && !s.contains("alt=media")) {
            s = s + (s.contains("?") ? "&" : "?") + "alt=media";
        }
        return s;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        releasePlayer();
    }
}
