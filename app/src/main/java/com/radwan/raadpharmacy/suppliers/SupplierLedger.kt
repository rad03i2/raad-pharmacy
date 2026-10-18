package com.radwan.raadpharmacy.suppliers

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import com.radwan.raadpharmacy.data.MonthlyReports
import com.radwan.raadpharmacy.shopping.ShoppingIdentity
import com.radwan.raadpharmacy.shopping.ShoppingReceipt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

@Serializable
internal data class SupplierEntry(val id: String,val supplier: String,val kind: String,val amount: Long,
    val day: String,val note: String="",val pending: Boolean=false) {
    fun validate() { UUID.fromString(id);require(supplier.trim().length in 1..160 && kind in setOf("DEBT","PAYMENT") &&
        amount in 1..1_000_000_000_000L && note.length<=1000)
        require(LocalDate.parse(day) in LocalDate.of(2000,1,1)..LocalDate.of(2100,12,31)) }
}
internal data class SupplierTotals(val debts: Long,val payments: Long,val closing: Long,val suppliers: List<SupplierBalance>)
internal data class SupplierBalance(val name: String,val debts: Long,val payments: Long,val closing: Long)
internal object SupplierReports {
    fun totals(rows: List<SupplierEntry>,period: MonthlyReports.Period): SupplierTotals {
        val balances=rows.groupBy {it.supplier}.map { (name,entries) ->
            var debt=0L;var payment=0L;var closing=0L
            entries.forEach { row -> val date=LocalDate.parse(row.day)
                if(date<=period.through) closing=Math.addExact(closing,if(row.kind=="DEBT") row.amount else -row.amount)
                if(date>=period.from && date<=period.through) if(row.kind=="DEBT") debt=Math.addExact(debt,row.amount)
                    else payment=Math.addExact(payment,row.amount)
            };SupplierBalance(name,debt,payment,closing)
        }.filter {it.debts!=0L || it.payments!=0L || it.closing!=0L}.sortedByDescending {it.closing}
        return SupplierTotals(balances.fold(0L){a,b->Math.addExact(a,b.debts)},balances.fold(0L){a,b->Math.addExact(a,b.payments)},
            balances.fold(0L){a,b->Math.addExact(a,b.closing)},balances)
    }
}
/** Separate append-only ledger; same UUID + different values is a conflict, never an overwrite. */
internal class SupplierLocalStore(context: Context,name: String="supplier-ledger.db") : SQLiteOpenHelper(context,name,null,1,
    DatabaseErrorHandler {throw SQLiteDatabaseCorruptException("Supplier ledger preserved")}) {
    override fun onCreate(db: SQLiteDatabase) {db.execSQL("CREATE TABLE records(pharmacy TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,pending INTEGER NOT NULL,PRIMARY KEY(pharmacy,id))")}
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int)=error("Unsupported supplier database")
    @Synchronized fun list(identity: ShoppingIdentity): List<SupplierEntry> = readableDatabase.rawQuery(
        "SELECT payload,pending FROM records WHERE pharmacy=? AND (pending=0 OR owner=?) ORDER BY id",arrayOf(identity.pharmacy,identity.owner)).use {c ->
        buildList {while(c.moveToNext()) add(Json.decodeFromString<SupplierEntry>(c.getString(0)).copy(pending=c.getInt(1)!=0))} }
    @Synchronized fun add(identity: ShoppingIdentity,row: SupplierEntry) {row.validate()
        val old=list(identity).firstOrNull {it.id==row.id}
        check(old==null || old.copy(pending=false)==row.copy(pending=false)) {"SUPPLIER_ID_CONFLICT"}
        if(old==null) writableDatabase.insertOrThrow("records",null,values(identity,row,true))
    }
    private fun values(identity: ShoppingIdentity,row: SupplierEntry,pending: Boolean)=ContentValues().apply {
        put("pharmacy",identity.pharmacy);put("id",row.id);put("owner",identity.owner);put("payload",Json.encodeToString(row.copy(pending=false)));put("pending",if(pending)1 else 0) }
    @Synchronized fun acknowledge(identity: ShoppingIdentity,row: SupplierEntry,r: ShoppingReceipt) {
        check(r.owner==identity.owner && r.pharmacy==identity.pharmacy && r.id==row.id && r.active) {"SUPPLIER_RECEIPT_MISMATCH"}
        writableDatabase.execSQL("UPDATE records SET pending=0 WHERE pharmacy=? AND id=? AND owner=? AND payload=?",
            arrayOf(identity.pharmacy,row.id,identity.owner,Json.encodeToString(row.copy(pending=false))))
    }
    @Synchronized fun merge(identity: ShoppingIdentity,rows: List<SupplierEntry>) {
        require(rows.map{it.id}.toSet().size==rows.size); rows.forEach {it.validate()}
        val db=writableDatabase;db.beginTransaction()
        try {val old=list(identity).associateBy {it.id}
            rows.forEach {row -> val previous=old[row.id]
                check(previous==null || previous.copy(pending=false)==row.copy(pending=false)) {"SUPPLIER_ID_CONFLICT"}
                if(previous==null || !previous.pending) db.insertWithOnConflict("records",null,values(identity,row,false),SQLiteDatabase.CONFLICT_REPLACE)
            };db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
}
