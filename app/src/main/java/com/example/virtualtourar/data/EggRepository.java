package com.example.virtualtourar.data;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.Source;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Repository for fetching & normalizing Egg data + resolving media URIs. */
public class EggRepository {
    private static final String TAG = "EggRepository";
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    /** Root ref used for bucket-relative paths like "eggs/abc/photo.jpg". */
    private final StorageReference storageRoot = FirebaseStorage.getInstance().getReference();

    // -------------------- Public API --------------------

    /** Fetch all eggs. */
    public Task<List<EggEntry>> fetchAllEggs() { return fetchAllEggs(false); }

    /** Fetch all eggs; set forceServer=true to bypass local cache. */
    public Task<List<EggEntry>> fetchAllEggs(boolean forceServer) {
        return db.collection("eggs")
                .get(forceServer ? Source.SERVER : Source.DEFAULT)
                .onSuccessTask(qs -> {
                    List<EggEntry> out = new ArrayList<>();
                    for (DocumentSnapshot d : qs.getDocuments()) {
                        EggEntry e = d.toObject(EggEntry.class);
                        if (e != null) {
                            e.id = d.getId();
                            sanitizeMediaFields(e);
                            hydrateQuizIfNeeded(d, e);
                            out.add(e);
                        }
                    }
                    return Tasks.forResult(out);
                });
    }

    /** Fetch a single egg by id. */
    public Task<EggEntry> fetchEggById(String id) { return fetchEggById(id, false); }

    public Task<EggEntry> fetchEggById(String id, boolean forceServer) {
        return db.collection("eggs").document(id)
                .get(forceServer ? Source.SERVER : Source.DEFAULT)
                .onSuccessTask(d -> {
                    EggEntry e = d.toObject(EggEntry.class);
                    if (e != null) {
                        e.id = d.getId();
                        sanitizeMediaFields(e);
                        hydrateQuizIfNeeded(d, e);
                    }
                    return Tasks.forResult(e);
                });
    }

    /** Turn a Storage *path* (e.g. "/eggs/.../photo_0.jpg") into a download URL. */
    public Task<Uri> downloadUrlFromPath(String storagePath) {
        String p = trimLeadingSlash(storagePath);
        return storageRoot.child(p).getDownloadUrl();
    }

    /** Batch helper: paths → URLs. */
    public Task<List<Uri>> downloadUrlsFromPaths(List<String> storagePaths) {
        List<Task<Uri>> tasks = new ArrayList<>();
        if (storagePaths != null) {
            for (String sp : storagePaths) {
                if (!TextUtils.isEmpty(sp)) tasks.add(downloadUrlFromPath(sp));
            }
        }
        return Tasks.whenAllSuccess(tasks);
    }

    /**
     * Resolve any media reference into a streamable Uri:
     * - http(s) → Uri.parse
     * - content:// or file:// → Uri.parse
     * - gs://bucket/path or bucket path → Firebase getDownloadUrl
     */
    public Task<Uri> resolveToUri(@Nullable String urlOrPath) {
        String s = normalizeUrlOrPath(urlOrPath);
        if (TextUtils.isEmpty(s)) {
            return Tasks.forException(new IllegalArgumentException("Empty media reference"));
        }
        if (looksHttp(s) || s.startsWith("content://") || s.startsWith("file://")) {
            return Tasks.forResult(Uri.parse(s));
        }
        StorageReference ref = storageRefFrom(s);
        if (ref == null) {
            return Tasks.forException(new IllegalArgumentException("Bad storage ref: " + s));
        }
        return ref.getDownloadUrl();
    }

    // -------------------- Normalization & mapping --------------------

    /** Normalize media fields (trim, strip leading '/', fix REST URLs, drop empties). */
    private void sanitizeMediaFields(EggEntry e) {
        if (e == null) return;

        // URLs: trim + fix REST "alt=media"
        e.cardImageUrl = normalizeEmptyToNull(normalizeUrlOrPath(e.cardImageUrl));
        e.audioUrl     = normalizeEmptyToNull(normalizeUrlOrPath(e.audioUrl));

        // Paths: trim, strip leading '/', leave gs:// as-is
        e.audioPath = normalizeStoragePath(e.audioPath);

        if (e.photoPaths != null && !e.photoPaths.isEmpty()) {
            List<String> clean = new ArrayList<>();
            for (String raw : e.photoPaths) {
                if (TextUtils.isEmpty(raw)) continue;
                String s = raw.trim();
                // If it's a REST URL, normalize it and keep it as URL; else it's a path/gs://
                if (looksHttp(s)) {
                    s = normalizeUrlOrPath(s);
                } else if (!s.startsWith("gs://")) {
                    s = trimLeadingSlash(s);
                }
                if (!TextUtils.isEmpty(s)) clean.add(s);
            }
            e.photoPaths = clean.isEmpty() ? null : clean;
        }

        // Defensive: sometimes both audioUrl and audioPath exist—prefer URL
        if (!TextUtils.isEmpty(e.audioUrl)) e.audioPath = normalizeEmptyToNull(e.audioPath);
    }

