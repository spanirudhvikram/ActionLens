package com.example.actionlens.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filter determining eligibility and relevance of incoming notifications for ActionLens.
 *
 * Implements a conservative three-way relevance decision:
 * - ACCEPT: Clear evidence of an actionable commitment, assignment, payment, meeting, or appointment.
 * - REJECT: Clearly non-actionable content (casual chat, social engagement, weather, ads, file paths, corrupted text).
 * - UNCERTAIN: Possible task with insufficient evidence; discarded safely without persisting raw text.
 *
 * Invariants:
 * 1. Preserves notification context: combines title subject with body predicate when split.
 * 2. Does not pollute task titles with chat sender names, group names, or app labels.
 * 3. Never discriminates against unfamiliar packages, personal names, or Tamil/multilingual text.
 * 4. Zero retention of rejected or uncertain raw message bodies.
 */
public final class NotificationFilter {

    public static final String REASON_ACCEPTED_ACTIONABLE = "ACCEPTED_ACTIONABLE";
    public static final String REASON_REJECTED_SYSTEM_PACKAGE = "REJECTED_SYSTEM_PACKAGE";
    public static final String REASON_REJECTED_ONGOING = "REJECTED_ONGOING";
    public static final String REASON_REJECTED_GROUP_SUMMARY = "REJECTED_GROUP_SUMMARY";
    public static final String REASON_REJECTED_SELF_PACKAGE = "REJECTED_SELF_PACKAGE";
    public static final String REASON_REJECTED_EMPTY_OR_CORRUPT = "REJECTED_EMPTY_OR_CORRUPT";
    public static final String REASON_REJECTED_FILE_PATH = "REJECTED_FILE_PATH";
    public static final String REASON_REJECTED_REDACTION_PLACEHOLDER = "REJECTED_REDACTION_PLACEHOLDER";
    public static final String REASON_REJECTED_CASUAL_CHAT = "REJECTED_CASUAL_CHAT";
    public static final String REASON_REJECTED_SOCIAL_ENGAGEMENT = "REJECTED_SOCIAL_ENGAGEMENT";
    public static final String REASON_REJECTED_WEATHER_UPDATE = "REJECTED_WEATHER_UPDATE";
    public static final String REASON_REJECTED_PROMOTIONAL_OR_AD = "REJECTED_PROMOTIONAL_OR_AD";
    public static final String REASON_FILTERED_UNCERTAIN_RELEVANCE = "FILTERED_UNCERTAIN_RELEVANCE";

    public enum RelevanceDecision {
        ACCEPT,
        REJECT,
        UNCERTAIN
    }

    public static final class FilterResult {
        private final RelevanceDecision decision;
        private final String reasonCode;

        public FilterResult(RelevanceDecision decision, String reasonCode) {
            this.decision = decision;
            this.reasonCode = reasonCode;
        }

        public RelevanceDecision getDecision() {
            return decision;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public boolean isAccepted() {
            return decision == RelevanceDecision.ACCEPT;
        }

        public boolean isRejected() {
            return decision == RelevanceDecision.REJECT;
        }

        public boolean isUncertain() {
            return decision == RelevanceDecision.UNCERTAIN;
        }
    }

