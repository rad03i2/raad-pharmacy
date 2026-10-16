package com.radwan.raadpharmacy.cloud

import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class CloudConnectionDiagnosticsTest {
    @Test fun dnsTlsAndConnectionFailuresHaveDifferentCodes() {
        assertEquals(CloudFailureCode.DNS, CloudConnectionDiagnostics.classify(UnknownHostException()).code)
        assertEquals(CloudFailureCode.TLS, CloudConnectionDiagnostics.classify(SSLHandshakeException("fixture")).code)
        assertEquals(CloudFailureCode.CONNECT, CloudConnectionDiagnostics.classify(ConnectException()).code)
    }
    @Test fun sdkWrappingDoesNotHideAFixedTransportMarkerOrExposeExceptionContents() {
        val error = IllegalStateException("SSLHandshakeException secret-token fixture@example.invalid https://private/path")
        val detail = CloudConnectionDiagnostics.classify(error)
        assertEquals(CloudFailureCode.TLS, detail.code)
        assertFalse(detail.toString().contains("secret-token"))
        assertFalse(detail.toString().contains("fixture@example.invalid"))
        assertFalse(detail.toString().contains("private/path"))
    }
    @Test fun recoveredCoreChannelsDoNotGetResetByAnAlreadyScheduledRetry() {
        val health = CloudRealtimeHealth()
        assertTrue(health.needsRecovery(true, true))
        health.ledger.set(true); health.messages.set(true)
        assertFalse(health.needsRecovery(true, true))
        health.messages.set(false); assertTrue(health.needsRecovery(true, true))
    }
    @Test fun aMessagesOnlyListenerDoesNotRequireAnInactiveLedgerSubscription() {
        val health = CloudRealtimeHealth(); health.messages.set(true)
        assertFalse(health.needsRecovery(false, true))
        health.reset(); assertTrue(health.needsRecovery(false, true))
    }
}
