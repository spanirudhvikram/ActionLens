package com.example.actionlens.service;

import com.example.actionlens.extractor.ActionExtractor;
import com.example.actionlens.extractor.ExtractedAction;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.privacy.PrivacyEngine;
import com.example.actionlens.privacy.SensitivityLevel;
import com.example.actionlens.service.NotificationFilter.FilterResult;
import com.example.actionlens.service.NotificationFilter.RelevanceDecision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying notification filtering logic.
 *
 * Verifies that ongoing events, group summaries, system packages,
 * ActionLens self-notifications, and empty alerts are discarded
 * prior to any privacy assessment or extraction.
 */
public class NotificationFilterTest {

    private static final String OUR_APP_PKG = "com.example.actionlens";

    @Test
    public void testOngoingNotificationIsRejected() {
        boolean eligible = NotificationFilter.isEligible(
                "com.whatsapp",
                OUR_APP_PKG,
                true, // ongoing
                false,
                "Call in progress",
                "00:45"
        );
        assertFalse("Ongoing notifications must be rejected", eligible);
    }

    @Test
    public void testGroupSummaryNotificationIsRejected() {
        boolean eligible = NotificationFilter.isEligible(
                "com.slack",
                OUR_APP_PKG,
                false,
                true, // group summary
                "3 new messages",
                "Team Chat"
        );
        assertFalse("Group summary notifications must be rejected to prevent duplicates", eligible);
    }

    @Test
    public void testSelfNotificationIsRejected() {
        boolean eligible = NotificationFilter.isEligible(
                OUR_APP_PKG,
                OUR_APP_PKG,
                false,
                false,
                "Task Created",
                "New task added"
        );
        assertFalse("ActionLens self-notifications must be rejected to avoid infinite loops", eligible);
    }

    @Test
    public void testSystemPackagesAreRejected() {
        String[] systemPkgs = {
                "android",
                "com.android.systemui",
                "com.android.vending",
                "com.google.android.packageinstaller",
                "com.android.providers.downloads",
                "com.google.android.gms"
        };

        for (String pkg : systemPkgs) {
            boolean eligible = NotificationFilter.isEligible(
                    pkg,
                    OUR_APP_PKG,
                    false,
                    false,
                    "System alert",
                    "Battery update"
            );
            assertFalse("System package " + pkg + " must be rejected", eligible);
        }
    }

    @Test
    public void testEmptyOrWhitespaceNotificationIsRejected() {
        assertFalse(NotificationFilter.isEligible("com.whatsapp", OUR_APP_PKG, false, false, null, null));
        assertFalse(NotificationFilter.isEligible("com.whatsapp", OUR_APP_PKG, false, false, "", ""));
        assertFalse(NotificationFilter.isEligible("com.whatsapp", OUR_APP_PKG, false, false, "   ", "   \t\n"));
    }

    @Test
    public void testValidNotificationsAreAccepted() {
        assertTrue(NotificationFilter.isEligible(
                "com.whatsapp",
                OUR_APP_PKG,
                false,
                false,
                "Project Group",
                "Submit budget report by tomorrow 5 PM"
        ));

        assertTrue(NotificationFilter.isEligible(
                "com.google.android.gm",
                OUR_APP_PKG,
                false,
                false,
                "Meeting Notice",
                "Review project timeline by Friday at Room 101"
        ));
    }

    @Test
    public void testNotificationWithTitleOnlyIsAccepted() {
        assertTrue(NotificationFilter.isEligible(
                "com.slack",
                OUR_APP_PKG,
                false,
                false,
                "Submit timesheet by 18:00",
                null
        ));
    }

    @Test
    public void testNotificationWithTextOnlyIsAccepted() {
        assertTrue(NotificationFilter.isEligible(
                "com.slack",
                OUR_APP_PKG,
                false,
                false,
                null,
                "Submit timesheet by 18:00"
        ));
    }

    @Test
    public void testCombineNotificationText() {
        assertEquals("Meeting: Room 101 at 4 PM",
                NotificationFilter.combineNotificationText("Meeting", "Room 101 at 4 PM"));
        assertEquals("Meeting",
                NotificationFilter.combineNotificationText("Meeting", ""));
        assertEquals("Room 101 at 4 PM",
                NotificationFilter.combineNotificationText(null, "Room 101 at 4 PM"));
        assertEquals("",
                NotificationFilter.combineNotificationText(null, null));
    }

    @Test
    public void testExtractNotificationBody_SeparatesTitleAndBody() {
        String body = NotificationFilter.extractNotificationBody(
                "Project Team",
                "Submit chemistry lab report by Oct 15 in Room 302.",
                null
        );
        assertEquals("Submit chemistry lab report by Oct 15 in Room 302.", body);
    }

