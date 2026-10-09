package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.CustomerEntity
import com.radwan.raadpharmacy.data.LedgerEntryEntity
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudHandoverTest {
    @Test fun oldDemonstrationCredentialsAreNotAcceptedAsLiveAccounts() {
        assertFalse(CloudHandover.isLiveAccount("raad@raad-pharmacy.local"))
        assertTrue(CloudHandover.isLiveAccount("raad@raad-pharmacy-live.local"))
        assertFalse(CloudHandover.isLiveAccount(null))
    }

    @Test fun newLiveTenantStartsWithNoOldLedgerOrPendingUploads() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences("raad_handover_v33181", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val dao = PharmacyLedgerDatabase.get(app).dao()
        val customerId = "11111111-1111-4111-8111-111111111111"
        dao.insertCustomer(CustomerEntity(customerId, "تجربة", null, "", "", 0, "", 1000L))
        dao.insertEntry(LedgerEntryEntity("22222222-2222-4222-8222-222222222222",
            customerId, "DEBT", 1000, null, null, "", 1000L))
        CloudSyncJournal(app).markTransactionUpsert("22222222-2222-4222-8222-222222222222")
        CloudSyncJournal(app).markCustomerUpsert(customerId)
        val oldId = CloudDeviceStore(app).deviceId()

        CloudHandoverLocalReset.prepare(app)
        assertTrue(dao.getCustomers().isEmpty())
        assertTrue(dao.getEntries().isEmpty())
        assertTrue(CloudSyncJournal(app).snapshot().isEmpty)
        assertNotEquals(oldId, CloudDeviceStore(app).deviceId())

        CloudHandoverLocalReset.prepare(app)
        assertTrue(CloudSyncJournal(app).snapshot().isEmpty)
    }
}
