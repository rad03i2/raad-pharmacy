package com.radwan.raadpharmacy.shopping

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class ShoppingLocalStoreTest {
    private val owner=ShoppingIdentity("00000000-0000-4000-8000-000000000011","00000000-0000-4000-8000-000000000001")
    private val item=ShoppingItem("00000000-0000-4000-8000-000000000071","علاج", "علبتان",true,1000,"رعد")
    private lateinit var local: ShoppingLocalStore
    private lateinit var root: File
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    @Before fun open() { root=File(context.noBackupFilesDir,"shopping-test-${UUID.randomUUID()}");local=ShoppingLocalStore(context,root) }
    @After fun close() {local.close();root.deleteRecursively()}
    private fun ack(row: ShoppingItem=item,active: Boolean=true,deleting: Boolean=false)=local.acknowledge(owner,
        ShoppingOperation(row,deleting),ShoppingReceipt(owner.owner,owner.pharmacy,row.id,active))
    @Test fun pendingAdditionAndDeletionSurviveProcessReopen() {
        local.add(owner,item);local.close();local=ShoppingLocalStore(context,root)
        assertEquals(1,local.pending(owner).size);assertEquals("علاج",local.list(owner).single().title)
        local.complete(owner,item.id);local.close();local=ShoppingLocalStore(context,root)
        assertTrue(local.list(owner).isEmpty());assertTrue(local.pending(owner).single().deleting)
        assertEquals("",local.pending(owner).single().item.title);assertEquals("",local.pending(owner).single().item.note)
        assertFalse(local.pending(owner).single().item.hasPhoto)
    }
    @Test fun purchaseWinsOverLateCreateAcknowledgementAndStaleSnapshot() {
        local.add(owner,item);local.complete(owner,item.id);ack()
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,listOf(item)))
        assertTrue(local.list(owner).isEmpty());assertTrue(local.pending(owner).single().deleting)
        ack(active=false,deleting=true);assertTrue(local.pending(owner).isEmpty())
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,emptyList()))
        assertTrue(local.list(owner).isEmpty())
    }
    @Test fun onlyExactServerReceiptCanAcknowledgePendingWork() {
        local.add(owner,item)
        listOf(ShoppingReceipt("other",owner.pharmacy,item.id,true),ShoppingReceipt(owner.owner,UUID.randomUUID().toString(),item.id,true),
            ShoppingReceipt(owner.owner,owner.pharmacy,UUID.randomUUID().toString(),true)).forEach { receipt ->
            assertThrows(IllegalStateException::class.java) {local.acknowledge(owner,ShoppingOperation(item,false),receipt)}
            assertEquals(1,local.pending(owner).size)
        }
        ack();assertTrue(local.pending(owner).isEmpty())
        local.complete(owner,item.id)
        assertThrows(IllegalStateException::class.java) {ack(active=true,deleting=true)}
        assertEquals(1,local.pending(owner).size)
    }
    @Test fun fullSnapshotRemovesOnlySyncedRowsAndKeepsAllUnboughtOfflineAdditions() {
        local.add(owner,item);ack()
        val missing=item.copy(id=UUID.randomUUID().toString(),title="دواء غير متوفر",createdAt=2000)
        local.add(owner,missing)
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,emptyList()))
        assertEquals(missing.id,local.list(owner).single().id)
        assertEquals(missing.id,local.pending(owner).single().item.id)
    }
    @Test fun tenantAndAccountScopesNeverSendAnotherOwnersPendingOperations() {
        local.add(owner,item)
        val peer=owner.copy(owner=UUID.randomUUID().toString())
        assertTrue(local.pending(peer).isEmpty());assertTrue(local.list(peer).isEmpty())
        assertTrue(local.list(owner.copy(pharmacy=UUID.randomUUID().toString())).isEmpty())
        ack();assertEquals(1,local.list(peer).size)
    }
    @Test fun invalidOrForeignSnapshotCannotPartiallyReplaceTheList() {
        local.add(owner,item);ack()
        assertThrows(IllegalStateException::class.java) {local.merge(owner,ShoppingSnapshot("foreign",owner.pharmacy,emptyList()))}
        assertThrows(IllegalArgumentException::class.java) {local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,listOf(item.copy(id="invalid"))))}
        assertThrows(IllegalArgumentException::class.java) {local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,listOf(item,item)))}
        assertEquals(item.id,local.list(owner).single().id)
    }
    @Test fun damagedShoppingDatabaseIsPreservedInsteadOfBeingSilentlyDeleted() {
        local.close()
        val file=File(root,"shopping.db");val original="damaged fixture database; preserve unsent data".toByteArray()
        file.writeBytes(original);local=ShoppingLocalStore(context,root)
        assertThrows(android.database.sqlite.SQLiteException::class.java) {local.list(owner)}
        assertArrayEquals(original,file.readBytes())
    }
    @Test fun moreThanThousandMaterialsAndNewWeekPreserveActualRowsWithoutReset() {
        val rows=(1..1200).map { item.copy(id=UUID.randomUUID().toString(),title="مادة $it",createdAt=it.toLong()) }
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,rows))
        local.add(owner,item.copy(createdAt=10L*24*60*60*1000))
        assertEquals(1201,local.list(owner).size);assertEquals(item.id,local.list(owner).last().id)
        local.complete(owner,rows[600].id)
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,rows))
        assertEquals(1200,local.list(owner).size);assertFalse(local.list(owner).any {it.id==rows[600].id})
    }
}
