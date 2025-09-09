package com.example.virtualtourar;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;

public class ImageViewerDialogFragment extends DialogFragment {

    private static final String ARG_URI = "arg_uri";

    public static ImageViewerDialogFragment newInstance(Uri uri) {
        ImageViewerDialogFragment f = new ImageViewerDialogFragment();
        Bundle b = new Bundle();
        b.putString(ARG_URI, uri.toString());
        f.setArguments(b);
        return f;
    }

    @NonNull @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(0xCC000000));
        d.setContentView(R.layout.dialog_full_image);

        String s = getArguments() != null ? getArguments().getString(ARG_URI) : null;
        ImageView image = d.findViewById(R.id.fullImage);
        if (s != null) Glide.with(requireContext()).load(Uri.parse(s)).into(image);

        d.findViewById(R.id.btnClose).setOnClickListener(v -> dismiss());
        // Tap-to-dismiss
        image.setOnClickListener(v -> dismiss());
        return d;
    }
}
