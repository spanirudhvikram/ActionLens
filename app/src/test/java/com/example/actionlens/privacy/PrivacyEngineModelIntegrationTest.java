package com.example.actionlens.privacy;

import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;
import com.example.actionlens.privacy.ml.OnnxPiiClassifier;

import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests verifying the Phase E Step 4C hybrid PrivacyEngine integration with OnnxPiiClassifier:
 * 1. Model BIO token prediction to entity span conversion using exact character offsets.
 * 2. Safe handling of malformed BIO sequences (orphan I-, consecutive B-, type switches).
 * 3. Exclusion of DATE_TIME and O tokens, preserving dates for deadline extraction.
 * 4. Overlap resolution priority: rule-based high-confidence matches strictly take precedence
 *    over lower-priority model entities (PERSON, LOCATION).
 * 5. Fail-closed policy: model inference failures elevate to at least MEDIUM and require user approval.
 * 6. Non-regression of existing sensitivity classification and approval gating.
 */
public class PrivacyEngineModelIntegrationTest {

    private ActionExtractor actionExtractor;

    @Before
    public void setUp() {
        actionExtractor = new ActionExtractor(ZoneId.of("UTC"));
    }

    // =========================================================================
    // 1. BIO Token Sequence Conversion to Entity Spans
    // =========================================================================