    @Test
    public void testExtractNotificationBody_PrefersBigTextWhenPresent() {
        String body = NotificationFilter.extractNotificationBody(
                "Project Team",
                "Tag1",
                "Submit chemistry lab report by Oct 15 in Room 302."
        );
        assertEquals("Submit chemistry lab report by Oct 15 in Room 302.", body);
    }

    @Test
    public void testExtractNotificationBody_FallsBackToTitleIfBodyEmpty() {
        String body = NotificationFilter.extractNotificationBody(
                "Submit chemistry lab report by Oct 15 in Room 302.",
                null,
                null
        );
        assertEquals("Submit chemistry lab report by Oct 15 in Room 302.", body);
    }

    @Test
    public void testIsEligible_WithBigText() {
        assertTrue(NotificationFilter.isEligible(
                "com.whatsapp",
                OUR_APP_PKG,
                false,
                false,
                null,
                null,
                "Submit chemistry lab report by Oct 15 in Room 302."
        ));
    }

    // =========================================================================
    // Phase 2: Conservative Notification Relevance Filtering Test Suite
    // =========================================================================

    @Test
    public void testScenario1_CasualChatIsRejected() {
        String[] casualMessages = {
                "The gang is ready",
                "Okay, thanks.",
                "How are you?",
                "I'm on my way",
                "See you tomorrow",
                "Sounds good!",
                "Done deal",
                "What's up?"
        };

        for (String msg : casualMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("Friend", msg);
            assertEquals("Message '" + msg + "' must be rejected as casual chat",
                    RelevanceDecision.REJECT, result.getDecision());
            assertEquals(NotificationFilter.REASON_REJECTED_CASUAL_CHAT, result.getReasonCode());
            assertFalse(result.isAccepted());
            assertFalse(NotificationFilter.isEligible("com.whatsapp", OUR_APP_PKG, false, false, "Friend", msg));
        }
    }

    @Test
    public void testScenario2_SocialMediaEngagementIsRejected() {
        String[] socialMessages = {
                "Sent you a reel.",
                "Liked your photo",
                "Reacted to your story: 🔥",
                "Mentioned you in a comment",
                "Started following you",
                "Commented on your post"
        };

        for (String msg : socialMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("Instagram", msg);
            assertEquals("Message '" + msg + "' must be rejected as social engagement",
                    RelevanceDecision.REJECT, result.getDecision());
            assertEquals(NotificationFilter.REASON_REJECTED_SOCIAL_ENGAGEMENT, result.getReasonCode());
            assertFalse(result.isAccepted());
            assertFalse(NotificationFilter.isEligible("com.instagram.android", OUR_APP_PKG, false, false, "Instagram", msg));
        }
    }

    @Test
    public void testScenario3_WeatherUpdatesAndGenericAdvertisementsAreRejected() {
        // Weather
        String[] weatherMessages = {
                "Cloudy. See full forecast.",
                "Weather forecast: Thunderstorm alert for your area",
                "AQI is 150 - Moderate air quality",
                "Rain expected this afternoon"
        };
        for (String msg : weatherMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("Weather", msg);
            assertEquals("Weather message '" + msg + "' must be rejected",
                    RelevanceDecision.REJECT, result.getDecision());
            assertEquals(NotificationFilter.REASON_REJECTED_WEATHER_UPDATE, result.getReasonCode());
            assertFalse(result.isAccepted());
        }

        // Generic Advertisements / Promotions
        String[] promoMessages = {
                "Flat 50% off on all items! Use code SAVE50.",
                "Buy 1 get 1 free! Mega sale ends tonight.",
                "Exclusive offer: Claim your cashback now",
                "Special discount just for you! Order now and get free delivery."
        };
        for (String msg : promoMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("ShoppingApp", msg);
            assertEquals("Promo message '" + msg + "' must be rejected",
                    RelevanceDecision.REJECT, result.getDecision());
            assertEquals(NotificationFilter.REASON_REJECTED_PROMOTIONAL_OR_AD, result.getReasonCode());
            assertFalse(result.isAccepted());
        }
    }

    @Test
    public void testScenario4_ClearAssignmentDeadlineIsAccepted() {
        String[] actionableAssignments = {
                "Submit the lab report by tomorrow.",
                "Upload assignment 3 before Friday 11:59 PM.",
                "Complete chemistry homework by Oct 15 in Room 204.",
                "Turn in literature essay before Monday 5 PM."
        };

        for (String msg : actionableAssignments) {
            FilterResult result = NotificationFilter.evaluateRelevance("Classroom", msg);
            assertEquals("Assignment message '" + msg + "' must be accepted",
                    RelevanceDecision.ACCEPT, result.getDecision());
            assertEquals(NotificationFilter.REASON_ACCEPTED_ACTIONABLE, result.getReasonCode());
            assertTrue(result.isAccepted());
            assertTrue(NotificationFilter.isEligible("com.unfamiliar.schoolapp", OUR_APP_PKG, false, false, "Professor", msg));
        }
    }

