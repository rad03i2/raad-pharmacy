package com.radwan.raadpharmacy.cloud

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudMessagingFeedbackTest {
    @Test fun offlineIsBasedOnNetworkState() {
        assertEquals(CloudMessagingFeedback.Kind.OFFLINE, CloudMessagingFeedback.kind(IOException(), false))
        assertEquals(CloudMessagingFeedback.Kind.SERVER, CloudMessagingFeedback.kind(IOException(), true))
    }
    @Test fun serverRejectionsAreNotInternetErrors() {
        for ((message, expected) in mapOf(
            "ALERT_DEVICE_INVALID 42501" to CloudMessagingFeedback.Kind.DEVICE,
            "MESSAGE_RATE_LIMIT" to CloudMessagingFeedback.Kind.RATE,
            "HTTP 401 JWT expired" to CloudMessagingFeedback.Kind.SESSION,
            "MESSAGE_RECIPIENT_INVALID 403" to CloudMessagingFeedback.Kind.PERMISSION,
            "unknown secret text" to CloudMessagingFeedback.Kind.UNKNOWN
        )) assertEquals(expected, CloudMessagingFeedback.kind(IllegalStateException(message)))
        assertEquals(CloudMessagingFeedback.Kind.TIMEOUT, CloudMessagingFeedback.kind(SocketTimeoutException()))
    }
}
