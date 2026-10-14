package com.example.actionlens.privacy;

import com.example.actionlens.privacy.ml.OnnxPiiClassifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core privacy detection, sensitivity classification, and data masking engine.
 *
 * Enforces a strict privacy-first pipeline:
 * - Deterministic, rule-based detection for emails, phones, OTPs, credentials, and financial identifiers.
 * - In-place redaction/masking before any downstream processing or storage.
 * - Zero logging of sensitive values or raw input text.
 */
public class PrivacyEngine {

    private final OnnxPiiClassifier classifier;
    private final AiScreeningState screeningState;

    public PrivacyEngine() {
        this(null, AiScreeningState.READY);
    }

    public PrivacyEngine(OnnxPiiClassifier classifier) {
        this(classifier, classifier != null ? AiScreeningState.READY : AiScreeningState.READY);
    }

    public PrivacyEngine(OnnxPiiClassifier classifier, AiScreeningState screeningState) {
        this.classifier = classifier;
        this.screeningState = screeningState != null ? screeningState : AiScreeningState.READY;
    }

    public OnnxPiiClassifier getClassifier() {
        return classifier;
    }

    public AiScreeningState getScreeningState() {
        return screeningState;
    }

    // 1. Email Address Pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
            Pattern.CASE_INSENSITIVE
    );

    // 2. Phone Number Pattern (Handles international, dashed, parenthesized, and 10-digit formats; excludes dates)
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:(?:\\+\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}|\\b(?:\\+91[-.\\s]?)?[6-9]\\d{9}\\b)"
    );

    // Date exclusion pattern to avoid misclassifying dates (e.g. 2026-10-12) as phone numbers
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b|\\b\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4}\\b"
    );

    // 3. OTP & Verification Code Patterns
    private static final Pattern OTP_PATTERN = Pattern.compile(
            "(?i)\\b(?:otp|one[- ]time password|verification code|security code|auth(?:entication)? code|passcode|secret code)(?:\\s+(?:is|code))*\\s*[:=–-]?\\s*([A-Z0-9]{4,8})\\b|" +
            "(?i)\\b(?:use code|enter code)\\s+([0-9]{4,8})\\b"
    );

    // 4. Passwords, PINs & Credentials Patterns
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)\\b(?:password|passwd|pwd|passphrase|secret key|login pin|atm pin|pin code)(?:\\s+is)*\\s*[:=–-]?\\s*([^\\s,;]{3,32})\\b"
    );

    // 5. Financial Identifiers Patterns
    // 5a. Credit / Debit card patterns (13-19 digits, with spaces or hyphens)
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "\\b(?:\\d{4}[-\\s]\\d{4}[-\\s]\\d{4}[-\\s]\\d{4}|\\d{4}[-\\s]\\d{6}[-\\s]\\d{5}|\\d{13,19})\\b"
    );

    // 5b. CVV / CVC
    private static final Pattern CVV_PATTERN = Pattern.compile(
            "(?i)\\b(?:cvv|cvv2|cvc|security code)(?:\\s+is)*\\s*[:=–-]?\\s*(\\d{3,4})\\b"
    );

    // 5c. Bank Account Number
    private static final Pattern BANK_ACCOUNT_PATTERN = Pattern.compile(
            "(?i)\\b(?:ac(?:count)?\\s*(?:no|num|number)?|a/c\\s*(?:no|num|number)?|iban)(?:\\s+is)*\\s*[:=–-]?\\s*([A-Z0-9]{8,24})\\b"
    );

    // 5d. UPI ID Pattern
    private static final Pattern UPI_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9.\\-_]{2,64}@(okhdfcbank|okaxis|oksbi|paytm|ybl|apl|upi|axl|ibl|barodampay|kotak|icici|gpay|freecharge)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Evaluates text for sensitive content, classifies sensitivity, and generates
     * a sanitized version with all sensitive segments redacted.
     *
     * @param input Raw text to be processed
     * @return PrivacyAssessment containing sensitivity rating and masked text
     */
    public PrivacyAssessment assessAndMask(String input) {
        return assessAndMask(input, this.classifier);
    }

    public PrivacyAssessment assessAndMask(String input, OnnxPiiClassifier activeClassifier) {
        if (input == null || input.trim().isEmpty()) {
            return new PrivacyAssessment(
                    SensitivityLevel.LOW,
                    Collections.emptyList(),
                    "",
                    false,
                    "No content to evaluate.",
                    0
            );
        }

        List<PiiMatch> matches = new ArrayList<>();
        List<Boolean> failureFlag = new ArrayList<>();

        // 1. Detect Passwords & Credentials (HIGH)
        detectPasswords(input, matches);

        // 2. Detect OTPs & Verification Codes (HIGH)
        detectOtps(input, matches);

        // 3. Detect Financial Identifiers (HIGH)
        detectFinancial(input, matches);

        // 4. Detect Emails (MEDIUM)
        detectEmails(input, matches);

        // 5. Detect Phone Numbers (MEDIUM)
        detectPhoneNumbers(input, matches);

        // 6. Detect Model Spans (ML-based PII detection)
        if (activeClassifier != null && screeningState == AiScreeningState.READY) {
            detectModelSpans(input, activeClassifier, matches, failureFlag);
        }

        boolean inferenceFailed = !failureFlag.isEmpty();
        boolean screeningDegraded = inferenceFailed ||
                screeningState == AiScreeningState.FAILED ||
                screeningState == AiScreeningState.INITIALIZING;

        // Record all detected types prior to overlap resolution to preserve classification integrity
        EnumSet<PiiType> detectedTypes = EnumSet.noneOf(PiiType.class);
        for (PiiMatch m : matches) {
            detectedTypes.add(m.getType());
        }

        // Filter and merge overlapping matches so zero sensitive cleartext characters leak
        List<PiiMatch> resolvedMatches = resolveOverlaps(matches);

        // Build masked output
        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;

        for (PiiMatch match : resolvedMatches) {
            sb.append(input, lastIndex, match.getStartIndex());
            sb.append(match.getReplacementToken());
            lastIndex = match.getEndIndex();
        }
        sb.append(input.substring(lastIndex));

        String maskedContent = sb.toString();

        // Determine sensitivity level (fail-closed if inference failed or screening degraded)
        SensitivityLevel level = calculateSensitivity(detectedTypes, screeningDegraded);
        boolean requiresApproval = level != SensitivityLevel.LOW;
        String rationale = buildRationale(detectedTypes, level, screeningDegraded);

        return new PrivacyAssessment(
                level,
                new ArrayList<>(detectedTypes),
                maskedContent,
                requiresApproval,
                rationale,
                resolvedMatches.size()
        );
    }

    private void detectEmails(String input, List<PiiMatch> matches) {
        Matcher matcher = EMAIL_PATTERN.matcher(input);
        while (matcher.find()) {
            matches.add(new PiiMatch(
                    PiiType.EMAIL,
                    matcher.start(),
                    matcher.end(),
                    "[REDACTED: EMAIL]"
            ));
        }
    }

    private void detectPhoneNumbers(String input, List<PiiMatch> matches) {
        // Find date ranges to prevent false positive phone matches on dates
        List<int[]> dateRanges = new ArrayList<>();
        Matcher dateMatcher = DATE_PATTERN.matcher(input);
        while (dateMatcher.find()) {
            dateRanges.add(new int[]{dateMatcher.start(), dateMatcher.end()});
        }

        Matcher matcher = PHONE_PATTERN.matcher(input);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // Check if phone match overlaps with a date
            boolean overlapsDate = false;
            for (int[] range : dateRanges) {
                if (!(end <= range[0] || start >= range[1])) {
                    overlapsDate = true;
                    break;
                }
            }

            if (!overlapsDate) {
                matches.add(new PiiMatch(
                        PiiType.PHONE,
                        start,
                        end,
                        "[REDACTED: PHONE]"
                ));
            }
        }
    }

    private void detectOtps(String input, List<PiiMatch> matches) {
        Matcher matcher = OTP_PATTERN.matcher(input);
        while (matcher.find()) {
            int start = matcher.start(1) != -1 ? matcher.start(1) : matcher.start(2);
            int end = matcher.end(1) != -1 ? matcher.end(1) : matcher.end(2);
            if (start != -1 && end != -1) {
                matches.add(new PiiMatch(
                        PiiType.OTP,
                        start,
                        end,
                        "[REDACTED: OTP]"
                ));
            }
        }
    }

    private void detectPasswords(String input, List<PiiMatch> matches) {
        Matcher matcher = PASSWORD_PATTERN.matcher(input);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start != -1 && end != -1) {
                matches.add(new PiiMatch(
                        PiiType.PASSWORD,
                        start,
                        end,
                        "[REDACTED: PASSWORD]"
                ));
            }
        }
    }

    private void detectFinancial(String input, List<PiiMatch> matches) {
        // 1. Credit/Debit Card Numbers with Luhn check
        Matcher cardMatcher = CARD_PATTERN.matcher(input);
        while (cardMatcher.find()) {
            String digits = cardMatcher.group().replaceAll("[-\\s]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && isValidLuhn(digits)) {
                matches.add(new PiiMatch(
                        PiiType.FINANCIAL,
                        cardMatcher.start(),
                        cardMatcher.end(),
                        "[REDACTED: CARD]"
                ));
            }
        }

        // 2. CVV
        Matcher cvvMatcher = CVV_PATTERN.matcher(input);
        while (cvvMatcher.find()) {
            int start = cvvMatcher.start(1);
            int end = cvvMatcher.end(1);
            if (start != -1 && end != -1) {
                matches.add(new PiiMatch(
                        PiiType.FINANCIAL,
                        start,
                        end,
                        "[REDACTED: CVV]"
                ));
            }
        }

        // 3. Bank Account Numbers
        Matcher bankMatcher = BANK_ACCOUNT_PATTERN.matcher(input);
        while (bankMatcher.find()) {
            int start = bankMatcher.start(1);
            int end = bankMatcher.end(1);
            if (start != -1 && end != -1) {
                matches.add(new PiiMatch(
                        PiiType.FINANCIAL,
                        start,
                        end,
                        "[REDACTED: ACCOUNT]"
                ));
            }
        }

        // 4. UPI IDs
        Matcher upiMatcher = UPI_PATTERN.matcher(input);
        while (upiMatcher.find()) {
            matches.add(new PiiMatch(
                    PiiType.FINANCIAL,
                    upiMatcher.start(),
                    upiMatcher.end(),
                    "[REDACTED: UPI]"
            ));
        }
    }

    /**
     * Resolves overlapping match ranges by merging overlapping or touching spans.
     * Guarantees that no sensitive characters leak between overlapping matches
     * and that replacement tokens represent the highest priority sensitivity type.
     */
    private List<PiiMatch> resolveOverlaps(List<PiiMatch> matches) {
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        Collections.sort(matches);
        List<PiiMatch> resolved = new ArrayList<>();
        PiiMatch current = matches.get(0);

        for (int i = 1; i < matches.size(); i++) {
            PiiMatch next = matches.get(i);
            if (next.getStartIndex() < current.getEndIndex()) {
                // Overlapping or nested match: merge boundaries
                int mergedStart = Math.min(current.getStartIndex(), next.getStartIndex());
                int mergedEnd = Math.max(current.getEndIndex(), next.getEndIndex());
                PiiMatch winner;
                if (getPriority(next.getType()) > getPriority(current.getType())) {
                    winner = next;
                } else if (getPriority(next.getType()) < getPriority(current.getType())) {
                    winner = current;
                } else {
                    // Equal priority: prioritize the longer enclosing match
                    int currentLen = current.getEndIndex() - current.getStartIndex();
                    int nextLen = next.getEndIndex() - next.getStartIndex();
                    winner = nextLen > currentLen ? next : current;
                }
                current = new PiiMatch(winner.getType(), mergedStart, mergedEnd, winner.getReplacementToken());
            } else {
                resolved.add(current);
                current = next;
            }
        }
        resolved.add(current);
        return resolved;
    }

    private int getPriority(PiiType type) {
        switch (type) {
            case PASSWORD:
                return 7;
            case FINANCIAL:
                return 6;
            case OTP:
                return 5;
            case EMAIL:
                return 4;
            case PHONE:
                return 4;
            case IDENTIFIER:
                return 3;
            case PERSON:
                return 2;
            case LOCATION:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Policy check: verifies whether an assessment is eligible for automatic approval.
     * Returns true ONLY for LOW sensitivity items with zero required approvals.
     * Guarantees that HIGH and MEDIUM content can never be approved silently.
     */
    public static boolean canAutoApprove(PrivacyAssessment assessment) {
        if (assessment == null) {
            return false;
        }
        return !assessment.requiresUserApproval() && assessment.getSensitivityLevel() == SensitivityLevel.LOW;
    }

    private SensitivityLevel calculateSensitivity(EnumSet<PiiType> detected) {
        return calculateSensitivity(detected, false);
    }

    private SensitivityLevel calculateSensitivity(EnumSet<PiiType> detected, boolean inferenceFailed) {
        SensitivityLevel level = SensitivityLevel.LOW;
        if (detected.contains(PiiType.PASSWORD) ||
                detected.contains(PiiType.FINANCIAL) ||
                detected.contains(PiiType.OTP) ||
                detected.contains(PiiType.IDENTIFIER)) {
            level = SensitivityLevel.HIGH;
        } else if (detected.contains(PiiType.EMAIL) ||
                detected.contains(PiiType.PHONE) ||
                detected.contains(PiiType.PERSON) ||
                detected.contains(PiiType.LOCATION)) {
            level = SensitivityLevel.MEDIUM;
        }

        // Fail-closed guarantee: if model inference failed, elevate to at least MEDIUM
        if (inferenceFailed && level == SensitivityLevel.LOW) {
            level = SensitivityLevel.MEDIUM;
        }

        return level;
    }

    private String buildRationale(EnumSet<PiiType> detected, SensitivityLevel level) {
        return buildRationale(detected, level, false);
    }

    private String buildRationale(EnumSet<PiiType> detected, SensitivityLevel level, boolean screeningDegraded) {
        if (level == SensitivityLevel.LOW) {
            return "No sensitive personal details identified.";
        }
        StringBuilder sb = new StringBuilder();
        if (detected.isEmpty()) {
            if (screeningDegraded) {
                sb.append("AI screening model unready or classifier check failed; elevated to manual review for safety. ");
                sb.append(level.getPolicySummary());
                return sb.toString();
            }
        }
        sb.append("Detected ");
        List<String> names = new ArrayList<>();
        for (PiiType type : detected) {
            names.add(type.getDisplayName());
        }
        sb.append(String.join(", ", names));
        sb.append(". ");
        if (screeningDegraded) {
            sb.append("(Note: ML classifier unready or classifier check failed). ");
        }
        sb.append(level.getPolicySummary());
        return sb.toString();
    }

    private void detectModelSpans(String input, OnnxPiiClassifier classifier, List<PiiMatch> matches, List<Boolean> failureFlag) {
        if (classifier == null) {
            return;
        }
        try {
            List<OnnxPiiClassifier.TokenPrediction> predictions = classifier.classify(input);
            if (predictions != null && !predictions.isEmpty()) {
                List<PiiMatch> modelMatches = convertPredictionsToMatches(input, predictions);
                matches.addAll(modelMatches);
            }
        } catch (Exception e) {
            // Fail-closed: catch any exception, avoid crashing notification processing, and flag failure
            failureFlag.add(Boolean.TRUE);
        }
    }

    /**
     * Converts model token predictions into complete entity spans using original-text
     * character offsets. Safely merges consecutive B- and I- tokens into contiguous spans,
     * handles malformed BIO sequences (orphan I- tags, type switches), and filters out
     * O, DATE_TIME (preserved for task extraction), and invalid/sentinel offsets.
     */
    public static List<PiiMatch> convertPredictionsToMatches(String input, List<OnnxPiiClassifier.TokenPrediction> predictions) {
        if (input == null || input.isEmpty() || predictions == null || predictions.isEmpty()) {
            return Collections.emptyList();
        }

        List<PiiMatch> matches = new ArrayList<>();
        int currentStart = -1;
        int currentEnd = -1;
        String currentEntityType = null;

        for (OnnxPiiClassifier.TokenPrediction pred : predictions) {
            if (pred == null) {
                continue;
            }

            int tStart = pred.getStartOffset();
            int tEnd = pred.getEndOffset();

            // Sentinel or invalid offsets validation
            if (tStart < 0 || tEnd <= tStart || tEnd > input.length()) {
                continue;
            }

            String entityType = pred.getEntityType();
            PiiType piiType = mapModelEntityToPiiType(entityType);

            // Ignore O, DATE_TIME, and unsupported entities
            if (piiType == null || pred.isDateTime()) {
                if (currentEntityType != null) {
                    addModelMatchIfValid(input, currentEntityType, currentStart, currentEnd, matches);
                    currentEntityType = null;
                    currentStart = -1;
                    currentEnd = -1;
                }
                continue;
            }

            if (currentEntityType != null) {
                // Continuation: must be I- prefix, same entity type, and start >= currentStart
                if (pred.isInside() && entityType.equalsIgnoreCase(currentEntityType) && tStart >= currentStart) {
                    currentEnd = Math.max(currentEnd, tEnd);
                } else {
                    // Start of a new entity (either B- tag or different entity type)
                    addModelMatchIfValid(input, currentEntityType, currentStart, currentEnd, matches);
                    currentEntityType = entityType;
                    currentStart = tStart;
                    currentEnd = tEnd;
                }
            } else {
                // Start a new entity span (handles B- tag as well as orphan I- tag safely)
                currentEntityType = entityType;
                currentStart = tStart;
                currentEnd = tEnd;
            }
        }

        // Flush remaining open span
        if (currentEntityType != null) {
            addModelMatchIfValid(input, currentEntityType, currentStart, currentEnd, matches);
        }

        return matches;
    }

    private static void addModelMatchIfValid(String input, String entityType, int start, int end, List<PiiMatch> matches) {
        if (start < 0 || end <= start || end > input.length()) {
            return;
        }
        PiiType piiType = mapModelEntityToPiiType(entityType);
        if (piiType != null) {
            matches.add(new PiiMatch(
                    piiType,
                    start,
                    end,
                    getReplacementToken(piiType, entityType)
            ));
        }
    }

    public static PiiType mapModelEntityToPiiType(String entityType) {
        if (entityType == null) return null;
        switch (entityType.toUpperCase(Locale.ROOT)) {
            case "EMAIL_ADDRESS":
                return PiiType.EMAIL;
            case "PHONE_NUMBER":
                return PiiType.PHONE;
            case "PASSWORD":
                return PiiType.PASSWORD;
            case "CREDIT_CARD":
            case "FINANCIAL":
            case "IBAN_CODE":
            case "US_BANK_NUMBER":
                return PiiType.FINANCIAL;
            case "PERSON":
                return PiiType.PERSON;
            case "LOCATION":
            case "COORDINATE":
                return PiiType.LOCATION;
            case "US_SSN":
            case "US_PASSPORT":
            case "US_DRIVER_LICENSE":
            case "US_ITIN":
            case "US_LICENSE_PLATE":
            case "IMEI":
            case "IP_ADDRESS":
            case "MAC_ADDRESS":
            case "NRP":
            case "ORGANIZATION":
            case "TITLE":
            case "URL":
            case "AGE":
                return PiiType.IDENTIFIER;
            case "DATE_TIME":
            case "O":
            default:
                return null;
        }
    }

    public static String getReplacementToken(PiiType type, String entityType) {
        if (type == null) return "[REDACTED]";
        switch (type) {
            case EMAIL:
                return "[REDACTED: EMAIL]";
            case PHONE:
                return "[REDACTED: PHONE]";
            case PASSWORD:
                return "[REDACTED: PASSWORD]";
            case FINANCIAL:
                if ("CREDIT_CARD".equalsIgnoreCase(entityType)) {
                    return "[REDACTED: CARD]";
                }
                return "[REDACTED: FINANCIAL]";
            case PERSON:
                return "[REDACTED: PERSON]";
            case LOCATION:
                return "[REDACTED: LOCATION]";
            case IDENTIFIER:
                return "[REDACTED: IDENTIFIER]";
            case OTP:
                return "[REDACTED: OTP]";
            default:
                return "[REDACTED]";
        }
    }

    /**
     * Standard Luhn checksum algorithm for credit/debit card validation.
     */
    private boolean isValidLuhn(String cardNumber) {
        int sum = 0;
        boolean alternate = false;
        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int n = cardNumber.charAt(i) - '0';
            if (n < 0 || n > 9) {
                return false;
            }
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}