    @Test
    public void testScenario5_PaymentReminderIsAccepted() {
        String[] paymentMessages = {
                "Pay the examination fee before Friday.",
                "Electricity bill payment due tomorrow.",
                "Settle tuition fee dues by Oct 20.",
                "Rent payment due by 1st of the month."
        };

        for (String msg : paymentMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("Finance", msg);
            assertEquals("Payment reminder '" + msg + "' must be accepted",
                    RelevanceDecision.ACCEPT, result.getDecision());
            assertEquals(NotificationFilter.REASON_ACCEPTED_ACTIONABLE, result.getReasonCode());
            assertTrue(result.isAccepted());
            assertTrue(NotificationFilter.isEligible("com.unfamiliar.bankapp", OUR_APP_PKG, false, false, "Bank", msg));
        }
    }

    @Test
    public void testScenario6_MeetingOrAppointmentIsAccepted() {
        String[] meetingMessages = {
                "Project review meeting at 10 AM.",
                "Doctor appointment scheduled for tomorrow at 4 PM in Room 102.",
                "Attend weekly team sync on Monday at 11 AM.",
                "Schedule client debrief in room 301 at 2 PM."
        };

        for (String msg : meetingMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("Calendar", msg);
            assertEquals("Meeting reminder '" + msg + "' must be accepted",
                    RelevanceDecision.ACCEPT, result.getDecision());
            assertEquals(NotificationFilter.REASON_ACCEPTED_ACTIONABLE, result.getReasonCode());
            assertTrue(result.isAccepted());
            assertTrue(NotificationFilter.isEligible("org.custom.workplace", OUR_APP_PKG, false, false, "Manager", msg));
        }
    }

    @Test
    public void testScenario7_TitleAndBodyContextPreserved_AndSeparateDeadline() {
        // 1. Partial predicate body: Title contains task name, Body contains deadline
        String title = "CS101 Lab Report";
        String body = "Due tomorrow at 5 PM";

        String preparedBody = NotificationFilter.extractNotificationBody(title, body, null);
        assertEquals("CS101 Lab Report: Due tomorrow at 5 PM", preparedBody);

        FilterResult filterResult = NotificationFilter.evaluateRelevance(title, preparedBody);
        assertTrue(filterResult.isAccepted());

        ActionExtractor extractor = new ActionExtractor();
        ExtractedAction action = extractor.extract(preparedBody);

        assertEquals("Task title must be pure subject without trailing deadline phrases",
                "CS101 Lab Report", action.getTitle());
        assertTrue("Deadline must be extracted separately", action.hasDeadline());
        assertTrue("Explicit time must be extracted", action.isHasExplicitTime());
        assertNotNull(action.getDeadlineTimestamp());

        // 2. Complete sentence body: Sender/group name in title must NOT pollute task title
        String senderTitle = "Project Team";
        String fullBody = "Submit chemistry lab report by Oct 15 in Room 302.";

        String preservedBody = NotificationFilter.extractNotificationBody(senderTitle, fullBody, null);
        assertEquals("Submit chemistry lab report by Oct 15 in Room 302.", preservedBody);

        ExtractedAction senderAction = extractor.extract(preservedBody);
        assertEquals("Submit chemistry lab report", senderAction.getTitle());
        assertFalse(senderAction.getTitle().contains("Project Team"));
        assertEquals("Room 302", senderAction.getLocation());
    }

    @Test
    public void testScenario8_UncertainMessageIsNotAutomaticallySavedAsActiveTask() {
        String[] uncertainMessages = {
                "Check this",
                "Maybe we can meet tomorrow?",
                "Did you see the latest update?",
                "Here is some information for you"
        };

        for (String msg : uncertainMessages) {
            FilterResult result = NotificationFilter.evaluateRelevance("Chat", msg);
            assertEquals("Uncertain message '" + msg + "' must have UNCERTAIN decision",
                    RelevanceDecision.UNCERTAIN, result.getDecision());
            assertEquals(NotificationFilter.REASON_FILTERED_UNCERTAIN_RELEVANCE, result.getReasonCode());
            assertFalse("Uncertain message must not be accepted for automatic task creation", result.isAccepted());

            boolean eligible = NotificationFilter.isEligible("com.custom.messenger", OUR_APP_PKG, false, false, "Contact", msg);
            assertFalse("Uncertain message must not be eligible for processing", eligible);
        }
    }

