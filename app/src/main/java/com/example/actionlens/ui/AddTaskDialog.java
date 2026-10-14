package com.example.actionlens.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.actionlens.R;
import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;
import com.example.actionlens.privacy.PrivacyApprovalCallback;
import com.example.actionlens.privacy.PrivacyApprovalDialog;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.privacy.PrivacyEngine;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Controller for the manual task entry dialog.
 *
 * Implements the complete privacy-first pipeline:
 * Input -> PrivacyEngine Assessment -> (User Approval Gate if sensitive) -> ActionExtractor -> Room Persistence.
 */
public class AddTaskDialog {

    public interface OnTaskCreatedListener {
        void onTaskCreated(ActionTask task);
    }

    private final Context context;
    private final TaskRepository repository;
    private final PrivacyEngine privacyEngine;
    private final ActionExtractor actionExtractor;
    private final OnTaskCreatedListener listener;

    public AddTaskDialog(Context context,
                         TaskRepository repository,
                         OnTaskCreatedListener listener) {
        this.context = context;
        this.repository = repository;
        this.listener = listener;
        this.privacyEngine = new PrivacyEngine();
        this.actionExtractor = new ActionExtractor();
    }

    public void show() {
        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.dialog_add_task, null);

        TextInputLayout tilAnnouncement = dialogView.findViewById(R.id.tilAnnouncement);
        TextInputEditText etAnnouncement = dialogView.findViewById(R.id.etAnnouncementInput);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(dialogView)
                .setCancelable(true)
                .setPositiveButton("Extract & Create Card", null) // Overridden below to prevent auto-dismiss on error
                .setNegativeButton("Cancel", (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = etAnnouncement.getText() != null ? etAnnouncement.getText().toString().trim() : "";

                // Validate empty input
                if (input.isEmpty()) {
                    tilAnnouncement.setError("Please enter or paste an announcement.");
                    return;
                }
                tilAnnouncement.setError(null);
                dialog.dismiss();

                // Process through Privacy Pipeline
                processInput(input);
            });
        });

        dialog.show();
    }

    private void processInput(String rawInput) {
        // 1. Privacy Assessment & In-Place Masking
        PrivacyAssessment assessment = privacyEngine.assessAndMask(rawInput);

        // 2. Decision Gate: Auto-approve LOW or require Explicit Approval for MEDIUM/HIGH
        if (PrivacyEngine.canAutoApprove(assessment)) {
            // LOW sensitivity: proceed directly with sanitized content
            extractAndSave(assessment.getMaskedContent());
        } else {
            // MEDIUM or HIGH sensitivity: launch Privacy Approval Dialog
            PrivacyApprovalDialog approvalDialog = new PrivacyApprovalDialog(
                    context,
                    assessment,
                    new PrivacyApprovalCallback() {
                        @Override
                        public void onApproved(String sanitizedText) {
                            extractAndSave(sanitizedText);
                        }

                        @Override
                        public void onRejected() {
                            Toast.makeText(context, "Task discarded. Zero data stored.", Toast.LENGTH_SHORT).show();
                        }
                    }
            );
            approvalDialog.show();
        }
    }

    private void extractAndSave(String sanitizedText) {
        // 3. Action Extraction from Sanitized Content Only
        ExtractedAction action = actionExtractor.extract(sanitizedText);

        // 4. Ambiguous Date Handling: Confirm with user instead of guessing
        if (action.isDateAmbiguous()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("Confirm Ambiguous Date")
                    .setMessage(action.getAmbiguousDateReason() + "\n\nWould you like to keep '" +
                            action.getDeadlineFormatted() + "' as the deadline?")
                    .setPositiveButton("Confirm", (d, which) -> persistTask(action))
                    .setNegativeButton("Remove Date", (d, which) -> {
                        // Persist task without ambiguous deadline
                        ExtractedAction noDateAction = new ExtractedAction(
                                action.getTitle(),
                                action.getActionVerb(),
                                null,
                                null,
                                false,
                                false,
                                null,
                                action.getPriority(),
                                action.getLocation(),
                                action.getSource()
                        );
                        persistTask(noDateAction);
                    })
                    .show();
        } else {
            persistTask(action);
        }
    }

    private void persistTask(ExtractedAction action) {
        long now = System.currentTimeMillis();
        ActionTask task = new ActionTask(
                action.getTitle(),
                action.getActionVerb(),
                action.getDeadlineTimestamp(),
                action.getDeadlineFormatted(),
                action.isHasExplicitTime(),
                action.getPriority(),
                TaskStatus.ACTIVE,
                action.getLocation(),
                action.getSource(),
                now,
                now
        );

        // 5. Background Room Persistence
        repository.insert(task, taskId -> {
            task.setId(taskId);
            if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).runOnUiThread(() -> {
                    Toast.makeText(context, "Action Card created!", Toast.LENGTH_SHORT).show();
                    if (listener != null) {
                        listener.onTaskCreated(task);
                    }
                });
            }
        });
    }
}
