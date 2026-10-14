package com.example.actionlens.extractor;

import androidx.annotation.NonNull;

import com.example.actionlens.data.TaskPriority;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic rule-based extraction engine for ActionLens.
 *
 * Extracts structured actionable tasks from sanitized text:
 * - Concise title and action verb.
 * - Date and time parsing with explicit ambiguity detection.
 * - Priority classification (LOW, NORMAL, HIGH, URGENT).
 * - Location identification.
 *
 * Privacy Invariant:
 * Operates purely on sanitized text payloads. Never logs, persists, or leaks original text.
 */
public class ActionExtractor {

    private static final List<String> ACTION_VERBS = Arrays.asList(
            "Submit", "Pay", "Attend", "Register", "Review", "Call", "Email",
            "Complete", "Upload", "Prepare", "Sign", "Send", "Bring", "Check",
            "Apply", "Renew", "Verify", "Read", "Finish", "Collect", "Return",
            "Schedule", "Meet", "Confirm", "Fill", "Download", "Join"
    );

    // Imperative preamble cleanups
    private static final Pattern PREAMBLE_PATTERN = Pattern.compile(
            "(?i)^(?:please\\s+|kindly\\s+|don't\\s+forget\\s+to\\s+|do\\s+not\\s+forget\\s+to\\s+|" +
            "remember\\s+to\\s+|make\\s+sure\\s+to\\s+|reminder\\s*[:–-]\\s*|action\\s+required\\s*[:–-]\\s*|" +
            "urgent\\s*[:–-]\\s*|important\\s*[:–-]\\s*|notice\\s*[:–-]\\s*|announcement\\s*[:–-]\\s*)+"
    );

    // Date & Time Patterns
    private static final Pattern RELATIVE_DAY_PATTERN = Pattern.compile(
            "(?i)\\b(today|tonight|tomorrow|day\\s+after\\s+tomorrow)\\b"
    );

    private static final Pattern DAY_OF_WEEK_PATTERN = Pattern.compile(
            "(?i)\\b(?:(this|next)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"
    );

    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile(
            "(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|" +
            "aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b"
    );

