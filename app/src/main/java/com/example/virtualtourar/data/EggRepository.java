package com.example.virtualtourar.data;

import android.net.Uri;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.List;

public class EggRepository {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final StorageReference storage = FirebaseStorage.getInstance().getReference();

    /** Fetch all eggs from Firestore and attach the docId to each object. */
    public Task<List<EggEntry>> fetchAllEggs() {
        return db.collection("eggs").get().onSuccessTask(qs -> {
            List<EggEntry> out = new ArrayList<>();
            for (DocumentSnapshot d : qs.getDocuments()) {
                EggEntry e = d.toObject(EggEntry.class);
                if (e != null) { e.id = d.getId(); out.add(e); }
            }
            return Tasks.forResult(out);
        });
    }

    /** Turn a Storage path (e.g. "/eggs/.../photo_0.jpg") into a download URL. */
    public Task<Uri> downloadUrlFromPath(String storagePath) {
        return storage.getStorage().getReference(storagePath).getDownloadUrl();
    }
}
