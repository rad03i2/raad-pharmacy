package com.radwan.raadpharmacy.shopping

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
class ShoppingComposerDraftTest {
    private val owner=ShoppingIdentity("fixture-owner","fixture-pharmacy")
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    @Test fun draftAndPendingPhotoSurviveReopenAndCannotLeakToAnotherAccount() {
        val root=File(context.noBackupFilesDir,"draft-${UUID.randomUUID()}")
        try {
            val store=ShoppingComposerDraftStore(root)
            val draft=ShoppingComposerDraft(owner.owner,owner.pharmacy,UUID.randomUUID().toString(),"علاج","علبتان","content://fixture/photo")
            store.save(draft)
            val reopened=ShoppingComposerDraftStore(root)
            assertEquals(draft,reopened.load(owner));assertNull(reopened.load(owner.copy(owner="other")))
            reopened.save(draft.copy(title="اسم أحدث",uri=null))
            assertEquals(draft.uri,reopened.load(owner)!!.uri)
            reopened.imported(owner,draft.id)
            assertNull(reopened.load(owner)!!.uri)
            reopened.clear("other-id");assertNotNull(reopened.load(owner))
            reopened.clear(draft.id);assertNull(reopened.load(owner))
        } finally {root.deleteRecursively()}
    }
    @Test fun aDamagedDraftCannotCrashPhotoComposerInitialization() {
        val root=File(context.noBackupFilesDir,"corrupt-draft-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val file=File(root,"composer.json")
            file.writeText("""{"owner":"fixture-owner","pharmacy":"fixture-pharmacy","id":"damaged-id"}""")
            assertNull(ShoppingComposerDraftStore(root).load(owner));assertTrue(file.exists())
            file.writeText("invalid json")
            assertNull(ShoppingComposerDraftStore(root).load(owner));assertTrue(file.exists())
        } finally {root.deleteRecursively()}
    }
    @Test fun galleryIsImageOnlyAndCancellationNeverImportsAnImage() {
        val contract=ShoppingGalleryContract();val intent=contract.createIntent(context,Unit)
        assertEquals("image/*",intent.type)
        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
        if(Build.VERSION.SDK_INT<30) assertEquals(Intent.ACTION_GET_CONTENT,intent.action)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED,Intent().setData(Uri.parse("content://fixture/a"))))
        assertNull(contract.parseResult(Activity.RESULT_OK,null))
        assertEquals(Uri.parse("content://fixture/a"),contract.parseResult(Activity.RESULT_OK,Intent().setData(Uri.parse("content://fixture/a"))))
    }
    @Test fun areaQueueIsAccountScopedAndExplicitSameNameRequestsCanMoveToTheCurrentAccount() {
        val root=File(context.noBackupFilesDir,"area-scope-${UUID.randomUUID()}")
        val first=ShoppingIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString())
        val peer=first.copy(owner=UUID.randomUUID().toString())
        val db=ShoppingLocalStore(context,root)
        try {
            db.addArea(first,"الكرادة");assertTrue(db.areas(peer).isEmpty());assertTrue(db.areas(peer,true).isEmpty())
            db.addArea(peer,"الكرادة");assertEquals(listOf("الكرادة"),db.areas(peer,true));assertTrue(db.areas(first,true).isEmpty())
            db.acknowledgeArea(first,"الكرادة",AreaReceipt(first.owner,first.pharmacy,"الكرادة"))
            assertEquals(listOf("الكرادة"),db.areas(peer,true))
            db.merge(peer,ShoppingSnapshot(peer.owner,peer.pharmacy,emptyList(),areas=listOf("الكرادة")))
            assertTrue(db.areas(peer,true).isEmpty());assertEquals(listOf("الكرادة"),db.areas(first))
            assertTrue(db.areas(peer.copy(pharmacy=UUID.randomUUID().toString())).isEmpty())
        } finally {db.close();root.deleteRecursively()}
    }
    @Test fun v25MetadataDatabaseUpgradesWithoutLosingPendingMaterials() {
        val root=File(context.noBackupFilesDir,"upgrade-${UUID.randomUUID()}")
        val id=ShoppingIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString())
        try {
            var db=ShoppingLocalStore(context,root)
            try {
                db.add(id,ShoppingItem(UUID.randomUUID().toString(),"علاج محفوظ"))
                db.writableDatabase.execSQL("DROP TABLE areas");db.writableDatabase.version=2
            } finally {db.close()}
            db=ShoppingLocalStore(context,root)
            try {
                assertEquals("علاج محفوظ",db.list(id).single().title)
                db.addArea(id,"المنصور");assertEquals(listOf("المنصور"),db.areas(id,true))
                assertThrows(IllegalStateException::class.java) {db.acknowledgeArea(id,"المنصور",AreaReceipt("other",id.pharmacy,"المنصور"))}
                assertEquals(1,db.areas(id,true).size)
                db.acknowledgeArea(id,"المنصور",AreaReceipt(id.owner,id.pharmacy,"المنصور"));assertTrue(db.areas(id,true).isEmpty())
            } finally {db.close()}
        } finally {root.deleteRecursively()}
    }
}
