package com.example.actionlens;

import android.content.Context;
import android.graphics.Paint;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.actionlens.data.ActionLensDatabase;
import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.ActionTaskDao;
import com.example.actionlens.data.TaskPriority;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.privacy.AiScreeningState;
import com.example.actionlens.privacy.PrivacyEngine;
import com.example.actionlens.privacy.SensitivityLevel;
import com.example.actionlens.privacy.ml.BertWordPieceTokenizer;
import com.example.actionlens.privacy.ml.OnnxPiiClassifier;
import com.example.actionlens.service.NotificationFilter;
import com.example.actionlens.service.NotificationProcessor;
import com.example.actionlens.ui.ActionCardAdapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * On-device Android connected instrumentation test suite validating the complete
 * ActionLens end-to-end workflow on real Android runtime (emulator-5554):
 *
 * 1. Real SQLite Room Database: inserts, updates, duplicate counts, deletions.
 * 2. Real ONNX Runtime Inference & Tokenizer: loaded from APK assets.
 * 3. End-to-end notification processing: filtering -> privacy assessment -> consent -> extraction -> Room.
 * 4. Privacy guarantees on-device: zero cleartext secrets in real SQLite database.
 * 5. Action Card UI binding: ViewGroup inflation, strike-through, badges, and toggle interactions.
 */
@RunWith(AndroidJUnit4.class)
public class EndToEndWorkflowInstrumentedTest {

