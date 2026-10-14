package com.example.actionlens.privacy.ml;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;
import com.example.actionlens.privacy.PiiType;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.privacy.PrivacyEngine;
import com.example.actionlens.privacy.SensitivityLevel;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * On-device Android connected instrumentation test for the hybrid PrivacyEngine.
 * Executes the real packaged ONNX model (bert_small_pii.onnx) and tokenizer assets,
 * verifying that end-to-end token classification, entity span conversion, masking,
 * and ActionExtractor deadline parsing succeed on real Android runtime.
 */
@RunWith(AndroidJUnit4.class)
public class PrivacyEngineHybridInstrumentedTest {

    @Test
    public void realModelInference_MasksPerson_PreservesDeadlineOnDevice() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // 1. Load tokenizer and model from APK assets
        BertWordPieceTokenizer tokenizer = OnnxPiiClassifier.loadTokenizerFromAssets(appContext);
        assertNotNull("Tokenizer asset must load", tokenizer);

        try (OnnxPiiClassifier classifier = OnnxPiiClassifier.fromAsset(appContext, tokenizer)) {
            assertNotNull("Classifier must initialize", classifier);

            // 2. Instantiate hybrid PrivacyEngine with real ONNX classifier
            PrivacyEngine hybridEngine = new PrivacyEngine(classifier);

            // 3. Evaluate realistic notification with a personal name and deadline
            String notification = "Please submit the quarterly audit to Michael Scott by tomorrow at 5 PM in Room 101.";
            PrivacyAssessment assessment = hybridEngine.assessAndMask(notification);

            assertNotNull(assessment);
            assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
            assertTrue(assessment.requiresUserApproval());
            assertFalse(PrivacyEngine.canAutoApprove(assessment));
            assertTrue("Should detect PERSON entity", assessment.getDetectedTypes().contains(PiiType.PERSON));

            // Verify masking: person name is redacted
            String masked = assessment.getMaskedContent();
            assertFalse("Must not contain raw person name Michael", masked.contains("Michael"));
            assertFalse("Must not contain raw person name Scott", masked.contains("Scott"));
            assertTrue("Must contain [REDACTED: PERSON]", masked.contains("[REDACTED: PERSON]"));

            // Dates and room numbers must remain unredacted
            assertTrue("Deadline phrase 'tomorrow at 5 PM' must remain unmasked", masked.contains("tomorrow at 5 PM"));
            assertTrue("Location 'Room 101' must remain unmasked", masked.contains("Room 101"));

            // 4. ActionExtractor extracts action from masked content
            ActionExtractor extractor = new ActionExtractor();
            ExtractedAction action = extractor.extract(masked);

            assertEquals("Submit", action.getActionVerb());
            assertEquals("Room 101", action.getLocation());
            assertTrue(action.isHasExplicitTime());
            assertNotNull(action.getDeadlineTimestamp());

            // Core privacy invariant: stored task title contains ZERO raw person names
            assertFalse(action.getTitle().contains("Michael"));
            assertFalse(action.getTitle().contains("Scott"));
        }
    }

    @Test
    public void realModelInference_RulePrecedenceOverModel_OnDevice() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BertWordPieceTokenizer tokenizer = OnnxPiiClassifier.loadTokenizerFromAssets(appContext);

        try (OnnxPiiClassifier classifier = OnnxPiiClassifier.fromAsset(appContext, tokenizer)) {
            PrivacyEngine hybridEngine = new PrivacyEngine(classifier);

            // Email address containing a person name ("alice.smith@example.com")
            String notification = "Contact analyst at alice.smith@example.com before Friday.";
            PrivacyAssessment assessment = hybridEngine.assessAndMask(notification);

            // High-confidence EMAIL rule must win and mask as [REDACTED: EMAIL]
            String masked = assessment.getMaskedContent();
            assertTrue(masked.contains("[REDACTED: EMAIL]"));
            assertFalse(masked.contains("alice.smith@example.com"));
            assertFalse(masked.contains("example.com"));
        }
    }

    @Test
    public void testModelReadiness_InitializingState_BlocksAutoApproveOnDevice() {
        // Text with zero regex triggers
        String notification = "Prepare presentation slides for Friday seminar.";
        PrivacyEngine initializingEngine = new PrivacyEngine(null, com.example.actionlens.privacy.AiScreeningState.INITIALIZING);

        PrivacyAssessment assessment = initializingEngine.assessAndMask(notification);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse("Cannot auto-approve while model is initializing on device",
                PrivacyEngine.canAutoApprove(assessment));
    }

    @Test
    public void testModelReadiness_FailedState_BlocksAutoApproveOnDevice() {
        String notification = "Organize laboratory benches before inspection.";
        PrivacyEngine failedEngine = new PrivacyEngine(null, com.example.actionlens.privacy.AiScreeningState.FAILED);

        PrivacyAssessment assessment = failedEngine.assessAndMask(notification);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse("Cannot auto-approve when AI screening failed on device",
                PrivacyEngine.canAutoApprove(assessment));
    }
}
