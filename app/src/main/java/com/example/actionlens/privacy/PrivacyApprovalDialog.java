package com.example.actionlens.privacy;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.example.actionlens.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dialog component presenting the privacy review workflow to the user.
 * Displays only the masked/sanitized version of sensitive or uncertain content,
 * giving the user transparent control before persistence.
 */
public class PrivacyApprovalDialog {

    private final Context context;
    private final PrivacyAssessment assessment;
    private final PrivacyApprovalCallback callback;

    public PrivacyApprovalDialog(Context context, PrivacyAssessment assessment, PrivacyApprovalCallback callback) {
        this.context = context;
        this.assessment = assessment;
        this.callback = callback;
    }

    public void show() {
        if (context == null || assessment == null) {
            if (callback != null) {
                callback.onRejected();
            }
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.dialog_privacy_approval, null);

        TextView tvBadge = dialogView.findViewById(R.id.tvSensitivityBadge);
        TextView tvRationale = dialogView.findViewById(R.id.tvPrivacyRationale);
        TextView tvPreview = dialogView.findViewById(R.id.tvMaskedPreview);

        SensitivityLevel level = assessment.getSensitivityLevel();
        if (level == SensitivityLevel.HIGH) {
            tvBadge.setText("HIGH SENSITIVITY REVIEW");
            tvBadge.setTextColor(Color.parseColor("#991B1B"));
            tvBadge.setBackgroundColor(Color.parseColor("#FEE2E2"));
        } else if (level == SensitivityLevel.MEDIUM) {
            tvBadge.setText("MEDIUM SENSITIVITY REVIEW");
            tvBadge.setTextColor(Color.parseColor("#92400E"));
            tvBadge.setBackgroundColor(Color.parseColor("#FEF3C7"));
        } else {
            tvBadge.setText("LOW SENSITIVITY");
            tvBadge.setTextColor(Color.parseColor("#065F46"));
            tvBadge.setBackgroundColor(Color.parseColor("#D1FAE5"));
        }

        tvRationale.setText(assessment.getRationale());
        tvPreview.setText(assessment.getMaskedContent());

        final boolean[] responded = new boolean[]{false};

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Privacy & Security Review")
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton("Approve (Save Masked)", (d, which) -> {
                    if (!responded[0]) {
                        responded[0] = true;
                        if (callback != null) {
                            callback.onApproved(assessment.getMaskedContent());
                        }
                    }
                })
                .setNegativeButton("Discard", (d, which) -> {
                    if (!responded[0]) {
                        responded[0] = true;
                        if (callback != null) {
                            callback.onRejected();
                        }
                    }
                })
                .setOnCancelListener(d -> {
                    if (!responded[0]) {
                        responded[0] = true;
                        if (callback != null) {
                            callback.onRejected();
                        }
                    }
                })
                .setOnDismissListener(d -> {
                    if (!responded[0]) {
                        responded[0] = true;
                        if (callback != null) {
                            callback.onRejected();
                        }
                    }
                })
                .create();

        dialog.show();
    }
}
