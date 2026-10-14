package com.example.actionlens.privacy;

/**
 * Enumeration of identifiable Personally Identifiable Information (PII)
 * and sensitive data categories handled by ActionLens.
 */
public enum PiiType {
    EMAIL("Email Address"),
    PHONE("Phone Number"),
    OTP("One-Time Password / Verification Code"),
    PASSWORD("Password / Security PIN"),
    FINANCIAL("Financial Identifier / Card / Account"),
    PERSON("Personal Name / Identity"),
    LOCATION("Location / Address"),
    IDENTIFIER("Personal / Government Identifier");

    private final String displayName;

    PiiType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
