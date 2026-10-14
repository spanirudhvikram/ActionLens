package com.example.actionlens.privacy;

/**
 * Lifecycle and readiness states of on-device AI/ML PII screening.
 *
 * Enforces strict fail-closed privacy guarantees:
 * - INITIALIZING: Model assets are being loaded in the background. Incoming notifications
 *   must be dropped immediately to prevent silent auto-approval of unscreened text.
 * - READY: Model and vocabulary are fully loaded and operational. Normal hybrid screening applies.
 * - FAILED: Model initialization failed. Silent rule-only auto-approval is strictly forbidden.
 */
public enum AiScreeningState {
    INITIALIZING,
    READY,
    FAILED
}
