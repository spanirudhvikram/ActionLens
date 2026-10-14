package com.example.actionlens.extractor;

import com.example.actionlens.data.TaskPriority;

import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying rule-based action extraction:
 * - Date and time parsing (Oct 15, tomorrow at 5 PM, next Monday, by 18:00).
 * - Ambiguous date detection.
 * - Missing deadline handling.
 * - Priority calculation and location extraction.
 */
public class ActionExtractorTest {

    private ActionExtractor extractor;
    private ZonedDateTime fixedReferenceTime;

    @Before
    public void setUp() {
        ZoneId zone = ZoneId.of("UTC");
        extractor = new ActionExtractor(zone);
        // Fixed reference: Friday, Oct 9, 2026, at 10:00 AM UTC
        fixedReferenceTime = ZonedDateTime.of(
                LocalDate.of(2026, Month.OCTOBER, 9),
                LocalTime.of(10, 0),
                zone
        );
    }

    @Test
    public void testExtraction_MonthAndDay() {
        // "Oct 15"
        String text = "Please submit the final lab report by Oct 15 in Room 402.";
        ExtractedAction action = extractor.extract(text, fixedReferenceTime);

        assertNotNull(action);
        assertEquals("Submit the final lab report", action.getTitle());
        assertEquals("Submit", action.getActionVerb());
        assertTrue(action.hasDeadline());
        assertFalse(action.isDateAmbiguous());
        assertEquals("Room 402", action.getLocation());

        // Check date is Oct 15, 2026
        ZonedDateTime deadline = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(action.getDeadlineTimestamp()),
                ZoneId.of("UTC")
        );
        assertEquals(2026, deadline.getYear());
        assertEquals(Month.OCTOBER, deadline.getMonth());
        assertEquals(15, deadline.getDayOfMonth());
    }

    @Test
    public void testExtraction_TomorrowAt5PM() {
        // "tomorrow at 5 PM"
        String text = "Kindly pay the conference registration fee tomorrow at 5 PM online.";
        ExtractedAction action = extractor.extract(text, fixedReferenceTime);

        assertNotNull(action);
        assertEquals("Pay the conference registration fee", action.getTitle());
        assertEquals("Pay", action.getActionVerb());
        assertTrue(action.hasDeadline());
        assertTrue(action.isHasExplicitTime());
        assertFalse(action.isDateAmbiguous());

        ZonedDateTime deadline = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(action.getDeadlineTimestamp()),
                ZoneId.of("UTC")
        );
        // Tomorrow relative to Oct 9 is Oct 10
        assertEquals(10, deadline.getDayOfMonth());
        assertEquals(17, deadline.getHour());
        assertEquals(0, deadline.getMinute());
    }

    @Test
    public void testExtraction_NextMonday() {
        // "next Monday"
        String text = "Attend the department orientation next Monday.";
        ExtractedAction action = extractor.extract(text, fixedReferenceTime);

        assertNotNull(action);
        assertEquals("Attend the department orientation", action.getTitle());
        assertEquals("Attend", action.getActionVerb());
        assertTrue(action.hasDeadline());
        assertFalse(action.isDateAmbiguous());

        ZonedDateTime deadline = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(action.getDeadlineTimestamp()),
                ZoneId.of("UTC")
        );
        // Friday Oct 9 -> Next Monday is Oct 12
        assertEquals(Month.OCTOBER, deadline.getMonth());
        assertEquals(12, deadline.getDayOfMonth());
    }

    @Test
    public void testExtraction_By1800() {
        // "by 18:00"
        String text = "Upload the project source code by 18:00.";
        ExtractedAction action = extractor.extract(text, fixedReferenceTime);

        assertNotNull(action);
        assertEquals("Upload the project source code", action.getTitle());
        assertEquals("Upload", action.getActionVerb());
        assertTrue(action.hasDeadline());
        assertTrue(action.isHasExplicitTime());

        ZonedDateTime deadline = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(action.getDeadlineTimestamp()),
                ZoneId.of("UTC")
        );
        // Time is 18:00 on today (since 18:00 > 10:00 reference time)
        assertEquals(9, deadline.getDayOfMonth());
        assertEquals(18, deadline.getHour());
        assertEquals(0, deadline.getMinute());
    }

    @Test
    public void testExtraction_MissingDeadline_DoesNotInventDate() {
        String text = "Review the team research proposal and compile notes.";
        ExtractedAction action = extractor.extract(text, fixedReferenceTime);

        assertNotNull(action);
        assertEquals("Review the team research proposal and compile notes", action.getTitle());
        assertEquals("Review", action.getActionVerb());
        assertFalse(action.hasDeadline());
        assertNull(action.getDeadlineTimestamp());
        assertNull(action.getDeadlineFormatted());
        assertFalse(action.isDateAmbiguous());
    }

    @Test
    public void testExtraction_AmbiguousDateFlagged() {
        // 05/06/2026 can be May 6 or June 5
        String text = "Schedule client debrief on 05/06/2026 at 2 PM on Zoom.";
        ExtractedAction action = extractor.extract(text, fixedReferenceTime);

        assertNotNull(action);
        assertTrue(action.isDateAmbiguous());
        assertNotNull(action.getAmbiguousDateReason());
        assertTrue(action.getAmbiguousDateReason().contains("MM/DD or DD/MM"));
        assertEquals("Zoom", action.getLocation());
    }

    @Test
    public void testPriorityClassification() {
        // Urgent
        ExtractedAction urgentAction = extractor.extract(
                "URGENT: Submit the tax declaration immediately!",
                fixedReferenceTime
        );
        assertEquals(TaskPriority.URGENT, urgentAction.getPriority());

        // High
        ExtractedAction highAction = extractor.extract(
                "Important: Complete mandatory compliance training by Oct 20.",
                fixedReferenceTime
        );
        assertEquals(TaskPriority.HIGH, highAction.getPriority());

        // Low
        ExtractedAction lowAction = extractor.extract(
                "Optional: Check out the new coding guidelines if possible.",
                fixedReferenceTime
        );
        assertEquals(TaskPriority.LOW, lowAction.getPriority());

        // Normal
        ExtractedAction normalAction = extractor.extract(
                "Bring notebooks to the discussion group.",
                fixedReferenceTime
        );
        assertEquals(TaskPriority.NORMAL, normalAction.getPriority());
    }

    @Test
    public void testExtraction_LocationRecognition() {
        ExtractedAction action1 = extractor.extract("Meet the project team in Room 204 at 11 AM.");
        assertEquals("Room 204", action1.getLocation());

        ExtractedAction action2 = extractor.extract("Join the weekly sync on Zoom at 4 PM.");
        assertEquals("Zoom", action2.getLocation());
    }
}
