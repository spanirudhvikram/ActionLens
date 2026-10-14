package com.example.actionlens.privacy.ml;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * On-device ONNX Runtime wrapper for token-level PII classification.
 *
 * Models used: {@code onnx-community/bert-small-pii-detection-ONNX}
 * (4-layer BERT-small, 49 token-classification BIO labels).
 *
 * Privacy Invariants:
 * 1. Operates 100% locally in-memory via ONNX Runtime Android (zero network calls).
 * 2. Model file is copied only to app-private storage (context.getFilesDir()), never external/shared storage.
 * 3. Never logs raw notification content, PII spans, or user text.
 * 4. Accurately maps token spans back to the original text via {@link BertWordPieceTokenizer}.
 * 5. Identifies DATE_TIME spans explicitly so deadline extraction logic can retain them.
 */
public class OnnxPiiClassifier implements AutoCloseable {

    public static final String DEFAULT_MODEL_ASSET_NAME = "bert_small_pii.onnx";
    public static final String DEFAULT_VOCAB_ASSET_NAME = "bert_vocab.txt";
    public static final long EXPECTED_MODEL_SIZE_BYTES = 28748527L;

    public static final int MAX_SEQUENCE_LENGTH = 512;
    public static final int MAX_CONTENT_TOKENS = 510;
    public static final int NUM_LABELS = 49;

    /**
     * Exact 49-label mapping from the official model configuration (config.json).
     * Index 0 is "O", followed by 24 entity types with paired B- and I- BIO prefixes.
     */
    public static final String[] LABELS = {
            "O",                     // 0
            "B-AGE",                 // 1
            "I-AGE",                 // 2
            "B-COORDINATE",          // 3
            "I-COORDINATE",          // 4
            "B-CREDIT_CARD",         // 5
            "I-CREDIT_CARD",         // 6
            "B-DATE_TIME",           // 7
            "I-DATE_TIME",           // 8
            "B-EMAIL_ADDRESS",       // 9
            "I-EMAIL_ADDRESS",       // 10
            "B-FINANCIAL",           // 11
            "I-FINANCIAL",           // 12
            "B-IBAN_CODE",           // 13
            "I-IBAN_CODE",           // 14
            "B-IMEI",                // 15
            "I-IMEI",                // 16
            "B-IP_ADDRESS",          // 17
            "I-IP_ADDRESS",          // 18
            "B-LOCATION",            // 19
            "I-LOCATION",            // 20
            "B-MAC_ADDRESS",         // 21
            "I-MAC_ADDRESS",         // 22
            "B-NRP",                 // 23
            "I-NRP",                 // 24
            "B-ORGANIZATION",        // 25
            "I-ORGANIZATION",        // 26
            "B-PASSWORD",            // 27
            "I-PASSWORD",            // 28
            "B-PERSON",              // 29
            "I-PERSON",              // 30
            "B-PHONE_NUMBER",        // 31
            "I-PHONE_NUMBER",        // 32
            "B-TITLE",               // 33
            "I-TITLE",               // 34
            "B-URL",                 // 35
            "I-URL",                 // 36
            "B-US_BANK_NUMBER",      // 37
            "I-US_BANK_NUMBER",      // 38
            "B-US_DRIVER_LICENSE",   // 39
            "I-US_DRIVER_LICENSE",   // 40
            "B-US_ITIN",             // 41
            "I-US_ITIN",             // 42
            "B-US_LICENSE_PLATE",    // 43
            "I-US_LICENSE_PLATE",    // 44
            "B-US_PASSPORT",         // 45
            "I-US_PASSPORT",         // 46
            "B-US_SSN",              // 47
            "I-US_SSN"               // 48
    };

    private static final Map<String, Integer> LABEL_TO_ID;
    static {
        Map<String, Integer> map = new HashMap<>(NUM_LABELS);
        for (int i = 0; i < LABELS.length; i++) {
            map.put(LABELS[i], i);
        }
        LABEL_TO_ID = Collections.unmodifiableMap(map);
    }

    /**
     * Immutable prediction for a single token in the input string.
     */
    public static class TokenPrediction {
        private final String tokenText;
        private final int labelId;
        private final String labelName;
        private final int startOffset;
        private final int endOffset;

