package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.CloudSyncJournal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerReleaseCleanupTest {
    @Test fun laterUpgradePreservesCustomersLedgerUsersSettingsAndPendingWrites() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("raad_release_33182", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("raad_cloud_auth", Context.MODE_PRIVATE)
            .edit().putBoolean("has_offline_session", true).commit()
        app.getSharedPreferences("raad_pharmacy_data", Context.MODE_PRIVATE)
            .edit().putString("customers", "[{\"name\":\"old\"}]").commit()
        val dao = PharmacyLedgerDatabase.get(app).dao()
        val customerId = "a2e13baf-3ecb-4d30-a099-7e13788e4052"
        dao.insertCustomer(CustomerEntity(customerId, "تجربة", null, "", "", 0, "", 1000L))
        dao.insertEntry(LedgerEntryEntity("fd12c987-af26-4aa5-93a0-1ce602d4fd40",customerId,
            "DEBT", 2000L, null, null, "", 1000L))
        // The financial transaction now persists its own pending upload atomically.

        LedgerReleaseCleanup.clearOnce(app)

        assertEquals(1, dao.customerCount())
        assertEquals(1, dao.entryCount())
        assertEquals(setOf(customerId), CloudSyncJournal(app).snapshot().customerUpserts)
        assertTrue(app.getSharedPreferences("raad_cloud_auth", Context.MODE_PRIVATE)
            .getBoolean("has_offline_session", false))
        LedgerReleaseCleanup.clearOnce(app)
        assertEquals(1, dao.customerCount())
    }
}
