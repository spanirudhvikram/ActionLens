package com.example.actionlens.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, bounded, privacy-safe diagnostics tracker for notification processing.
 *
 * Privacy Invariants:
 * 1. ZERO raw notification bodies, personal names, phone numbers, email addresses, or unredacted secrets are ever recorded.
 * 2. Only high-level reason codes, source app labels, and sanitized reason codes are retained.
 * 3. Operates entirely in-memory with strictly bounded size (max 100 recent events).
 * 4. Never persists diagnostic data to database or disk files.
 */
public final class ProcessingDiagnostics {

    public static final String RECEIVED = "RECEIVED";
    public static final String FILTERED_NON_ACTIONABLE = "FILTERED_NON_ACTIONABLE";
    public static final String FILTERED_DUPLICATE = "FILTERED_DUPLICATE";
    public static final String PRIVACY_REVIEW_REQUIRED = "PRIVACY_REVIEW_REQUIRED";
    public static final String REJECTED_BY_USER = "REJECTED_BY_USER";
    public static final String EXTRACTION_FAILED = "EXTRACTION_FAILED";
    public static final String TASK_SAVED = "TASK_SAVED";
    public static final String PERSISTENCE_FAILED = "PERSISTENCE_FAILED";

    public static final class DiagnosticEvent {
        private final long timestamp;
        private final String reasonCode;
        private final String sourceAppLabel;
        private final String safeDetail;

        public DiagnosticEvent(long timestamp, String reasonCode, String sourceAppLabel, String safeDetail) {
            this.timestamp = timestamp;
            this.reasonCode = reasonCode;
            this.sourceAppLabel = sourceAppLabel != null ? sourceAppLabel : "Unknown";
            this.safeDetail = safeDetail != null ? safeDetail : "";
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getSourceAppLabel() {
            return sourceAppLabel;
        }

        public String getSafeDetail() {
            return safeDetail;
        }
    }

    private static final int MAX_RECENT_EVENTS = 100;

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final List<DiagnosticEvent> recentEvents = Collections.synchronizedList(new ArrayList<>());
    private final ContentDeduplicator.Clock clock;

    public ProcessingDiagnostics() {
        this(System::currentTimeMillis);
    }

    public ProcessingDiagnostics(ContentDeduplicator.Clock clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
        for (String code : new String[]{
                RECEIVED, FILTERED_NON_ACTIONABLE, FILTERED_DUPLICATE,
                PRIVACY_REVIEW_REQUIRED, REJECTED_BY_USER,
                EXTRACTION_FAILED, TASK_SAVED, PERSISTENCE_FAILED
        }) {
            counters.put(code, new AtomicInteger(0));
        }
    }

    public void recordEvent(String reasonCode, String sourceAppLabel) {
        recordEvent(reasonCode, sourceAppLabel, null);
    }

    public void recordEvent(String reasonCode, String sourceAppLabel, String safeDetail) {
        if (reasonCode == null) return;
        counters.computeIfAbsent(reasonCode, k -> new AtomicInteger(0)).incrementAndGet();

        synchronized (recentEvents) {
            if (recentEvents.size() >= MAX_RECENT_EVENTS) {
                recentEvents.remove(0);
            }
            recentEvents.add(new DiagnosticEvent(clock.currentTimeMillis(), reasonCode, sourceAppLabel, safeDetail));
        }
    }

    public int getCount(String reasonCode) {
        AtomicInteger count = counters.get(reasonCode);
        return count != null ? count.get() : 0;
    }

    public List<DiagnosticEvent> getRecentEvents() {
        synchronized (recentEvents) {
            return Collections.unmodifiableList(new ArrayList<>(recentEvents));
        }
    }

    public void reset() {
        for (AtomicInteger counter : counters.values()) {
            counter.set(0);
        }
        recentEvents.clear();
    }
}
