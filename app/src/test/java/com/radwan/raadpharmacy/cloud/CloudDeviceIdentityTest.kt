package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudDeviceIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() {
        context.getSharedPreferences("raad_cloud_device", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun switchingAccountsNeverUpdatesAnotherUsersDeviceAndReturningReusesItsOwnIdentity() {
        val first = CloudDeviceStore(context)
        first.savePushIdentity("raad", "pharmacy")
        val raadDevice = first.deviceId()
        val raadToken = first.pushTokenRowId()
        first.clearPushIdentity()
        val second = CloudDeviceStore(context)
        second.savePushIdentity("ahmed", "pharmacy")
        assertNotEquals(raadDevice, second.deviceId())
        assertNotEquals(raadToken, second.pushTokenRowId())
        second.clearPushIdentity()
        second.savePushIdentity("raad", "pharmacy")
        assertEquals(raadDevice, second.deviceId())
        assertEquals(raadToken, second.pushTokenRowId())
    }
    @Test fun upgradingAnInstallationWithAnUnknownLegacyDeviceOwnerDoesNotReuseThatRow() {
        val store = CloudDeviceStore(context)
        val legacyDevice = store.deviceId()
        store.markBootstrapped("pharmacy")
        store.savePushIdentity("ahmed", "pharmacy")
        assertNotEquals(legacyDevice, store.deviceId())
        assertTrue(store.isBootstrapped("pharmacy"))
        val id = store.deviceId()
        CloudDeviceStore(context).savePushIdentity("ahmed", "pharmacy")
        assertEquals(id, store.deviceId())
    }
}