        public TokenPrediction(String tokenText, int labelId, String labelName, int startOffset, int endOffset) {
            this.tokenText = tokenText;
            this.labelId = labelId;
            this.labelName = labelName;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public String getTokenText() {
            return tokenText;
        }

        public int getLabelId() {
            return labelId;
        }

        public String getLabelName() {
            return labelName;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        /** Returns true if this token is predicted as any PII category (i.e. not "O"). */
        public boolean isPii() {
            return labelId != 0;
        }

        /** Returns true if this prediction is a DATE_TIME entity (B-DATE_TIME or I-DATE_TIME). */
        public boolean isDateTime() {
            return labelId == 7 || labelId == 8;
        }

        /** Returns the base entity name (e.g. "EMAIL_ADDRESS", "PERSON", "DATE_TIME"), or "O". */
        public String getEntityType() {
            if (labelId == 0) return "O";
            if (labelName.startsWith("B-") || labelName.startsWith("I-")) {
                return labelName.substring(2);
            }
            return labelName;
        }

        public boolean isBeginning() {
            return labelName.startsWith("B-");
        }

        public boolean isInside() {
            return labelName.startsWith("I-");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TokenPrediction that = (TokenPrediction) o;
            return labelId == that.labelId &&
                    startOffset == that.startOffset &&
                    endOffset == that.endOffset &&
                    Objects.equals(tokenText, that.tokenText) &&
                    Objects.equals(labelName, that.labelName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tokenText, labelId, labelName, startOffset, endOffset);
        }

        @Override
        public String toString() {
            // Privacy invariant: do not log raw token text to prevent PII exposure in logs
            return "TokenPrediction{" +
                    "label=" + labelName + " (" + labelId + ")" +
                    ", offsets=[" + startOffset + ", " + endOffset + "]" +
                    '}';
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final BertWordPieceTokenizer tokenizer;
    private final boolean ownsEnvironment;
    private volatile boolean closed = false;

    public OnnxPiiClassifier(OrtEnvironment env, OrtSession session, BertWordPieceTokenizer tokenizer) {
        this(env, session, tokenizer, false);
    }

    public OnnxPiiClassifier(OrtEnvironment env, OrtSession session, BertWordPieceTokenizer tokenizer, boolean ownsEnvironment) {
        if (env == null) {
            throw new IllegalArgumentException("OrtEnvironment must not be null");
        }
        if (session == null) {
            throw new IllegalArgumentException("OrtSession must not be null");
        }
        if (tokenizer == null) {
            throw new IllegalArgumentException("BertWordPieceTokenizer must not be null");
        }
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.ownsEnvironment = ownsEnvironment;
    }

    /**
     * Protected constructor for creating test doubles or subclasses without initializing native ONNX sessions.
     */
    protected OnnxPiiClassifier(BertWordPieceTokenizer tokenizer) {
        this.env = null;
        this.session = null;
        this.tokenizer = tokenizer;
        this.ownsEnvironment = false;
    }

    /**
     * Factory method creating a classifier from the default assets in the APK.
     */
    public static OnnxPiiClassifier fromAsset(Context context, BertWordPieceTokenizer tokenizer) throws IOException, OrtException {
        return fromAsset(context, DEFAULT_MODEL_ASSET_NAME, tokenizer);
    }

    /**
     * Factory method creating a classifier from a named model asset.
     */
    public static OnnxPiiClassifier fromAsset(Context context, String assetName, BertWordPieceTokenizer tokenizer) throws IOException, OrtException {
        File modelFile = getOrCopyModelFromAssets(context, assetName);
        return fromFile(modelFile, tokenizer);
    }

    /**
     * Factory method creating a classifier from an existing model file on disk.
     */
    public static OnnxPiiClassifier fromFile(File modelFile, BertWordPieceTokenizer tokenizer) throws OrtException {
        if (modelFile == null || !modelFile.exists()) {
            throw new IllegalArgumentException("Model file does not exist: " + (modelFile != null ? modelFile.getAbsolutePath() : "null"));
        }
        if (tokenizer == null) {
            throw new IllegalArgumentException("Tokenizer must not be null");
        }

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        OrtSession session = env.createSession(modelFile.getAbsolutePath(), opts);

        return new OnnxPiiClassifier(env, session, tokenizer, true);
    }

    /**
     * Convenience helper to load the default tokenizer from assets.
     */
    public static BertWordPieceTokenizer loadTokenizerFromAssets(Context context) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        try (InputStream is = context.getAssets().open(DEFAULT_VOCAB_ASSET_NAME)) {
            return BertWordPieceTokenizer.fromInputStream(is, true);
        }
    }

    /**
     * Safely copies the model from APK assets to app-private internal storage,
     * reusing a verified existing copy if present.
     */
    public static File getOrCopyModelFromAssets(Context context, String assetName) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        if (assetName == null || assetName.trim().isEmpty()) {
            throw new IllegalArgumentException("Asset name must not be empty");
        }

        File modelsDir = new File(context.getFilesDir(), "models");
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw new IOException("Failed to create app-private models directory: " + modelsDir.getAbsolutePath());
        }

        File targetFile = new File(modelsDir, assetName);
        if (targetFile.exists() && targetFile.length() == EXPECTED_MODEL_SIZE_BYTES) {
            return targetFile;
        }

        File tempFile = new File(modelsDir, assetName + ".tmp");
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();

            if (total != EXPECTED_MODEL_SIZE_BYTES) {
                tempFile.delete();
                throw new IOException("Model asset size mismatch: expected " + EXPECTED_MODEL_SIZE_BYTES + " bytes, got " + total);
            }
        }

