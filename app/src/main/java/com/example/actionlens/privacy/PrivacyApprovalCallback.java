package com.example.actionlens.privacy;

/**
 * Callback for user consent decisions during the privacy approval workflow.
 */
public interface PrivacyApprovalCallback {

    /**
     * Called when the user explicitly consents to proceed with the sanitized, masked content.
     *
     * @param sanitizedText Content with sensitive items redacted
     */
    void onApproved(String sanitizedText);

    /**
     * Called when the user rejects processing or cancels the review.
     * The item must be immediately purged from temporary memory.
     */
    void onRejected();
}
