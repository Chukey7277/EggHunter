package com.example.virtualtourar.data;

import com.google.firebase.firestore.GeoPoint;
import java.util.List;

public class EggEntry {
    public String id;               // Firestore document id (set client-side)
    public String title;
    public String description;
    public String userId;

    public GeoPoint geo;            // latitude/longitude
    public Double alt;              // optional altitude (meters)
    public Double heading;          // optional yaw (deg)
    public Double horizAcc;
    public Double vertAcc;

//    public String quizQuestion;
//    public String quizAnswer;


    public List<String> photoPaths; // Storage paths like /eggs/{id}/photos/photo_0.jpg
    public String audioPath;        // Storage path
    public Boolean hasMedia;

    public String cardImageUrl;
    public String audioUrl;

    public EggEntry() {} // Firestore needs no-arg

    public double lat() { return geo != null ? geo.getLatitude() : 0; }
    public double lng() { return geo != null ? geo.getLongitude() : 0; }
}
