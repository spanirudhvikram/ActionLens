package com.example.actionlens.privacy;

import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the privacy evaluation result for a processed text payload.
 * Contains only the sanitized/masked content, never the original raw text.
 */
public class PrivacyAssessment {

    private final SensitivityLevel sensitivityLevel;
    private final List<PiiType> detectedTypes;
    private final String maskedContent;
    private final boolean requiresUserApproval;
    private final String rationale;
    private final int redactedItemCount;

    public PrivacyAssessment(
            SensitivityLevel sensitivityLevel,
            List<PiiType> detectedTypes,
            String maskedContent,
            boolean requiresUserApproval,
            String rationale,
            int redactedItemCount) {
        this.sensitivityLevel = sensitivityLevel != null ? sensitivityLevel : SensitivityLevel.LOW;
        this.detectedTypes = detectedTypes != null ? Collections.unmodifiableList(detectedTypes) : Collections.emptyList();
        this.maskedContent = maskedContent != null ? maskedContent : "";
        this.requiresUserApproval = requiresUserApproval;
        this.rationale = rationale != null ? rationale : "";
        this.redactedItemCount = redactedItemCount;
    }

    public SensitivityLevel getSensitivityLevel() {
        return sensitivityLevel;
    }

    public List<PiiType> getDetectedTypes() {
        return detectedTypes;
    }

    public String getMaskedContent() {
        return maskedContent;
    }

    public boolean requiresUserApproval() {
        return requiresUserApproval;
    }

    public String getRationale() {
        return rationale;
    }

    public int getRedactedItemCount() {
        return redactedItemCount;
    }

    public boolean hasRedactions() {
        return redactedItemCount > 0;
    }
}
