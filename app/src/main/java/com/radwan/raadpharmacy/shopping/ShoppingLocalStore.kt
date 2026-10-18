package com.radwan.raadpharmacy.shopping

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.radwan.raadpharmacy.suppliers.SupplierEntry
import java.io.File
import java.util.UUID

@Serializable
internal data class ShoppingItem(val id: String, val title: String, val note: String = "", val hasPhoto: Boolean = false,
    val createdAt: Long = 0, val createdBy: String = "", val pending: Boolean = false)
@Serializable
internal data class ShoppingSnapshot(val owner: String, val pharmacy: String, val items: List<ShoppingItem>,
    val trash: List<ShoppingTrash> = emptyList(), val suppliers: List<SupplierEntry> = emptyList(), val serverTime: Long = 0)
@Serializable
internal data class ShoppingReceipt(val owner: String, val pharmacy: String, val id: String, val active: Boolean)
@Serializable
internal data class ShoppingTrash(val item: ShoppingItem,val deletionId: String,val expiresAt: Long)
@Serializable
internal data class ShoppingChange(val operation: String,val item: ShoppingItem,val action: String,
    val deletionId: String,val deletedAt: Long)
@Serializable
internal data class ShoppingChangeReceipt(val owner: String,val pharmacy: String,val id: String,
    val operation: String,val active: Boolean,val trashed: Boolean)
internal data class ShoppingIdentity(val owner: String, val pharmacy: String)
internal data class ShoppingOperation(val item: ShoppingItem, val deleting: Boolean)

