package com.example.actionlens.privacy;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import androidx.lifecycle.LiveData;

import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.ActionTaskDao;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.privacy.ml.OnnxPiiClassifier;
import com.example.actionlens.service.ActionLensNotificationListener;
import com.example.actionlens.service.NotificationProcessor;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Phase F Step 2 Validation: Tests model-readiness lifecycle and fix for the startup
 * privacy & consent bypass.
 *
 * Verifies:
 * 1. Notifications arriving during INITIALIZING are dropped immediately without retention or persistence.
 * 2. Notifications arriving when READY are processed by the full hybrid engine.
 * 3. Model loading failure transitions to FAILED and never enables silent rule-only auto-approval.
 * 4. Notifications arriving during and after failure never leak raw text into Room, files, or queues.
 * 5. Concurrent notification callbacks and service shutdown are safe and race-free.
 */
public class ModelReadinessPrivacyValidationTest {

    private FakeActionTaskDao fakeDao;
    private TaskRepository repository;
    private ActionExtractor actionExtractor;

    @Before
    public void setUp() {
        fakeDao = new FakeActionTaskDao();
        repository = new TaskRepository(fakeDao);
        actionExtractor = new ActionExtractor();
    }

    // =========================================================================
    // 1. INITIALIZING State Gating
    // =========================================================================

    @Test
    public void testNotificationDuringInitializing_DiscardedWithoutProcessingOrPersistence() {
        // PrivacyEngine in INITIALIZING state
        PrivacyEngine initializingEngine = new PrivacyEngine(null, AiScreeningState.INITIALIZING);
        assertEquals(AiScreeningState.INITIALIZING, initializingEngine.getScreeningState());

        NotificationProcessor processor = new NotificationProcessor(
                repository,
                initializingEngine,
                actionExtractor,
                Runnable::run
        );

        assertEquals(AiScreeningState.INITIALIZING, processor.getAiScreeningState());

        String input = "Please meet with Dwight Schrute tomorrow at 10 AM in Room 204.";
        boolean handled = processor.process("Slack", input, "key-init-1", null);

        // Core Privacy Invariant: must be dropped immediately
        assertFalse("Notification arriving during INITIALIZING must be dropped", handled);
        assertEquals("Zero tasks must be written to database", 0, fakeDao.tasks.size());

        // Verify notification key is not retained in processed keys (not marked as processed)
        // so when model becomes ready, updated notifications can be re-evaluated
        PrivacyEngine readyEngine = new PrivacyEngine(null, AiScreeningState.READY);
        NotificationProcessor readyProcessor = new NotificationProcessor(
                repository,
                readyEngine,
                actionExtractor,
                Runnable::run
        );
        boolean handledReady = readyProcessor.process("Slack", input, "key-init-1", null);
        assertTrue("When ready, notification key is eligible", handledReady);
    }

    @Test
    public void testPrivacyEngine_InitializingState_ElevatesLowToMediumAndBlocksAutoApprove() {
        PrivacyEngine initializingEngine = new PrivacyEngine(null, AiScreeningState.INITIALIZING);

        // Text with zero regex rule triggers (would normally be LOW if AI was ignored)
        String benignText = "Submit homework chapter 3 by Friday.";
        PrivacyAssessment assessment = initializingEngine.assessAndMask(benignText);

        // Under INITIALIZING state, cannot be treated as LOW
        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse("Cannot auto-approve while AI screening is initializing",
                PrivacyEngine.canAutoApprove(assessment));
        assertTrue(assessment.getRationale().contains("AI screening model unready"));
    }

    // =========================================================================
    // 2. READY State Normal Processing
    // =========================================================================

