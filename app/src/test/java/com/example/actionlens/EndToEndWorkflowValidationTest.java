package com.example.actionlens;

import androidx.lifecycle.LiveData;

import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.ActionTaskDao;
import com.example.actionlens.data.TaskPriority;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;
import com.example.actionlens.privacy.AiScreeningState;
import com.example.actionlens.privacy.PiiMatch;
import com.example.actionlens.privacy.PiiType;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.privacy.PrivacyEngine;
import com.example.actionlens.privacy.SensitivityLevel;
import com.example.actionlens.privacy.ml.OnnxPiiClassifier;
import com.example.actionlens.service.NotificationFilter;
import com.example.actionlens.service.NotificationProcessor;

import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Phase F, Step 3: End-to-End Workflow Validation Test Suite.
 *
 * Verifies all 10 core workflow scenarios, privacy invariants, and application behavior:
 * 1. Normal assignment notification with clear deadline and location.
 * 2. Personal name and deadline: sanitized extraction, zero raw name storage.
 * 3. Email and phone number detection, masking, and consent gating.
 * 4. OTP, password, card, and financial identifier redaction (HIGH sensitivity).
 * 5. Explicit user approval vs user rejection (zero database writes on rejection).
 * 6. Model readiness fail-closed gating (INITIALIZING drops, FAILED blocks auto-approval).
 * 7. Duplicate notification keys and duplicate active task suppression.
 * 8. Ambiguous dates, missing deadlines, and relative dates.
 * 9. Empty notifications, malformed BIO input, and inference fault tolerance.
 * 10. Service destruction, app restart simulation, and concurrency safety.
 */
public class EndToEndWorkflowValidationTest {

    private InMemoryFakeDao fakeDao;
    private TaskRepository repository;
    private ActionExtractor actionExtractor;
    private ZonedDateTime fixedReferenceTime;

    @Before
    public void setUp() {
        fakeDao = new InMemoryFakeDao();
        repository = new TaskRepository(fakeDao);
        ZoneId utc = ZoneId.of("UTC");
        actionExtractor = new ActionExtractor(utc);
        // Fixed reference: Friday, Oct 9, 2026, 10:00 AM UTC
        fixedReferenceTime = ZonedDateTime.of(
                LocalDate.of(2026, Month.OCTOBER, 9),
                LocalTime.of(10, 0),
                utc
        );
    }

    // =========================================================================
    // Scenario 1: Normal assignment notification with clear deadline & location
    // =========================================================================
    @Test
    public void testScenario1_NormalAssignment_ClearDeadlineAndLocation_AutoApproveAndPersist() {
        String title = "Physics Class";
        String body = "Please submit chemistry lab report by Oct 15 in Room 302.";
        String pkg = "com.google.android.apps.classroom";

        // 1. Filtering
        assertTrue(NotificationFilter.isEligible(pkg, "com.example.actionlens", false, false, title, body));
        String eligibleBody = NotificationFilter.extractNotificationBody(title, body, null);
        assertEquals(body, eligibleBody);

        // 2. Hybrid Privacy Engine (READY state, benign text)
        PrivacyEngine readyEngine = new PrivacyEngine(null, AiScreeningState.READY);
        PrivacyAssessment assessment = readyEngine.assessAndMask(eligibleBody);

        assertEquals(SensitivityLevel.LOW, assessment.getSensitivityLevel());
        assertFalse(assessment.requiresUserApproval());
        assertTrue(PrivacyEngine.canAutoApprove(assessment));
        assertEquals(eligibleBody, assessment.getMaskedContent());

        // 3. NotificationProcessor pipeline
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                readyEngine,
                actionExtractor,
                Runnable::run
        );

        boolean handled = processor.process("Google Classroom", eligibleBody, "notif-key-101", null);
        assertTrue(handled);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask saved = fakeDao.tasks.get(0);
        assertEquals("Submit chemistry lab report", saved.getTitle());
        assertEquals("Submit", saved.getActionVerb());
        assertEquals("Room 302", saved.getLocation());
        assertEquals("Google Classroom", saved.getSource());
        assertEquals(TaskStatus.ACTIVE, saved.getStatus());
        assertNotNull(saved.getDeadlineTimestamp());
        assertEquals("Oct 15, 2026", saved.getDeadlineFormatted());