    @Test
    public void testModelSpanConversion_SinglePersonToken() {
        String input = "Hello Alice how are you";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Collections.singletonList(
                new OnnxPiiClassifier.TokenPrediction("Alice", 29, "B-PERSON", 6, 11)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertEquals(1, matches.size());
        PiiMatch m = matches.get(0);
        assertEquals(PiiType.PERSON, m.getType());
        assertEquals(6, m.getStartIndex());
        assertEquals(11, m.getEndIndex());
        assertEquals("[REDACTED: PERSON]", m.getReplacementToken());
    }

    @Test
    public void testModelSpanConversion_MultiTokenPerson_SubwordsAndWhitespace() {
        // "John Doe" spanning index 5 to 13
        String input = "Meet John Doe at noon";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("John", 29, "B-PERSON", 5, 9),
                new OnnxPiiClassifier.TokenPrediction("Doe", 30, "I-PERSON", 10, 13)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertEquals(1, matches.size());
        PiiMatch m = matches.get(0);
        assertEquals(PiiType.PERSON, m.getType());
        assertEquals(5, m.getStartIndex());
        assertEquals(13, m.getEndIndex());
        assertEquals("[REDACTED: PERSON]", m.getReplacementToken());
        assertEquals("John Doe", input.substring(m.getStartIndex(), m.getEndIndex()));
    }

    @Test
    public void testModelSpanConversion_ConsecutiveBeginningsAreSplit() {
        // Two consecutive people without intervening O tag: "Alice Bob"
        String input = "Alice Bob are present";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("Alice", 29, "B-PERSON", 0, 5),
                new OnnxPiiClassifier.TokenPrediction("Bob", 29, "B-PERSON", 6, 9)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).getStartIndex());
        assertEquals(5, matches.get(0).getEndIndex());
        assertEquals(6, matches.get(1).getStartIndex());
        assertEquals(9, matches.get(1).getEndIndex());
    }

    @Test
    public void testModelSpanConversion_OrphanInsideTokenSafelyFormsSpan() {
        // Malformed BIO: token labeled I-PERSON without prior B-PERSON
        String input = "Report from Smith today";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Collections.singletonList(
                new OnnxPiiClassifier.TokenPrediction("Smith", 30, "I-PERSON", 12, 17)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertEquals(1, matches.size());
        PiiMatch m = matches.get(0);
        assertEquals(PiiType.PERSON, m.getType());
        assertEquals(12, m.getStartIndex());
        assertEquals(17, m.getEndIndex());
    }

    @Test
    public void testModelSpanConversion_IgnoresDateTimeTokens() {
        // B-DATE_TIME (7) and I-DATE_TIME (8) must NOT generate PII redactions
        String input = "Due tomorrow at 5 PM";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("tomorrow", 7, "B-DATE_TIME", 4, 12),
                new OnnxPiiClassifier.TokenPrediction("at", 8, "I-DATE_TIME", 13, 15),
                new OnnxPiiClassifier.TokenPrediction("5", 8, "I-DATE_TIME", 16, 17),
                new OnnxPiiClassifier.TokenPrediction("PM", 8, "I-DATE_TIME", 18, 20)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertTrue("DATE_TIME tokens must produce 0 PII matches", matches.isEmpty());
    }

    @Test
    public void testModelSpanConversion_IgnoresOutsideTokensAndFlushesOpenSpan() {
        // "Alice" followed by "said" (O) followed by "Bob"
        String input = "Alice said Bob";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("Alice", 29, "B-PERSON", 0, 5),
                new OnnxPiiClassifier.TokenPrediction("said", 0, "O", 6, 10),
                new OnnxPiiClassifier.TokenPrediction("Bob", 29, "B-PERSON", 11, 14)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertEquals(2, matches.size());
        assertEquals("Alice", input.substring(matches.get(0).getStartIndex(), matches.get(0).getEndIndex()));
        assertEquals("Bob", input.substring(matches.get(1).getStartIndex(), matches.get(1).getEndIndex()));
    }

    @Test
    public void testModelSpanConversion_FiltersSentinelAndSpecialOffsets() {
        String input = "Hello world";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("[CLS]", 29, "B-PERSON", -1, -1),
                new OnnxPiiClassifier.TokenPrediction("out_of_bounds", 29, "B-PERSON", 0, 50),
                new OnnxPiiClassifier.TokenPrediction("inverted", 29, "B-PERSON", 5, 2),
                new OnnxPiiClassifier.TokenPrediction("[SEP]", 29, "B-PERSON", -1, -1)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertTrue("Invalid and sentinel offsets must be filtered out", matches.isEmpty());
    }

    @Test
    public void testModelSpanConversion_SwitchingEntityTypesSplitsSpans() {
        // B-PERSON followed by I-LOCATION without O
        String input = "Alice Boston";
        List<OnnxPiiClassifier.TokenPrediction> predictions = Arrays.asList(
                new OnnxPiiClassifier.TokenPrediction("Alice", 29, "B-PERSON", 0, 5),
                new OnnxPiiClassifier.TokenPrediction("Boston", 20, "I-LOCATION", 6, 12)
        );

        List<PiiMatch> matches = PrivacyEngine.convertPredictionsToMatches(input, predictions);

        assertEquals(2, matches.size());
        assertEquals(PiiType.PERSON, matches.get(0).getType());
        assertEquals(PiiType.LOCATION, matches.get(1).getType());
    }

    // =========================================================================
    // 2. Hybrid PrivacyEngine Execution with Test Classifier
    // =========================================================================

    @Test
    public void testHybridEngine_DetectsPersonViaModel_ClassifiedAsMedium() {
        String input = "Please submit the report to Michael Scott by Friday.";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("Michael", 29, "B-PERSON", 28, 35);
        stub.addPrediction("Scott", 30, "I-PERSON", 36, 41);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(assessment));
        assertTrue(assessment.getDetectedTypes().contains(PiiType.PERSON));

        // Redaction check
        assertEquals("Please submit the report to [REDACTED: PERSON] by Friday.", assessment.getMaskedContent());
        assertFalse(assessment.getMaskedContent().contains("Michael"));
        assertFalse(assessment.getMaskedContent().contains("Scott"));
    }

    @Test
    public void testHybridEngine_DetectsLocationViaModel_ClassifiedAsMedium() {
        String input = "Attend the conference at San Francisco tomorrow.";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("San", 19, "B-LOCATION", 25, 28);
        stub.addPrediction("Francisco", 20, "I-LOCATION", 29, 38);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.getDetectedTypes().contains(PiiType.LOCATION));
        assertEquals("Attend the conference at [REDACTED: LOCATION] tomorrow.", assessment.getMaskedContent());
        assertFalse(assessment.getMaskedContent().contains("San Francisco"));
    }

    @Test
    public void testHybridEngine_DetectsIdentifierViaModel_ClassifiedAsHigh() {
        String input = "Confirm identity with SSN 123-45-6789 before 5 PM.";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("123-45-6789", 47, "B-US_SSN", 26, 37);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.getDetectedTypes().contains(PiiType.IDENTIFIER));
        assertEquals("Confirm identity with SSN [REDACTED: IDENTIFIER] before 5 PM.", assessment.getMaskedContent());
        assertFalse(assessment.getMaskedContent().contains("123-45-6789"));
    }

    @Test
    public void testHybridEngine_RuleMatchTakesPrecedenceOverNestedModelPerson() {
        // Regex matches full email: alice.smith@company.com [17, 40]
        // Model predicts PERSON on "alice.smith" [17, 28]
        String input = "Send summary to alice.smith@company.com by 3 PM.";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("alice.smith", 29, "B-PERSON", 16, 27);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        // Entire email must be replaced with [REDACTED: EMAIL], never [REDACTED: PERSON]
        assertEquals("Send summary to [REDACTED: EMAIL] by 3 PM.", assessment.getMaskedContent());
        assertFalse(assessment.getMaskedContent().contains("alice.smith"));
        assertFalse(assessment.getMaskedContent().contains("company.com"));
        assertFalse(assessment.getMaskedContent().contains("[REDACTED: PERSON]"));
    }

    @Test
    public void testHybridEngine_RuleOtpAndModelPersonBothRedacted() {
        String input = "Alice, your login OTP is 948201 for today.";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("Alice", 29, "B-PERSON", 0, 5);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.getDetectedTypes().contains(PiiType.OTP));
        assertTrue(assessment.getDetectedTypes().contains(PiiType.PERSON));

        String masked = assessment.getMaskedContent();
        assertEquals("[REDACTED: PERSON], your login OTP is [REDACTED: OTP] for today.", masked);
        assertFalse(masked.contains("Alice"));
        assertFalse(masked.contains("948201"));
    }

    @Test
    public void testHybridEngine_PreservesDatesForActionExtractorDeadline() {
        // Text contains a person (model PII) and a deadline "tomorrow at 5 PM"
        String input = "Submit project report to Dwight Schrute by tomorrow at 5 PM in Room 101.";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("Dwight", 29, "B-PERSON", 25, 31);
        stub.addPrediction("Schrute", 30, "I-PERSON", 32, 39);
        // Model also predicts DATE_TIME on "tomorrow at 5 PM" - must NOT be redacted
        stub.addPrediction("tomorrow", 7, "B-DATE_TIME", 43, 51);
        stub.addPrediction("at", 8, "I-DATE_TIME", 52, 54);
        stub.addPrediction("5", 8, "I-DATE_TIME", 55, 56);
        stub.addPrediction("PM", 8, "I-DATE_TIME", 57, 59);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        // Person is masked, but deadline and location remain intact
        String masked = assessment.getMaskedContent();
        assertEquals("Submit project report to [REDACTED: PERSON] by tomorrow at 5 PM in Room 101.", masked);
        assertFalse(masked.contains("Dwight"));
        assertFalse(masked.contains("Schrute"));

        // ActionExtractor executes on the masked content
        ZonedDateTime refTime = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, ZoneId.of("UTC"));
        ExtractedAction action = actionExtractor.extract(masked, refTime);

        assertEquals("Submit", action.getActionVerb());
        assertEquals("Room 101", action.getLocation());
        assertTrue(action.isHasExplicitTime());
        assertNotNull(action.getDeadlineTimestamp());
        assertNotNull(action.getDeadlineFormatted());
        assertTrue("Stored task title must not contain raw person name", !action.getTitle().contains("Dwight"));
    }

    // =========================================================================
    // 3. Fail-Closed Policy Verification
    // =========================================================================

    @Test
    public void testFailClosed_ModelInferenceThrows_ElevatesLowToMediumAndRequiresApproval() {
        String input = "Read chapter 5 before tomorrow morning.";
        OnnxPiiClassifier throwingClassifier = new OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                throw new RuntimeException("Simulated native inference engine fault");
            }
        };

        PrivacyEngine engine = new PrivacyEngine(throwingClassifier);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        // Without classifier failure, this text would be LOW.
        // Under fail-closed policy, it must be elevated to at least MEDIUM with approval required.
        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(assessment));
        assertTrue(assessment.getRationale().contains("classifier check failed"));
    }

    @Test
    public void testFailClosed_ModelInferenceThrows_PreservesExistingHighSensitivity() {
        String input = "Urgent: your OTP is 582910 to confirm payment.";
        OnnxPiiClassifier throwingClassifier = new OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                throw new RuntimeException("Simulated native inference failure");
            }
        };

        PrivacyEngine engine = new PrivacyEngine(throwingClassifier);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        // Must remain HIGH due to rule OTP match
        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertFalse(PrivacyEngine.canAutoApprove(assessment));
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: OTP]"));
        assertFalse(assessment.getMaskedContent().contains("582910"));
    }

    @Test
    public void testFailClosed_InferenceFailureDoesNotCrashProcessing() {
        OnnxPiiClassifier throwingClassifier = new OnnxPiiClassifier(null) {
            @Override
            public synchronized List<TokenPrediction> classify(String text) {
                throw new IllegalStateException("ONNX runtime session was corrupted");
            }
        };

        PrivacyEngine engine = new PrivacyEngine(throwingClassifier);
        // Must not throw or crash
        PrivacyAssessment assessment = engine.assessAndMask("Any benign input text.");
        assertNotNull(assessment);
        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
    }

    // =========================================================================
    // 4. Sensitivity Ordering & Non-Regression
    // =========================================================================

    @Test
    public void testSensitivityNeverLowered_RuleHighWithModelMedium() {
        String input = "Payment OTP 123456 for Michael";
        TestStubClassifier stub = new TestStubClassifier(input);
        stub.addPrediction("Michael", 29, "B-PERSON", 23, 30);

        PrivacyEngine engine = new PrivacyEngine(stub);
        PrivacyAssessment assessment = engine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
    }

    @Test
    public void testPureRuleEngine_RetainsExistingLowSensitivityBehavior() {
        // When classifier is null, pure rule engine operates as before
        PrivacyEngine engine = new PrivacyEngine(null);
        PrivacyAssessment assessment = engine.assessAndMask("Organize notes for Friday lecture.");

        assertEquals(SensitivityLevel.LOW, assessment.getSensitivityLevel());
        assertFalse(assessment.requiresUserApproval());
        assertTrue(PrivacyEngine.canAutoApprove(assessment));
    }

    // =========================================================================
    // Helper Test Double
    // =========================================================================

    private static class TestStubClassifier extends OnnxPiiClassifier {
        private final List<TokenPrediction> stubPredictions = new ArrayList<>();

        TestStubClassifier(String input) {
            super(null);
        }

        void addPrediction(String text, int labelId, String labelName, int startOffset, int endOffset) {
            stubPredictions.add(new TokenPrediction(text, labelId, labelName, startOffset, endOffset));
        }

        @Override
        public synchronized List<TokenPrediction> classify(String text) {
            return stubPredictions;
        }
    }
}
