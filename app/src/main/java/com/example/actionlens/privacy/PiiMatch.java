package com.example.actionlens.privacy;

/**
 * Represents a detected segment of sensitive content within a text string.
 * Note: Adheres to data minimization by not retaining the raw sensitive substring.
 */
public class PiiMatch implements Comparable<PiiMatch> {

    private final PiiType type;
    private final int startIndex;
    private final int endIndex;
    private final String replacementToken;

    public PiiMatch(PiiType type, int startIndex, int endIndex, String replacementToken) {
        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalArgumentException("Invalid match boundaries: [" + startIndex + ", " + endIndex + "]");
        }
        this.type = type;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.replacementToken = replacementToken;
    }

    public PiiType getType() {
        return type;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public String getReplacementToken() {
        return replacementToken;
    }

    @Override
    public int compareTo(PiiMatch other) {
        int cmp = Integer.compare(this.startIndex, other.startIndex);
        if (cmp != 0) {
            return cmp;
        }
        // If start positions are identical, prioritize longer match
        return Integer.compare(other.endIndex, this.endIndex);
    }
}
