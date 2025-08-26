package com.example.virtualtourar.data;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

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
    public @Nullable Double horizAcc;  // meters (horizontal accuracy)
    public @Nullable Double vertAcc;   // meters (vertical accuracy)

    // Pose snapshot (4x4 matrix flattened, length 16)
    public @Nullable List<Float> poseMatrix;

    // Media (Storage paths or absolute URLs)
    public @Nullable List<String> photoPaths;
    public @Nullable String audioPath;
    public @Nullable Boolean hasMedia;

    // Optional direct URLs (used by viewer for faster loads)
    public @Nullable String cardImageUrl;
    public @Nullable String audioUrl;

    // Quiz gate
    public @Nullable List<QuizQuestion> quiz;

    // Timestamps (server generated)
    public @Nullable Timestamp createdAt;
    public @Nullable Timestamp updatedAt;

    public @Nullable List<String> collectedBy;

    public @Nullable String speechTranscript;

    public EggEntry() {} // Firestore needs a public no-arg constructor

    public double lat() { return geo != null ? geo.getLatitude() : 0; }
    public double lng() { return geo != null ? geo.getLongitude() : 0; }

    /** Returns true only if there's at least one well-formed quiz question. */
    public boolean hasValidQuiz() {
        if (quiz == null || quiz.isEmpty()) return false;
        QuizQuestion q = quiz.get(0);
        if (q == null || q.options == null || q.options.size() < 2 || q.answer == null) return false;
        long a = q.answer; // stored as Long in Firestore
        return a >= 0 && a < q.options.size();
    }

    /** Backwards-compat alias if you previously called hasQuiz(). */
    public boolean hasQuiz() { return hasValidQuiz(); }

    /** Single multiple-choice question. */
    @Keep
    public static class QuizQuestion {
        public String q;
        public List<String> options;
        public Long answer; // index into options (use Long for Firestore)

        public QuizQuestion() {}
    }
}