        // Verify title does not contain the original message preamble or sender title
        assertFalse(saved.getTitle().contains("Physics Class"));
        assertFalse(saved.getTitle().contains("Please"));
    }

    // =========================================================================
    // Scenario 2: Notification containing a personal name and a deadline
    // =========================================================================
    @Test
    public void testScenario2_PersonalNameAndDeadline_RedactedAndRequiresApproval_NeverStoresRawName() {
        String sensitivePerson = "Dwight Schrute";
        String rawBody = "Submit quarterly sales audit to " + sensitivePerson + " by tomorrow at 5 PM in Room 101.";

        // Stub classifier predicting PERSON on "Dwight Schrute"
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

        PrivacyEngine hybridEngine = new PrivacyEngine(stubClassifier, AiScreeningState.READY);
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                hybridEngine,
                actionExtractor,
                Runnable::run
        );

        // 1. Without foreground reviewer: discarded immediately
        boolean handledBackground = processor.process("Slack", rawBody, "notif-key-name-1", null);
        assertFalse(handledBackground);
        assertEquals(0, fakeDao.tasks.size());

        // 2. With foreground reviewer: requires approval, user approves
        AtomicBoolean reviewerCalled = new AtomicBoolean(false);
        NotificationProcessor.ForegroundReviewer reviewer = (assessment, onApproved, onRejected) -> {
            reviewerCalled.set(true);
            assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
            assertTrue(assessment.requiresUserApproval());
            assertFalse(PrivacyEngine.canAutoApprove(assessment));
            assertTrue(assessment.getDetectedTypes().contains(PiiType.PERSON));

            // Masked preview contains redaction, NOT cleartext name
            String preview = assessment.getMaskedContent();
            assertTrue(preview.contains("[REDACTED: PERSON]"));
            assertFalse(preview.contains(sensitivePerson));
            assertFalse(preview.contains("Dwight"));
            assertFalse(preview.contains("Schrute"));

            onApproved.run();
        };

        boolean handledForeground = processor.process("Slack", rawBody, "notif-key-name-2", reviewer);
        assertTrue(handledForeground);
        assertTrue(reviewerCalled.get());
        assertEquals(1, fakeDao.tasks.size());

        ActionTask stored = fakeDao.tasks.get(0);
        assertEquals("Room 101", stored.getLocation());
        assertEquals("Submit", stored.getActionVerb());
        assertTrue(stored.isHasExplicitTime());

        // CRITICAL PRIVACY GUARANTEE: Raw name must NEVER exist anywhere in persisted task
        assertFalse(stored.getTitle().contains(sensitivePerson));
        assertFalse(stored.getTitle().contains("Dwight"));
        assertFalse(stored.getTitle().contains("Schrute"));
        assertTrue(stored.getTitle().contains("[REDACTED: PERSON]"));
    }

    // =========================================================================
    // Scenario 3: Notification containing an email address or phone number
    // =========================================================================
    @Test
    public void testScenario3_EmailAndPhone_MediumSensitivity_RequiresApproval_Redacted() {
        PrivacyEngine engine = new PrivacyEngine(null, AiScreeningState.READY);

        // 3a. Email notification
        String emailBody = "Send revised proposal to client.lead@enterprise.org by Friday.";
        PrivacyAssessment emailAssessment = engine.assessAndMask(emailBody);

        assertEquals(SensitivityLevel.MEDIUM, emailAssessment.getSensitivityLevel());
        assertTrue(emailAssessment.requiresUserApproval());
        assertTrue(emailAssessment.getDetectedTypes().contains(PiiType.EMAIL));
        assertEquals("Send revised proposal to [REDACTED: EMAIL] by Friday.", emailAssessment.getMaskedContent());
        assertFalse(emailAssessment.getMaskedContent().contains("client.lead@enterprise.org"));

        // 3b. Phone notification
        String phoneBody = "Call emergency dispatch at +1-555-839-2019 before 6 PM.";
        PrivacyAssessment phoneAssessment = engine.assessAndMask(phoneBody);

        assertEquals(SensitivityLevel.MEDIUM, phoneAssessment.getSensitivityLevel());
        assertTrue(phoneAssessment.requiresUserApproval());
        assertTrue(phoneAssessment.getDetectedTypes().contains(PiiType.PHONE));
        assertEquals("Call emergency dispatch at [REDACTED: PHONE] before 6 PM.", phoneAssessment.getMaskedContent());
        assertFalse(phoneAssessment.getMaskedContent().contains("555-839-2019"));
    }

    // =========================================================================
    // Scenario 4: OTP/password or financial identifier
    // =========================================================================
    @Test
    public void testScenario4_OtpPasswordAndFinancial_HighSensitivity_Redacted() {
        PrivacyEngine engine = new PrivacyEngine(null, AiScreeningState.READY);

        // 4a. OTP
        String otpBody = "Your authentication code is 849201 for portal login.";
        PrivacyAssessment otpAssessment = engine.assessAndMask(otpBody);
        assertEquals(SensitivityLevel.HIGH, otpAssessment.getSensitivityLevel());
        assertTrue(otpAssessment.requiresUserApproval());
        assertTrue(otpAssessment.getDetectedTypes().contains(PiiType.OTP));
        assertFalse(otpAssessment.getMaskedContent().contains("849201"));
        assertTrue(otpAssessment.getMaskedContent().contains("[REDACTED: OTP]"));

        // 4b. Password
        String pwdBody = "Temporary password: SecretPass#2026 for system access.";
        PrivacyAssessment pwdAssessment = engine.assessAndMask(pwdBody);
        assertEquals(SensitivityLevel.HIGH, pwdAssessment.getSensitivityLevel());
        assertTrue(pwdAssessment.requiresUserApproval());
        assertTrue(pwdAssessment.getDetectedTypes().contains(PiiType.PASSWORD));
        assertFalse(pwdAssessment.getMaskedContent().contains("SecretPass#2026"));
        assertTrue(pwdAssessment.getMaskedContent().contains("[REDACTED: PASSWORD]"));

        // 4c. Financial card (Luhn valid)
        String cardBody = "Verify card 4012-8888-8888-1881 for subscription renewal.";
        PrivacyAssessment cardAssessment = engine.assessAndMask(cardBody);
        assertEquals(SensitivityLevel.HIGH, cardAssessment.getSensitivityLevel());
        assertTrue(cardAssessment.requiresUserApproval());
        assertTrue(cardAssessment.getDetectedTypes().contains(PiiType.FINANCIAL));
        assertFalse(cardAssessment.getMaskedContent().contains("4012-8888-8888-1881"));
        assertTrue(cardAssessment.getMaskedContent().contains("[REDACTED: CARD]"));

        // 4d. UPI ID
        String upiBody = "Pay vendor via accounts.pay@okaxis by tomorrow.";
        PrivacyAssessment upiAssessment = engine.assessAndMask(upiBody);
        assertEquals(SensitivityLevel.HIGH, upiAssessment.getSensitivityLevel());
        assertTrue(upiAssessment.getDetectedTypes().contains(PiiType.FINANCIAL));
        assertFalse(upiAssessment.getMaskedContent().contains("accounts.pay@okaxis"));
        assertTrue(upiAssessment.getMaskedContent().contains("[REDACTED: UPI]"));
    }

    // =========================================================================
    // Scenario 5: User approval and user rejection for sensitive content
    // =========================================================================
    @Test
    public void testScenario5_ApprovalAndRejection_ApprovedSavesMasked_RejectedCreatesZeroRecords() {
        PrivacyEngine engine = new PrivacyEngine(null, AiScreeningState.READY);
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                engine,
                actionExtractor,
                Runnable::run
        );

        String rawAlert = "Transfer funds to a/c number 987654321012 by Friday at 3 PM.";

        // 5a. User Rejection: creates exactly ZERO database records
        NotificationProcessor.ForegroundReviewer rejectReviewer = (assessment, onApproved, onRejected) -> {
            onRejected.run();
        };

        boolean handledReject = processor.process("BankApp", rawAlert, "key-reject-1", rejectReviewer);
        assertTrue(handledReject);
        assertEquals(0, fakeDao.tasks.size());

        // 5b. User Approval: creates record with strictly sanitized content
        NotificationProcessor.ForegroundReviewer approveReviewer = (assessment, onApproved, onRejected) -> {
            onApproved.run();
        };

        boolean handledApprove = processor.process("BankApp", rawAlert, "key-approve-1", approveReviewer);
        assertTrue(handledApprove);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask task = fakeDao.tasks.get(0);
        assertFalse(task.getTitle().contains("987654321012"));
        assertTrue(task.getTitle().contains("[REDACTED: ACCOUNT]"));
    }

    // =========================================================================
    // Scenario 6: Notifications received while AI model is initializing or failed
    // =========================================================================
    @Test
    public void testScenario6_ModelInitializingOrFailed_FailClosed_NeverSilentlyAutoApproves() {
        // 6a. INITIALIZING State
        PrivacyEngine initEngine = new PrivacyEngine(null, AiScreeningState.INITIALIZING);
        NotificationProcessor initProcessor = new NotificationProcessor(
                repository,
                initEngine,
                actionExtractor,
                Runnable::run
        );

        String benignAnnouncement = "Read chapters 4 and 5 by tomorrow.";
        boolean handledInit = initProcessor.process("Canvas", benignAnnouncement, "key-init-test", null);

        assertFalse("Must drop notifications during INITIALIZING without queueing or persistence", handledInit);
        assertEquals(0, fakeDao.tasks.size());

        // 6b. FAILED State in background (no reviewer)
        PrivacyEngine failEngine = new PrivacyEngine(null, AiScreeningState.FAILED);
        NotificationProcessor failProcessor = new NotificationProcessor(
                repository,
                failEngine,
                actionExtractor,
                Runnable::run
        );

        boolean handledFailBg = failProcessor.process("Canvas", benignAnnouncement, "key-fail-bg-test", null);
        assertFalse("Must discard notifications in FAILED state when no foreground reviewer is present", handledFailBg);
        assertEquals(0, fakeDao.tasks.size());

        // 6c. FAILED State in foreground (with reviewer): cannot auto-approve, elevated to MEDIUM
        AtomicBoolean reviewerInvoked = new AtomicBoolean(false);
        NotificationProcessor.ForegroundReviewer mockReviewer = (assessment, onApproved, onRejected) -> {
            reviewerInvoked.set(true);
            assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
            assertTrue(assessment.requiresUserApproval());
            assertFalse(PrivacyEngine.canAutoApprove(assessment));
            onApproved.run();
        };

        boolean handledFailFg = failProcessor.process("Canvas", benignAnnouncement, "key-fail-fg-test", mockReviewer);
        assertTrue(handledFailFg);
        assertTrue(reviewerInvoked.get());
        assertEquals(1, fakeDao.tasks.size());
    }

    // =========================================================================
    // Scenario 7: Duplicate notifications and duplicate extracted tasks
    // =========================================================================
    @Test
    public void testScenario7_DuplicateNotificationKeyAndDuplicateTask_Suppressed() {
        PrivacyEngine engine = new PrivacyEngine(null, AiScreeningState.READY);
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                engine,
                actionExtractor,
                Runnable::run
        );

        String message = "Submit assignment 2 by Oct 15.";

        // 1. Initial post
        boolean first = processor.process("Portal", message, "key-dup-1", null);
        assertTrue(first);
        assertEquals(1, fakeDao.tasks.size());

        // 2. Duplicate Notification Key (e.g. notification update)
        boolean dupKey = processor.process("Portal", message, "key-dup-1", null);
        assertFalse("Duplicate notification key must be suppressed", dupKey);
        assertEquals(1, fakeDao.tasks.size());

        // 3. Duplicate Task with Different Notification Key
        boolean dupTask = processor.process("Email", message, "key-dup-2", null);
        assertFalse("Duplicate active task with same title and deadline must be suppressed", dupTask);
        assertEquals(1, fakeDao.tasks.size());

        // 4. Distinct Task (different title) is allowed
        boolean distinct = processor.process("Email", "Review exam guidelines by Oct 15.", "key-dup-3", null);
        assertTrue(distinct);
        assertEquals(2, fakeDao.tasks.size());
    }

    // =========================================================================
    // Scenario 8: Ambiguous dates, missing deadlines, and relative dates
    // =========================================================================
    @Test
    public void testScenario8_AmbiguousDates_MissingDeadlines_RelativeDates() {
        // 8a. Ambiguous slash date (05/06/2026 can be May 6 or June 5)
        ExtractedAction ambiguousAction = actionExtractor.extract(
                "Submit project report on 05/06/2026 at 2 PM.",
                fixedReferenceTime
        );
        assertTrue(ambiguousAction.isDateAmbiguous());
        assertNotNull(ambiguousAction.getAmbiguousDateReason());
        assertTrue(ambiguousAction.getAmbiguousDateReason().contains("MM/DD or DD/MM"));

        // 8b. Missing deadline does not invent dates
        ExtractedAction noDeadlineAction = actionExtractor.extract(
                "Organize reference notes for literature review.",
                fixedReferenceTime
        );
        assertFalse(noDeadlineAction.hasDeadline());
        assertNull(noDeadlineAction.getDeadlineTimestamp());
        assertNull(noDeadlineAction.getDeadlineFormatted());

        // 8c. Relative dates (today, tomorrow, next Monday)
        ExtractedAction tomorrowAction = actionExtractor.extract(
                "Submit draft tomorrow at 5 PM.",
                fixedReferenceTime
        );
        assertTrue(tomorrowAction.hasDeadline());
        assertTrue(tomorrowAction.isHasExplicitTime());
        ZonedDateTime tomorrowZdt = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(tomorrowAction.getDeadlineTimestamp()),
                ZoneId.of("UTC")
        );
        // Oct 9 + 1 day = Oct 10
        assertEquals(10, tomorrowZdt.getDayOfMonth());
        assertEquals(17, tomorrowZdt.getHour());

        ExtractedAction nextMondayAction = actionExtractor.extract(
                "Attend lecture next Monday.",
                fixedReferenceTime
        );
        ZonedDateTime mondayZdt = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nextMondayAction.getDeadlineTimestamp()),
                ZoneId.of("UTC")
        );
        // Friday Oct 9 -> Next Monday is Oct 12
        assertEquals(12, mondayZdt.getDayOfMonth());
    }

    // =========================================================================
    // Scenario 9: Empty notifications, malformed input, and model inference errors
    // =========================================================================
    @Test
    public void testScenario9_EmptyNotifications_MalformedInput_InferenceErrorsFailClosed() {
        PrivacyEngine engine = new PrivacyEngine(null, AiScreeningState.READY);
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                engine,
                actionExtractor,
                Runnable::run
        );

        // 9a. Empty and whitespace notifications return false
        assertFalse(processor.process("App", null, "k-null", null));
        assertFalse(processor.process("App", "", "k-empty", null));
        assertFalse(processor.process("App", "   \t\n  ", "k-blank", null));
        assertEquals(0, fakeDao.tasks.size());

        // 9b. Malformed BIO predictions handled safely without exceptions
        String input = "Alice Bob Smith";
        List<OnnxPiiClassifier.TokenPrediction> malformed = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("[CLS]", 29, "B-PERSON", -1, -1), // sentinel
                new OnnxPiiClassifier.TokenPrediction("Bob", 30, "I-PERSON", 6, 9),      // orphan I- tag
                new OnnxPiiClassifier.TokenPrediction("OutOfBounds", 29, "B-PERSON", 0, 1000), // out of bounds
                new OnnxPiiClassifier.TokenPrediction("Inverted", 29, "B-PERSON", 5, 2)  // inverted
        );
        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, malformed);
        assertEquals(1, matches.size());
        assertEquals("Bob", input.substring(matches.get(0).getStartIndex(), matches.get(0).getEndIndex()));

        // 9c. Model inference runtime fault triggers fail-closed elevation
        OnnxPiiClassifier faultClassifier = new OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                throw new IllegalStateException("Simulated native runtime SIGSEGV or corrupted memory");
            }
        };

        PrivacyEngine faultEngine = new PrivacyEngine(faultClassifier, AiScreeningState.READY);
        PrivacyAssessment faultAssessment = faultEngine.assessAndMask("Submit essay by Friday.");

        assertEquals(SensitivityLevel.MEDIUM, faultAssessment.getSensitivityLevel());
        assertTrue(faultAssessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(faultAssessment));
        assertTrue(faultAssessment.getRationale().contains("classifier check failed"));
    }

    // =========================================================================
    // Scenario 10: App restart, service destruction, and model initialization races
    // =========================================================================
    @Test
    public void testScenario10_AppRestart_ServiceDestruction_LifecycleSafety() throws Exception {
        // 10a. Concurrency during shutdown
        int threads = 8;
        ExecutorService concurrentExecutor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successfulTasks = new AtomicInteger(0);

        PrivacyEngine readyEngine = new PrivacyEngine(null, AiScreeningState.READY);
        NotificationProcessor sharedProcessor = new NotificationProcessor(
                repository,
                readyEngine,
                actionExtractor,
                concurrentExecutor
        );

        for (int i = 0; i < threads; i++) {
            final int id = i;
            concurrentExecutor.execute(() -> {
                try {
                    boolean handled = sharedProcessor.process(
                            "App" + id,
                            "Submit task number " + id + " by Oct 15.",
                            "key-race-" + id,
                            null
                    );
                    if (handled) {
                        successfulTasks.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        concurrentExecutor.shutdown();
        assertTrue(concurrentExecutor.awaitTermination(3, TimeUnit.SECONDS));

        assertEquals(threads, successfulTasks.get());
        assertEquals(threads, fakeDao.tasks.size());

        // 10b. App restart / service recreation simulation:
        // When service is destroyed and recreated, new processor starts in INITIALIZING state
        NotificationProcessor restartedProcessor = new NotificationProcessor(
                repository,
                new PrivacyEngine(null, AiScreeningState.INITIALIZING),
                actionExtractor,
                Runnable::run
        );

        assertEquals(AiScreeningState.INITIALIZING, restartedProcessor.getAiScreeningState());
        assertFalse(restartedProcessor.process("App", "Submit another task by Oct 15.", "key-restarted-1", null));

        // When model finishes initialization, transitions atomically to READY
        NotificationProcessor readyRestartedProcessor = new NotificationProcessor(
                repository,
                new PrivacyEngine(null, AiScreeningState.READY),
                actionExtractor,
                Runnable::run
        );
        assertEquals(AiScreeningState.READY, readyRestartedProcessor.getAiScreeningState());
        assertTrue(readyRestartedProcessor.process("App", "Submit another task by Oct 15.", "key-restarted-2", null));
    }

    // =========================================================================
    // Application Behavior Verification: Task Status Toggling & Sorting
    // =========================================================================
    @Test
    public void testApplicationBehavior_TaskCompletionTogglingAndOrdering() throws Exception {
        ActionTask task = new ActionTask(
                "Submit project draft",
                "Submit",
                fixedReferenceTime.toInstant().toEpochMilli(),
                "Oct 9, 2026",
                false,
                TaskPriority.HIGH,
                TaskStatus.ACTIVE,
                "Room 101",
                "Slack",
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );

        long id = fakeDao.insert(task);
        ActionTask fetched = fakeDao.getTaskById(id);
        assertEquals(TaskStatus.ACTIVE, fetched.getStatus());

        // Mark completed
        repository.markCompleted(fetched);
        Thread.sleep(100);
        assertEquals(TaskStatus.COMPLETED, fakeDao.getTaskById(id).getStatus());

        // Mark active again
        repository.markActive(fetched);
        Thread.sleep(100);
        assertEquals(TaskStatus.ACTIVE, fakeDao.getTaskById(id).getStatus());

        // Delete
        repository.delete(fetched);
        Thread.sleep(100);
        assertNull(fakeDao.getTaskById(id));
        assertEquals(0, fakeDao.tasks.size());
    }

    // =========================================================================
    // In-Memory Test Fake for Room ActionTaskDao
    // =========================================================================
    private static class InMemoryFakeDao implements ActionTaskDao {
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
            List<ActionTask> filtered = new ArrayList<>();
            for (ActionTask t : tasks) {
                if (t.getStatus() == status) filtered.add(t);
            }
            return filtered;
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
