package com.example.virtualtourar;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class EggDetailBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_TITLE = "t";
    private static final String ARG_DESC  = "d";
    private static final String ARG_IMG   = "i";
    private static final String ARG_AUDIO = "a";

    private MediaPlayer player;
    private ProgressBar audioProgress;
    private MaterialButton playPause;

    public static void show(androidx.fragment.app.FragmentManager fm,
                            String title, String desc,
                            @Nullable String imageUrl,
                            @Nullable String audioUrl) {
        EggDetailBottomSheet f = new EggDetailBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_DESC,  desc);
        b.putString(ARG_IMG,   imageUrl);
        b.putString(ARG_AUDIO, audioUrl);
        f.setArguments(b);
        f.show(fm, "eggDetail");
    }

    @Nullable @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_egg_detail, container, false);

        TextView title = v.findViewById(R.id.title);
        TextView desc  = v.findViewById(R.id.desc);
        ImageView img  = v.findViewById(R.id.image);
        audioProgress  = v.findViewById(R.id.audio_progress);
        playPause      = v.findViewById(R.id.play_pause);

        String t  = getArguments() != null ? getArguments().getString(ARG_TITLE, "Egg") : "Egg";
        String d  = getArguments() != null ? getArguments().getString(ARG_DESC, "") : "";
        String iu = getArguments() != null ? getArguments().getString(ARG_IMG) : null;
        String au = getArguments() != null ? getArguments().getString(ARG_AUDIO) : null;

        title.setText(!TextUtils.isEmpty(t) ? t : "Egg");
        desc.setText(d != null ? d : "");

        // Image: load http(s) directly; if gs:// or path -> resolve to download URL
        if (!TextUtils.isEmpty(iu)) {
            loadImage(iu, img);
        } else {
            img.setVisibility(View.GONE);
        }

        // Audio: wire play/pause if URL present
        if (!TextUtils.isEmpty(au)) {
            playPause.setOnClickListener(btn -> prepareAndToggle(au));
        } else {
            playPause.setVisibility(View.GONE);
            audioProgress.setVisibility(View.GONE);
        }

        return v;
    }

    private void loadImage(String urlOrPath, ImageView iv) {
        if (urlOrPath.startsWith("http")) {
            Glide.with(this).load(urlOrPath).into(iv);
        } else {
            // gs://bucket/... or /path/in/bucket
            StorageReference ref = urlOrPath.startsWith("gs://")
                    ? FirebaseStorage.getInstance().getReferenceFromUrl(urlOrPath)
                    : FirebaseStorage.getInstance().getReference().child(urlOrPath);
            ref.getDownloadUrl().addOnSuccessListener(uri ->
                    Glide.with(this).load(uri).into(iv)
            ).addOnFailureListener(e -> iv.setVisibility(View.GONE));
        }
    }

    private void prepareAndToggle(String urlOrPath) {
        if (player != null && player.isPlaying()) {
            player.pause();
            playPause.setText("Play");
            return;
        }
        audioProgress.setVisibility(View.VISIBLE);

        if (urlOrPath.startsWith("http")) {
            startPlayer(Uri.parse(urlOrPath));
        } else {
            StorageReference ref = urlOrPath.startsWith("gs://")
                    ? FirebaseStorage.getInstance().getReferenceFromUrl(urlOrPath)
                    : FirebaseStorage.getInstance().getReference().child(urlOrPath);
            ref.getDownloadUrl().addOnSuccessListener(uri -> startPlayer(uri))
                    .addOnFailureListener(e -> { audioProgress.setVisibility(View.GONE); });
        }
    }

    private void startPlayer(Uri uri) {
        releasePlayer();
        player = MediaPlayer.create(requireContext(), uri); // streams http(s)
        if (player == null) {
            audioProgress.setVisibility(View.GONE);
            return;
        }
        player.setOnPreparedListener(mp -> {
            audioProgress.setVisibility(View.GONE);
            mp.start();
            playPause.setText("Pause");
        });
        player.setOnCompletionListener(mp -> {
            playPause.setText("Play");
        });
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

    @Override public void onDestroyView() {
        super.onDestroyView();
        releasePlayer();
    }
}
