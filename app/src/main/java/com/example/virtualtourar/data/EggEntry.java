package com.example.virtualtourar.data;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/** Firestore model for an egg entry (viewer). */
@Keep
public class EggEntry {

    public String id;
    public String userId;
    public String title;
    public String description;

    // Geospatial
    public @Nullable GeoPoint geo;
    public @Nullable Double alt;
    public @Nullable Double heading;
    public @Nullable Double horizAcc;  // meters
    public @Nullable Double vertAcc;   // meters

    // Pose snapshot (4x4 matrix flattened, length 16)
    public @Nullable List<Float> poseMatrix;

    // Media
    public @Nullable List<String> photoPaths;
    public @Nullable String audioPath;
    public @Nullable Boolean hasMedia;

    // Optional URLs
    public @Nullable String cardImageUrl;
    public @Nullable String audioUrl;

    // Quiz gate
    public @Nullable List<QuizQuestion> quiz;

    // Timestamps
    public @Nullable Timestamp createdAt;
    public @Nullable Timestamp updatedAt;

    public @Nullable List<String> collectedBy;

    public @Nullable String speechTranscript;

    // Anchoring
    public @Nullable String anchorType;      // "CLOUD" | "GEO" | "LOCAL"
    public @Nullable String cloudId;         // canonical field used by EggLayer
    public @Nullable String cloudAnchorId;   // optional alt name (if some docs used this)
    public @Nullable Long   cloudTtlDays;    // usually 1
    public @Nullable Timestamp cloudHostedAt;

    // (Optional) metadata the creator saved
    public @Nullable String placementType;       // "DepthPoint", "Point", "Plane", etc.
    public @Nullable Float  distanceFromCamera;  // meters at authoring time
    public @Nullable String refImage;            // if you ever use Augmented Images

    public EggEntry() {} // Firestore needs a public no-arg constructor

    public double lat() { return geo != null ? geo.getLatitude() : 0; }
    public double lng() { return geo != null ? geo.getLongitude() : 0; }

    /** Returns a usable Cloud Anchor ID, trying both common field names. */
    public @Nullable String bestCloudId() {
        if (cloudId != null && !cloudId.trim().isEmpty()) return cloudId.trim();
        if (cloudAnchorId != null && !cloudAnchorId.trim().isEmpty()) return cloudAnchorId.trim();
        return null;
    }

    /** First image to show: cardImageUrl if present, else first photo path (kept for back-compat). */
    public @Nullable String firstImageOrUrl() {
        if (cardImageUrl != null && !cardImageUrl.trim().isEmpty()) return cardImageUrl.trim();
        if (photoPaths != null && !photoPaths.isEmpty()) {
            String p = photoPaths.get(0);
            if (p != null) {
                p = p.trim();
                if (!p.startsWith("http") && !p.startsWith("gs://") && p.startsWith("/")) {
                    p = p.substring(1); // avoid StorageReference("/...") issues
                }
                if (!p.isEmpty()) return p;
            }
        }
        return null;
    }

    /** Normalized first image for display (Glide): trims quotes, adds alt=media for REST links, strips leading '/' for bucket paths. */
    public @Nullable String firstImageForDisplay() {
        String raw = firstImageOrUrl();
        return normalizeUrlOrPath(raw);
    }

    /** Prefer audioUrl; else audioPath. Normalized for MediaPlayer: REST links get alt=media, bucket paths keep as-is (without leading '/'). */
    public @Nullable String primaryAudioForPlayback() {
        String raw = (audioUrl != null && !audioUrl.trim().isEmpty()) ? audioUrl : audioPath;
        if (raw == null) return null;
        // For bucket paths (not http/gs), strip leading '/'
        String s = raw.trim();
        if (!s.startsWith("http") && !s.startsWith("gs://") && s.startsWith("/")) {
            s = s.substring(1);
        }
        return normalizeUrlOrPath(s);
    }

    /** True if at least one well-formed quiz question exists. */
    public boolean hasValidQuiz() {
        if (quiz == null || quiz.isEmpty()) return false;
        for (QuizQuestion q : quiz) {
            if (q != null && q.hasValidOptions()) return true;
        }
        return false;
    }

    public boolean hasQuiz() { return hasValidQuiz(); }

    public boolean hasImage() {
        if (cardImageUrl != null && !cardImageUrl.trim().isEmpty()) return true;
        return photoPaths != null && !photoPaths.isEmpty();
    }

    public boolean isCloud() { return "CLOUD".equalsIgnoreCase(s(anchorType)); }
    public boolean isGeo()   { return "GEO".equalsIgnoreCase(s(anchorType)); }

    // -------------------- Helpers --------------------

    private static String s(@Nullable String v) { return v == null ? "" : v; }

    /** Normalize Firestore/Storage strings used as URLs or bucket paths:
     *  - trim & strip accidental surrounding quotes
     *  - append alt=media to Firebase REST storage URLs (so Glide/MediaPlayer fetch file directly)
     *  - leave gs:// untouched
     *  - for bucket paths (no scheme), assume caller already stripped leading '/' if needed
     */
    private static @Nullable String normalizeUrlOrPath(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("https://firebasestorage.googleapis.com/v0/")
                && !s.contains("alt=media")) {
            s = s + (s.contains("?") ? "&" : "?") + "alt=media";
        }
        return s;
    }

    /** Multiple-choice question – supports both old and new field names. */
    @Keep
    public static class QuizQuestion {
        // New generator fields:
        public @Nullable String question;
        public @Nullable Long   correctIndex;

        // Old generator fields (back-compat):
        public @Nullable String q;
        public @Nullable Long   answer;

        // Common:
        public @Nullable List<String> options;

        public QuizQuestion() {}

        /** Stable prompt getter for UI. */
        public String getPrompt() {
            if (question != null && !question.trim().isEmpty()) return question.trim();
            if (q != null && !q.trim().isEmpty()) return q.trim();
            return "";
        }

        /** Stable correct-index getter for UI. */
        public int getCorrectIndex() {
            int idx = -1;
            if (correctIndex != null) idx = safeLongToInt(correctIndex);
            else if (answer != null)  idx = safeLongToInt(answer);
            if (idx < 0) idx = 0;
            int sz = (options != null) ? options.size() : 0;
            if (sz > 0 && idx >= sz) idx = 0;
            return idx;
        }

        /** At least two options present. */
        public boolean hasValidOptions() {
            return options != null && options.size() >= 2;
        }

        private static int safeLongToInt(@Nullable Long v) {
            if (v == null) return 0;
            long l = v;
            if (l > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (l < Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) l;
        }
    }
}