        if (targetFile.exists() && !targetFile.delete()) {
            tempFile.delete();
            throw new IOException("Failed to replace existing model file: " + targetFile.getAbsolutePath());
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete();
            throw new IOException("Failed to rename temporary model file to " + targetFile.getAbsolutePath());
        }

        return targetFile;
    }

    /**
     * Classifies PII in the given input text string.
     * Synchronized to prevent concurrent thread usage from corrupting the OrtSession state.
     *
     * @param text Raw notification text
     * @return List of predictions for real tokens (special tokens excluded)
     * @throws OrtException If model execution fails
     */
    public synchronized List<TokenPrediction> classify(String text) throws OrtException {
        if (closed) {
            throw new IllegalStateException("OnnxPiiClassifier is closed");
        }
        if (session == null) {
            throw new IllegalStateException("OrtSession is not initialized");
        }
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<BertWordPieceTokenizer.Token> rawTokens = tokenizer.tokenize(text, false);
        if (rawTokens.isEmpty()) {
            return Collections.emptyList();
        }

        // Truncate content tokens safely to at most 510 tokens so [CLS] + content + [SEP] <= 512
        List<BertWordPieceTokenizer.Token> contentTokens = rawTokens;
        if (contentTokens.size() > MAX_CONTENT_TOKENS) {
            contentTokens = contentTokens.subList(0, MAX_CONTENT_TOKENS);
        }

        // Prepend [CLS] and append [SEP] with sentinel offsets (-1, -1)
        List<BertWordPieceTokenizer.Token> modelTokens = new ArrayList<>(contentTokens.size() + 2);
        modelTokens.add(new BertWordPieceTokenizer.Token(
                BertWordPieceTokenizer.DEFAULT_CLS_TOKEN,
                tokenizer.getClsTokenId(),
                -1, -1
        ));
        modelTokens.addAll(contentTokens);
        modelTokens.add(new BertWordPieceTokenizer.Token(
                BertWordPieceTokenizer.DEFAULT_SEP_TOKEN,
                tokenizer.getSepTokenId(),
                -1, -1
        ));

        int seqLen = modelTokens.size();
        long[][] inputIds = new long[1][seqLen];
        long[][] attentionMask = new long[1][seqLen];
        long[][] tokenTypeIds = new long[1][seqLen];

        for (int i = 0; i < seqLen; i++) {
            inputIds[0][i] = modelTokens.get(i).getId();
            attentionMask[0][i] = 1L;
            tokenTypeIds[0][i] = 0L;
        }

        OnnxTensor inputIdsTensor = null;
        OnnxTensor attentionMaskTensor = null;
        OnnxTensor tokenTypeIdsTensor = null;

        try {
            inputIdsTensor = OnnxTensor.createTensor(env, inputIds);
            attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask);
            tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds);

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>(3);
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            inputs.put("token_type_ids", tokenTypeIdsTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                if (result.size() == 0) {
                    throw new IllegalStateException("Model inference produced no output tensors");
                }
                OnnxValue outputVal = result.get(0);
                if (!(outputVal instanceof OnnxTensor)) {
                    throw new IllegalStateException("Expected output to be OnnxTensor, got: " +
                            (outputVal != null ? outputVal.getClass().getName() : "null"));
                }
                Object valObj = ((OnnxTensor) outputVal).getValue();
                if (!(valObj instanceof float[][][])) {
                    throw new IllegalStateException("Expected float[][][] logits, got: " +
                            (valObj != null ? valObj.getClass().getName() : "null"));
                }
                float[][][] logits = (float[][][]) valObj;
                if (logits.length != 1 || logits[0].length != seqLen || logits[0][0].length != NUM_LABELS) {
                    throw new IllegalStateException(String.format(
                            Locale.US,
                            "Unexpected logits shape [%d, %d, %d], expected [1, %d, %d]",
                            logits.length,
                            logits.length > 0 ? logits[0].length : 0,
                            logits.length > 0 && logits[0].length > 0 ? logits[0][0].length : 0,
                            seqLen,
                            NUM_LABELS
                    ));
                }

                return decodeLogits(modelTokens, logits);
            }
        } finally {
            if (inputIdsTensor != null) inputIdsTensor.close();
            if (attentionMaskTensor != null) attentionMaskTensor.close();
            if (tokenTypeIdsTensor != null) tokenTypeIdsTensor.close();
        }
    }

    /**
     * Decodes model logits into structured TokenPredictions using argmax classification.
     * Special tokens ([CLS], [SEP]) and tokens with invalid sentinel offsets (-1, -1)
     * are automatically filtered out.
     */
    public static List<TokenPrediction> decodeLogits(
            List<BertWordPieceTokenizer.Token> modelTokens,
            float[][][] logits) {
        if (modelTokens == null || logits == null) {
            return Collections.emptyList();
        }
        int seqLen = modelTokens.size();
        if (logits.length != 1 || logits[0].length != seqLen) {
            throw new IllegalArgumentException(String.format(
                    Locale.US,
                    "Mismatch between modelTokens size (%d) and logits sequence dimension (%d)",
                    seqLen,
                    logits.length > 0 ? logits[0].length : 0
            ));
        }

        List<TokenPrediction> predictions = new ArrayList<>();
        for (int i = 0; i < seqLen; i++) {
            BertWordPieceTokenizer.Token token = modelTokens.get(i);
            // Ignore special tokens and tokens with sentinel offsets (-1, -1)
            if (token.isSpecialToken() || !token.hasValidOffsets()) {
                continue;
            }

            float[] tokenLogits = logits[0][i];
            if (tokenLogits.length != NUM_LABELS) {
                throw new IllegalArgumentException(String.format(
                        Locale.US,
                        "Expected %d logits per token, got %d",
                        NUM_LABELS,
                        tokenLogits.length
                ));
            }

            int bestLabelId = 0;
            float maxLogit = tokenLogits[0];
            for (int c = 1; c < NUM_LABELS; c++) {
                if (tokenLogits[c] > maxLogit) {
                    maxLogit = tokenLogits[c];
                    bestLabelId = c;
                }
            }

            String labelName = getLabelName(bestLabelId);
            predictions.add(new TokenPrediction(
                    token.getText(),
                    bestLabelId,
                    labelName,
                    token.getStartOffset(),
                    token.getEndOffset()
            ));
        }
        return Collections.unmodifiableList(predictions);
    }

    public static String getLabelName(int id) {
        if (id < 0 || id >= LABELS.length) {
            throw new IllegalArgumentException("Invalid label ID: " + id + ", must be in [0, " + (LABELS.length - 1) + "]");
        }
        return LABELS[id];
    }

    public static int getLabelId(String label) {
        Integer id = LABEL_TO_ID.get(label);
        if (id == null) {
            throw new IllegalArgumentException("Unknown label name: " + label);
        }
        return id;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
            }
        }
        if (ownsEnvironment && env != null) {
            env.close();
        }
    }
}
