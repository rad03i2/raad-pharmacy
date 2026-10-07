package com.radwan.raadpharmacy.cloud

import org.junit.Assert.*
import org.junit.Test

class DirectedNotificationTest {
    @Test fun directedAlertIsAcceptedOnlyForRecipient() {
        val alert = CloudNotificationEventRow("event","pharmacy",actorUserId="sender",eventType="TEAM_ALERT",
            recipientUserId="recipient",createdAt="2026-10-07T00:00:00Z")
        assertTrue(alert.isAddressedTo("recipient"))
        assertFalse(alert.isAddressedTo("sender"))
        assertFalse(alert.isAddressedTo("third"))
        assertFalse(alert.copy(recipientUserId=null).isAddressedTo("recipient"))
    }
    @Test fun financialEventsRemainShared() {
        val alert = CloudNotificationEventRow("event","pharmacy",eventType="DEBT_CREATED",createdAt="2026-10-07T00:00:00Z")
        assertTrue(alert.isAddressedTo("recipient")); assertTrue(alert.isAddressedTo("third"))
    }
}
