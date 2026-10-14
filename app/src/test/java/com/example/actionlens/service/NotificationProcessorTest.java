package com.example.actionlens.service;

import androidx.lifecycle.LiveData;

import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.ActionTaskDao;
import com.example.actionlens.data.TaskPriority;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.privacy.PrivacyEngine;
import com.example.actionlens.privacy.SensitivityLevel;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for NotificationProcessor verifying:
 * 1. Duplicate notification key suppression
 * 2. Duplicate task title+deadline suppression
 * 3. Auto-approval and source attribution for LOW sensitivity
 * 4. Immediate discard without retention for MEDIUM/HIGH when unreviewed
 * 5. Foreground approval flow and masked persistence
 * 6. Foreground rejection flow resulting in zero writes
 */
public class NotificationProcessorTest {

    private FakeActionTaskDao fakeDao;
    private TaskRepository repository;
    private NotificationProcessor processor;

    @Before
    public void setUp() {
        fakeDao = new FakeActionTaskDao();
        repository = new TaskRepository(fakeDao);
        // Use synchronous Runnable::run executor for deterministic tests
        processor = new NotificationProcessor(
                repository,
                new PrivacyEngine(),
                new ActionExtractor(),
                Runnable::run
        );
    }

    @Test
    public void testLowSensitivity_AutoApprovesAndSavesWithSourceAttribution() {
        String alertText = "Submit chapter summary by Oct 15 in Room 204.";
        boolean handled = processor.process("Slack", alertText, "key-slack-1", null);

        assertTrue("LOW sensitivity notification should be handled and persisted", handled);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask task = fakeDao.tasks.get(0);
        assertEquals("Submit chapter summary", task.getTitle());
        assertEquals("Submit", task.getActionVerb());
        assertEquals("Room 204", task.getLocation());
        assertEquals("Slack", task.getSource());
        assertEquals(TaskStatus.ACTIVE, task.getStatus());
    }

    @Test
    public void testRegression_NotificationProjectTeam_ExtractsTaskTitleLocationAndDeadline() {
        String title = "Project Team";
        String tag = "Tag1";
        String body = "Submit chemistry lab report by Oct 15 in Room 302.";

        String eligibleBody = NotificationFilter.extractNotificationBody(title, body, null);
        assertEquals("Submit chemistry lab report by Oct 15 in Room 302.", eligibleBody);

        boolean handled = processor.process("Project Team", eligibleBody, "key-tag1", null);
        assertTrue(handled);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask task = fakeDao.tasks.get(0);
        assertEquals("Submit chemistry lab report", task.getTitle());
        assertEquals("Submit", task.getActionVerb());
        assertEquals("Room 302", task.getLocation());
        assertEquals("Oct 15, 2026", task.getDeadlineFormatted());
        assertNotNull(task.getDeadlineTimestamp());
        assertEquals("Project Team", task.getSource());

        // Verify title is pure and not contaminated with title/sender or tag
        assertFalse(task.getTitle().contains("Project"));
        assertFalse(task.getTitle().contains("Team"));
        assertFalse(task.getTitle().contains("Tag1"));
    }

    @Test
    public void testRegression_NotificationBigText_ExtractsTaskTitleLocationAndDeadline() {
        String title = "Project Team";
        String tag = "Tag1";
        String text = "Tag1";
        String bigText = "Submit chemistry lab report by Oct 15 in Room 302.";

        String eligibleBody = NotificationFilter.extractNotificationBody(title, text, bigText);
        assertEquals("Submit chemistry lab report by Oct 15 in Room 302.", eligibleBody);

        boolean handled = processor.process("Project Team", eligibleBody, "key-tag2", null);
        assertTrue(handled);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask task = fakeDao.tasks.get(0);
        assertEquals("Submit chemistry lab report", task.getTitle());
        assertEquals("Submit", task.getActionVerb());
        assertEquals("Room 302", task.getLocation());
        assertEquals("Oct 15, 2026", task.getDeadlineFormatted());
        assertEquals("Project Team", task.getSource());
    }

    @Test
    public void testDuplicateNotificationKey_SuppressesSecondCall() {
        String alertText = "Submit chapter summary by Oct 15 in Room 204.";
        boolean first = processor.process("WhatsApp", alertText, "key-unique-1", null);
        assertTrue(first);
        assertEquals(1, fakeDao.tasks.size());

        // Identical notification key posted again (e.g. notification update)
        boolean second = processor.process("WhatsApp", alertText, "key-unique-1", null);
        assertFalse("Duplicate notification key must be suppressed", second);
        assertEquals(1, fakeDao.tasks.size());
    }

