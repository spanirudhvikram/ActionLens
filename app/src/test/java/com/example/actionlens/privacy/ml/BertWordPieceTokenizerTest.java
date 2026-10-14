package com.example.actionlens.privacy.ml;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link BertWordPieceTokenizer}.
 *
 * Coverage goals:
 *  - Exact start/end character offsets for every token type
 *  - Greedy WordPiece splitting and ##-continuation offsets
 *  - [UNK] fallback (unknown word, word-too-long)
 *  - [CLS] / [SEP] sentinel offsets (-1, -1)
 *  - doLowerCase=true NFD-accent stripping and offset stability
 *  - doLowerCase=false case-preserving mode
 *  - CJK character splitting
 *  - Control character skipping inside words
 *  - null / empty / whitespace-only input
 *  - encode() helper
 *  - Token helper predicates: isSpecialToken(), hasValidOffsets()
 *  - fromReader() factory
 *  - Vocabulary lookup helpers
 *  - Multiple consecutive punctuation characters
 *
 * Limitations documented in each relevant test.
 */
public class BertWordPieceTokenizerTest {

    // -------------------------------------------------------------------------
    // Shared vocabulary helpers
    // -------------------------------------------------------------------------

    /**
     * Minimal vocabulary used by most tests.
     *
     * Index assignment mirrors real BERT: [PAD]=0, [UNK]=1, [CLS]=2, [SEP]=3, [MASK]=4.
     */
    private BertWordPieceTokenizer createTokenizer() {
        Map<String, Integer> vocab = new LinkedHashMap<>();
        String[] words = {
                "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",   // 0-4
                "hello", "world", "!", ",", ".",                  // 5-9
                "play", "##ing", "cafe", "e",                     // 10-13  (note: "e" for accent test)
                "submit", "report", "123",                        // 14-16
                "a", "b", "c"                                     // 17-19
        };
        for (int i = 0; i < words.length; i++) {
            vocab.put(words[i], i);
        }
        return new BertWordPieceTokenizer(vocab, true);
    }

    /** Vocabulary where [UNK]=100, [CLS]=101, [SEP]=102 (standard BERT defaults). */
    private BertWordPieceTokenizer createBertDefaultsTokenizer() {
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("[PAD]",  0);
        vocab.put("[UNK]",  100);
        vocab.put("[CLS]",  101);
        vocab.put("[SEP]",  102);
        vocab.put("[MASK]", 103);
        vocab.put("hello",  1000);
        return new BertWordPieceTokenizer(vocab, true);
    }

    // =========================================================================
    // 1. Basic word + punctuation tokenization
    // =========================================================================

    @Test
    public void tokenizesSimpleWords() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello world");

