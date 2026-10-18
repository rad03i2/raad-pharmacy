package com.radwan.raadpharmacy.shopping

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
internal data class ShoppingItem(val id: String, val title: String, val note: String = "", val hasPhoto: Boolean = false,
    val createdAt: Long = 0, val createdBy: String = "", val pending: Boolean = false)
@Serializable
internal data class ShoppingSnapshot(val owner: String, val pharmacy: String, val items: List<ShoppingItem>)
@Serializable
internal data class ShoppingReceipt(val owner: String, val pharmacy: String, val id: String, val active: Boolean)
internal data class ShoppingIdentity(val owner: String, val pharmacy: String)
internal data class ShoppingOperation(val item: ShoppingItem, val deleting: Boolean)

/** A separate database, excluded from Android backup. Never opens or migrates the financial Room database. */
internal class ShoppingLocalStore(context: Context, directory: File = File(context.noBackupFilesDir,"medicine-shopping")) :
    SQLiteOpenHelper(context, File(directory.apply { mkdirs() },"shopping.db").absolutePath, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA secure_delete=ON") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE items(pharmacy TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,title TEXT NOT NULL,
            note TEXT NOT NULL,photo INTEGER NOT NULL,created_at INTEGER NOT NULL,created_by TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('CREATE','SYNCED','DELETE')),PRIMARY KEY(pharmacy,id))""")
        db.execSQL("CREATE INDEX items_scope ON items(pharmacy,state,created_at,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported shopping database version")
    private fun values(identity: ShoppingIdentity, item: ShoppingItem, state: String) = ContentValues().apply {
        put("pharmacy",identity.pharmacy); put("id",item.id); put("owner",identity.owner); put("title",item.title)
        put("note",item.note); put("photo",if(item.hasPhoto) 1 else 0); put("created_at",item.createdAt)
        put("created_by",item.createdBy); put("state",state)
    }
    private fun query(pharmacy: String): List<Triple<ShoppingItem,String,String>> = readableDatabase.rawQuery(
        "SELECT id,title,note,photo,created_at,created_by,state,owner FROM items WHERE pharmacy=? ORDER BY created_at,id", arrayOf(pharmacy)).use { c ->
        buildList { while(c.moveToNext()) add(Triple(ShoppingItem(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)!=0,
            c.getLong(4),c.getString(5),c.getString(6)!="SYNCED"),c.getString(6),c.getString(7))) }
    }
    @Synchronized fun list(identity: ShoppingIdentity): List<ShoppingItem> = query(identity.pharmacy)
        .filter { it.second!="DELETE" && (it.second=="SYNCED" || it.third==identity.owner) }.map { it.first }
    @Synchronized fun pending(identity: ShoppingIdentity): List<ShoppingOperation> = query(identity.pharmacy)
        .filter { it.second=="DELETE" || (it.second=="CREATE" && it.third==identity.owner) }
        .sortedBy { if(it.second=="DELETE") 0 else 1 }.map { ShoppingOperation(it.first,it.second=="DELETE") }
    @Synchronized fun alreadySaved(identity: ShoppingIdentity,item: ShoppingItem): Boolean {
        val old=query(identity.pharmacy).firstOrNull { it.first.id==item.id } ?: return false
        check(old.second!="DELETE" && old.first.title==item.title && old.first.note==item.note && old.first.hasPhoto==item.hasPhoto) {
            "تعارض في مادة محفوظة؛ أغلق الإضافة وأعد فتحها."
        }
        return true
    }
    @Synchronized fun add(identity: ShoppingIdentity, item: ShoppingItem) {
        UUID.fromString(identity.pharmacy); UUID.fromString(item.id)
        require(item.title.length<=240 && item.note.length<=1000 && (item.title.isNotBlank() || item.hasPhoto))
        if(!alreadySaved(identity,item)) writableDatabase.insertOrThrow("items",null,values(identity,item,"CREATE"))
    }
    @Synchronized fun complete(identity: ShoppingIdentity, id: String) {
        // Persist an opaque delete intent BEFORE erasing a file; a crash cannot re-create the content.
        val row = query(identity.pharmacy).firstOrNull { it.first.id==id } ?: return
        if(row.second=="DELETE") return
        check(row.second=="SYNCED" || row.third==identity.owner)
        writableDatabase.update("items",values(identity,ShoppingItem(id,""),"DELETE"),"pharmacy=? AND id=?",arrayOf(identity.pharmacy,id))
    }
    @Synchronized fun acknowledge(identity: ShoppingIdentity, operation: ShoppingOperation, receipt: ShoppingReceipt) {
        check(receipt.owner==identity.owner && receipt.pharmacy==identity.pharmacy && receipt.id==operation.item.id) { "SHOPPING_RECEIPT_MISMATCH" }
        check(!operation.deleting || !receipt.active) { "SHOPPING_RECEIPT_MISMATCH" }
        val id=operation.item.id
        if(!receipt.active) writableDatabase.delete("items","pharmacy=? AND id=?",arrayOf(identity.pharmacy,id))
        else writableDatabase.execSQL("UPDATE items SET state='SYNCED' WHERE pharmacy=? AND id=? AND state='CREATE' AND owner=?",
            arrayOf(identity.pharmacy,id,identity.owner))
    }
    @Synchronized fun merge(identity: ShoppingIdentity, snapshot: ShoppingSnapshot) {
        check(snapshot.owner==identity.owner && snapshot.pharmacy==identity.pharmacy) { "SHOPPING_SNAPSHOT_MISMATCH" }
        require(snapshot.items.map { it.id }.toSet().size==snapshot.items.size)
        snapshot.items.forEach { UUID.fromString(it.id); require(it.title.length<=240 && it.note.length<=1000) }
        val db=writableDatabase
        db.beginTransaction()
        try {
            val before=query(identity.pharmacy).associateBy { it.first.id }
            val ids=snapshot.items.map { it.id }.toSet()
            before.values.filter { it.second=="SYNCED" && it.first.id !in ids }.forEach {
                db.delete("items","pharmacy=? AND id=?",arrayOf(identity.pharmacy,it.first.id))
            }
            snapshot.items.forEach { row ->
                val old=before[row.id]
                // A stale snapshot may contain an item the user just bought. Never revive a pending delete.
                if(old==null || old.second=="SYNCED") db.insertWithOnConflict("items",null,
                    values(identity,row,"SYNCED"),SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun activePhotoIds(pharmacy: String): Set<String> = query(pharmacy).filter {
        it.second!="DELETE" && it.first.hasPhoto }.map { it.first.id }.toSet()
}