    private Context context;
    private ActionLensDatabase database;
    private ActionTaskDao dao;
    private TaskRepository repository;
    private ActionExtractor extractor;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Use real in-memory Room database on Android SQLite runtime
        database = Room.inMemoryDatabaseBuilder(context, ActionLensDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = database.actionTaskDao();
        repository = new TaskRepository(dao);
        extractor = new ActionExtractor();
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    public void testEndToEnd_RealRoomPipeline_LowSensitivity_AutoApprovesAndPersists() {
        String title = "Study Group";
        String body = "Please submit chemistry lab report by Oct 15 in Room 302.";

        assertTrue(NotificationFilter.isEligible("org.telegram.messenger", "com.example.actionlens", false, false, title, body));
        String eligibleBody = NotificationFilter.extractNotificationBody(title, body, null);

        NotificationProcessor processor = new NotificationProcessor(
                repository,
                new PrivacyEngine(null, AiScreeningState.READY),
                extractor,
                Runnable::run
        );

        boolean handled = processor.process("Telegram", eligibleBody, "key-device-101", null);
        assertTrue(handled);

        List<ActionTask> activeTasks = dao.getTasksByStatusSync(TaskStatus.ACTIVE);
        assertEquals(1, activeTasks.size());

        ActionTask task = activeTasks.get(0);
        assertEquals("Submit chemistry lab report", task.getTitle());
        assertEquals("Submit", task.getActionVerb());
        assertEquals("Room 302", task.getLocation());
        assertEquals("Telegram", task.getSource());
        assertEquals(TaskStatus.ACTIVE, task.getStatus());
        assertNotNull(task.getDeadlineTimestamp());
        assertEquals("Oct 15, 2026", task.getDeadlineFormatted());
    }

    @Test
    public void testEndToEnd_RealRoomPipeline_HighSensitivity_ApprovedStoresZeroSecrets() {
        String secretOtp = "849201";
        String notification = "Your verification code is " + secretOtp + " to authorize transfer by 5 PM.";

        NotificationProcessor processor = new NotificationProcessor(
                repository,
                new PrivacyEngine(null, AiScreeningState.READY),
                extractor,
                Runnable::run
        );

        AtomicBoolean approved = new AtomicBoolean(false);
        NotificationProcessor.ForegroundReviewer reviewer = (assessment, onApproved, onRejected) -> {
            assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
            assertTrue(assessment.requiresUserApproval());
            approved.set(true);
            onApproved.run();
        };

        boolean handled = processor.process("Banking", notification, "key-otp-device", reviewer);
        assertTrue(handled);
        assertTrue(approved.get());

        List<ActionTask> saved = dao.getAllTasksSync();
        assertEquals(1, saved.size());
        ActionTask task = saved.get(0);

        // Core Privacy Invariant: secret OTP must not exist in SQLite table
        assertFalse("Raw OTP secret must not exist in SQLite title", task.getTitle().contains(secretOtp));
        assertTrue("Title must contain redaction token", task.getTitle().contains("[REDACTED: OTP]"));
    }

    @Test
    public void testEndToEnd_RealRoomPipeline_DuplicateSuppressionInSQLite() {
        NotificationProcessor processor = new NotificationProcessor(
                repository,
                new PrivacyEngine(null, AiScreeningState.READY),
                extractor,
                Runnable::run
        );

        String text = "Submit weekly timesheet by Friday.";

        // First post succeeds
        boolean first = processor.process("HR App", text, "key-dup-dev-1", null);
        assertTrue(first);
        assertEquals(1, dao.getTaskCount());

        // Same task from different key is suppressed by SQLite countMatchingTasks
        boolean second = processor.process("Slack", text, "key-dup-dev-2", null);
        assertFalse("Duplicate active task must be suppressed", second);
        assertEquals(1, dao.getTaskCount());
    }

    @Test
    public void testEndToEnd_RealRoomPipeline_TaskStatusTransitions() {
        ActionTask task = new ActionTask(
                "Submit project proposal",
                "Submit",
                null,
                null,
                false,
                TaskPriority.NORMAL,
                TaskStatus.ACTIVE,
                null,
                "Manual Input",
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );

        long id = dao.insert(task);
        ActionTask fetched = dao.getTaskById(id);
        assertEquals(TaskStatus.ACTIVE, fetched.getStatus());

        // Mark completed
        fetched.setStatus(TaskStatus.COMPLETED);
        dao.update(fetched);
        assertEquals(TaskStatus.COMPLETED, dao.getTaskById(id).getStatus());
        assertEquals(1, dao.getTasksByStatusSync(TaskStatus.COMPLETED).size());
        assertEquals(0, dao.getTasksByStatusSync(TaskStatus.ACTIVE).size());

        // Mark active again
        fetched.setStatus(TaskStatus.ACTIVE);
        dao.update(fetched);
        assertEquals(TaskStatus.ACTIVE, dao.getTaskById(id).getStatus());
        assertEquals(1, dao.getTasksByStatusSync(TaskStatus.ACTIVE).size());
    }

    @Test
    public void testEndToEnd_RealModelWithRealRoom_RedactsPersonOnDevice() throws Exception {
        BertWordPieceTokenizer tokenizer = OnnxPiiClassifier.loadTokenizerFromAssets(context);
        assertNotNull(tokenizer);

        try (OnnxPiiClassifier classifier = OnnxPiiClassifier.fromAsset(context, tokenizer)) {
            assertNotNull(classifier);

            PrivacyEngine hybridEngine = new PrivacyEngine(classifier, AiScreeningState.READY);
            NotificationProcessor processor = new NotificationProcessor(
                    repository,
                    hybridEngine,
                    extractor,
                    Runnable::run
            );

            String rawAlert = "Please submit the quarterly audit to Michael Scott by tomorrow at 5 PM in Room 101.";

            AtomicBoolean reviewerCalled = new AtomicBoolean(false);
            NotificationProcessor.ForegroundReviewer reviewer = (assessment, onApproved, onRejected) -> {
                reviewerCalled.set(true);
                assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
                assertTrue(assessment.requiresUserApproval());
                // Masked preview contains redaction
                assertTrue(assessment.getMaskedContent().contains("[REDACTED: PERSON]"));
                assertFalse(assessment.getMaskedContent().contains("Michael"));
                onApproved.run();
            };

            boolean handled = processor.process("Outlook", rawAlert, "key-onnx-room-1", reviewer);
            assertTrue(handled);
            assertTrue(reviewerCalled.get());

            List<ActionTask> tasks = dao.getAllTasksSync();
            assertEquals(1, tasks.size());
            ActionTask saved = tasks.get(0);

            // Core Invariant: Real SQLite row contains redacted person, zero raw names
            assertFalse(saved.getTitle().contains("Michael"));
            assertFalse(saved.getTitle().contains("Scott"));
            assertTrue(saved.getTitle().contains("[REDACTED: PERSON]"));
            assertEquals("Room 101", saved.getLocation());
            assertEquals("Submit", saved.getActionVerb());
            assertTrue(saved.isHasExplicitTime());
        }
    }

    @Test
    public void testEndToEnd_ActionCardAdapter_BindsActiveAndCompletedTasksCorrectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                ActionCardAdapter adapter = new ActionCardAdapter(new ActionCardAdapter.OnTaskActionListener() {
                    @Override
                    public void onToggleComplete(ActionTask task) {}
                    @Override
                    public void onDelete(ActionTask task) {}
                });

                Context themedContext = new android.view.ContextThemeWrapper(context, R.style.Theme_ActionLens);
                ViewGroup parent = new FrameLayout(themedContext);
                ActionCardAdapter.TaskViewHolder holder = adapter.onCreateViewHolder(parent, 0);

                // 1. Bind Active Task
                ActionTask activeTask = new ActionTask(
                        "Submit research proposal",
                        "Submit",
                        System.currentTimeMillis() + 86400000L,
                        "Tomorrow at 5 PM",
                        true,
                        TaskPriority.URGENT,
                        TaskStatus.ACTIVE,
                        "Room 204",
                        "Slack",
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                );

                holder.bind(activeTask, null);

                TextView tvTitle = holder.itemView.findViewById(R.id.tvTaskTitle);
                TextView tvStatus = holder.itemView.findViewById(R.id.tvStatusBadge);
                CheckBox cbComplete = holder.itemView.findViewById(R.id.cbComplete);

                assertEquals("Submit research proposal", tvTitle.getText().toString());
                assertEquals("ACTIVE", tvStatus.getText().toString());
                assertFalse(cbComplete.isChecked());
                assertEquals(0, tvTitle.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG);

                // 2. Bind Completed Task
                ActionTask completedTask = new ActionTask(
                        "Submit research proposal",
                        "Submit",
                        null,
                        null,
                        false,
                        TaskPriority.NORMAL,
                        TaskStatus.COMPLETED,
                        null,
                        "Slack",
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                );

                holder.bind(completedTask, null);

                assertEquals("COMPLETED", tvStatus.getText().toString());
                assertTrue(cbComplete.isChecked());
                assertNotEquals(0, tvTitle.getPaintFlags() & Paint.STRIKE_THRU_TEXT_FLAG);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }
}
