package com.example.actionlens;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.actionlens.util.NotificationPermissionHelper;

/**
 * Activity presenting user controls and transparent explanations for notification access,
 * privacy invariants, and data retention.
 */
public class PrivacySettingsActivity extends AppCompatActivity {

    private TextView tvAccessStatusBadge;
    private TextView tvAccessStatusDesc;
    private Button btnToggleAccess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_settings);

        ImageView btnBack = findViewById(R.id.btnBackPrivacy);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        tvAccessStatusBadge = findViewById(R.id.tvAccessStatusBadge);
        tvAccessStatusDesc = findViewById(R.id.tvAccessStatusDesc);
        btnToggleAccess = findViewById(R.id.btnToggleAccess);

        if (btnToggleAccess != null) {
            btnToggleAccess.setOnClickListener(v ->
                    NotificationPermissionHelper.openNotificationListenerSettings(this)
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessStatus();
    }

    private void updateAccessStatus() {
        boolean isEnabled = NotificationPermissionHelper.isNotificationListenerEnabled(this);

        if (tvAccessStatusBadge == null || tvAccessStatusDesc == null || btnToggleAccess == null) {
            return;
        }

        if (isEnabled) {
            tvAccessStatusBadge.setText("ENABLED");
            tvAccessStatusBadge.setTextColor(Color.parseColor("#16A34A"));
            tvAccessStatusBadge.setBackgroundColor(Color.parseColor("#DCFCE7"));
            tvAccessStatusDesc.setText("ActionLens is authorized to inspect incoming notifications. Processing happens strictly on-device, and raw text is never logged or stored.");
            btnToggleAccess.setText("Manage in System Settings");
            btnToggleAccess.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#475569")));
        } else {
            tvAccessStatusBadge.setText("DISABLED");
            tvAccessStatusBadge.setTextColor(Color.parseColor("#DC2626"));
            tvAccessStatusBadge.setBackgroundColor(Color.parseColor("#FEE2E2"));
            tvAccessStatusDesc.setText("Notification access is currently disabled. ActionLens cannot automatically discover tasks from messages, emails, or alerts until authorized.");
            btnToggleAccess.setText("Enable Notification Access");
            btnToggleAccess.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1E3A8A")));
        }
    }
}