/** A separate database, excluded from Android backup. Never opens or migrates the financial Room database. */
internal class ShoppingLocalStore(context: Context, directory: File = File(context.noBackupFilesDir,"medicine-shopping"),
    private val clock: ()->Long = System::currentTimeMillis) :
    SQLiteOpenHelper(context, File(directory.apply { mkdirs() },"shopping.db").absolutePath, null, 2, DatabaseErrorHandler {
        // Never let the default Android corruption handler silently delete unsent shopping data.
        throw SQLiteDatabaseCorruptException("تعذر فتح مخزن قائمة الشراء؛ احتُفظ بالملف دون إعادة تهيئته.")
    }) {
    override fun onConfigure(db: SQLiteDatabase) { db.rawQuery("PRAGMA secure_delete=ON",null).use { it.moveToFirst() } }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE items(pharmacy TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,title TEXT NOT NULL,
            note TEXT NOT NULL,photo INTEGER NOT NULL,created_at INTEGER NOT NULL,created_by TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('CREATE','SYNCED','DELETE')),PRIMARY KEY(pharmacy,id))""")
        db.execSQL("CREATE INDEX items_scope ON items(pharmacy,state,created_at,id)")
        createRecovery(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<2) createRecovery(db) }
    private fun createRecovery(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trash(pharmacy TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(pharmacy,id))")
        db.execSQL("CREATE TABLE changes(sequence INTEGER PRIMARY KEY AUTOINCREMENT,pharmacy TEXT NOT NULL,owner TEXT NOT NULL,id TEXT NOT NULL,operation TEXT NOT NULL UNIQUE,payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX changes_scope ON changes(pharmacy,owner,sequence)")
    }
    private fun protected(pharmacy: String): Set<String> = readableDatabase.rawQuery(
        "SELECT id FROM changes WHERE pharmacy=?",arrayOf(pharmacy)).use { c -> buildSet {while(c.moveToNext()) add(c.getString(0))} }
    @Synchronized fun trash(identity: ShoppingIdentity,now: Long=clock()): List<ShoppingTrash> = readableDatabase.rawQuery(
        "SELECT payload FROM trash WHERE pharmacy=?",arrayOf(identity.pharmacy)).use { c -> buildList {
            while(c.moveToNext()) add(Json.decodeFromString<ShoppingTrash>(c.getString(0)))
        } }.filter {it.expiresAt>now}.sortedByDescending {it.expiresAt}
    @Synchronized fun changes(identity: ShoppingIdentity): List<ShoppingChange> = readableDatabase.rawQuery(
        "SELECT payload FROM changes WHERE pharmacy=? AND owner=? ORDER BY sequence",arrayOf(identity.pharmacy,identity.owner)).use { c ->
        buildList {while(c.moveToNext()) add(Json.decodeFromString<ShoppingChange>(c.getString(0)))} }
    private fun putTrash(pharmacy: String,row: ShoppingTrash) { writableDatabase.insertWithOnConflict("trash",null,ContentValues().apply {
        put("pharmacy",pharmacy);put("id",row.item.id);put("payload",Json.encodeToString(row)) },SQLiteDatabase.CONFLICT_REPLACE) }
    private fun putChange(identity: ShoppingIdentity,row: ShoppingChange) { writableDatabase.insertOrThrow("changes",null,ContentValues().apply {
        put("pharmacy",identity.pharmacy);put("owner",identity.owner);put("id",row.item.id);put("operation",row.operation);put("payload",Json.encodeToString(row)) }) }
    @Synchronized fun purchase(identity: ShoppingIdentity,id: String,now: Long=clock()) {
        val row=list(identity).firstOrNull {it.id==id} ?: return
        val operation=UUID.randomUUID().toString()
        val db=writableDatabase;db.beginTransaction()
        try {
            putTrash(identity.pharmacy,ShoppingTrash(row,operation,now+86_400_000L))
            putChange(identity,ShoppingChange(operation,row,"TRASH",operation,now))
            db.delete("items","pharmacy=? AND id=?",arrayOf(identity.pharmacy,id));db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun restore(identity: ShoppingIdentity,id: String,now: Long=clock()) {
        val held=trash(identity,now).firstOrNull {it.item.id==id} ?: error("انتهت مهلة استعادة المادة.")
        val db=writableDatabase;db.beginTransaction()
        try {
            putChange(identity,ShoppingChange(UUID.randomUUID().toString(),held.item,"RESTORE",held.deletionId,held.expiresAt-86_400_000L))
            db.insertWithOnConflict("items",null,values(identity,held.item,"SYNCED"),SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("trash","pharmacy=? AND id=?",arrayOf(identity.pharmacy,id));db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun acknowledgeChange(identity: ShoppingIdentity,op: ShoppingChange,r: ShoppingChangeReceipt) {
        check(r.owner==identity.owner && r.pharmacy==identity.pharmacy && r.id==op.item.id && r.operation==op.operation) {"SHOPPING_RECEIPT_MISMATCH"}
        writableDatabase.delete("changes","pharmacy=? AND owner=? AND operation=?",arrayOf(identity.pharmacy,identity.owner,op.operation))
    }
    @Synchronized fun expire(now: Long=clock()) {
        val expired=readableDatabase.rawQuery("SELECT pharmacy,id,payload FROM trash",null).use { c -> buildList {
            while(c.moveToNext()) if(Json.decodeFromString<ShoppingTrash>(c.getString(2)).expiresAt<=now) add(c.getString(0) to c.getString(1)) } }
        expired.forEach { (pharmacy,id) -> writableDatabase.delete("trash","pharmacy=? AND id=?",arrayOf(pharmacy,id)) }
        // A durable opaque delete can still reach the server after a long offline interval.
        val redactions=readableDatabase.rawQuery("SELECT operation,payload FROM changes",null).use { c -> buildList {
            while(c.moveToNext()) { val op=Json.decodeFromString<ShoppingChange>(c.getString(1))
                if(op.action=="TRASH" && op.deletedAt+86_400_000L<=now) add(op.copy(item=ShoppingItem(op.item.id,"مادة"))) } } }
        redactions.forEach { op -> writableDatabase.execSQL("UPDATE changes SET payload=? WHERE operation=?",arrayOf(Json.encodeToString(op),op.operation)) }
    }
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
        .filter { it.second!="DELETE" && (it.second=="SYNCED" || it.third==identity.owner) }.map { it.first.copy(pending=it.first.pending || it.first.id in protected(identity.pharmacy)) }
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
            val held=protected(identity.pharmacy)
            val ids=snapshot.items.map { it.id }.toSet()
            val trashIds=snapshot.trash.map {it.item.id}.toSet()
            require(trashIds.size==snapshot.trash.size && trashIds.intersect(ids).isEmpty())
            snapshot.trash.forEach { UUID.fromString(it.item.id);UUID.fromString(it.deletionId)
                require(it.item.title.length<=240 && it.item.note.length<=1000) }
            db.rawQuery("SELECT id FROM trash WHERE pharmacy=?",arrayOf(identity.pharmacy)).use { c ->
                val remove=buildList {while(c.moveToNext()) if(c.getString(0) !in held) add(c.getString(0))}
                remove.forEach {db.delete("trash","pharmacy=? AND id=?",arrayOf(identity.pharmacy,it))}
            }
            snapshot.trash.filter {it.item.id !in held}.forEach {putTrash(identity.pharmacy,it)}
            before.values.filter { it.second=="SYNCED" && it.first.id !in ids && it.first.id !in held }.forEach {
                db.delete("items","pharmacy=? AND id=?",arrayOf(identity.pharmacy,it.first.id))
            }
            snapshot.items.forEach { row ->
                val old=before[row.id]
                // A stale snapshot may contain an item the user just bought. Never revive a pending delete.
                if(row.id !in held && (old==null || old.second=="SYNCED")) db.insertWithOnConflict("items",null,
                    values(identity,row,"SYNCED"),SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun knownIds(): Set<String> = readableDatabase.rawQuery("SELECT id FROM items UNION SELECT id FROM trash UNION SELECT id FROM changes",null).use { c ->
        buildSet {while(c.moveToNext()) add(c.getString(0))}
    }
    @Synchronized fun activePhotoIds(pharmacy: String): Set<String> = query(pharmacy).filter {
        it.second!="DELETE" && it.first.hasPhoto }.map { it.first.id }.toSet() +
        trash(ShoppingIdentity("",pharmacy)).filter {it.item.hasPhoto}.map {it.item.id} +
        readableDatabase.rawQuery("SELECT payload FROM changes WHERE pharmacy=?",arrayOf(pharmacy)).use {c -> buildSet {
            while(c.moveToNext()) {val op=Json.decodeFromString<ShoppingChange>(c.getString(0));if(op.item.hasPhoto) add(op.item.id)} } }
}
