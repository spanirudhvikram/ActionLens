package com.example.actionlens.privacy.ml;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link OnnxPiiClassifier}.
 * Tests label mappings, BIO tagging, argmax decoding, sentinel offset filtering,
 * output shape validation, and privacy invariants without requiring native ONNX binaries.
 */
public class OnnxPiiClassifierTest {

    // =========================================================================
    // 1. Label mapping and configuration checks
    // =========================================================================

    @Test
    public void exactly49LabelsDefined() {
        assertEquals(49, OnnxPiiClassifier.NUM_LABELS);
        assertEquals(49, OnnxPiiClassifier.LABELS.length);
    }

    @Test
    public void label0IsOutside() {
        assertEquals("O", OnnxPiiClassifier.getLabelName(0));
        assertEquals(0, OnnxPiiClassifier.getLabelId("O"));
    }

    @Test
    public void expectedPiiEntityLabelsMatchModelConfig() {
        // Verified against model config.json
        assertEquals("B-AGE",            OnnxPiiClassifier.getLabelName(1));
        assertEquals("I-AGE",            OnnxPiiClassifier.getLabelName(2));
        assertEquals("B-DATE_TIME",      OnnxPiiClassifier.getLabelName(7));
        assertEquals("I-DATE_TIME",      OnnxPiiClassifier.getLabelName(8));
        assertEquals("B-EMAIL_ADDRESS",  OnnxPiiClassifier.getLabelName(9));
        assertEquals("I-EMAIL_ADDRESS",  OnnxPiiClassifier.getLabelName(10));
        assertEquals("B-PERSON",         OnnxPiiClassifier.getLabelName(29));
        assertEquals("I-PERSON",         OnnxPiiClassifier.getLabelName(30));
        assertEquals("B-PHONE_NUMBER",   OnnxPiiClassifier.getLabelName(31));
        assertEquals("I-PHONE_NUMBER",   OnnxPiiClassifier.getLabelName(32));
        assertEquals("B-US_SSN",         OnnxPiiClassifier.getLabelName(47));
        assertEquals("I-US_SSN",         OnnxPiiClassifier.getLabelName(48));
    }

    @Test
    public void labelMappingRoundTrip() {
        for (int i = 0; i < OnnxPiiClassifier.NUM_LABELS; i++) {
            String name = OnnxPiiClassifier.getLabelName(i);
            assertNotNull(name);
            assertEquals("Roundtrip failed for id " + i, i, OnnxPiiClassifier.getLabelId(name));
        }
    }