    @Test
    public void testScenario9_TamilAndMixedTamilEnglishActionMessagesAreAccepted() {
        // 1. Pure Tamil action message
        String tamilMsg = "நாளை மாலை 5 மணிக்கு அறிக்கையை சமர்ப்பிக்கவும்";
        FilterResult tamilRes = NotificationFilter.evaluateRelevance("கல்லூரி", tamilMsg);
        assertEquals(RelevanceDecision.ACCEPT, tamilRes.getDecision());
        assertEquals(NotificationFilter.REASON_ACCEPTED_ACTIONABLE, tamilRes.getReasonCode());
        assertTrue(tamilRes.isAccepted());

        // 2. Mixed Tamil-English payment message
        String tanglishPayment = "Exam fee pay பண்ணுங்க before Friday";
        FilterResult tanglishPayRes = NotificationFilter.evaluateRelevance("Finance", tanglishPayment);
        assertEquals(RelevanceDecision.ACCEPT, tanglishPayRes.getDecision());
        assertEquals(NotificationFilter.REASON_ACCEPTED_ACTIONABLE, tanglishPayRes.getReasonCode());
        assertTrue(tanglishPayRes.isAccepted());

        // 3. Mixed Tamil-English submission message
        String tanglishAssignment = "Assignment submit பண்ணுங்க tomorrow";
        FilterResult tanglishAssignRes = NotificationFilter.evaluateRelevance("Course", tanglishAssignment);
        assertEquals(RelevanceDecision.ACCEPT, tanglishAssignRes.getDecision());
        assertEquals(NotificationFilter.REASON_ACCEPTED_ACTIONABLE, tanglishAssignRes.getReasonCode());
        assertTrue(tanglishAssignRes.isAccepted());

        // 4. Unfamiliar package name with Tamil text must be accepted
        assertTrue(NotificationFilter.isEligible(
                "in.ac.tamilnaducollege.portal",
                OUR_APP_PKG,
                false,
                false,
                "தேர்வு அறிவிப்பு",
                "நாளை மாலை 5 மணிக்கு அறிக்கையை சமர்ப்பிக்கவும்"
        ));
    }

    @Test
    public void testScenario10_ExistingPrivacyAndConsentSafeguardsPreserved() {
        // 1. Highly sensitive message (financial OTP) is filtered as actionable, but privacy gate mandates consent
        String sensitiveFinancial = "Payment of examination fee requires authorization OTP is 482910 by tomorrow.";
        FilterResult filterRes = NotificationFilter.evaluateRelevance("Bank", sensitiveFinancial);
        assertTrue("Financial task with deadline is accepted by relevance filter", filterRes.isAccepted());

        // 2. Pass to PrivacyEngine: must detect OTP as HIGH sensitivity and require user approval
        PrivacyEngine engine = new PrivacyEngine();
        PrivacyAssessment assessment = engine.assessAndMask(sensitiveFinancial);

        assertEquals("Sensitive message must remain classified as HIGH sensitivity",
                SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue("High sensitivity message must require explicit user approval",
                assessment.requiresUserApproval());
        assertFalse("High sensitivity message cannot be auto-approved",
                PrivacyEngine.canAutoApprove(assessment));

        // 3. Verification of redaction: OTP must be masked and never stored in plain text
        assertFalse("Unredacted OTP secret must never appear in masked content",
                assessment.getMaskedContent().contains("482910"));
        assertTrue("Redaction tag must be present",
                assessment.getMaskedContent().contains("[REDACTED: OTP]"));
    }

    @Test
    public void testCorruptOrFilePathOrPlaceholderOnly_AreRejected() {
        // Punctuation / Corrupt only
        FilterResult punctRes = NotificationFilter.evaluateRelevance("???");
        assertEquals(RelevanceDecision.REJECT, punctRes.getDecision());
        assertEquals(NotificationFilter.REASON_REJECTED_EMPTY_OR_CORRUPT, punctRes.getReasonCode());

        // File path only
        FilterResult fileRes = NotificationFilter.evaluateRelevance("/storage/emulated/0/Download/document.pdf");
        assertEquals(RelevanceDecision.REJECT, fileRes.getDecision());
        assertEquals(NotificationFilter.REASON_REJECTED_FILE_PATH, fileRes.getReasonCode());

        // Redaction placeholder only
        FilterResult redactRes = NotificationFilter.evaluateRelevance("[REDACTED: OTP]");
        assertEquals(RelevanceDecision.REJECT, redactRes.getDecision());
        assertEquals(NotificationFilter.REASON_REJECTED_REDACTION_PLACEHOLDER, redactRes.getReasonCode());
    }
}
