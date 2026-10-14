package com.example.actionlens.service;

import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;
import com.example.actionlens.privacy.AiScreeningState;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.privacy.PrivacyEngine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Coordinates notification parsing, privacy inspection, duplicate suppression,
 * and persistence.
 *
 * Privacy Invariants:
 * 1. Only newly posted, eligible notification text is evaluated.
 * 2. Original notification text is processed ephemerally and never stored.
 * 3. LOW sensitivity notifications are safely auto-approved and extracted.
 * 4. MEDIUM/HIGH sensitivity notifications require explicit user consent. If the user
 *    cannot review immediately in the foreground, the content is discarded rather than buffered.
 * 5. Rejection/cancellation results in zero database writes.
 */
public class NotificationProcessor {

    public interface ForegroundReviewer {
        void requestApproval(PrivacyAssessment assessment, Runnable onApproved, Runnable onRejected);
    }

    private final TaskRepository repository;
    private final PrivacyEngine privacyEngine;
    private final ActionExtractor actionExtractor;
    private final Executor executor;

    // In-memory bounded LRU set tracking recently processed notification keys (max 100 entries)
    private final Set<String> processedKeys;

    public NotificationProcessor(TaskRepository repository) {
        this(repository, new PrivacyEngine(), new ActionExtractor(), Executors.newSingleThreadExecutor());
    }

    public NotificationProcessor(TaskRepository repository, PrivacyEngine privacyEngine, ActionExtractor actionExtractor) {
        this(repository, privacyEngine, actionExtractor, Executors.newSingleThreadExecutor());
    }

    public NotificationProcessor(TaskRepository repository, PrivacyEngine privacyEngine, ActionExtractor actionExtractor, Executor executor) {
        this.repository = repository;
        this.privacyEngine = privacyEngine;
        this.actionExtractor = actionExtractor;
        this.executor = executor != null ? executor : Executors.newSingleThreadExecutor();

        Map<String, Boolean> lruMap = new LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 100;
            }
        };
        this.processedKeys = Collections.synchronizedSet(Collections.newSetFromMap(lruMap));
    }

    public PrivacyEngine getPrivacyEngine() {
        return privacyEngine;
    }

    public AiScreeningState getAiScreeningState() {
        return privacyEngine != null ? privacyEngine.getScreeningState() : AiScreeningState.FAILED;
    }

    /**
     * Processes an incoming notification through the privacy-first pipeline.
     *
     * @param sourceAppLabel Human-readable label of originating app (e.g. "WhatsApp", "Slack")
     * @param notificationText Combined notification text
     * @param notificationKey Unique Android notification key
     * @param reviewer Optional foreground reviewer if an activity is currently active
     * @return true if an ActionTask was created or approval was requested; false if ignored/discarded
     */
    public boolean process(String sourceAppLabel, String notificationText, String notificationKey, ForegroundReviewer reviewer) {
        if (notificationText == null || notificationText.trim().isEmpty()) {
            return false;
        }

        // 0. AI Screening Readiness Guard
        AiScreeningState state = getAiScreeningState();
        if (state == AiScreeningState.INITIALIZING) {
            // While the model is INITIALIZING, do not pass notifications to a rule-only processor
            // for automatic approval. Drop notifications without retaining their raw text in a queue,
            // deferred runnable, database, or persistent storage.
            return false;
        }

        if (state == AiScreeningState.FAILED && reviewer == null) {
            // When initialization fails (FAILED), do not silently enable automatic rule-only processing.
            // If no foreground reviewer is present to provide explicit user consent, discard immediately.
            return false;
        }

        // 1. Relevance Decision Guard (Defense-in-depth: Reject non-actionable or uncertain content)
        NotificationFilter.FilterResult filterResult = NotificationFilter.evaluateRelevance(sourceAppLabel, notificationText);
        if (!filterResult.isAccepted()) {
            return false;
        }

        // 2. Duplicate Notification Check (by notification key)
        if (notificationKey != null && !notificationKey.isEmpty()) {
            if (processedKeys.contains(notificationKey)) {
                return false;
            }
            processedKeys.add(notificationKey);
        }

        // 2. Privacy Assessment & In-Place Masking
        PrivacyAssessment assessment = privacyEngine.assessAndMask(notificationText);

        // 3. Decision Gate
        if (PrivacyEngine.canAutoApprove(assessment)) {
            // LOW sensitivity: proceed directly on sanitized text
            return extractAndPersist(sourceAppLabel, assessment.getMaskedContent());
        } else {
            // MEDIUM or HIGH sensitivity
            if (reviewer != null) {
                // User is in foreground; request explicit approval
                reviewer.requestApproval(
                        assessment,
                        () -> executor.execute(() -> extractAndPersist(sourceAppLabel, assessment.getMaskedContent())),
                        () -> {
                            // On reject: zero database writes
                        }
                );
                return true;
            } else {
                // Privacy Mandate: If user cannot review immediately in the foreground,
                // DISCARD in-memory content immediately. Never queue or buffer raw or sanitized text!
                return false;
            }
        }
    }

    private boolean extractAndPersist(String sourceAppLabel, String sanitizedText) {
        ExtractedAction action = actionExtractor.extract(sanitizedText);

        // 4. Duplicate Task Check (by normalized title & deadline)
        if (repository != null) {
            int matching = repository.countMatchingTasks(action.getTitle(), action.getDeadlineTimestamp());
            if (matching > 0) {
                // Task with identical title and deadline is already active; suppress duplicate
                return false;
            }

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
                    sourceAppLabel != null ? sourceAppLabel : "Notification",
                    now,
                    now
            );

            repository.insertSync(task);
            return true;
        }
        return false;
    }
}
