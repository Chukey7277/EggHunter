package com.example.virtualtourar;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

/** Fullscreen image viewer that accepts http(s), gs://, or bucket paths. */
public class ImageViewerDialogFragment extends DialogFragment {

    private static final String TAG = "ImageViewerDialog";
    private static final String ARG_REF = "arg_ref"; // http(s), gs://, or bucket path

    /** Pass *any* reference: http(s) URL, gs:// URL, or storage path ("eggs/.../photo.jpg"). */
    public static ImageViewerDialogFragment newFromAny(@Nullable String urlOrPath) {
        ImageViewerDialogFragment f = new ImageViewerDialogFragment();
        Bundle b = new Bundle();
        b.putString(ARG_REF, urlOrPath);
        f.setArguments(b);
        return f;
    }

    /** Back-compat if you already have a Uri. */
    public static ImageViewerDialogFragment newInstance(@Nullable Uri uri) {
        return newFromAny(uri != null ? uri.toString() : null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(0xCC000000)); // semi-transparent black
        d.setContentView(R.layout.dialog_full_image);
        d.setCanceledOnTouchOutside(true);
        setCancelable(true);

        ImageView image = d.findViewById(R.id.fullImage);
        View close = d.findViewById(R.id.btnClose);
        View progress = d.findViewById(R.id.progress); // optional (in layout)

        String rawRef = (getArguments() != null) ? getArguments().getString(ARG_REF) : null;
        if (rawRef == null || rawRef.trim().isEmpty()) {
            dismissAllowingStateLoss();
            return d;
        }

        if (progress != null) progress.setVisibility(View.VISIBLE);

        // Normalize common REST URL quirk (needs alt=media to stream the actual bytes)
        String ref = normalizeUrlOrPath(rawRef);

        if (ref.startsWith("http")) {
            loadWithGlide(Uri.parse(ref), image, progress);
        } else if (ref.startsWith("gs://")) {
            // Resolve gs:// → https download URL
            StorageReference sref = FirebaseStorage.getInstance().getReferenceFromUrl(ref);
            sref.getDownloadUrl()
                    .addOnSuccessListener(uri -> loadWithGlide(uri, image, progress))
                    .addOnFailureListener(err -> {
                        Log.w(TAG, "gs:// resolve failed: " + ref, err);
                        if (isAdded()) dismissAllowingStateLoss();
                    });
        } else {
            // Treat as bucket path ("eggs/.../photo.jpg" or "/eggs/.../photo.jpg")
            String path = ref.startsWith("/") ? ref.substring(1) : ref;
            StorageReference sref = FirebaseStorage.getInstance().getReference().child(path);
            sref.getDownloadUrl()
                    .addOnSuccessListener(uri -> loadWithGlide(uri, image, progress))
                    .addOnFailureListener(err -> {
                        Log.w(TAG, "path resolve failed: " + path, err);
                        if (isAdded()) dismissAllowingStateLoss();
                    });
        }

        // Close handlers
        if (close != null) close.setOnClickListener(v -> dismiss());
        image.setOnClickListener(v -> dismiss());

        // --- Optional: pinch-to-zoom with PhotoView (add dependency first) ---
        // implementation 'com.github.chrisbanes:PhotoView:2.3.0'
        // PhotoViewAttacher attacher = new PhotoViewAttacher(image);
        // attacher.update();

        return d;
    }

    private void loadWithGlide(Uri uri, ImageView target, @Nullable View progress) {
        if (!isAdded()) return;

        Glide.with(this)
                .load(uri)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .dontAnimate()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e,
                                                Object model,
                                                Target<Drawable> t,
                                                boolean isFirstResource) {
                        Log.w(TAG, "Glide load failed for " + model, e);
                        if (progress != null) progress.setVisibility(View.GONE);
                        if (isAdded()) dismissAllowingStateLoss();
                        return false; // allow Glide to use error placeholder if set
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource,
                                                   Object model,
                                                   Target<Drawable> t,
                                                   DataSource ds,
                                                   boolean isFirstResource) {
                        if (progress != null) progress.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(target);
    }

    /** Trim quotes and add alt=media for Firebase REST URLs. */
    private static String normalizeUrlOrPath(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("https://firebasestorage.googleapis.com/v0/")
                && !s.contains("alt=media")) {
            s = s + (s.contains("?") ? "&" : "?") + "alt=media";
        }
        return s;
    }
}
