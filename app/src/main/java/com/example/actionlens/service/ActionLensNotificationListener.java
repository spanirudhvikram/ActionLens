package com.example.actionlens.service;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.privacy.AiScreeningState;
import com.example.actionlens.privacy.PrivacyEngine;
import com.example.actionlens.privacy.ml.BertWordPieceTokenizer;
import com.example.actionlens.privacy.ml.OnnxPiiClassifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System notification listener service for ActionLens.
 *
 * Privacy Invariants:
 * 1. Only processes newly posted, non-ongoing, non-system notifications.
 * 2. Originating text is processed ephemerally and never logged or persisted.
 * 3. All database operations and privacy assessments execute off the main thread.
 * 4. Sensitive notifications are discarded if the user cannot review immediately.
 * 5. While AI model is INITIALIZING, notifications are dropped immediately without retaining raw text.
 * 6. If AI initialization fails, silent rule-only auto-approval is strictly forbidden.
 */
public class ActionLensNotificationListener extends NotificationListenerService {

    private static volatile NotificationProcessor.ForegroundReviewer activeForegroundReviewer;

    public static void setForegroundReviewer(NotificationProcessor.ForegroundReviewer reviewer) {
        activeForegroundReviewer = reviewer;
    }

    public static void clearForegroundReviewer(NotificationProcessor.ForegroundReviewer reviewer) {
        if (activeForegroundReviewer == reviewer) {
            activeForegroundReviewer = null;
        }
    }

    public static NotificationProcessor.ForegroundReviewer getForegroundReviewer() {
        return activeForegroundReviewer;
    }

    private final AtomicReference<AiScreeningState> screeningState =
            new AtomicReference<>(AiScreeningState.INITIALIZING);

    private ExecutorService workerExecutor;
    private volatile NotificationProcessor processor;
    private volatile OnnxPiiClassifier classifier;

    public AiScreeningState getAiScreeningState() {
        return screeningState.get();
    }

    public NotificationProcessor getProcessor() {
        return processor;
    }

    /**
     * Package-private helper allowing unit/instrumentation tests to configure readiness state.
     */
    void setTestState(AiScreeningState state, NotificationProcessor testProcessor) {
        synchronized (this) {
            this.screeningState.set(state);
            this.processor = testProcessor;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.workerExecutor = Executors.newSingleThreadExecutor();
        this.screeningState.set(AiScreeningState.INITIALIZING);

        TaskRepository repository = new TaskRepository(getApplication());
        // Processor initially created with INITIALIZING state; cannot auto-approve
        this.processor = new NotificationProcessor(
                repository,
                new PrivacyEngine(null, AiScreeningState.INITIALIZING),
                new ActionExtractor(),
                workerExecutor
        );

        workerExecutor.execute(() -> {
            try {
                BertWordPieceTokenizer tokenizer = OnnxPiiClassifier.loadTokenizerFromAssets(getApplicationContext());
                OnnxPiiClassifier loadedClassifier = OnnxPiiClassifier.fromAsset(getApplicationContext(), tokenizer);

                synchronized (this) {
                    if (workerExecutor.isShutdown()) {
                        loadedClassifier.close();
                        return;
                    }
                    this.classifier = loadedClassifier;
                    this.processor = new NotificationProcessor(
                            repository,
                            new PrivacyEngine(loadedClassifier, AiScreeningState.READY),
                            new ActionExtractor(),
                            workerExecutor
                    );
                    this.screeningState.set(AiScreeningState.READY);
                }
            } catch (Exception e) {
                // Fail-closed: Never log raw notification text, PII, or sensitive exception details
                synchronized (this) {
                    this.screeningState.set(AiScreeningState.FAILED);
                    this.processor = new NotificationProcessor(
                            repository,
                            new PrivacyEngine(null, AiScreeningState.FAILED),
                            new ActionExtractor(),
                            workerExecutor
                    );
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (this) {
            screeningState.set(AiScreeningState.FAILED);
            if (workerExecutor != null) {
                workerExecutor.shutdown();
            }
            if (classifier != null) {
                classifier.close();
                classifier = null;
            }
            processor = null;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }

        // 1. AI Screening Readiness Guard:
        // While INITIALIZING, drop notifications immediately without retaining raw text
        // in a queue, deferred runnable, database, file, or persistent storage.
        AiScreeningState currentState = screeningState.get();
        if (currentState == AiScreeningState.INITIALIZING) {
            return;
        }

        // 2. If FAILED and no foreground reviewer is active: drop immediately.
        // Never silently enable automatic rule-only processing.
        if (currentState == AiScreeningState.FAILED && activeForegroundReviewer == null) {
            return;
        }

        Notification notification = sbn.getNotification();
        boolean isOngoing = sbn.isOngoing() || (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean isGroupSummary = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

        String title = "";
        String text = "";
        String bigText = "";
        Bundle extras = notification.extras;
        if (extras != null) {
            CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence bt = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence txt = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (t != null) {
                title = t.toString();
            }
            if (bt != null) {
                bigText = bt.toString();
            }
            if (txt != null) {
                text = txt.toString();
            }
        }

        if (!NotificationFilter.isEligible(
                sbn.getPackageName(),
                getPackageName(),
                isOngoing,
                isGroupSummary,
                title,
                text,
                bigText)) {
            return;
        }

        String eligibleBody = NotificationFilter.extractNotificationBody(title, text, bigText);
        String appLabel = resolveAppLabel(sbn.getPackageName());
        String source = resolveSource(appLabel, title, eligibleBody);
        String notificationKey = sbn.getKey();

        try {
            workerExecutor.execute(() -> {
                NotificationProcessor currentProc = processor;
                if (currentProc != null && screeningState.get() != AiScreeningState.INITIALIZING) {
                    currentProc.process(source, eligibleBody, notificationKey, activeForegroundReviewer);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Service was shut down concurrently
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Notification removals do not delete previously captured action tasks
    }

    private String resolveAppLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "Notification";
        }
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private String resolveSource(String appLabel, String title, String body) {
        if (appLabel != null && !appLabel.equals("Shell") && !appLabel.equals("Notification") && !appLabel.isEmpty()) {
            return appLabel;
        }
        if (title != null && !title.trim().isEmpty() && !title.trim().equals(body)) {
            return title.trim();
        }
        return appLabel != null ? appLabel : "Notification";
    }
}
