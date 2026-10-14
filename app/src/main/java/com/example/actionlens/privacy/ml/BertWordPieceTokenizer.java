package com.example.actionlens.privacy.ml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Self-contained Java WordPiece tokenizer compatible with BERT vocabularies.
 *
 * Privacy Invariants:
 * 1. Tracks exact character start and end offsets in the original raw text string
 *    for every token, enabling safe and boundary-accurate PII span masking.
 * 2. Assigns sentinel offsets (-1, -1) to special tokens ([CLS], [SEP], [PAD]) so
 *    they can never be matched or redacted as user text.
 * 3. Operates entirely in memory with zero cloud network calls or file retention.
 */
public class BertWordPieceTokenizer {

    public static final String DEFAULT_PAD_TOKEN = "[PAD]";
    public static final String DEFAULT_UNK_TOKEN = "[UNK]";
    public static final String DEFAULT_CLS_TOKEN = "[CLS]";
    public static final String DEFAULT_SEP_TOKEN = "[SEP]";
    public static final String DEFAULT_MASK_TOKEN = "[MASK]";
    public static final String CONTINUATION_PREFIX = "##";

    public static final int DEFAULT_MAX_CHARS_PER_WORD = 100;

    /**
     * Immutable representation of a token produced by the tokenizer.
     */
    public static class Token {
        private final String text;
        private final int id;
        private final int startOffset;
        private final int endOffset;

        public Token(String text, int id, int startOffset, int endOffset) {
            this.text = text;
            this.id = id;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public String getText() {
            return text;
        }

        public int getId() {
            return id;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        /**
         * Returns true if this token is a synthetic special token (e.g. [CLS], [SEP])
         * that does not correspond to a span in the original user text.
         */
        public boolean isSpecialToken() {
            return startOffset == -1 && endOffset == -1;
        }

        /**
         * Returns true if this token has valid start and end character offsets.
         */
        public boolean hasValidOffsets() {
            return startOffset >= 0 && endOffset >= startOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Token token = (Token) o;
            return id == token.id &&
                    startOffset == token.startOffset &&
                    endOffset == token.endOffset &&
                    Objects.equals(text, token.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, id, startOffset, endOffset);
        }

        @Override
        public String toString() {
            return "Token{" +
                    "text='" + text + '\'' +
                    ", id=" + id +
                    ", startOffset=" + startOffset +
                    ", endOffset=" + endOffset +
                    '}';
        }
    }

    private final Map<String, Integer> vocab;
    private final Map<Integer, String> idToToken;
    private final boolean doLowerCase;
    private final int maxCharsPerWord;

    private final int padTokenId;
    private final int unkTokenId;
    private final int clsTokenId;
    private final int sepTokenId;
    private final int maskTokenId;

    public BertWordPieceTokenizer(Map<String, Integer> vocabulary) {
        this(vocabulary, true, DEFAULT_MAX_CHARS_PER_WORD);
    }

    public BertWordPieceTokenizer(Map<String, Integer> vocabulary, boolean doLowerCase) {
        this(vocabulary, doLowerCase, DEFAULT_MAX_CHARS_PER_WORD);
    }

    public BertWordPieceTokenizer(Map<String, Integer> vocabulary, boolean doLowerCase, int maxCharsPerWord) {
        if (vocabulary == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }
        this.vocab = Collections.unmodifiableMap(new LinkedHashMap<>(vocabulary));
        this.doLowerCase = doLowerCase;
        this.maxCharsPerWord = maxCharsPerWord > 0 ? maxCharsPerWord : DEFAULT_MAX_CHARS_PER_WORD;

        Map<Integer, String> reverseMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : this.vocab.entrySet()) {
            reverseMap.put(entry.getValue(), entry.getKey());
        }
        this.idToToken = Collections.unmodifiableMap(reverseMap);

        this.padTokenId = vocab.getOrDefault(DEFAULT_PAD_TOKEN, 0);
        this.unkTokenId = vocab.getOrDefault(DEFAULT_UNK_TOKEN, 100);
        this.clsTokenId = vocab.getOrDefault(DEFAULT_CLS_TOKEN, 101);
        this.sepTokenId = vocab.getOrDefault(DEFAULT_SEP_TOKEN, 102);
        this.maskTokenId = vocab.getOrDefault(DEFAULT_MASK_TOKEN, 103);
    }

