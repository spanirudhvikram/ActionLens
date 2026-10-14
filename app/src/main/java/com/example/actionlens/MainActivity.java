
package com.example.actionlens;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnViewTasks = findViewById(R.id.btnViewTasks);
        Button btnPrivacySettings = findViewById(R.id.btnPrivacySettings);

        btnViewTasks.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, TasksActivity.class);
            startActivity(intent);
        });

        if (btnPrivacySettings != null) {
            btnPrivacySettings.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, PrivacySettingsActivity.class);
                startActivity(intent);
            });
        }
    }
}