    // -------------------- Quiz mapping (tolerant) --------------------

    /**
     * If Firestore didn’t bind quiz cleanly, coerce from List<Map<...>> into typed QuizQuestion objects.
     * Handles both {question, correctIndex} and legacy {q, answer}.
     */
    @SuppressWarnings("unchecked")
    private void hydrateQuizIfNeeded(DocumentSnapshot d, EggEntry e) {
        if (e == null) return;

        // If already mapped AND looks valid, keep it.
        if (e.quiz != null && !e.quiz.isEmpty()) {
            boolean looksValid = false;
            for (EggEntry.QuizQuestion q : e.quiz) {
                if (q != null && q.options != null && q.options.size() >= 2) {
                    if (!isEmpty(q.question) || !isEmpty(q.q)) { looksValid = true; break; }
                }
            }
            if (looksValid) return;
        }

        Object raw = d.get("quiz");
        if (!(raw instanceof List)) return;

        List<?> list = (List<?>) raw;
        List<EggEntry.QuizQuestion> fixed = new ArrayList<>();

        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;

            EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();

            // Prompt (support both keys)
            String promptNew = str(m.get("question"));
            String promptOld = str(m.get("q"));
            if (!isEmpty(promptNew)) q.question = promptNew;
            if (!isEmpty(promptOld)) q.q = promptOld;

            // Options
            Object optsObj = m.get("options");
            if (optsObj instanceof List) {
                q.options = new ArrayList<>();
                for (Object opt : (List<?>) optsObj) {
                    if (opt != null) q.options.add(String.valueOf(opt));
                }
            }

            // Correct index (support both keys; numbers may arrive as Long/Double/String)
            Long idx = numAsLong(m.get("correctIndex"));
            if (idx == null) idx = numAsLong(m.get("answer"));
            if (idx != null) {
                q.correctIndex = idx;
                q.answer = idx; // keep legacy field too
            }

            if (q.options != null && q.options.size() >= 2) {
                fixed.add(q);
            }
        }

        if (!fixed.isEmpty()) {
            e.quiz = fixed;
        } else {
            Log.d(TAG, "hydrateQuizIfNeeded: quiz present but not usable in doc " + d.getId());
        }
    }

    // -------------------- Small helpers --------------------

    private static boolean isEmpty(String s){ return s == null || s.trim().isEmpty(); }
    private static String str(Object o){ return o == null ? null : String.valueOf(o); }
    private static Long numAsLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception ignore) { return null; }
    }

    private static boolean looksHttp(String s) {
        return s.startsWith("https://") || s.startsWith("http://")
                || s.startsWith("https%3A%2F%2F") || s.startsWith("http%3A%2F%2F");
    }

    private static String trimLeadingSlash(@Nullable String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    /** For Storage paths: trim, remove leading '/', null if empty; leave gs:// untouched. */
    private static String normalizeStoragePath(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("gs://")) return s;
        return trimLeadingSlash(s);
    }

    /**
     * Normalize Firestore/Storage strings used as *URLs*:
     * - trim quotes/whitespace
     * - append alt=media to REST storage URLs (so Glide/MediaPlayer fetch the file directly)
     */
    private static String normalizeUrlOrPath(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return "";
        // Trim accidental surrounding quotes
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("https://firebasestorage.googleapis.com/v0/")
                && !s.contains("alt=media")) {
            s = s + (s.contains("?") ? "&" : "?") + "alt=media";
        }
        return s;
    }

    private static String normalizeEmptyToNull(@Nullable String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private @Nullable StorageReference storageRefFrom(String urlOrPath) {
        try {
            if (TextUtils.isEmpty(urlOrPath)) return null;
            if (urlOrPath.startsWith("gs://")) {
                return FirebaseStorage.getInstance().getReferenceFromUrl(urlOrPath);
            }
            // treat as bucket path (allow leading '/')
            return storageRoot.child(trimLeadingSlash(urlOrPath));
        } catch (Exception e) {
            Log.w(TAG, "storageRefFrom failed for: " + urlOrPath, e);
            return null;
        }
    }

    // (kept if you add geo queries later)
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // -------------------- Optional convenience --------------------

    /** Returns true if this Cloud Anchor is probably expired (based on hostedAt + ttlDays). */
    public static boolean isLikelyExpired(@Nullable Timestamp hostedAt, @Nullable Long ttlDays) {
        if (hostedAt == null || ttlDays == null) return false;
        long start = hostedAt.toDate().getTime();
        long ttlMs = ttlDays * 24L * 60L * 60L * 1000L;
        return System.currentTimeMillis() > (start + ttlMs);
    }

    /** Build a simple, safe display string for a GeoPoint (handy for debugging). */
    public static String fmtGeo(@Nullable GeoPoint gp) {
        return gp == null ? "—" : String.format(java.util.Locale.US, "%.6f, %.6f", gp.getLatitude(), gp.getLongitude());
    }
}
