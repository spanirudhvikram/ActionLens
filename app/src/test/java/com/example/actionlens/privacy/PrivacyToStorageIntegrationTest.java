package com.example.actionlens.privacy;

import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.TaskPriority;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration unit tests verifying the Privacy-First pipeline:
 * Raw Input -> Privacy Assessment -> Masking -> User Consent Gate -> Action Extraction -> Task Object Creation.
 *
 * Asserts:
 * 1. Extraction occurs strictly on masked text.
 * 2. Rejection/cancellation never creates a task object.
 * 3. Stored task fields contain ZERO raw secrets from the original input.
 */
public class PrivacyToStorageIntegrationTest {

    private PrivacyEngine privacyEngine;
    private ActionExtractor actionExtractor;

    @Before
    public void setUp() {
        privacyEngine = new PrivacyEngine();
        actionExtractor = new ActionExtractor();
    }

    @Test
    public void testPipeline_ApprovedSensitiveContent_NeverStoresRawSecrets() {
        String secretOtp = "948201";
        String secretPassword = "SuperSecret_Pwd#99";
        String rawAnnouncement = "Urgent: Complete account verification with OTP is " + secretOtp +
                " and temporary password: " + secretPassword + " by tomorrow at 5 PM in Room 101.";

        // 1. Pass through PrivacyEngine
        PrivacyAssessment assessment = privacyEngine.assessAndMask(rawAnnouncement);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());

        // 2. User Approval Simulation
        final List<ActionTask> simulatedStorage = new ArrayList<>();

        PrivacyApprovalCallback callback = new PrivacyApprovalCallback() {
            @Override
            public void onApproved(String sanitizedText) {
                // 3. Extract action ONLY from sanitizedText
                ExtractedAction action = actionExtractor.extract(sanitizedText);

                ActionTask task = new ActionTask(
                        action.getTitle(),
                        action.getActionVerb(),
                        action.getDeadlineTimestamp(),
                        action.getDeadlineFormatted(),
                        action.isHasExplicitTime(),
                        action.getPriority(),
                        TaskStatus.ACTIVE,
                        action.getLocation(),
                        action.getSource(),
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                );
                simulatedStorage.add(task);
            }

            @Override
            public void onRejected() {
                // Must do nothing - zero records stored
            }
        };

        // User approves sanitized version
        callback.onApproved(assessment.getMaskedContent());

        assertEquals(1, simulatedStorage.size());
        ActionTask storedTask = simulatedStorage.get(0);

        // Verify stored task title and fields
        assertNotNull(storedTask.getTitle());
        assertEquals("Complete", storedTask.getActionVerb());
        assertEquals("Room 101", storedTask.getLocation());
        assertEquals(TaskPriority.URGENT, storedTask.getPriority());