    @Test
    public void testDuplicateTask_SuppressedWhenIdenticalTitleAndDeadline() {
        String alertText = "Complete final project report by Oct 15.";
        boolean first = processor.process("Slack", alertText, "key-alpha", null);
        assertTrue(first);
        assertEquals(1, fakeDao.tasks.size());

        // Same task text from another key (or another app)
        boolean second = processor.process("Email", alertText, "key-beta", null);
        assertFalse("Duplicate active task with same title and deadline must be suppressed", second);
        assertEquals(1, fakeDao.tasks.size());
    }

    @Test
    public void testMediumOrHighSensitivity_WithoutReviewer_DiscardsImmediately() {
        // Contains phone number -> MEDIUM sensitivity
        String alertText = "Call security team at +1-555-839-2019 to report access badge by tomorrow 5 PM";

        boolean handled = processor.process("Telegram", alertText, "key-phone-1", null);

        assertFalse("Sensitive notification without foreground reviewer must be discarded", handled);
        assertEquals("Zero tasks must be written to database", 0, fakeDao.tasks.size());
    }

    @Test
    public void testMediumOrHighSensitivity_WithReviewer_ApprovedSavesOnlyMaskedContent() {
        String secretOtp = "729104";
        String alertText = "Urgent: Complete account recovery with OTP is " + secretOtp + " by tomorrow at 5 PM in Room 101.";

        NotificationProcessor.ForegroundReviewer mockReviewer = new NotificationProcessor.ForegroundReviewer() {
            @Override
            public void requestApproval(PrivacyAssessment assessment, Runnable onApproved, Runnable onRejected) {
                assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
                assertTrue(assessment.requiresUserApproval());
                // User approves
                onApproved.run();
            }
        };

        boolean handled = processor.process("SMS", alertText, "key-otp-1", mockReviewer);

        assertTrue(handled);
        assertEquals(1, fakeDao.tasks.size());

        ActionTask task = fakeDao.tasks.get(0);
        assertTrue(task.getTitle().startsWith("Complete"));
        assertEquals("SMS", task.getSource());
        assertEquals("Room 101", task.getLocation());
        assertEquals(TaskPriority.URGENT, task.getPriority());

        // CRITICAL: Raw OTP secret must NOT be in any task field
        assertFalse(task.getTitle().contains(secretOtp));
        if (task.getLocation() != null) {
            assertFalse(task.getLocation().contains(secretOtp));
        }
    }

    @Test
    public void testMediumOrHighSensitivity_WithReviewer_RejectedProducesZeroWrites() {
        String alertText = "Bank transfer to account 1234567890123456 required by Friday.";

        NotificationProcessor.ForegroundReviewer mockReviewer = new NotificationProcessor.ForegroundReviewer() {
            @Override
            public void requestApproval(PrivacyAssessment assessment, Runnable onApproved, Runnable onRejected) {
                // User rejects / dismisses
                onRejected.run();
            }
        };

        boolean handled = processor.process("BankApp", alertText, "key-fin-1", mockReviewer);

        assertTrue(handled); // Handled by routing to reviewer
        assertEquals("Rejected approval must create zero database records", 0, fakeDao.tasks.size());
    }

    @Test
    public void testNullOrEmptyNotification_ReturnsFalse() {
        assertFalse(processor.process("App", null, "k1", null));
        assertFalse(processor.process("App", "", "k2", null));
        assertFalse(processor.process("App", "    \n", "k3", null));
        assertEquals(0, fakeDao.tasks.size());
    }

    private static class FakeActionTaskDao implements ActionTaskDao {
        final List<ActionTask> tasks = new ArrayList<>();
        private long idCounter = 1;

        @Override
        public long insert(ActionTask task) {
            task.setId(idCounter++);
            tasks.add(task);
            return task.getId();
        }

        @Override
        public void update(ActionTask task) {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).getId() == task.getId()) {
                    tasks.set(i, task);
                    break;
                }
            }
        }

        @Override
        public void delete(ActionTask task) {
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
        public List<ActionTask> getTasksByStatusSync(TaskStatus status) {
            return tasks;
        }

        @Override
        public List<ActionTask> getAllTasksSync() {
            return tasks;
        }

        @Override
        public ActionTask getTaskById(long id) {
            for (ActionTask t : tasks) {
                if (t.getId() == id) return t;
            }
            return null;
        }

        @Override
        public int getTaskCount() {
            return tasks.size();
        }

        @Override
        public int countMatchingTasks(String title, Long deadlineTimestamp) {
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
        public void deleteAll() {
            tasks.clear();
        }
    }
}