    private static final Pattern DAY_MONTH_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|" +
            "aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b"
    );

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile(
            "\\b(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b"
    );

    private static final Pattern SLASH_DATE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})\\b"
    );

    private static final Pattern TIME_12H_PATTERN = Pattern.compile(
            "(?i)\\b(?:at|by|before)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b"
    );

    private static final Pattern TIME_24H_PATTERN = Pattern.compile(
            "(?i)\\b(?:at|by|before)\\s+([01]?\\d|2[0-3]):([0-5]\\d)\\b"
    );

    // Location pattern
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?i)\\b(?:in|at|on|via)\\s+(room\\s+[A-Za-z0-9]+|hall\\s+[A-Za-z0-9]+|auditorium|library|" +
            "lab\\s+[A-Za-z0-9]+|zoom|google\\s+meet|teams|microsoft\\s+teams)\\b"
    );

    // Priority keywords
    private static final Pattern URGENT_PATTERN = Pattern.compile(
            "(?i)\\b(urgent|asap|immediately|emergency|critical)\\b"
    );

    private static final Pattern HIGH_PRIORITY_PATTERN = Pattern.compile(
            "(?i)\\b(important|mandatory|required|strict|final\\s+date|last\\s+date|penalty)\\b"
    );

    private static final Pattern LOW_PRIORITY_PATTERN = Pattern.compile(
            "(?i)\\b(optional|low\\s+priority|whenever|fyi|if\\s+possible|no\\s+rush)\\b"
    );

    private final ZoneId zoneId;

    public ActionExtractor() {
        this(ZoneId.systemDefault());
    }

    public ActionExtractor(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * Extracts structured action card entities from sanitized text using current time as reference.
     *
     * @param sanitizedText Content after PII scrubbing/masking
     * @return ExtractedAction containing parsed attributes
     */
    public ExtractedAction extract(String sanitizedText) {
        return extract(sanitizedText, ZonedDateTime.now(zoneId));
    }

    /**
     * Extracts structured action card entities using a specified reference time.
     * Allows deterministic unit testing for relative dates.
     */
    public ExtractedAction extract(String sanitizedText, ZonedDateTime referenceTime) {
        if (sanitizedText == null || sanitizedText.trim().isEmpty()) {
            return new ExtractedAction(
                    "Untitled Task",
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    TaskPriority.NORMAL,
                    null,
                    "Manual Input"
            );
        }

        String cleanedText = sanitizedText.trim();

        // 1. Parse Date and Time
        ParsedDateTime parsedDate = parseDateTime(cleanedText, referenceTime);

        // 2. Identify Action Verb & Generate Title
        String actionVerb = detectActionVerb(cleanedText);
        String title = generateTitle(cleanedText, actionVerb);

        // 3. Determine Priority
        TaskPriority priority = calculatePriority(cleanedText, parsedDate, referenceTime);

        // 4. Extract Location
        String location = extractLocation(cleanedText);

        Long deadlineTimestamp = parsedDate != null ? parsedDate.timestamp : null;
        String deadlineFormatted = parsedDate != null ? parsedDate.formattedString : null;
        boolean hasExplicitTime = parsedDate != null && parsedDate.hasExplicitTime;
        boolean isDateAmbiguous = parsedDate != null && parsedDate.isAmbiguous;
        String ambiguousReason = parsedDate != null ? parsedDate.ambiguousReason : null;

        return new ExtractedAction(
                title,
                actionVerb,
                deadlineTimestamp,
                deadlineFormatted,
                hasExplicitTime,
                isDateAmbiguous,
                ambiguousReason,
                priority,
                location,
                "Manual Input"
        );
    }

    private static class ParsedDateTime {
        Long timestamp;
        String formattedString;
        boolean hasExplicitTime;
        boolean isAmbiguous;
        String ambiguousReason;
    }

    /**
     * Documented Date & Time Parsing Strategy:
     * 1. Relative tokens (today, tomorrow, next Friday)
     * 2. Named month + day (Oct 15, October 15)
     * 3. Numeric ISO dates (2026-10-15)
     * 4. Ambiguous numeric dates (05/06/2026 flagged as ambiguous if both <= 12)
     * 5. Time component (12-hour AM/PM or 24-hour)
     */
    private ParsedDateTime parseDateTime(String text, ZonedDateTime referenceTime) {
        LocalDate extractedDate = null;
        LocalTime extractedTime = null;
        boolean hasExplicitTime = false;
        boolean isAmbiguous = false;
        String ambiguousReason = null;

        LocalDate today = referenceTime.toLocalDate();

        // 1. Relative days
        Matcher relMatcher = RELATIVE_DAY_PATTERN.matcher(text);
        if (relMatcher.find()) {
            String word = relMatcher.group(1).toLowerCase(Locale.ROOT);
            if (word.equals("today") || word.equals("tonight")) {
                extractedDate = today;
            } else if (word.equals("tomorrow")) {
                extractedDate = today.plusDays(1);
            } else if (word.contains("after tomorrow")) {
                extractedDate = today.plusDays(2);
            }
        }

        // 2. Day of week (e.g., "next Monday", "this Friday")
        if (extractedDate == null) {
            Matcher dowMatcher = DAY_OF_WEEK_PATTERN.matcher(text);
            if (dowMatcher.find()) {
                String dayName = dowMatcher.group(2).toUpperCase(Locale.ROOT);
                try {
                    DayOfWeek targetDow = DayOfWeek.valueOf(dayName);
                    extractedDate = today.with(TemporalAdjusters.next(targetDow));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // 3. Month + Day (e.g., "Oct 15", "October 15")
        if (extractedDate == null) {
            Matcher mdMatcher = MONTH_DAY_PATTERN.matcher(text);
            if (mdMatcher.find()) {
                Month month = parseMonth(mdMatcher.group(1));
                int day = Integer.parseInt(mdMatcher.group(2));
                if (month != null && day >= 1 && day <= month.maxLength()) {
                    int year = today.getYear();
                    LocalDate candidate = LocalDate.of(year, month, day);
                    if (candidate.isBefore(today.minusDays(7))) {
                        candidate = candidate.plusYears(1);
                    }
                    extractedDate = candidate;
                }
            }
        }

        // 4. Day + Month (e.g., "15 Oct", "15th October")
        if (extractedDate == null) {
            Matcher dmMatcher = DAY_MONTH_PATTERN.matcher(text);
            if (dmMatcher.find()) {
                int day = Integer.parseInt(dmMatcher.group(1));
                Month month = parseMonth(dmMatcher.group(2));
                if (month != null && day >= 1 && day <= month.maxLength()) {
                    int year = today.getYear();
                    LocalDate candidate = LocalDate.of(year, month, day);
                    if (candidate.isBefore(today.minusDays(7))) {
                        candidate = candidate.plusYears(1);
                    }
                    extractedDate = candidate;
                }
            }
        }

        // 5. ISO Numeric Date (YYYY-MM-DD)
        if (extractedDate == null) {
            Matcher isoMatcher = ISO_DATE_PATTERN.matcher(text);
            if (isoMatcher.find()) {
                try {
                    int year = Integer.parseInt(isoMatcher.group(1));
                    int month = Integer.parseInt(isoMatcher.group(2));
                    int day = Integer.parseInt(isoMatcher.group(3));
                    extractedDate = LocalDate.of(year, month, day);
                } catch (Exception ignored) {
                }
            }
        }

        // 6. Ambiguous slash/dot date check (e.g. "05/06/2026")
        if (extractedDate == null) {
            Matcher slashMatcher = SLASH_DATE_PATTERN.matcher(text);
            if (slashMatcher.find()) {
                try {
                    int part1 = Integer.parseInt(slashMatcher.group(1));
                    int part2 = Integer.parseInt(slashMatcher.group(2));
                    int year = Integer.parseInt(slashMatcher.group(3));
                    if (year < 100) year += 2000;

                    if (part1 <= 12 && part2 <= 12 && part1 != part2) {
                        // Flag ambiguity: could be MM/DD or DD/MM
                        isAmbiguous = true;
                        ambiguousReason = "Date '" + slashMatcher.group() + "' could represent MM/DD or DD/MM format.";
                        // Default candidate: assume day first if valid
                        extractedDate = LocalDate.of(year, part2, part1);
                    } else if (part1 > 12 && part2 <= 12) {
                        // Unambiguous DD/MM
                        extractedDate = LocalDate.of(year, part2, part1);
                    } else if (part2 > 12 && part1 <= 12) {
                        // Unambiguous MM/DD
                        extractedDate = LocalDate.of(year, part1, part2);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Parse Time Component
        Matcher time12Matcher = TIME_12H_PATTERN.matcher(text);
        if (time12Matcher.find()) {
            int hour = Integer.parseInt(time12Matcher.group(1));
            int minute = time12Matcher.group(2) != null ? Integer.parseInt(time12Matcher.group(2)) : 0;
            String ampm = time12Matcher.group(3).toLowerCase(Locale.ROOT);
            if (ampm.equals("pm") && hour < 12) hour += 12;
            if (ampm.equals("am") && hour == 12) hour = 0;
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                extractedTime = LocalTime.of(hour, minute);
                hasExplicitTime = true;
            }
        }

        if (extractedTime == null) {
            Matcher time24Matcher = TIME_24H_PATTERN.matcher(text);
            if (time24Matcher.find()) {
                int hour = Integer.parseInt(time24Matcher.group(1));
                int minute = Integer.parseInt(time24Matcher.group(2));
                extractedTime = LocalTime.of(hour, minute);
                hasExplicitTime = true;
            }
        }

        // If no date and no time were found, do not invent a deadline
        if (extractedDate == null && extractedTime == null) {
            return null;
        }

        // If time is found without explicit date, default date to today or tomorrow
        if (extractedDate == null) {
            extractedDate = today;
            if (extractedTime.isBefore(referenceTime.toLocalTime())) {
                extractedDate = today.plusDays(1);
            }
        }

        // If date is found without explicit time, default to 23:59 end-of-day
        if (extractedTime == null) {
            extractedTime = LocalTime.of(23, 59);
        }

        LocalDateTime ldt = LocalDateTime.of(extractedDate, extractedTime);
        ZonedDateTime zdt = ldt.atZone(zoneId);

        ParsedDateTime result = new ParsedDateTime();
        result.timestamp = zdt.toInstant().toEpochMilli();
        result.hasExplicitTime = hasExplicitTime;
        result.isAmbiguous = isAmbiguous;
        result.ambiguousReason = ambiguousReason;

        // Build human-readable formatted string
        DateTimeFormatter formatter;
        if (hasExplicitTime) {
            formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.ENGLISH);
        } else {
            formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
        }
        result.formattedString = zdt.format(formatter);

        return result;
    }

    private Month parseMonth(String monthStr) {
        if (monthStr == null) return null;
        String m = monthStr.toLowerCase(Locale.ROOT);
        if (m.startsWith("jan")) return Month.JANUARY;
        if (m.startsWith("feb")) return Month.FEBRUARY;
        if (m.startsWith("mar")) return Month.MARCH;
        if (m.startsWith("apr")) return Month.APRIL;
        if (m.startsWith("may")) return Month.MAY;
        if (m.startsWith("jun")) return Month.JUNE;
        if (m.startsWith("jul")) return Month.JULY;
        if (m.startsWith("aug")) return Month.AUGUST;
        if (m.startsWith("sep")) return Month.SEPTEMBER;
        if (m.startsWith("oct")) return Month.OCTOBER;
        if (m.startsWith("nov")) return Month.NOVEMBER;
        if (m.startsWith("dec")) return Month.DECEMBER;
        return null;
    }

    private String detectActionVerb(String text) {
        for (String verb : ACTION_VERBS) {
            Pattern p = Pattern.compile("(?i)\\b" + verb + "\\b");
            if (p.matcher(text).find()) {
                return verb;
            }
        }
        return null;
    }

    private String generateTitle(String text, String actionVerb) {
        // Strip preamble like "Please...", "Reminder:..."
        String cleaned = PREAMBLE_PATTERN.matcher(text).replaceFirst("").trim();

        // Extract first sentence
        String[] sentences = cleaned.split("[.\\n;!?]+");
        String firstSentence = sentences.length > 0 ? sentences[0].trim() : cleaned;

        // Strip trailing deadline phrases like "by tomorrow", "tomorrow at 5 PM", "next Monday", ": Due tomorrow at 5 PM", ": Due Friday at 5 PM"
        firstSentence = firstSentence.replaceAll(
                "(?i)\\s*[:–-]?\\s+(?:(?:due|deadline|by|before|on|at)\\s+)?(?:tomorrow|today|tonight|next\\s+\\w+|this\\s+\\w+|monday|tuesday|wednesday|thursday|friday|saturday|sunday|jan(?:uary)?\\s+\\d+|feb(?:ruary)?\\s+\\d+|mar(?:ch)?\\s+\\d+|apr(?:il)?\\s+\\d+|may\\s+\\d+|jun(?:e)?\\s+\\d+|jul(?:y)?\\s+\\d+|aug(?:ust)?\\s+\\d+|sep(?:tember)?\\s+\\d+|oct(?:ober)?\\s+\\d+|nov(?:ember)?\\s+\\d+|dec(?:ember)?\\s+\\d+|\\d{1,2}\\s+[ap]m|\\d{1,2}:\\d{2}).*$",
                ""
        ).trim();

        if (firstSentence.endsWith(":") || firstSentence.endsWith("-") || firstSentence.endsWith("–")) {
            firstSentence = firstSentence.substring(0, firstSentence.length() - 1).trim();
        }

        if (firstSentence.isEmpty()) {
            return actionVerb != null ? actionVerb + " Task" : "Action Item";
        }

        // Capitalize first letter
        firstSentence = Character.toUpperCase(firstSentence.charAt(0)) + firstSentence.substring(1);

        // Cap title cleanly to 70 characters
        if (firstSentence.length() > 70) {
            int lastSpace = firstSentence.lastIndexOf(' ', 67);
            if (lastSpace != -1) {
                firstSentence = firstSentence.substring(0, lastSpace) + "...";
            } else {
                firstSentence = firstSentence.substring(0, 67) + "...";
            }
        }

        return firstSentence;
    }

    private TaskPriority calculatePriority(String text, ParsedDateTime parsedDate, ZonedDateTime referenceTime) {
        if (URGENT_PATTERN.matcher(text).find()) {
            return TaskPriority.URGENT;
        }

        // Check proximity: if deadline is within 24 hours, classify as URGENT
        if (parsedDate != null && parsedDate.timestamp != null) {
            long diffMillis = parsedDate.timestamp - referenceTime.toInstant().toEpochMilli();
            if (diffMillis > 0 && diffMillis <= 24 * 60 * 60 * 1000L) {
                return TaskPriority.URGENT;
            }
        }

        if (HIGH_PRIORITY_PATTERN.matcher(text).find()) {
            return TaskPriority.HIGH;
        }

        if (LOW_PRIORITY_PATTERN.matcher(text).find()) {
            return TaskPriority.LOW;
        }

        return TaskPriority.NORMAL;
    }

    private String extractLocation(String text) {
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        if (matcher.find()) {
            String loc = matcher.group(1).trim();
            // Capitalize properly
            return Character.toUpperCase(loc.charAt(0)) + loc.substring(1);
        }
        return null;
    }
}