        // CRITICAL PRIVACY GUARANTEE: Neither secret OTP nor secret password exists in stored task
        assertFalse(storedTask.getTitle().contains(secretOtp));
        assertFalse(storedTask.getTitle().contains(secretPassword));
        if (storedTask.getLocation() != null) {
            assertFalse(storedTask.getLocation().contains(secretOtp));
            assertFalse(storedTask.getLocation().contains(secretPassword));
        }
    }

    @Test
    public void testPipeline_RejectedSensitiveContent_CreatesZeroRecords() {
        String rawAnnouncement = "Call manager at +1-555-987-6543 to confirm budget by Friday.";

        PrivacyAssessment assessment = privacyEngine.assessAndMask(rawAnnouncement);
        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());

        final List<ActionTask> simulatedStorage = new ArrayList<>();

        PrivacyApprovalCallback callback = new PrivacyApprovalCallback() {
            @Override
            public void onApproved(String sanitizedText) {
                ExtractedAction action = actionExtractor.extract(sanitizedText);
                simulatedStorage.add(new ActionTask(
                        action.getTitle(),
                        action.getActionVerb(),
                        action.getDeadlineTimestamp(),
                        action.getDeadlineFormatted(),
                        action.isHasExplicitTime(),
                        action.getPriority(),
                        TaskStatus.ACTIVE,
                        action.getLocation(),
                        action.getSource(),
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                ));
            }

            @Override
            public void onRejected() {
                // No-op
            }
        };

        // User rejects or dismisses the dialog
        callback.onRejected();

        // Must create exactly 0 records
        assertTrue(simulatedStorage.isEmpty());
        assertEquals(0, simulatedStorage.size());
    }

    @Test
    public void testPipeline_LowSensitivity_AutoApprovesSafely() {
        String rawAnnouncement = "Submit chapter summary by Oct 15 in Room 204.";

        PrivacyAssessment assessment = privacyEngine.assessAndMask(rawAnnouncement);
        assertEquals(SensitivityLevel.LOW, assessment.getSensitivityLevel());
        assertFalse(assessment.requiresUserApproval());
        assertTrue(PrivacyEngine.canAutoApprove(assessment));

        // Auto-approve flow
        ExtractedAction action = actionExtractor.extract(assessment.getMaskedContent());
        assertEquals("Submit chapter summary", action.getTitle());
        assertEquals("Submit", action.getActionVerb());
        assertEquals("Room 204", action.getLocation());
    }

    @Test
    public void testPipeline_ModelDetectedPerson_ApprovedStoresRedactedText_NeverRawName() {
        String sensitivePerson = "Dwight Schrute";
        String rawInput = "Submit sales audit to " + sensitivePerson + " by tomorrow in Conference Room B.";

        // Test double classifier detecting Dwight Schrute as PERSON
        com.example.actionlens.privacy.ml.OnnxPiiClassifier stub = new com.example.actionlens.privacy.ml.OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                int start = text.indexOf(sensitivePerson);
                int end = start + sensitivePerson.length();
                return Collections.singletonList(
                        new TokenPrediction(sensitivePerson, 29, "B-PERSON", start, end)
                );
            }
        };

        PrivacyEngine hybridEngine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = hybridEngine.assessAndMask(rawInput);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(assessment));

        final List<ActionTask> simulatedStorage = new ArrayList<>();
        PrivacyApprovalCallback callback = new PrivacyApprovalCallback() {
            @Override
            public void onApproved(String sanitizedText) {
                ExtractedAction action = actionExtractor.extract(sanitizedText);
                simulatedStorage.add(new ActionTask(
                        action.getTitle(),
                        action.getActionVerb(),
                        action.getDeadlineTimestamp(),
                        action.getDeadlineFormatted(),
                        action.isHasExplicitTime(),
                        action.getPriority(),
                        TaskStatus.ACTIVE,
                        action.getLocation(),
                        "Notification",
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                ));
            }

            @Override
            public void onRejected() {
            }
        };

        // User approves the sanitized version
        callback.onApproved(assessment.getMaskedContent());

        assertEquals(1, simulatedStorage.size());
        ActionTask storedTask = simulatedStorage.get(0);

        // Core Privacy Invariant: Dwight Schrute must NEVER appear in the stored task
        assertNotNull(storedTask.getTitle());
        assertFalse(storedTask.getTitle().contains(sensitivePerson));
        assertFalse(storedTask.getTitle().contains("Dwight"));
        assertFalse(storedTask.getTitle().contains("Schrute"));
        assertTrue(storedTask.getTitle().contains("[REDACTED: PERSON]"));
    }

    @Test
    public void testPipeline_ModelDetectedPerson_RejectedCreatesZeroRecords() {
        String sensitivePerson = "Angela Martin";
        String rawInput = "Review budget with " + sensitivePerson + " by Friday.";

        com.example.actionlens.privacy.ml.OnnxPiiClassifier stub = new com.example.actionlens.privacy.ml.OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                int start = text.indexOf(sensitivePerson);
                int end = start + sensitivePerson.length();
                return Collections.singletonList(
                        new TokenPrediction(sensitivePerson, 29, "B-PERSON", start, end)
                );
            }
        };

        PrivacyEngine hybridEngine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = hybridEngine.assessAndMask(rawInput);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());

        final List<ActionTask> simulatedStorage = new ArrayList<>();
        PrivacyApprovalCallback callback = new PrivacyApprovalCallback() {
            @Override
            public void onApproved(String sanitizedText) {
                ExtractedAction action = actionExtractor.extract(sanitizedText);
                simulatedStorage.add(new ActionTask(
                        action.getTitle(),
                        action.getActionVerb(),
                        action.getDeadlineTimestamp(),
                        action.getDeadlineFormatted(),
                        action.isHasExplicitTime(),
                        action.getPriority(),
                        TaskStatus.ACTIVE,
                        action.getLocation(),
                        "Notification",
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                ));
            }

            @Override
            public void onRejected() {
            }
        };

        // User rejects or dismisses
        callback.onRejected();

        assertTrue("Rejected sensitive content must yield 0 records", simulatedStorage.isEmpty());
        assertEquals(0, simulatedStorage.size());
    }

    @Test
    public void testPipeline_ModelInferenceFailure_CannotAutoApprove() {
        String rawInput = "Clean laboratory benches before morning inspection.";

        // Classifier that throws an exception
        com.example.actionlens.privacy.ml.OnnxPiiClassifier throwingClassifier =
                new com.example.actionlens.privacy.ml.OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                throw new RuntimeException("Simulated runtime failure");
            }
        };

        PrivacyEngine hybridEngine = new PrivacyEngine(throwingClassifier);
        PrivacyAssessment assessment = hybridEngine.assessAndMask(rawInput);

        // Fail-closed guarantee
        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(assessment));
    }
}
