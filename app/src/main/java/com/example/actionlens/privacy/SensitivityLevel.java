package com.example.actionlens.privacy;

/**
 * Categorization of information sensitivity.
 * Defines the handling and approval policy required before persistence.
 */
public enum SensitivityLevel {
    /**
     * Standard task content with no detected PII or security risks.
     * Safe for direct processing.
     */
    LOW("Low Sensitivity", "No sensitive personal details detected."),

    /**
     * Contains contact details (phone, email) or ambiguous entities.
     * Requires user review/approval before saving.
     */
    MEDIUM("Medium Sensitivity", "Contains personal contact details. Review recommended."),

    /**
     * Contains credentials, OTPs, PINs, or financial identifiers.
     * High risk; strictly requires user confirmation and mandatory masking.
     */
    HIGH("High Sensitivity", "Contains credentials, verification codes, or financial data. Must be masked and approved.");

    private final String label;
    private final String policySummary;

    SensitivityLevel(String label, String policySummary) {
        this.label = label;
        this.policySummary = policySummary;
    }

    public String getLabel() {
        return label;
    }

    public String getPolicySummary() {
        return policySummary;
    }
}