    /**
     * Loads a tokenizer from a BERT vocab.txt reader (one token per line, line index is ID).
     */
    public static BertWordPieceTokenizer fromReader(Reader reader) throws IOException {
        return fromReader(reader, true, DEFAULT_MAX_CHARS_PER_WORD);
    }

    public static BertWordPieceTokenizer fromReader(Reader reader, boolean doLowerCase) throws IOException {
        return fromReader(reader, doLowerCase, DEFAULT_MAX_CHARS_PER_WORD);
    }

    public static BertWordPieceTokenizer fromReader(Reader reader, boolean doLowerCase, int maxCharsPerWord) throws IOException {
        Map<String, Integer> vocabMap = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            int id = 0;
            while ((line = br.readLine()) != null) {
                String token = line.trim();
                if (!token.isEmpty()) {
                    vocabMap.put(token, id);
                }
                id++;
            }
        }
        return new BertWordPieceTokenizer(vocabMap, doLowerCase, maxCharsPerWord);
    }

    /**
     * Loads a tokenizer from a BERT vocab.txt input stream using UTF-8 encoding.
     */
    public static BertWordPieceTokenizer fromInputStream(InputStream inputStream) throws IOException {
        return fromReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    public static BertWordPieceTokenizer fromInputStream(InputStream inputStream, boolean doLowerCase) throws IOException {
        return fromReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8), doLowerCase);
    }

    /**
     * Tokenizes text into tokens with original character offsets, without special tokens.
     */
    public List<Token> tokenize(String text) {
        return tokenize(text, false);
    }

    /**
     * Tokenizes text into tokens with original character offsets.
     *
     * @param text Original raw input string
     * @param addSpecialTokens If true, prepends [CLS] and appends [SEP] with sentinel offsets (-1, -1)
     * @return List of Token objects
     */
    public List<Token> tokenize(String text, boolean addSpecialTokens) {
        List<Token> result = new ArrayList<>();

        if (addSpecialTokens) {
            result.add(new Token(DEFAULT_CLS_TOKEN, clsTokenId, -1, -1));
        }

        if (text != null && !text.isEmpty()) {
            tokenizeBasic(text, result);
        }

        if (addSpecialTokens) {
            result.add(new Token(DEFAULT_SEP_TOKEN, sepTokenId, -1, -1));
        }

        return result;
    }

    /**
     * Convenience method returning token IDs.
     */
    public int[] encode(String text, boolean addSpecialTokens) {
        List<Token> tokens = tokenize(text, addSpecialTokens);
        int[] ids = new int[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            ids[i] = tokens.get(i).getId();
        }
        return ids;
    }

    /**
     * Basic tokenization: splits text on whitespace, punctuation, and CJK characters,
     * maintaining exact character boundaries.
     */
    private void tokenizeBasic(String text, List<Token> outputTokens) {
        int length = text.length();
        int i = 0;

        while (i < length) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (isWhitespace(cp)) {
                i += charCount;
                continue;
            }

            if (isPunctuation(cp) || isChineseChar(cp)) {
                processWord(text, i, i + charCount, outputTokens);
                i += charCount;
                continue;
            }

            // Word token: accumulate until next whitespace, punctuation, or CJK char
            int wordStart = i;
            i += charCount;
            while (i < length) {
                int nextCp = text.codePointAt(i);
                if (isWhitespace(nextCp) || isPunctuation(nextCp) || isChineseChar(nextCp)) {
                    break;
                }
                i += Character.charCount(nextCp);
            }
            int wordEnd = i;
            processWord(text, wordStart, wordEnd, outputTokens);
        }
    }

    /**
     * Processes a single word chunk, mapping lowercased/NFD characters back to exact raw offsets,
     * and applies greedy WordPiece subword splitting.
     */
    private void processWord(String text, int wordStart, int wordEnd, List<Token> outputTokens) {
        StringBuilder normalizedWord = new StringBuilder();
        List<Integer> charStartOffsets = new ArrayList<>();
        List<Integer> charEndOffsets = new ArrayList<>();

        int idx = wordStart;
        while (idx < wordEnd) {
            int cp = text.codePointAt(idx);
            int charCount = Character.charCount(cp);

            if (isControl(cp)) {
                idx += charCount;
                continue;
            }

            if (doLowerCase) {
                int lowerCp = Character.toLowerCase(cp);
                String cpStr = new String(Character.toChars(lowerCp));
                String nfd = Normalizer.normalize(cpStr, Normalizer.Form.NFD);
                for (int k = 0; k < nfd.length(); k++) {
                    char c = nfd.charAt(k);
                    if (Character.getType(c) != Character.NON_SPACING_MARK) {
                        normalizedWord.append(c);
                        charStartOffsets.add(idx);
                        charEndOffsets.add(idx + charCount);
                    }
                }
            } else {
                for (int k = 0; k < charCount; k++) {
                    normalizedWord.append(text.charAt(idx + k));
                    charStartOffsets.add(idx);
                    charEndOffsets.add(idx + charCount);
                }
            }

            idx += charCount;
        }

        if (normalizedWord.length() == 0) {
            return;
        }

        String normStr = normalizedWord.toString();
        int normLen = normStr.length();

        if (normLen > maxCharsPerWord) {
            outputTokens.add(new Token(DEFAULT_UNK_TOKEN, unkTokenId, wordStart, wordEnd));
            return;
        }

        // Greedy WordPiece subword matching
        int start = 0;
        boolean isBad = false;
        List<Token> wordPieceTokens = new ArrayList<>();

        while (start < normLen) {
            int end = normLen;
            String curSubstr = null;
            int curId = -1;

            while (start < end) {
                String substr = normStr.substring(start, end);
                if (start > 0) {
                    substr = CONTINUATION_PREFIX + substr;
                }
                Integer id = vocab.get(substr);
                if (id != null) {
                    curSubstr = substr;
                    curId = id;
                    break;
                }
                end--;
            }

            if (curSubstr == null) {
                isBad = true;
                break;
            }

            int rawStart = charStartOffsets.get(start);
            int rawEnd = charEndOffsets.get(end - 1);
            wordPieceTokens.add(new Token(curSubstr, curId, rawStart, rawEnd));
            start = end;
        }

        if (isBad) {
            outputTokens.add(new Token(DEFAULT_UNK_TOKEN, unkTokenId, wordStart, wordEnd));
        } else {
            outputTokens.addAll(wordPieceTokens);
        }
    }

    public static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' ||
                Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    public static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') {
            return false;
        }
        int type = Character.getType(cp);
        return type == Character.CONTROL ||
                type == Character.FORMAT ||
                type == Character.PRIVATE_USE ||
                type == Character.SURROGATE ||
                type == Character.UNASSIGNED;
    }

    public static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) ||
                (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION ||
                type == Character.DASH_PUNCTUATION ||
                type == Character.START_PUNCTUATION ||
                type == Character.END_PUNCTUATION ||
                type == Character.INITIAL_QUOTE_PUNCTUATION ||
                type == Character.FINAL_QUOTE_PUNCTUATION ||
                type == Character.OTHER_PUNCTUATION;
    }

    public static boolean isChineseChar(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) ||
                (cp >= 0x3400 && cp <= 0x4DBF) ||
                (cp >= 0x20000 && cp <= 0x2A6DF) ||
                (cp >= 0x2A700 && cp <= 0x2B73F) ||
                (cp >= 0x2B740 && cp <= 0x2B81F) ||
                (cp >= 0x2B820 && cp <= 0x2CEAF) ||
                (cp >= 0xF900 && cp <= 0xFAFF) ||
                (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    public int getVocabSize() {
        return vocab.size();
    }

    public Map<String, Integer> getVocab() {
        return vocab;
    }

    public boolean containsToken(String token) {
        return vocab.containsKey(token);
    }

    public int getTokenId(String token) {
        return vocab.getOrDefault(token, unkTokenId);
    }

    public String getTokenString(int id) {
        return idToToken.getOrDefault(id, DEFAULT_UNK_TOKEN);
    }

    public int getPadTokenId() {
        return padTokenId;
    }

    public int getUnkTokenId() {
        return unkTokenId;
    }

    public int getClsTokenId() {
        return clsTokenId;
    }

    public int getSepTokenId() {
        return sepTokenId;
    }

    public int getMaskTokenId() {
        return maskTokenId;
    }
}