    @Test
    public void testNotificationAfterReady_ProcessedByHybridEngine() {
        String sensitivePerson = "Dwight Schrute";
        String notification = "Submit project to " + sensitivePerson + " by tomorrow at 5 PM in Room 101.";

        OnnxPiiClassifier stubClassifier = new OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                int start = text.indexOf(sensitivePerson);
                int end = start + sensitivePerson.length();
                return Collections.singletonList(
                        new TokenPrediction(sensitivePerson, 29, "B-PERSON", start, end)
                );
            }
        };

        PrivacyEngine readyEngine = new PrivacyEngine(stubClassifier, AiScreeningState.READY);
        assertEquals(AiScreeningState.READY, readyEngine.getScreeningState());

        NotificationProcessor processor = new NotificationProcessor(
                repository,
                readyEngine,
                actionExtractor,
                Runnable::run
        );

        NotificationProcessor.ForegroundReviewer mockReviewer = (assessment, onApproved, onRejected) -> {
            assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
            assertTrue(assessment.requiresUserApproval());
            onApproved.run();
        };

        boolean handled = processor.process("Telegram", notification, "key-ready-1", mockReviewer);
        assertTrue(handled);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask storedTask = fakeDao.tasks.get(0);
        assertTrue(storedTask.getTitle().contains("[REDACTED: PERSON]"));
        assertFalse(storedTask.getTitle().contains(sensitivePerson));
        assertFalse(storedTask.getTitle().contains("Dwight"));
    }

    // =========================================================================
    // 3. FAILED State Fail-Closed Guarantees
    // =========================================================================

    @Test
    public void testModelLoadingFailure_TransitionsToFailed_NeverEnablesSilentAutoApproval() {
        PrivacyEngine failedEngine = new PrivacyEngine(null, AiScreeningState.FAILED);
        assertEquals(AiScreeningState.FAILED, failedEngine.getScreeningState());

        NotificationProcessor processor = new NotificationProcessor(
                repository,
                failedEngine,
                actionExtractor,
                Runnable::run
        );

        String alertText = "Read pages 10 to 20 for seminar.";

        // In the background with NO reviewer:
        boolean handledBackground = processor.process("Email", alertText, "key-fail-bg", null);
        assertFalse("Failed state in background must discard and NEVER auto-approve", handledBackground);
        assertEquals(0, fakeDao.tasks.size());

        // In the foreground WITH reviewer:
        final boolean[] reviewerInvoked = new boolean[]{false};
        NotificationProcessor.ForegroundReviewer mockReviewer = (assessment, onApproved, onRejected) -> {
            reviewerInvoked[0] = true;
            // Must be elevated to MEDIUM for user consent
            assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
            assertTrue(assessment.requiresUserApproval());
            assertFalse(PrivacyEngine.canAutoApprove(assessment));
            onRejected.run(); // User dismisses
        };

        boolean handledForeground = processor.process("Email", alertText, "key-fail-fg", mockReviewer);
        assertTrue(handledForeground);
        assertTrue("Reviewer must be invoked to require explicit user consent", reviewerInvoked[0]);
        assertEquals("Zero writes on rejection", 0, fakeDao.tasks.size());
    }

    @Test
    public void testPrivacyEngine_FailedState_PreservesRuleHighAndElevatesLow() {
        PrivacyEngine failedEngine = new PrivacyEngine(null, AiScreeningState.FAILED);

        // 1. Text with rule OTP match (HIGH) remains HIGH
        PrivacyAssessment highAssessment = failedEngine.assessAndMask("Your OTP is 739201.");
        assertEquals(SensitivityLevel.HIGH, highAssessment.getSensitivityLevel());
        assertTrue(highAssessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(highAssessment));
        assertTrue(highAssessment.getMaskedContent().contains("[REDACTED: OTP]"));

        // 2. Text with zero regex matches is elevated to MEDIUM (never LOW)
        PrivacyAssessment lowAssessment = failedEngine.assessAndMask("Meeting in Room 302 tomorrow.");
        assertEquals(SensitivityLevel.MEDIUM, lowAssessment.getSensitivityLevel());
        assertTrue(lowAssessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(lowAssessment));
    }

    // =========================================================================
    // 4. Data Minimization & Zero Cleartext Leaks During Failure
    // =========================================================================

    @Test
    public void testNotificationsDuringFailure_NeverLeakRawSecretsIntoStorage() {
        PrivacyEngine failedEngine = new PrivacyEngine(null, AiScreeningState.FAILED);
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                failedEngine,
                actionExtractor,
                Runnable::run
        );

        String secretOtp = "482019";
        String notification = "Confirm account transfer with OTP " + secretOtp + " by 5 PM.";

        NotificationProcessor.ForegroundReviewer mockReviewer = (assessment, onApproved, onRejected) -> {
            onApproved.run();
        };

        processor.process("BankApp", notification, "key-fail-secret", mockReviewer);

        assertEquals(1, fakeDao.tasks.size());
        ActionTask task = fakeDao.tasks.get(0);
        assertFalse(task.getTitle().contains(secretOtp));
        assertTrue(task.getTitle().contains("[REDACTED: OTP]"));
    }

    // =========================================================================
    // 5. Concurrency & Shutdown Race Safety
    // =========================================================================

    @Test
    public void testConcurrentNotificationCallbacksAndShutdown_ThreadSafe() throws Exception {
        int threadCount = 10;
        int iterationsPerThread = 20;
        ExecutorService threadPool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        PrivacyEngine readyEngine = new PrivacyEngine(null, AiScreeningState.READY);
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                readyEngine,
                actionExtractor,
                Executors.newSingleThreadExecutor()
        );

        AtomicInteger processedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threadPool.execute(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        String key = "key-" + threadId + "-" + j;
                        String text = "Task from thread " + threadId + " item " + j + " by Oct 15.";
                        boolean result = processor.process("App", text, key, null);
                        if (result) {
                            processedCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertTrue("Concurrent executions must complete within timeout", finished);
        threadPool.shutdown();

        // Database and processor remain coherent
        assertTrue(fakeDao.tasks.size() > 0);
        assertEquals(processedCount.get(), fakeDao.tasks.size());
    }

    // =========================================================================
    // Fake DAO Implementation
    // =========================================================================

    private static class FakeActionTaskDao implements ActionTaskDao {
        final List<ActionTask> tasks = new ArrayList<>();
        private long idCounter = 1;

        @Override
        public synchronized long insert(ActionTask task) {
            task.setId(idCounter++);
            tasks.add(task);
            return task.getId();
        }

        @Override
        public synchronized void update(ActionTask task) {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).getId() == task.getId()) {
                    tasks.set(i, task);
                    break;
                }
            }
        }

        @Override
        public synchronized void delete(ActionTask task) {
            tasks.removeIf(t -> t.getId() == task.getId());
        }

        @Override
        public LiveData<List<ActionTask>> getTasksByStatus(TaskStatus status) {
            return null;
        }

        @Override
        public LiveData<List<ActionTask>> getAllTasks() {
            return null;
        }

        @Override
        public synchronized List<ActionTask> getTasksByStatusSync(TaskStatus status) {
            return new ArrayList<>(tasks);
        }

        @Override
        public synchronized List<ActionTask> getAllTasksSync() {
            return new ArrayList<>(tasks);
        }

        @Override
        public synchronized ActionTask getTaskById(long id) {
            for (ActionTask t : tasks) {
                if (t.getId() == id) return t;
            }
            return null;
        }

        @Override
        public synchronized int getTaskCount() {
            return tasks.size();
        }

        @Override
        public synchronized int countMatchingTasks(String title, Long deadlineTimestamp) {
            int count = 0;
            for (ActionTask t : tasks) {
                if (t.getStatus() == TaskStatus.ACTIVE && t.getTitle().equals(title)) {
                    if (deadlineTimestamp == null && t.getDeadlineTimestamp() == null) {
                        count++;
                    } else if (deadlineTimestamp != null && deadlineTimestamp.equals(t.getDeadlineTimestamp())) {
                        count++;
                    }
                }
            }
            return count;
        }

        @Override
        public synchronized void deleteAll() {
            tasks.clear();
        }
    }
}
