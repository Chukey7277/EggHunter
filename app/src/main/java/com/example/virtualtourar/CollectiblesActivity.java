package com.example.virtualtourar;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.virtualtourar.data.EggEntry;
import com.example.virtualtourar.data.EggRepository;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CollectiblesActivity extends AppCompatActivity {

    private static final String SP_NAME = "GeospatialActivity";
    private static final String COLLECTED_EGGS_KEY = "COLLECTED_EGGS";

    private EggRepository repository;
    private ProgressBar progress;
    private ListView listView;
    private TextView emptyView;
    private MaterialButton homeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collectibles);

        progress = findViewById(R.id.progress);
        listView = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        homeButton = findViewById(R.id.home_button);

        if (homeButton != null) {
            homeButton.setOnClickListener(v -> {
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            });
        }

        repository = new EggRepository();
        loadData();
    }

    private void loadData() {
        showLoading(true);

        // Read collected IDs saved by GeospatialActivity
        SharedPreferences sp = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        Set<String> collectedIds = new HashSet<>(sp.getStringSet(COLLECTED_EGGS_KEY, new HashSet<>()));

        repository.fetchAllEggs()
                .addOnSuccessListener(allEggs -> {
                    // Filter to collected eggs only
                    List<EggEntry> collected = new ArrayList<>();
                    for (EggEntry e : allEggs) {
                        if (e != null && e.id != null && collectedIds.contains(e.id)) {
                            collected.add(e);
                        }
                    }
                    bindList(collected);
                    showLoading(false);
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    emptyView.setText("Failed to load collectibles.");
                    emptyView.setVisibility(View.VISIBLE);
                });
    }

    private void bindList(List<EggEntry> items) {
        if (items == null || items.isEmpty()) {
            emptyView.setText("No collectibles yet.\nFind some eggs in AR first!");
            emptyView.setVisibility(View.VISIBLE);
            listView.setAdapter(null);
            return;
        }

        // Build a simple dataset for SimpleAdapter (title + desc)
        ArrayList<Map<String, String>> data = new ArrayList<>();
        for (EggEntry e : items) {
            Map<String, String> row = new HashMap<>();
            row.put("title", e.title != null ? e.title : "Egg");
            row.put("desc",  e.description != null ? e.description : "");
            data.add(row);
        }

        SimpleAdapter adapter = new SimpleAdapter(
                this,
                data,
                android.R.layout.simple_list_item_2,
                new String[] {"title", "desc"},
                new int[] {android.R.id.text1, android.R.id.text2}
        );
        listView.setAdapter(adapter);
        emptyView.setVisibility(View.GONE);

        // Show full card on tap
        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            Map<String, String> row = (Map<String, String>) parent.getItemAtPosition(position);
            String title = row.get("title");
            String desc  = row.get("desc");
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    .setTitle(title)
                    .setMessage(desc)
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        });
    }

    private void showLoading(boolean show) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        listView.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }
}
