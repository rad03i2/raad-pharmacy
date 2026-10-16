package com.radwan.raadpharmacy.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.UnknownHostException

class CloudLoginFeedbackTest {
    @Test fun rejectsCredentialsWithoutBlamingTheInternet() {
        val message = CloudLoginFeedback.forFailure(IllegalStateException("invalid_credentials"))
        assertEquals(true, message.contains("رفض خادم"))
        assertFalse(message.contains("الإنترنت"))
    }

    @Test fun distinguishesRateLimitAndServerNetworkFailure() {
        assertEquals(true, CloudLoginFeedback.forFailure(
            IllegalStateException("over_request_rate_limit")).contains("محاولات"))
        assertEquals(true, CloudLoginFeedback.forFailure(
            UnknownHostException("Host unavailable")).contains("خادم"))
    }

    @Test fun neverDisplaysRawPrivateServerMessages() {
        val message = CloudLoginFeedback.forFailure(
            IllegalStateException("request failed for customer@example.org private-token-value"))
        assertFalse(message.contains("customer@example.org"))
        assertFalse(message.contains("private-token-value"))
    }

    @Test fun displaysActionableTimeoutFeedback() {
        val message = CloudLoginFeedback.forFailure(
            java.net.SocketTimeoutException("Read timed out"))
        assertEquals(true, message.contains("مهلة"))
    }
}