    private static final Set<String> IGNORED_SYSTEM_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.providers.downloads",
            "com.google.android.gms"
    ));

    // 1. Casual Chat / Conversational Chatter
    private static final Pattern CASUAL_CHAT_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:" +
            // Acknowledgment / Agreement / Greetings
            "the\\s+gang\\s+is\\s+ready|" +
            "okay?,?(\\s+thanks)?|ok|k|kk|cool|nice|sure(\\s+thing)?|thanks(!)?|thank\\s+you|" +
            "sounds\\s+good|no\\s+problem|haha+|lol+|lmao|great|awesome|done\\s+deal|fine|alright|yes|no|yep|nope|got\\s+it|understood|" +
            // Status & Departures
            "i['’]?m\\s+on\\s+my\\s+way|on\\s+my\\s+way|omw|see\\s+you\\s+(?:soon|there|tomorrow)|talk\\s+later|call\\s+you\\s+later|" +
            "good\\s+(?:morning|night|afternoon|evening)|happy\\s+birthday|congratulations|congrats|take\\s+care|" +
            // Casual Questions
            "how\\s+are\\s+you\\??|what['’]?s\\s+up\\??|what\\s+are\\s+you\\s+doing\\??|are\\s+you\\s+free\\??|where\\s+are\\s+you\\??|did\\s+you\\s+(?:eat|sleep|reach)\\??" +
            ")\\s*[.!]?\\s*$"
    );

    // 2. Social Media Engagement
    private static final Pattern SOCIAL_MEDIA_PATTERN = Pattern.compile(
            "(?i)\\b(?:" +
            "sent\\s+(?:you\\s+)?(?:a\\s+)?(?:reel|photo|video|voice\\s+message|snap|post|tiktok|short|attachment)|" +
            "liked\\s+(?:your\\s+)?(?:story|post|photo|message|reel|comment)|" +
            "reacted\\s+(?:to\\s+your|with)|" +
            "commented\\s+on\\s+your|" +
            "mentioned\\s+you\\s+in|" +
            "tagged\\s+you\\s+in|" +
            "started\\s+following\\s+you|" +
            "new\\s+subscriber|" +
            "viewed\\s+your\\s+profile" +
            ")\\b"
    );

    // 3. Weather Updates
    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            "(?i)\\b(?:" +
            "cloudy\\.?\\s+see\\s+full\\s+forecast|" +
            "weather\\s+forecast|" +
            "thunderstorm(?:s)?\\s+(?:warning|expected|alert)|" +
            "sunny\\s+with\\s+a\\s+high|" +
            "rain\\s+(?:expected|alert|warning)|" +
            "air\\s+quality\\s+index|" +
            "aqi\\s+is\\s+\\d+|" +
            "temperature\\s+is\\s+\\d+|" +
            "currently\\s+\\d+°[cf]?" +
            ")\\b"
    );

    // 4. Advertisements & Promotional Alerts
    private static final Pattern PROMO_PATTERN = Pattern.compile(
            "(?i)\\b(?:" +
            "flat\\s+\\d+%(?:\\s+off)?|" +
            "up\\s+to\\s+\\d+%(?:\\s+off)?|" +
            "\\d+%(?:\\s+off|\\s+discount)|" +
            "use\\s+code\\s+[a-z0-9]+\\s+for|" +
            "buy\\s+1\\s+get\\s+1|" +
            "sale\\s+ends\\s+tonight|" +
            "mega\\s+sale|" +
            "flash\\s+sale|" +
            "exclusive\\s+(?:deal|offer)|" +
            "limited\\s+time\\s+offer|" +
            "cashback\\s+credited|" +
            "earn\\s+[₹$€£]\\d+|" +
            "claim\\s+your\\s+reward|" +
            "order\\s+now\\s+and\\s+get\\s+free\\s+delivery|" +
            "special\\s+discount|" +
            "coupon\\s+code" +
            ")\\b"
    );

    // 5. Raw File Paths
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "^(?:[A-Za-z]:\\\\|\\\\\\\\|/(?:[a-zA-Z0-9._-]+/)+)[a-zA-Z0-9._-]+\\.[a-zA-Z0-9]{2,4}$"
    );

    // 6. Redaction Placeholders Only
    private static final Pattern REDACTION_PLACEHOLDER_ONLY_PATTERN = Pattern.compile(
            "^(?:\\[REDACTED(?::\\s*[A-Z_]+)?\\]\\s*[:–-]?\\s*)+$"
    );

    // 7. Corrupt or Punctuation-Only Text
    private static final Pattern CORRUPT_OR_EMPTY_PATTERN = Pattern.compile(
            "^[\\p{Punct}\\s\\p{Sm}\\p{So}\ufffd]+$"
    );

    // 8. Actionable: Submissions, Assignments, Homework, Reports
    private static final Pattern ACTIONABLE_SUBMISSION_PATTERN = Pattern.compile(
            "(?i)\\b(?:submit|upload|turn\\s+in|hand\\s+in|finish|complete|review|read)\\b.*?" +
            "\\b(?:assignment(?:s)?|homework|lab\\s+report(?:s)?|report(?:s)?|essay(?:s)?|project(?:s)?|thesis|paper(?:s)?|draft(?:s)?|synopsis|record\\s+notebook(?:s)?|worksheet(?:s)?|code|repository|pr|document(?:s)?|slides|timeline|guidelines|timesheet(?:s)?|summary|chapter(?:s)?|page(?:s)?|notes|task(?:s)?|audit(?:s)?)\\b|" +
            "(?i)\\b(?:assignment(?:s)?|lab\\s+report(?:s)?|project\\s+report(?:s)?|homework|timesheet(?:s)?|lab|task(?:s)?|audit(?:s)?)\\b.*?" +
            "\\b(?:due|deadline|by|before)\\b|" +
            "(?i)\\b(?:assignment(?:s)?|homework|lab\\s+report(?:s)?|report(?:s)?|essay(?:s)?|project(?:s)?|thesis|paper(?:s)?|record\\s+notebook(?:s)?|worksheet(?:s)?|code|draft(?:s)?|task(?:s)?|audit(?:s)?)\\b.*?" +
            "\\b(?:submit|upload|turn\\s+in|hand\\s+in|finish|complete|review)\\b"
    );

    // 9. Actionable: Payments, Fees, Bills, Invoices, Renewals
    private static final Pattern ACTIONABLE_PAYMENT_PATTERN = Pattern.compile(
            "(?i)\\b(?:pay|renew|recharge|settle|clear|transfer|confirm|verify|authorize)\\b.*?" +
            "\\b(?:fee|fees|bill|bills|dues|rent|invoice(?:s)?|tuition|premium|subscription(?:s)?|recharge|balance|amount|payment(?:s)?|transfer(?:s)?|transaction(?:s)?|account(?:s)?|a/c|card(?:s)?|funds|money)\\b|" +
            "(?i)\\b(?:examination\\s+fee|exam\\s+fee|tuition\\s+fee|electricity\\s+bill|rent\\s+payment|maintenance\\s+bill|bank\\s+transfer|transfer\\s+funds)\\b.*?" +
            "\\b(?:due|deadline|before|by|payable|required)\\b|" +
            "(?i)\\b(?:fee|fees|bill|bills|dues|rent|invoice(?:s)?|tuition|recharge|payment(?:s)?|funds)\\b.*?" +
            "\\b(?:pay|renew|recharge|settle|clear|transfer)\\b"
    );

    // 10. Actionable: Meetings, Appointments, Conferences, Attendance
    private static final Pattern ACTIONABLE_MEETING_PATTERN = Pattern.compile(
            "(?i)\\b(?:attend|join|schedule|meet)\\b.*?" +
            "\\b(?:meeting(?:s)?|appointment(?:s)?|interview(?:s)?|sync(?:s)?|standup(?:s)?|call(?:s)?|conference(?:s)?|seminar(?:s)?|webinar(?:s)?|session(?:s)?|lecture(?:s)?|class(?:es)?|hearing(?:s)?|consultation(?:s)?|orientation)\\b|" +
            "(?i)\\b(?:project\\s+review\\s+meeting|team\\s+meeting|sync\\s+meeting|doctor\\s+appointment|dentist\\s+appointment|job\\s+interview|client\\s+debrief|department\\s+orientation|weekly\\s+sync|annual\\s+general\\s+meeting|agm)\\b.*?" +
            "\\b(?:at|in|on|by|via)\\b|" +
            "(?i)\\b(?:meeting|appointment)\\b.*?" +
            "\\b(?:in\\s+room|at\\s+\\d+|tomorrow|today|next\\s+\\w+)\\b"
    );

    // 11. Actionable: Registration, Applications, Forms
    private static final Pattern ACTIONABLE_REGISTRATION_PATTERN = Pattern.compile(
            "(?i)\\b(?:register|apply|enroll|sign\\s+up|fill\\s+out)\\b.*?" +
            "\\b(?:workshop|conference|course|exam|test|placement|admissions|scholarship|form|survey|feedback)\\b"
    );

    // 12. Actionable: Physical items: Bring, Collect, Return
    private static final Pattern ACTIONABLE_BRING_COLLECT_PATTERN = Pattern.compile(
            "(?i)\\b(?:bring|collect|return|pick\\s+up)\\b.*?" +
            "\\b(?:record\\s+notebook|notebook|documents|certificate|passport|id\\s+card|badge|keys|books|hall\\s+ticket|laptop|equipment)\\b"
    );

    // 13. Actionable: Imperative instruction with explicit date/time
    private static final Pattern ACTIONABLE_IMPERATIVE_PATTERN = Pattern.compile(
            "(?i)^(?:please\\s+|kindly\\s+|urgent\\s*[:–-]|reminder\\s*[:–-]|action\\s+required\\s*[:–-]|call\\s+|contact\\s+|reach\\s+out\\s+to\\s+|don't\\s+forget\\s+to\\s+|make\\s+sure\\s+to\\s+|remember\\s+to\\s+)" +
            ".*?\\b(?:by|before|on|at)\\s+(?:tomorrow|today|tonight|next\\s+\\w+|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|\\d{1,2}\\s+[ap]m|\\d{1,2}:\\d{2})"
    );

    // 14. Actionable: Tamil / Indic Action Signals
    private static final Pattern ACTIONABLE_TAMIL_PATTERN = Pattern.compile(
            "(?:சமர்ப்பி(?:க்கவும்|க்க|யுங்கள்)?|செலுத்த(?:வும்|ங்கள்)?|கட்ட(?:வும்|ங்கள்)?|கலந்துகொள்ள(?:வும்|ங்கள்)?|பதிவு\\s*செய்ய(?:வும்|ங்கள்)?|கடைசி\\s*(?:நாள்|தேதி)|முடிக்க(?:வும்|ங்கள்)?|கொண்டு\\s*(?:வரவும்|வாருங்கள்|வாங்க)|நினைவூட்டல்|அறிக்கை|கட்டணம்|தேர்வு|பண்ணுங்க|பண்ணுங்கள்)"
    );

    private NotificationFilter() {
        // Utility class
    }

    /**
     * Determines whether an incoming notification should be processed.
     */
    public static boolean isEligible(
            String packageName,
            String appPackageName,
            boolean isOngoing,
            boolean isGroupSummary,
            String title,
            String text) {
        return isEligible(packageName, appPackageName, isOngoing, isGroupSummary, title, text, null);
    }

    /**
     * Determines whether an incoming notification should be processed, inspecting
     * title, content text, and expanded big text.
     */
    public static boolean isEligible(
            String packageName,
            String appPackageName,
            boolean isOngoing,
            boolean isGroupSummary,
            String title,
            String text,
            String bigText) {
        FilterResult result = evaluate(packageName, appPackageName, isOngoing, isGroupSummary, title, text, bigText);
        return result.isAccepted();
    }

    /**
     * Evaluates an incoming notification with full metadata and returns an explicit FilterResult.
     */
    public static FilterResult evaluate(
            String packageName,
            String appPackageName,
            boolean isOngoing,
            boolean isGroupSummary,
            String title,
            String text,
            String bigText) {

        // 1. Reject ongoing notifications
        if (isOngoing) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_ONGOING);
        }

        // 2. Reject group summaries
        if (isGroupSummary) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_GROUP_SUMMARY);
        }

        // 3. Reject ActionLens self-notifications
        if (packageName == null || packageName.equals(appPackageName)) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_SELF_PACKAGE);
        }

        // 4. Reject core system packages
        if (IGNORED_SYSTEM_PACKAGES.contains(packageName.toLowerCase(Locale.ROOT))) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_SYSTEM_PACKAGE);
        }

        // 5. Reject empty bundles
        boolean hasTitle = title != null && !title.trim().isEmpty();
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasBigText = bigText != null && !bigText.trim().isEmpty();
        if (!hasTitle && !hasText && !hasBigText) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_EMPTY_OR_CORRUPT);
        }

        // 6. Context-preserving text preparation and relevance classification
        String preparedBody = extractNotificationBody(title, text, bigText);
        return evaluateRelevance(title, preparedBody);
    }

    /**
     * Evaluates the relevance of notification text content and returns an explicit decision.
     */
    public static FilterResult evaluateRelevance(String title, String body) {
        String cleanBody = body != null ? body.trim() : "";
        String cleanTitle = title != null ? title.trim() : "";

        String combined = cleanTitle.isEmpty() ? cleanBody :
                (cleanBody.isEmpty() ? cleanTitle : cleanTitle + " " + cleanBody);

        if (combined.isEmpty()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_EMPTY_OR_CORRUPT);
        }

        // 1. Punctuation or corrupt only
        if (CORRUPT_OR_EMPTY_PATTERN.matcher(cleanBody).matches() || CORRUPT_OR_EMPTY_PATTERN.matcher(combined).matches()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_EMPTY_OR_CORRUPT);
        }

        // 2. Redaction placeholder only
        if (REDACTION_PLACEHOLDER_ONLY_PATTERN.matcher(cleanBody).matches() ||
                REDACTION_PLACEHOLDER_ONLY_PATTERN.matcher(combined).matches()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_REDACTION_PLACEHOLDER);
        }

        // 3. Raw file path with no actionable context
        if (FILE_PATH_PATTERN.matcher(cleanBody).matches() || FILE_PATH_PATTERN.matcher(combined).matches()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_FILE_PATH);
        }

        // 4. Casual conversational chatter
        if (CASUAL_CHAT_PATTERN.matcher(cleanBody).matches() || CASUAL_CHAT_PATTERN.matcher(combined).matches()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_CASUAL_CHAT);
        }

        // 5. Social media engagement alerts
        if (SOCIAL_MEDIA_PATTERN.matcher(cleanBody).find() || SOCIAL_MEDIA_PATTERN.matcher(combined).find()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_SOCIAL_ENGAGEMENT);
        }

        // 6. Weather updates
        if (WEATHER_PATTERN.matcher(cleanBody).find() || WEATHER_PATTERN.matcher(combined).find()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_WEATHER_UPDATE);
        }

        // 7. Advertisements and promotional marketing
        if (PROMO_PATTERN.matcher(cleanBody).find() || PROMO_PATTERN.matcher(combined).find()) {
            return new FilterResult(RelevanceDecision.REJECT, REASON_REJECTED_PROMOTIONAL_OR_AD);
        }

        // 8. Positive actionable task detection
        if (isActionable(cleanBody) || isActionable(combined)) {
            return new FilterResult(RelevanceDecision.ACCEPT, REASON_ACCEPTED_ACTIONABLE);
        }

        // 9. Uncertain fallback: insufficient evidence to confidently decide
        return new FilterResult(RelevanceDecision.UNCERTAIN, REASON_FILTERED_UNCERTAIN_RELEVANCE);
    }

    /**
     * Convenience relevance evaluation on a single text string.
     */
    public static FilterResult evaluateRelevance(String text) {
        return evaluateRelevance(null, text);
    }

    private static boolean isActionable(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        // Tamil action markers
        if (ACTIONABLE_TAMIL_PATTERN.matcher(text).find()) {
            return true;
        }

        // Category 1: Submissions, Assignments, Homework, Reports
        if (ACTIONABLE_SUBMISSION_PATTERN.matcher(text).find()) {
            return true;
        }

        // Category 2: Payments, Fees, Bills, Invoices, Renewals
        if (ACTIONABLE_PAYMENT_PATTERN.matcher(text).find()) {
            return true;
        }

        // Category 3: Meetings, Appointments, Conferences, Attendance
        if (ACTIONABLE_MEETING_PATTERN.matcher(text).find()) {
            return true;
        }

        // Category 4: Registration, Applications, Forms
        if (ACTIONABLE_REGISTRATION_PATTERN.matcher(text).find()) {
            return true;
        }

        // Category 5: Physical items: Bring, Collect, Return
        if (ACTIONABLE_BRING_COLLECT_PATTERN.matcher(text).find()) {
            return true;
        }

        // Category 6: Imperative instruction with explicit date/time
        if (ACTIONABLE_IMPERATIVE_PATTERN.matcher(text).find()) {
            return true;
        }

        return false;
    }

    /**
     * Resolves the actionable announcement body from notification text parts,
     * preserving task subject context from title when body is a partial predicate.
     *
     * Precedence:
     * 1. EXTRA_BIG_TEXT (expanded message body) if non-empty and richest
     * 2. EXTRA_TEXT (standard message body) if non-empty
     * 3. EXTRA_TITLE (fallback only if announcement was placed entirely in title)
     *
     * Invariants:
     * - Keeps chat sender names, contact names, and group headers separate from body.
     * - Combines task topic/subject with partial deadline bodies (e.g. Title: "CS101 Lab", Body: "Due tomorrow").
     */
    public static String extractNotificationBody(String title, String text, String bigText) {
        String cleanBig = bigText != null ? bigText.trim() : "";
        String cleanText = text != null ? text.trim() : "";
        String cleanTitle = title != null ? title.trim() : "";

        String rawBody;
        if (!cleanBig.isEmpty() && cleanBig.length() >= cleanText.length()) {
            rawBody = cleanBig;
        } else if (!cleanText.isEmpty()) {
            rawBody = cleanText;
        } else if (!cleanBig.isEmpty()) {
            rawBody = cleanBig;
        } else {
            return cleanTitle;
        }

        if (cleanTitle.isEmpty()) {
            return rawBody;
        }

        if (cleanTitle.equalsIgnoreCase(rawBody)) {
            return rawBody;
        }

        if (rawBody.toLowerCase(Locale.ROOT).startsWith(cleanTitle.toLowerCase(Locale.ROOT))) {
            return rawBody;
        }

        // Context preservation: when body is a partial predicate or deadline (e.g. "Due tomorrow at 5 PM"),
        // the title holds the actual subject (e.g. "CS101 Lab Report").
        if (isPartialPredicate(rawBody)) {
            return cleanTitle + ": " + rawBody;
        }

        // Keep sender/group names separate
        return rawBody;
    }

    private static boolean isPartialPredicate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith(":") || lower.startsWith("-") || lower.startsWith("–")) {
            lower = lower.substring(1).trim();
        }
        return lower.startsWith("due ") ||
               lower.startsWith("due:") ||
               lower.startsWith("due-") ||
               lower.startsWith("due–") ||
               lower.startsWith("by ") ||
               lower.startsWith("before ") ||
               lower.startsWith("deadline:") ||
               lower.startsWith("deadline ") ||
               lower.startsWith("scheduled for ") ||
               lower.startsWith("starts at ") ||
               lower.startsWith("ends at ") ||
               (lower.startsWith("at ") && (lower.contains("am") || lower.contains("pm") || lower.contains("room"))) ||
               (lower.startsWith("on ") && (lower.contains("monday") || lower.contains("tuesday") || lower.contains("wednesday") ||
                                            lower.contains("thursday") || lower.contains("friday") || lower.contains("saturday") ||
                                            lower.contains("sunday") || lower.contains("jan") || lower.contains("feb") ||
                                            lower.contains("mar") || lower.contains("apr") || lower.contains("may") ||
                                            lower.contains("jun") || lower.contains("jul") || lower.contains("aug") ||
                                            lower.contains("sep") || lower.contains("oct") || lower.contains("nov") ||
                                            lower.contains("dec"))) ||
               (lower.startsWith("in ") && (lower.contains("room") || lower.contains("hall") || lower.contains("lab")));
    }

    /**
     * Combines available notification text parts into a single message string.
     * Preserved for backward compatibility.
     */
    public static String combineNotificationText(String title, String text) {
        String cleanTitle = title != null ? title.trim() : "";
        String cleanText = text != null ? text.trim() : "";

        if (!cleanTitle.isEmpty() && !cleanText.isEmpty()) {
            return cleanTitle + ": " + cleanText;
        } else if (!cleanTitle.isEmpty()) {
            return cleanTitle;
        } else {
            return cleanText;
        }
    }
}