        assertEquals(2, tokens.size());
        assertToken(tokens.get(0), "hello", 5, 0, 5);
        assertToken(tokens.get(1), "world", 6, 6, 11);
    }

    @Test
    public void tokenizesSimpleWordsAndPunctuation() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello world!");

        assertEquals(3, tokens.size());
        assertToken(tokens.get(0), "hello", 5,  0,  5);
        assertToken(tokens.get(1), "world", 6,  6, 11);
        assertToken(tokens.get(2), "!",     7, 11, 12);
    }

    @Test
    public void offsetsMustMapToOriginalString() {
        // Invariant: original.substring(token.start, token.end).equalsIgnoreCase(surface)
        BertWordPieceTokenizer tok = createTokenizer();
        String input = "hello world!";
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize(input);

        for (BertWordPieceTokenizer.Token t : tokens) {
            assertTrue("hasValidOffsets failed for " + t, t.hasValidOffsets());
            String slice = input.substring(t.getStartOffset(), t.getEndOffset());
            // The tokenizer lower-cases, so compare case-insensitively
            assertEquals(
                    "Offset slice mismatch for " + t,
                    t.getText().replace("##", "").toLowerCase(),
                    slice.toLowerCase()
            );
        }
    }

    @Test
    public void punctuationSplitFromWordBothSides() {
        BertWordPieceTokenizer tok = createTokenizer();
        // "hello,world" → "hello" "," "world"
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello,world");

        assertEquals(3, tokens.size());
        assertToken(tokens.get(0), "hello",  5, 0, 5);
        assertToken(tokens.get(1), ",",      8, 5, 6);
        assertToken(tokens.get(2), "world",  6, 6, 11);
    }

    @Test
    public void multipleConsecutivePunctuation() {
        BertWordPieceTokenizer tok = createTokenizer();
        // "!!" → two separate "!" tokens, each with correct offsets
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("!!");

        assertEquals(2, tokens.size());
        assertToken(tokens.get(0), "!", 7, 0, 1);
        assertToken(tokens.get(1), "!", 7, 1, 2);
    }

    // =========================================================================
    // 2. WordPiece greedy splitting with ##-continuation offsets
    // =========================================================================

    @Test
    public void tokenizesWordPieceContinuation() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("playing");

        assertEquals(2, tokens.size());
        assertToken(tokens.get(0), "play",  10, 0, 4);
        assertToken(tokens.get(1), "##ing", 11, 4, 7);
    }

    @Test
    public void wordPieceContinuationOffsetsMidSentence() {
        BertWordPieceTokenizer tok = createTokenizer();
        // "hello playing" – "playing" starts at offset 6
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello playing");

        assertEquals(3, tokens.size());
        assertToken(tokens.get(0), "hello",  5, 0,  5);
        assertToken(tokens.get(1), "play",  10, 6, 10);
        assertToken(tokens.get(2), "##ing", 11, 10, 13);
    }

    @Test
    public void wordPieceContinuationOffsetsMappedToOriginal() {
        BertWordPieceTokenizer tok = createTokenizer();
        String input = "playing";
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize(input);

        // "play" → input[0..4] == "play"
        assertEquals("play", input.substring(tokens.get(0).getStartOffset(),
                                              tokens.get(0).getEndOffset()));
        // "##ing" → input[4..7] == "ing"
        assertEquals("ing", input.substring(tokens.get(1).getStartOffset(),
                                             tokens.get(1).getEndOffset()));
    }

    // =========================================================================
    // 3. Mixed case → doLowerCase normalisation
    // =========================================================================

    @Test
    public void handlesMixedCase() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("HELLO");

        assertEquals(1, tokens.size());
        assertToken(tokens.get(0), "hello", 5, 0, 5);
    }

    @Test
    public void mixedCaseWordPiecePreservesOffsets() {
        BertWordPieceTokenizer tok = createTokenizer();
        // "PLAYING" lowercased → "playing" → play + ##ing
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("PLAYING");

        assertEquals(2, tokens.size());
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(4, tokens.get(0).getEndOffset());
        assertEquals(4, tokens.get(1).getStartOffset());
        assertEquals(7, tokens.get(1).getEndOffset());
    }

    @Test
    public void doLowerCaseFalsePreservesCase() {
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("[PAD]",  0);
        vocab.put("[UNK]",  1);
        vocab.put("[CLS]",  2);
        vocab.put("[SEP]",  3);
        vocab.put("[MASK]", 4);
        vocab.put("HELLO",  5);  // upper-case entry
        BertWordPieceTokenizer tok = new BertWordPieceTokenizer(vocab, false);

        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("HELLO");
        assertEquals(1, tokens.size());
        assertEquals("HELLO", tokens.get(0).getText());
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(5, tokens.get(0).getEndOffset());
    }

    @Test
    public void doLowerCaseFalse_unknownWhenCaseMismatch() {
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("[PAD]",  0);
        vocab.put("[UNK]",  1);
        vocab.put("[CLS]",  2);
        vocab.put("[SEP]",  3);
        vocab.put("[MASK]", 4);
        vocab.put("hello",  5);   // only lower-case in vocab
        BertWordPieceTokenizer tok = new BertWordPieceTokenizer(vocab, false);

        // "HELLO" with doLowerCase=false should not match "hello" → [UNK]
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("HELLO");
        assertEquals(1, tokens.size());
        assertEquals("[UNK]", tokens.get(0).getText());
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(5, tokens.get(0).getEndOffset());
    }

    // =========================================================================
    // 4. Accent / diacritic stripping (NFD normalization)
    //    LIMITATION NOTE: This tokenizer strips combining diacritical marks during
    //    doLowerCase NFD normalization (matching original BERT behaviour for
    //    bert-base-uncased). With a cased/multilingual model, set doLowerCase=false.
    //    The offset still refers to the original accented character span.
    // =========================================================================

    @Test
    public void accentStrippingRetainsOriginalOffsets() {
        // Vocab contains "e" (the base letter after stripping é's accent)
        BertWordPieceTokenizer tok = createTokenizer();
        // "é" is U+00E9, one Java char; after NFD → "e" + combining accent (stripped) → "e"
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("é");

        assertEquals(1, tokens.size());
        assertEquals("e", tokens.get(0).getText());
        // Offset must point to the original "é" (char at index 0, length 1)
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(1, tokens.get(0).getEndOffset());
    }

    @Test
    public void accentStrippingInMidWordRetainsOffsets() {
        // café → c a f e  (accent on 'é' stripped to 'e')
        // Our vocab has "cafe" so all four letters form one token.
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("café");

        assertEquals(1, tokens.size());
        assertEquals("cafe", tokens.get(0).getText());
        assertEquals(0, tokens.get(0).getStartOffset());
        // "café" in Java: 'c','a','f','é' → length 4
        assertEquals(4, tokens.get(0).getEndOffset());
    }

    // =========================================================================
    // 5. [UNK] fallback
    // =========================================================================

    @Test
    public void handlesUnknownWords() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("xyzunknown");

        assertEquals(1, tokens.size());
        assertToken(tokens.get(0), "[UNK]", 1, 0, 10);
    }

    @Test
    public void unkOffsetSpansEntireUnknownWord() {
        BertWordPieceTokenizer tok = createTokenizer();
        // Two words: "hello" (known) followed by "xyz" (unknown)
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello xyz");

        assertEquals(2, tokens.size());
        assertToken(tokens.get(0), "hello", 5,  0, 5);
        assertToken(tokens.get(1), "[UNK]", 1, 6, 9);
    }

    @Test
    public void unkForWordExceedingMaxCharsPerWord() {
        // Build a tokenizer with maxCharsPerWord=5
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("[PAD]",  0);
        vocab.put("[UNK]",  1);
        vocab.put("[CLS]",  2);
        vocab.put("[SEP]",  3);
        vocab.put("[MASK]", 4);
        vocab.put("hello",  5);
        BertWordPieceTokenizer tok = new BertWordPieceTokenizer(vocab, true, 5);

        // "toolong" has 7 chars > maxCharsPerWord=5 → [UNK]
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("toolong");
        assertEquals(1, tokens.size());
        assertEquals("[UNK]", tokens.get(0).getText());
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(7, tokens.get(0).getEndOffset());

        // "hello" has 5 chars == maxCharsPerWord=5 → still tokenizes normally
        tokens = tok.tokenize("hello");
        assertEquals(1, tokens.size());
        assertEquals("hello", tokens.get(0).getText());
    }

    // =========================================================================
    // 6. [CLS] / [SEP] sentinel offsets
    // =========================================================================

    @Test
    public void specialTokensUseSentinelOffsets() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello", true);

        // [CLS] is first
        BertWordPieceTokenizer.Token cls = tokens.get(0);
        assertEquals("[CLS]", cls.getText());
        assertEquals(-1, cls.getStartOffset());
        assertEquals(-1, cls.getEndOffset());
        assertTrue(cls.isSpecialToken());
        assertFalse(cls.hasValidOffsets());

        // [SEP] is last
        BertWordPieceTokenizer.Token sep = tokens.get(tokens.size() - 1);
        assertEquals("[SEP]", sep.getText());
        assertEquals(-1, sep.getStartOffset());
        assertEquals(-1, sep.getEndOffset());
        assertTrue(sep.isSpecialToken());
        assertFalse(sep.hasValidOffsets());
    }

    @Test
    public void withoutSpecialTokensNoSentinelOffsets() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello", false);

        assertEquals(1, tokens.size());
        assertFalse(tokens.get(0).isSpecialToken());
        assertTrue(tokens.get(0).hasValidOffsets());
    }

    @Test
    public void clsSepHaveCorrectIds() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello", true);

        // In createTokenizer(): [CLS]=2, [SEP]=3
        assertEquals(2, tokens.get(0).getId());   // [CLS]
        assertEquals(3, tokens.get(tokens.size() - 1).getId());  // [SEP]
    }

    @Test
    public void bertDefaultSpecialTokenIds() {
        // When [PAD]=0, [UNK]=100, [CLS]=101, [SEP]=102 are in vocab, IDs must match
        BertWordPieceTokenizer tok = createBertDefaultsTokenizer();
        assertEquals(100, tok.getUnkTokenId());
        assertEquals(101, tok.getClsTokenId());
        assertEquals(102, tok.getSepTokenId());
    }

    // =========================================================================
    // 7. CJK character splitting
    // =========================================================================

    @Test
    public void cjkCharactersAreTokenizedIndividually() {
        // Each CJK char is treated as its own word
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("[PAD]",  0);
        vocab.put("[UNK]",  1);
        vocab.put("[CLS]",  2);
        vocab.put("[SEP]",  3);
        vocab.put("[MASK]", 4);
        vocab.put("\u4e2d",  5);  // 中
        vocab.put("\u6587",  6);  // 文
        BertWordPieceTokenizer tok = new BertWordPieceTokenizer(vocab, false);

        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("中文");
        assertEquals(2, tokens.size());
        assertToken(tokens.get(0), "中", 5, 0, 1);
        assertToken(tokens.get(1), "文", 6, 1, 2);
    }

    // =========================================================================
    // 8. Control character skipping inside words
    // =========================================================================

    @Test
    public void controlCharactersSkippedInsideWord() {
        // U+0001 is a control character; it should be skipped, so "a\u0001b" → "ab"
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocab.put("[PAD]",  0);
        vocab.put("[UNK]",  1);
        vocab.put("[CLS]",  2);
        vocab.put("[SEP]",  3);
        vocab.put("[MASK]", 4);
        vocab.put("ab",     5);
        BertWordPieceTokenizer tok = new BertWordPieceTokenizer(vocab, true);

        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("a\u0001b");
        // Control char is stripped from the normalized text, so "ab" should be in vocab
        assertEquals(1, tokens.size());
        assertEquals("ab", tokens.get(0).getText());
        // The whole span [0, 3) covers the original "a\u0001b"
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(3, tokens.get(0).getEndOffset());
    }

    // =========================================================================
    // 9. null / empty / whitespace-only input
    // =========================================================================

    @Test
    public void handlesNullInput() {
        BertWordPieceTokenizer tok = createTokenizer();
        // null is treated as empty – must not throw
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize(null);
        assertTrue(tokens.isEmpty());
    }

    @Test
    public void handlesNullInputWithSpecialTokens() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize(null, true);
        // Only [CLS] and [SEP] should be present
        assertEquals(2, tokens.size());
        assertEquals("[CLS]", tokens.get(0).getText());
        assertEquals("[SEP]", tokens.get(1).getText());
    }

    @Test
    public void handlesEmptyInput() {
        BertWordPieceTokenizer tok = createTokenizer();
        assertTrue(tok.tokenize("").isEmpty());
        assertTrue(tok.tokenize("   ").isEmpty());
        assertTrue(tok.tokenize("\t\n\r").isEmpty());
    }

    // =========================================================================
    // 10. encode() convenience method
    // =========================================================================

    @Test
    public void encodeReturnsTrueIds() {
        BertWordPieceTokenizer tok = createTokenizer();
        // "hello" → id=5
        int[] ids = tok.encode("hello", false);
        assertArrayEquals(new int[]{5}, ids);
    }

    @Test
    public void encodeWithSpecialTokensIncludesClsSep() {
        BertWordPieceTokenizer tok = createTokenizer();
        // [CLS]=2  "hello"=5  [SEP]=3
        int[] ids = tok.encode("hello", true);
        assertArrayEquals(new int[]{2, 5, 3}, ids);
    }

    @Test
    public void encodeEmptyReturnsEmptyArray() {
        BertWordPieceTokenizer tok = createTokenizer();
        int[] ids = tok.encode("", false);
        assertEquals(0, ids.length);
    }

    // =========================================================================
    // 11. Token helper predicates
    // =========================================================================

    @Test
    public void hasValidOffsets_trueForNormalTokens() {
        BertWordPieceTokenizer.Token t = new BertWordPieceTokenizer.Token("hello", 5, 0, 5);
        assertTrue(t.hasValidOffsets());
        assertFalse(t.isSpecialToken());
    }

    @Test
    public void hasValidOffsets_falseForSentinelOffsets() {
        BertWordPieceTokenizer.Token t = new BertWordPieceTokenizer.Token("[CLS]", 2, -1, -1);
        assertFalse(t.hasValidOffsets());
        assertTrue(t.isSpecialToken());
    }

    @Test
    public void tokenEquality() {
        BertWordPieceTokenizer.Token t1 = new BertWordPieceTokenizer.Token("hello", 5, 0, 5);
        BertWordPieceTokenizer.Token t2 = new BertWordPieceTokenizer.Token("hello", 5, 0, 5);
        BertWordPieceTokenizer.Token t3 = new BertWordPieceTokenizer.Token("hello", 5, 1, 5);
        assertEquals(t1, t2);
        assertNotEquals(t1, t3);
    }

    @Test
    public void tokenToStringDoesNotThrow() {
        BertWordPieceTokenizer.Token t = new BertWordPieceTokenizer.Token("hello", 5, 0, 5);
        assertNotNull(t.toString());
        assertTrue(t.toString().contains("hello"));
    }

    // =========================================================================
    // 12. fromReader() factory
    // =========================================================================

    @Test
    public void fromReaderLoadsVocabByLineIndex() throws IOException {
        // Line 0 → [PAD], line 1 → [UNK], ... exactly like a real vocab.txt
        String vocabTxt = "[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\nhello\nworld\n";
        BertWordPieceTokenizer tok =
                BertWordPieceTokenizer.fromReader(new StringReader(vocabTxt), true);

        assertEquals(7, tok.getVocabSize());
        assertEquals(5, tok.getTokenId("hello"));
        assertEquals(6, tok.getTokenId("world"));
        // Special token IDs must come from vocab content
        assertEquals(2, tok.getClsTokenId());
        assertEquals(3, tok.getSepTokenId());
    }

    @Test
    public void fromReaderHandlesEmptyLines() throws IOException {
        // Empty lines should still advance the ID counter
        String vocabTxt = "[PAD]\n\n[UNK]\nhello\n";
        BertWordPieceTokenizer tok =
                BertWordPieceTokenizer.fromReader(new StringReader(vocabTxt), true);

        // [PAD]=0, empty line advances to 1, [UNK]=2, hello=3
        // Verify vocab size: 3 non-empty tokens
        assertEquals(3, tok.getVocabSize());
        assertEquals(3, tok.getTokenId("hello"));
    }

    // =========================================================================
    // 13. Vocabulary lookup helpers
    // =========================================================================

    @Test
    public void containsToken() {
        BertWordPieceTokenizer tok = createTokenizer();
        assertTrue(tok.containsToken("hello"));
        assertFalse(tok.containsToken("xyz_not_in_vocab"));
    }

    @Test
    public void getTokenId_returnsUnkForMissingToken() {
        BertWordPieceTokenizer tok = createTokenizer();
        // [UNK]=1 in createTokenizer()
        assertEquals(1, tok.getTokenId("not_in_vocab"));
    }

    @Test
    public void getTokenString_roundTrip() {
        BertWordPieceTokenizer tok = createTokenizer();
        int id = tok.getTokenId("hello");
        assertEquals("hello", tok.getTokenString(id));
    }

    @Test
    public void getTokenString_returnsUnkForUnknownId() {
        BertWordPieceTokenizer tok = createTokenizer();
        // 9999 is not in the vocab
        assertEquals("[UNK]", tok.getTokenString(9999));
    }

    @Test
    public void specialTokenIdHelpers() {
        BertWordPieceTokenizer tok = createTokenizer();
        // From createTokenizer(): [PAD]=0, [UNK]=1, [CLS]=2, [SEP]=3, [MASK]=4
        assertEquals(0, tok.getPadTokenId());
        assertEquals(1, tok.getUnkTokenId());
        assertEquals(2, tok.getClsTokenId());
        assertEquals(3, tok.getSepTokenId());
        assertEquals(4, tok.getMaskTokenId());
    }

    @Test
    public void nullVocabularyThrows() {
        try {
            new BertWordPieceTokenizer(null);
            fail("Expected IllegalArgumentException for null vocabulary");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // =========================================================================
    // 14. Full sentence offset integrity sweep
    //    Verifies that every content token's [start, end) maps to the correct
    //    slice of the original string (case-insensitively after ##-stripping).
    // =========================================================================

    @Test
    public void fullSentenceOffsetIntegrity() {
        BertWordPieceTokenizer tok = createTokenizer();
        String input = "Hello, playing world!";
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize(input, true);

        for (BertWordPieceTokenizer.Token t : tokens) {
            if (t.isSpecialToken()) continue;
            assertTrue("hasValidOffsets failed for: " + t, t.hasValidOffsets());
            String surface = t.getText().replace("##", "");
            String slice = input.substring(t.getStartOffset(), t.getEndOffset()).toLowerCase();
            assertEquals("Offset mismatch for " + t, surface.toLowerCase(), slice);
        }
    }

    @Test
    public void leadingWhitespaceOffsets() {
        BertWordPieceTokenizer tok = createTokenizer();
        // Leading spaces shift word start offset
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("   hello");
        assertEquals(1, tokens.size());
        assertEquals(3, tokens.get(0).getStartOffset());
        assertEquals(8, tokens.get(0).getEndOffset());
    }

    @Test
    public void trailingWhitespaceIgnored() {
        BertWordPieceTokenizer tok = createTokenizer();
        List<BertWordPieceTokenizer.Token> tokens = tok.tokenize("hello   ");
        assertEquals(1, tokens.size());
        assertEquals(0, tokens.get(0).getStartOffset());
        assertEquals(5, tokens.get(0).getEndOffset());
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static void assertToken(
            BertWordPieceTokenizer.Token token,
            String expectedText,
            int expectedId,
            int expectedStart,
            int expectedEnd) {
        assertEquals("text",  expectedText,  token.getText());
        assertEquals("id",    expectedId,    token.getId());
        assertEquals("start", expectedStart, token.getStartOffset());
        assertEquals("end",   expectedEnd,   token.getEndOffset());
    }
}
