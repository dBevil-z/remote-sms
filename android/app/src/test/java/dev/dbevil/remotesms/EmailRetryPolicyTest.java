package dev.dbevil.remotesms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EmailRetryPolicyTest {
    @Test
    public void retryDelayBacksOffButKeepsMessagesQueued() {
        assertEquals(0L, EmailRetryPolicy.nextDelayMs(0));
        assertEquals(60_000L, EmailRetryPolicy.nextDelayMs(1));
        assertEquals(5 * 60_000L, EmailRetryPolicy.nextDelayMs(2));
        assertEquals(60 * 60_000L, EmailRetryPolicy.nextDelayMs(20));
    }

    @Test
    public void lowBatteryAlertOnlyFiresOnceUntilRecovery() {
        assertTrue(EmailRetryPolicy.shouldSendLowBatteryAlert(19, false, false));
        assertFalse(EmailRetryPolicy.shouldSendLowBatteryAlert(19, false, true));
        assertFalse(EmailRetryPolicy.shouldSendLowBatteryAlert(20, false, false));
        assertFalse(EmailRetryPolicy.shouldSendLowBatteryAlert(10, true, false));
        assertTrue(EmailRetryPolicy.shouldResetLowBatteryAlert(25, false));
        assertTrue(EmailRetryPolicy.shouldResetLowBatteryAlert(18, true));
        assertFalse(EmailRetryPolicy.shouldResetLowBatteryAlert(19, false));
    }
}
