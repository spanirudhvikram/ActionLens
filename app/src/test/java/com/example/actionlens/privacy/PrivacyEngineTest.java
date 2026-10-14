package com.example.actionlens.privacy;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying the privacy detection, sensitivity rating,
 * and masking engine for ActionLens.
 */
public class PrivacyEngineTest {

    private PrivacyEngine privacyEngine;

    @Before
    public void setUp() {
        privacyEngine = new PrivacyEngine();
    }

    // =========================================================================
    // 1. Email Detection & Masking Tests
    // =========================================================================

    @Test
    public void testEmailMasking_SingleEmail() {
        String input = "Please send the report to analyst.smith@company.org by tomorrow morning.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertNotNull(assessment);
        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.getDetectedTypes().contains(PiiType.EMAIL));

        // Verify masking
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: EMAIL]"));
        assertFalse(assessment.getMaskedContent().contains("analyst.smith@company.org"));
        assertEquals("Please send the report to [REDACTED: EMAIL] by tomorrow morning.", assessment.getMaskedContent());
    }

    @Test
    public void testEmailMasking_MultipleEmails() {
        String input = "CC alice@domain.com and bob.jones@sub.domain.co.uk on the project review.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.getMaskedContent().contains("CC [REDACTED: EMAIL] and [REDACTED: EMAIL] on the project review."));
        assertFalse(assessment.getMaskedContent().contains("alice@domain.com"));
        assertFalse(assessment.getMaskedContent().contains("bob.jones@sub.domain.co.uk"));
    }

    // =========================================================================
    // 2. Phone Number Detection & Masking Tests
    // =========================================================================

    @Test
    public void testPhoneMasking_USAndInternationalFormats() {
        String input1 = "Call the client at +1-555-432-1098 regarding the deadline.";
        PrivacyAssessment assessment1 = privacyEngine.assessAndMask(input1);

        assertEquals(SensitivityLevel.MEDIUM, assessment1.getSensitivityLevel());
        assertTrue(assessment1.getDetectedTypes().contains(PiiType.PHONE));
        assertTrue(assessment1.getMaskedContent().contains("[REDACTED: PHONE]"));
        assertFalse(assessment1.getMaskedContent().contains("555-432-1098"));

        String input2 = "Urgent: contact support at (800) 555-0199 immediately.";
        PrivacyAssessment assessment2 = privacyEngine.assessAndMask(input2);
        assertTrue(assessment2.getMaskedContent().contains("[REDACTED: PHONE]"));
        assertFalse(assessment2.getMaskedContent().contains("555-0199"));
    }

    @Test
    public void testPhoneMasking_Indian10DigitFormat() {
        String input = "Reach out to advisor on +91 9876543210 before 5 PM.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: PHONE]"));
        assertFalse(assessment.getMaskedContent().contains("9876543210"));
    }

    @Test
    public void testPhoneMasking_DoesNotRedactStandardDates() {
        String input = "Submit the assignment on 2026-10-15 before 18:00.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        // Date should not be classified as a phone number
        assertEquals(SensitivityLevel.LOW, assessment.getSensitivityLevel());
        assertFalse(assessment.requiresUserApproval());
        assertEquals("Submit the assignment on 2026-10-15 before 18:00.", assessment.getMaskedContent());
    }

    // =========================================================================
    // 3. OTP & Verification Code Tests
    // =========================================================================

    @Test
    public void testOtpMasking_VariousKeywords() {
        String input1 = "Your OTP is 482910. Do not share it with anyone.";
        PrivacyAssessment assessment1 = privacyEngine.assessAndMask(input1);

        assertEquals(SensitivityLevel.HIGH, assessment1.getSensitivityLevel());
        assertTrue(assessment1.requiresUserApproval());
        assertTrue(assessment1.getDetectedTypes().contains(PiiType.OTP));
        assertTrue(assessment1.getMaskedContent().contains("[REDACTED: OTP]"));
        assertFalse(assessment1.getMaskedContent().contains("482910"));

        String input2 = "Use verification code: 938201 for logging in.";
        PrivacyAssessment assessment2 = privacyEngine.assessAndMask(input2);
        assertEquals(SensitivityLevel.HIGH, assessment2.getSensitivityLevel());
        assertTrue(assessment2.getMaskedContent().contains("[REDACTED: OTP]"));
        assertFalse(assessment2.getMaskedContent().contains("938201"));

        String input3 = "Security passcode 748392 valid for 10 minutes.";
        PrivacyAssessment assessment3 = privacyEngine.assessAndMask(input3);
        assertEquals(SensitivityLevel.HIGH, assessment3.getSensitivityLevel());
        assertTrue(assessment3.getMaskedContent().contains("[REDACTED: OTP]"));
        assertFalse(assessment3.getMaskedContent().contains("748392"));
    }

    // =========================================================================
    // 4. Password & PIN Tests
    // =========================================================================

    @Test
    public void testPasswordMasking_Credentials() {
        String input1 = "Your temporary password is SecretPass#2026. Please change upon first login.";
        PrivacyAssessment assessment1 = privacyEngine.assessAndMask(input1);

        assertEquals(SensitivityLevel.HIGH, assessment1.getSensitivityLevel());
        assertTrue(assessment1.requiresUserApproval());
        assertTrue(assessment1.getDetectedTypes().contains(PiiType.PASSWORD));
        assertTrue(assessment1.getMaskedContent().contains("[REDACTED: PASSWORD]"));
        assertFalse(assessment1.getMaskedContent().contains("SecretPass#2026"));

        String input2 = "Database credentials: pwd: dbSuperSecret_99 on server.";
        PrivacyAssessment assessment2 = privacyEngine.assessAndMask(input2);
        assertEquals(SensitivityLevel.HIGH, assessment2.getSensitivityLevel());
        assertTrue(assessment2.getMaskedContent().contains("[REDACTED: PASSWORD]"));
        assertFalse(assessment2.getMaskedContent().contains("dbSuperSecret_99"));

        String input3 = "Your login pin is 4892. Keep it confidential.";
        PrivacyAssessment assessment3 = privacyEngine.assessAndMask(input3);
        assertEquals(SensitivityLevel.HIGH, assessment3.getSensitivityLevel());
        assertTrue(assessment3.getMaskedContent().contains("[REDACTED: PASSWORD]"));
        assertFalse(assessment3.getMaskedContent().contains("4892"));
    }

    // =========================================================================
    // 5. Financial Identifiers Tests
    // =========================================================================

    @Test
    public void testFinancialMasking_ValidCardNumber() {
        // Valid Luhn test card: 4012888888881881 (Visa test format)
        String input = "Payment card ending 4012-8888-8888-1881 requires re-authorization.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.getDetectedTypes().contains(PiiType.FINANCIAL));
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: CARD]"));
        assertFalse(assessment.getMaskedContent().contains("4012-8888-8888-1881"));
    }

    @Test
    public void testFinancialMasking_CvvAndBankAccount() {
        String input1 = "Authorize payment with cvv: 789 before expiration.";
        PrivacyAssessment assessment1 = privacyEngine.assessAndMask(input1);

        assertEquals(SensitivityLevel.HIGH, assessment1.getSensitivityLevel());
        assertTrue(assessment1.getMaskedContent().contains("[REDACTED: CVV]"));
        assertFalse(assessment1.getMaskedContent().contains("789"));

        String input2 = "Transfer funds to account no: 987654321012 by Friday.";
        PrivacyAssessment assessment2 = privacyEngine.assessAndMask(input2);

        assertEquals(SensitivityLevel.HIGH, assessment2.getSensitivityLevel());
        assertTrue(assessment2.getMaskedContent().contains("[REDACTED: ACCOUNT]"));
        assertFalse(assessment2.getMaskedContent().contains("987654321012"));
    }

    @Test
    public void testFinancialMasking_UpiId() {
        String input = "Pay registration fee to organizer@okaxis by 4 PM.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: UPI]"));
        assertFalse(assessment.getMaskedContent().contains("organizer@okaxis"));
    }

    // =========================================================================
    // 6. Sensitivity Classification (LOW, MEDIUM, HIGH) Tests
    // =========================================================================

    @Test
    public void testClassification_LowSensitivityTask() {
        String input = "Complete Chapter 4 reading and prepare slides for Friday seminar.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.LOW, assessment.getSensitivityLevel());
        assertFalse(assessment.requiresUserApproval());
        assertFalse(assessment.hasRedactions());
        assertEquals(input, assessment.getMaskedContent());
    }

    @Test
    public void testClassification_MediumSensitivityTask() {
        String input = "Call project coordinator at 555-123-4567 to confirm schedule.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.MEDIUM, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.hasRedactions());
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: PHONE]"));
    }

    @Test
    public void testClassification_HighSensitivityTask() {
        String input = "Action Required: Enter verification code 492011 to approve transaction.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertTrue(assessment.hasRedactions());
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: OTP]"));
    }

    // =========================================================================
    // 7. Edge Cases & Data Minimization
    // =========================================================================

    @Test
    public void testEdgeCase_NullAndEmptyInputs() {
        PrivacyAssessment nullAssessment = privacyEngine.assessAndMask(null);
        assertEquals(SensitivityLevel.LOW, nullAssessment.getSensitivityLevel());
        assertFalse(nullAssessment.requiresUserApproval());
        assertEquals("", nullAssessment.getMaskedContent());

        PrivacyAssessment emptyAssessment = privacyEngine.assessAndMask("   ");
        assertEquals(SensitivityLevel.LOW, emptyAssessment.getSensitivityLevel());
        assertFalse(emptyAssessment.requiresUserApproval());
        assertEquals("", emptyAssessment.getMaskedContent());
    }

    @Test
    public void testMixedSensitiveContent_CombinedMasking() {
        String input = "Contact admin@corp.com with OTP: 739201 to reset password: MySecret#99 now.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        // Should classify as HIGH due to OTP and Password
        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());

        String masked = assessment.getMaskedContent();
        assertTrue(masked.contains("[REDACTED: EMAIL]"));
        assertTrue(masked.contains("[REDACTED: OTP]"));
        assertTrue(masked.contains("[REDACTED: PASSWORD]"));

        // Guarantee raw secrets are never in the masked content
        assertFalse(masked.contains("admin@corp.com"));
        assertFalse(masked.contains("739201"));
        assertFalse(masked.contains("MySecret#99"));
    }

    // =========================================================================
    // 8. Phase B Verification — Focused Gap & Edge Case Tests
    // =========================================================================

    @Test
    public void testOverlappingMatches_NestedAndAdjacentDetections() {
        // Email starting with a 10-digit number that could also trigger a phone pattern
        String input = "Contact user 9876543210@domain.org regarding the renewal.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        // Entire email must be cleanly masked, leaving no cleartext email fragments
        assertTrue(assessment.getMaskedContent().contains("[REDACTED: EMAIL]"));
        assertFalse(assessment.getMaskedContent().contains("domain.org"));
        assertFalse(assessment.getMaskedContent().contains("9876543210"));
        assertEquals("Contact user [REDACTED: EMAIL] regarding the renewal.", assessment.getMaskedContent());
    }

    @Test
    public void testOtpAndPassword_WithColonAndSpaceVariants() {
        String input1 = "Your OTP is: 849201. Please use it now.";
        PrivacyAssessment assessment1 = privacyEngine.assessAndMask(input1);
        assertEquals(SensitivityLevel.HIGH, assessment1.getSensitivityLevel());
        assertTrue(assessment1.getMaskedContent().contains("[REDACTED: OTP]"));
        assertFalse(assessment1.getMaskedContent().contains("849201"));

        String input2 = "Your password is: Super#Secret_123! for the demo account.";
        PrivacyAssessment assessment2 = privacyEngine.assessAndMask(input2);
        assertEquals(SensitivityLevel.HIGH, assessment2.getSensitivityLevel());
        assertTrue(assessment2.getMaskedContent().contains("[REDACTED: PASSWORD]"));
        assertFalse(assessment2.getMaskedContent().contains("Super#Secret_123!"));
    }

    @Test
    public void testFalsePositives_NormalTextAndTimes() {
        String input = "Sprint standup at 10:30 AM in Room 302 with 8 members on 2026-11-04.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.LOW, assessment.getSensitivityLevel());
        assertFalse(assessment.requiresUserApproval());
        assertEquals(0, assessment.getRedactedItemCount());
        assertEquals(input, assessment.getMaskedContent());
    }

    @Test
    public void testLuhnInvalidCard_NotRedactedAsCard() {
        // 16-digit sequence that fails Luhn checksum
        String input = "Tracking serial 1111-2222-3333-4445 for the shipped package.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        // Should not be flagged as financial card
        assertFalse(assessment.getDetectedTypes().contains(PiiType.FINANCIAL));
    }

    @Test
    public void testAutoApprovePolicy_StrictEnforcement() {
        PrivacyAssessment lowAssessment = privacyEngine.assessAndMask("Read book chapters 1 to 3.");
        assertTrue(PrivacyEngine.canAutoApprove(lowAssessment));

        PrivacyAssessment medAssessment = privacyEngine.assessAndMask("Call 555-123-4567 today.");
        assertFalse(PrivacyEngine.canAutoApprove(medAssessment));

        PrivacyAssessment highAssessment = privacyEngine.assessAndMask("Your OTP is 123456.");
        assertFalse(PrivacyEngine.canAutoApprove(highAssessment));

        assertFalse(PrivacyEngine.canAutoApprove(null));
    }

    @Test
    public void testMultipleSensitiveValues_FiveTypesInOneText() {
        String input = "Email alice@test.com, phone 555-987-6543, OTP is 482019, password: Pwd#1, card 4012888888881881.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        assertEquals(SensitivityLevel.HIGH, assessment.getSensitivityLevel());
        assertTrue(assessment.requiresUserApproval());
        assertEquals(5, assessment.getDetectedTypes().size());

        String masked = assessment.getMaskedContent();
        assertTrue(masked.contains("[REDACTED: EMAIL]"));
        assertTrue(masked.contains("[REDACTED: PHONE]"));
        assertTrue(masked.contains("[REDACTED: OTP]"));
        assertTrue(masked.contains("[REDACTED: PASSWORD]"));
        assertTrue(masked.contains("[REDACTED: CARD]"));

        assertFalse(masked.contains("alice@test.com"));
        assertFalse(masked.contains("555-987-6543"));
        assertFalse(masked.contains("482019"));
        assertFalse(masked.contains("Pwd#1"));
        assertFalse(masked.contains("4012888888881881"));
    }

    @Test
    public void testDataMinimization_ZeroRawSensitiveDataExposed() {
        String sensitiveWord = "UltraSecretToken9988";
        String input = "Your secret password: " + sensitiveWord + " expires soon.";
        PrivacyAssessment assessment = privacyEngine.assessAndMask(input);

        // Verify masked content does not contain secret
        assertFalse(assessment.getMaskedContent().contains(sensitiveWord));
        // Verify rationale does not contain secret
        assertFalse(assessment.getRationale().contains(sensitiveWord));
    }
}
