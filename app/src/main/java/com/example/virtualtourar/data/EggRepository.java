package com.example.virtualtourar.data;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EggRepository {
    private static final String TAG = "EggRepository";
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final StorageReference storage = FirebaseStorage.getInstance().getReference();

    /** Fetch all eggs. Set forceServer=true if you want to bypass local cache. */
    public Task<List<EggEntry>> fetchAllEggs() {
        return fetchAllEggs(false);
    }

    public Task<List<EggEntry>> fetchAllEggs(boolean forceServer) {
        return db.collection("eggs")
                .get(forceServer ? Source.SERVER : Source.DEFAULT)
                .onSuccessTask(qs -> {
                    List<EggEntry> out = new ArrayList<>();
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        EggEntry e = d.toObject(EggEntry.class);
                        if (e != null) {
                            e.id = d.getId();
                            hydrateQuizIfNeeded(d, e); // ← ensure quiz options/answer are typed
                            out.add(e);
                        }
                    }
                    return Tasks.forResult(out);
                });
    }

    /** Fetch a single egg by id (optionally force server). */
    public Task<EggEntry> fetchEggById(String id) {
        return fetchEggById(id, false);
    }

    public Task<EggEntry> fetchEggById(String id, boolean forceServer) {
        return db.collection("eggs").document(id)
                .get(forceServer ? Source.SERVER : Source.DEFAULT)
                .onSuccessTask(d -> {
                    EggEntry e = d.toObject(EggEntry.class);
                    if (e != null) {
                        e.id = d.getId();
                        hydrateQuizIfNeeded(d, e);
                    }
                    return Tasks.forResult(e);
                });
    }

    /** Turn a Storage *path* (e.g. "/eggs/.../photo_0.jpg") into a download URL (handles leading '/'). */
    public Task<Uri> downloadUrlFromPath(String storagePath) {
        String p = (storagePath != null && storagePath.startsWith("/"))
                ? storagePath.substring(1) : storagePath;
        return storage.child(p).getDownloadUrl();
    }

    /** Batch helper: paths → URLs. */
    public Task<List<Uri>> downloadUrlsFromPaths(List<String> storagePaths) {
        List<Task<Uri>> tasks = new ArrayList<>();
        if (storagePaths != null) {
            for (String sp : storagePaths) tasks.add(downloadUrlFromPath(sp));
        }
        return Tasks.whenAllSuccess(tasks);
    }

    // ----- helpers -----

    /** If Firestore didn’t bind quiz cleanly, coerce from List<Map<...>> into typed QuizQuestion objects. */
    @SuppressWarnings("unchecked")
    private void hydrateQuizIfNeeded(DocumentSnapshot d, EggEntry e) {
        if (e.quiz != null && !e.quiz.isEmpty()) return; // already good
        Object raw = d.get("quiz");
        if (!(raw instanceof List)) return;

        List<?> list = (List<?>) raw;
        List<EggEntry.QuizQuestion> fixed = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;

            EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
            Object qText = m.get("q");
            Object opts  = m.get("options");
            Object ans   = m.get("answer");

            q.q = qText != null ? String.valueOf(qText) : null;

            if (opts instanceof List) {
                q.options = new ArrayList<>();
                for (Object opt : (List<?>) opts) {
                    if (opt != null) q.options.add(String.valueOf(opt));
                }
            }

            if (ans instanceof Number) {
                // Firestore numbers often come back as Long/Double; store as Long in the model
                q.answer = ((Number) ans).longValue();
            } else if (ans instanceof String) {
                try { q.answer = Long.parseLong((String) ans); } catch (NumberFormatException ignore) {}
            }

            if (q.options != null && !q.options.isEmpty()) fixed.add(q);
        }

        if (!fixed.isEmpty()) {
            e.quiz = fixed;
        } else {
            Log.d(TAG, "hydrateQuizIfNeeded: quiz present but no usable options in doc " + d.getId());
        }
    }

    // Distance util (kept if you use fetchEggsNear in this app)
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
