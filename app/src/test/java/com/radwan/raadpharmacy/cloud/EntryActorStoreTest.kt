package com.radwan.raadpharmacy.cloud

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntryActorStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun creatorAndTeamNamesArePersistedIndependentlyOfLedgerAndRemainStableOnEdit() {
        val store = EntryActorStore.get(app)
        val entryId = "entry-actor-store-original-01"
        val originalCreator = "actor-user-raad-001"
        val laterEditor = "actor-user-ahmed-002"
        store.rememberActor(entryId, originalCreator)
        store.rememberProfiles(
            listOf(
                CloudTeamProfileRow(originalCreator, "pharmacy-test", "رعد", "MANAGER"),
                CloudTeamProfileRow(laterEditor, "pharmacy-test", "أحمد", "MANAGER")
            )
        )
        assertEquals(originalCreator, store.authorIds.value[entryId])
        assertEquals("رعد", store.displayNames.value[originalCreator])
        store.rememberActors(
            listOf(CloudTransactionRow(
                id = entryId,
                pharmacyId = "pharmacy-test",
                customerId = "customer-01",
                operationId = entryId,
                type = "DEBT",
                amount = 5000.0,
                createdBy = originalCreator,
                updatedBy = laterEditor,
                occurredAt = "2026-10-09T00:00:00Z",
                createdAt = "2026-10-09T00:00:00Z",
                updatedAt = "2026-10-09T00:00:00Z"
            ))
        )
        assertEquals(originalCreator, store.authorIds.value[entryId])
        assertEquals(originalCreator,
            app.getSharedPreferences("raad_entry_actor_attribution_v1", Context.MODE_PRIVATE)
                .getString("actor:$entryId", null))
        assertNull(store.authorIds.value["entry-that-has-no-creator"])
    }
}
