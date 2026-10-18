package com.radwan.raadpharmacy.shopping

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class ShoppingRecoveryTest {
    private val owner=ShoppingIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString())
    private val row=ShoppingItem(UUID.randomUUID().toString(),"دواء","علبتان",true,1000,"رعد")
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var local: ShoppingLocalStore
    @Before fun open() {root=File(context.noBackupFilesDir,"recover-${UUID.randomUUID()}");local=ShoppingLocalStore(context,root)}
    @After fun close() {local.close();root.deleteRecursively()}
    @Test fun offlinePurchaseAndRestoreSurviveReopenWithoutDroppingPayloadOrChangingId() {
        val now=System.currentTimeMillis()
        local.add(owner,row);local.purchase(owner,row.id,now)
        local.close();local=ShoppingLocalStore(context,root)
        assertTrue(local.list(owner).isEmpty());assertTrue(local.pending(owner).isEmpty())
        assertEquals(row.id,local.trash(owner).single().item.id)
        assertTrue(row.id in local.activePhotoIds(owner.pharmacy))
        local.restore(owner,row.id,now+1000)
        assertTrue(local.trash(owner).isEmpty());assertEquals(row.id,local.list(owner).single().id)
        assertEquals(listOf("TRASH","RESTORE"),local.changes(owner).map {it.action})
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,emptyList()))
        assertEquals(row.id,local.list(owner).single().id) // stale snapshot cannot remove a queued restore
    }
    @Test fun mutationReceiptMustMatchExactOperationAndScope() {
        local.add(owner,row);local.purchase(owner,row.id)
        val op=local.changes(owner).single()
        assertThrows(IllegalStateException::class.java) {local.acknowledgeChange(owner,op,ShoppingChangeReceipt(owner.owner,owner.pharmacy,row.id,UUID.randomUUID().toString(),false,true))}
        assertEquals(1,local.changes(owner).size)
        local.acknowledgeChange(owner,op,ShoppingChangeReceipt(owner.owner,owner.pharmacy,row.id,op.operation,false,true))
        assertTrue(local.changes(owner).isEmpty())
    }
    @Test fun dayExpiryRemovesRecoverableContentAndPhotoButKeepsOpaqueServerDelete() {
        val now=System.currentTimeMillis()
        local.add(owner,row);local.purchase(owner,row.id,now)
        local.expire(now+86_400_000)
        assertTrue(local.trash(owner,now+86_400_000).isEmpty())
        assertFalse(row.id in local.activePhotoIds(owner.pharmacy))
        val op=local.changes(owner).single()
        assertEquals("مادة",op.item.title);assertEquals("",op.item.note);assertFalse(op.item.hasPhoto)
        assertThrows(IllegalStateException::class.java) {local.restore(owner,row.id,now+86_400_001)}
    }
    @Test fun remoteTrashKeepsOriginalDevicePhotoUntilExpiryAndThenCleansIt() {
        val photos=ShoppingPhotos(context,root)
        photos.file(owner.pharmacy,row.id).apply {parentFile!!.mkdirs();writeText("private")}
        local.merge(owner,ShoppingSnapshot(owner.owner,owner.pharmacy,emptyList(),listOf(ShoppingTrash(row,UUID.randomUUID().toString(),System.currentTimeMillis()+1000))))
        photos.reconcile(local);assertTrue(photos.file(owner.pharmacy,row.id).exists())
        local.expire(System.currentTimeMillis()+2000);photos.reconcile(local)
        assertFalse(photos.file(owner.pharmacy,row.id).exists())
    }
    @Test fun versionOneUpgradePreservesUnsentLegacyCreateAndPermanentDelete() {
        local.close();File(root,"shopping.db").delete()
        SQLiteDatabase.openOrCreateDatabase(File(root,"shopping.db"),null).use {db ->
            db.execSQL("CREATE TABLE items(pharmacy TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,title TEXT NOT NULL,note TEXT NOT NULL,photo INTEGER NOT NULL,created_at INTEGER NOT NULL,created_by TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('CREATE','SYNCED','DELETE')),PRIMARY KEY(pharmacy,id))")
            db.execSQL("INSERT INTO items VALUES(?,?,?,?,?,0,1,'رعد','CREATE')",arrayOf(owner.pharmacy,row.id,owner.owner,"مادة",""));db.version=1
        }
        local=ShoppingLocalStore(context,root)
        assertEquals("مادة",local.pending(owner).single().item.title)
        assertTrue(local.trash(owner).isEmpty());assertTrue(local.changes(owner).isEmpty())
    }
}
