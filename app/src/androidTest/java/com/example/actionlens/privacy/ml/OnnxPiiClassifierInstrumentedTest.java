package com.example.actionlens.privacy.ml;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Android instrumentation smoke test for {@link OnnxPiiClassifier}.
 * Loads the actual packaged ONNX model and tokenizer from APK assets,
 * verifies on-device inference execution, and checks PII detection on a synthetic email address.
 */
@RunWith(AndroidJUnit4.class)
public class OnnxPiiClassifierInstrumentedTest {

    @Test
    public void loadsModelAndClassifiesSyntheticEmailOnDevice() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // 1. Load tokenizer from verified APK assets
        BertWordPieceTokenizer tokenizer = OnnxPiiClassifier.loadTokenizerFromAssets(appContext);
        assertNotNull("Tokenizer should load from assets", tokenizer);
        assertEquals("Vocab size should be 30522", 30522, tokenizer.getVocabSize());

        // 2. Instantiate classifier from packaged ONNX asset
        try (OnnxPiiClassifier classifier = OnnxPiiClassifier.fromAsset(appContext, tokenizer)) {
            assertNotNull("Classifier should initialize successfully", classifier);
            assertFalse("Classifier should not be closed", classifier.isClosed());

            // 3. Perform on-device inference on a synthetic sentence containing an email
            String syntheticText = "Please contact me at support@example.com for assistance.";
            List<OnnxPiiClassifier.TokenPrediction> predictions = classifier.classify(syntheticText);

            assertNotNull("Predictions should not be null", predictions);
            assertFalse("Predictions should not be empty", predictions.isEmpty());

            // 4. Verify predictions and offsets
            boolean emailDetected = false;
            for (OnnxPiiClassifier.TokenPrediction pred : predictions) {
                // Verify bounds
                assertTrue("Start offset must be >= 0", pred.getStartOffset() >= 0);
                assertTrue("End offset must be <= length", pred.getEndOffset() <= syntheticText.length());
                assertTrue("Start offset must precede end offset", pred.getStartOffset() < pred.getEndOffset());

                // Verify offset slice against original text
                String originalSlice = syntheticText.substring(pred.getStartOffset(), pred.getEndOffset()).toLowerCase();
                String tokenSlice = pred.getTokenText().replace("##", "").toLowerCase();
                assertEquals("Offset slice must map to token surface", tokenSlice, originalSlice);

                // Verify special tokens are never present
                assertNotEquals("[CLS]", pred.getTokenText());
                assertNotEquals("[SEP]", pred.getTokenText());

                // Check for email detection
                if ("EMAIL_ADDRESS".equals(pred.getEntityType())) {
                    emailDetected = true;
                    assertTrue("Email prediction should have isPii() == true", pred.isPii());
                }
            }

            assertTrue("Expected model to detect EMAIL_ADDRESS entity in synthetic sentence", emailDetected);
        }
    }

    @Test
    public void handlesEmptyInputWithoutInference() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BertWordPieceTokenizer tokenizer = OnnxPiiClassifier.loadTokenizerFromAssets(appContext);

        try (OnnxPiiClassifier classifier = OnnxPiiClassifier.fromAsset(appContext, tokenizer)) {
            assertTrue(classifier.classify("").isEmpty());
            assertTrue(classifier.classify("   ").isEmpty());
            assertTrue(classifier.classify(null).isEmpty());
        }
    }
}