    @Test
    public void bioLabelSymmetry() {
        // Every B- entity type must have an identical I- entity type
        int bCount = 0;
        int iCount = 0;
        for (int id = 1; id < OnnxPiiClassifier.NUM_LABELS; id++) {
            String label = OnnxPiiClassifier.getLabelName(id);
            if (label.startsWith("B-")) {
                bCount++;
                String entity = label.substring(2);
                int expectedContinuationId = id + 1;
                assertEquals("Matching I- tag for " + label,
                        "I-" + entity, OnnxPiiClassifier.getLabelName(expectedContinuationId));
            } else if (label.startsWith("I-")) {
                iCount++;
            }
        }
        assertEquals(24, bCount);
        assertEquals(24, iCount);
        assertEquals(49, 1 + bCount + iCount);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLabelIdThrows() {
        OnnxPiiClassifier.getLabelName(49);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeLabelIdThrows() {
        OnnxPiiClassifier.getLabelName(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownLabelNameThrows() {
        OnnxPiiClassifier.getLabelId("UNKNOWN_LABEL");
    }

    // =========================================================================
    // 2. TokenPrediction metadata and privacy tests
    // =========================================================================

    @Test
    public void tokenPredictionPiiPredicates() {
        OnnxPiiClassifier.TokenPrediction oPred =
                new OnnxPiiClassifier.TokenPrediction("hello", 0, "O", 0, 5);
        assertFalse(oPred.isPii());
        assertFalse(oPred.isDateTime());
        assertEquals("O", oPred.getEntityType());
        assertFalse(oPred.isBeginning());
        assertFalse(oPred.isInside());

        OnnxPiiClassifier.TokenPrediction emailPred =
                new OnnxPiiClassifier.TokenPrediction("john@test.com", 9, "B-EMAIL_ADDRESS", 10, 23);
        assertTrue(emailPred.isPii());
        assertFalse(emailPred.isDateTime());
        assertEquals("EMAIL_ADDRESS", emailPred.getEntityType());
        assertTrue(emailPred.isBeginning());
        assertFalse(emailPred.isInside());

        OnnxPiiClassifier.TokenPrediction dateTimePred =
                new OnnxPiiClassifier.TokenPrediction("friday", 7, "B-DATE_TIME", 30, 36);
        assertTrue(dateTimePred.isPii());
        assertTrue(dateTimePred.isDateTime());
        assertEquals("DATE_TIME", dateTimePred.getEntityType());
    }

    @Test
    public void tokenPredictionToStringDoesNotLeakRawText() {
        // Privacy invariant: toString must not contain tokenText
        String secret = "sensitive_password_123";
        OnnxPiiClassifier.TokenPrediction pred =
                new OnnxPiiClassifier.TokenPrediction(secret, 27, "B-PASSWORD", 0, secret.length());

        String representation = pred.toString();
        assertFalse("toString leaked raw secret text: " + representation,
                representation.contains(secret));
        assertTrue(representation.contains("B-PASSWORD"));
    }

    // =========================================================================
    // 3. decodeLogits tests: argmax, filtering, and shape validation
    // =========================================================================

    @Test
    public void decodeLogitsAppliesArgmaxAndFiltersSpecialTokens() {
        // Sequence: [CLS](-1,-1), "john"(0,4), "@"(4,5), "xyz"(5,8), [SEP](-1,-1)
        List<BertWordPieceTokenizer.Token> tokens = new ArrayList<>();
        tokens.add(new BertWordPieceTokenizer.Token("[CLS]", 101, -1, -1));
        tokens.add(new BertWordPieceTokenizer.Token("john", 2198, 0, 4));
        tokens.add(new BertWordPieceTokenizer.Token("@", 1030, 4, 5));
        tokens.add(new BertWordPieceTokenizer.Token("xyz", 100, 5, 8));
        tokens.add(new BertWordPieceTokenizer.Token("[SEP]", 102, -1, -1));

        int seqLen = tokens.size();
        float[][][] logits = new float[1][seqLen][OnnxPiiClassifier.NUM_LABELS];

        // Token 0 ([CLS]): set label to B-PERSON (29). Must still be filtered out.
        logits[0][0][29] = 10.0f;

        // Token 1 ("john"): set label to B-EMAIL_ADDRESS (9)
        logits[0][1][9] = 5.5f;

        // Token 2 ("@"): set label to I-EMAIL_ADDRESS (10)
        logits[0][2][10] = 7.2f;

        // Token 3 ("xyz"): set label to O (0)
        logits[0][3][0] = 3.0f;

        // Token 4 ([SEP]): set label to B-DATE_TIME (7). Must be filtered out.
        logits[0][4][7] = 8.0f;

        List<OnnxPiiClassifier.TokenPrediction> predictions =
                OnnxPiiClassifier.decodeLogits(tokens, logits);

        // Only 3 real content tokens must be returned; [CLS] and [SEP] filtered
        assertEquals(3, predictions.size());

        OnnxPiiClassifier.TokenPrediction p0 = predictions.get(0);
        assertEquals("john", p0.getTokenText());
        assertEquals(9, p0.getLabelId());
        assertEquals("B-EMAIL_ADDRESS", p0.getLabelName());
        assertEquals(0, p0.getStartOffset());
        assertEquals(4, p0.getEndOffset());

        OnnxPiiClassifier.TokenPrediction p1 = predictions.get(1);
        assertEquals("@", p1.getTokenText());
        assertEquals(10, p1.getLabelId());
        assertEquals("I-EMAIL_ADDRESS", p1.getLabelName());
        assertEquals(4, p1.getStartOffset());
        assertEquals(5, p1.getEndOffset());

        OnnxPiiClassifier.TokenPrediction p2 = predictions.get(2);
        assertEquals("xyz", p2.getTokenText());
        assertEquals(0, p2.getLabelId());
        assertEquals("O", p2.getLabelName());
        assertEquals(5, p2.getStartOffset());
        assertEquals(8, p2.getEndOffset());
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeLogitsThrowsOnMismatchedSequenceLength() {
        List<BertWordPieceTokenizer.Token> tokens = new ArrayList<>();
        tokens.add(new BertWordPieceTokenizer.Token("hello", 1, 0, 5));

        // Logits has length 2, but tokens has length 1
        float[][][] logits = new float[1][2][OnnxPiiClassifier.NUM_LABELS];
        OnnxPiiClassifier.decodeLogits(tokens, logits);
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeLogitsThrowsOnWrongLabelDimension() {
        List<BertWordPieceTokenizer.Token> tokens = new ArrayList<>();
        tokens.add(new BertWordPieceTokenizer.Token("hello", 1, 0, 5));

        // Logits dimension is 10 instead of 49
        float[][][] logits = new float[1][1][10];
        OnnxPiiClassifier.decodeLogits(tokens, logits);
    }

    @Test
    public void decodeLogitsHandlesNullInputs() {
        assertTrue(OnnxPiiClassifier.decodeLogits(null, null).isEmpty());
        assertTrue(OnnxPiiClassifier.decodeLogits(new ArrayList<>(), null).isEmpty());
    }

    // =========================================================================
    // 4. Model limits and constants
    // =========================================================================

    @Test
    public void sequenceLimitsPreserveSpecialTokenSpace() {
        assertEquals(512, OnnxPiiClassifier.MAX_SEQUENCE_LENGTH);
        assertEquals(510, OnnxPiiClassifier.MAX_CONTENT_TOKENS);
        // Guaranteed invariant: MAX_CONTENT_TOKENS + [CLS] + [SEP] <= MAX_SEQUENCE_LENGTH
        assertEquals(OnnxPiiClassifier.MAX_SEQUENCE_LENGTH,
                OnnxPiiClassifier.MAX_CONTENT_TOKENS + 2);
    }
}
