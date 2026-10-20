package com.radwan.raadpharmacy.followup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable internal data class FollowupIdentity(val owner: String, val pharmacy: String)
@Serializable internal data class ManualFollowupCase(val customer: String, val debts: Set<String>)
@Serializable internal data class ManualFollowupSnapshot(val owner: String, val pharmacy: String, val cases: List<ManualFollowupCase>)
@Serializable internal data class ManualFollowupReceipt(val owner: String, val pharmacy: String, val customer: String, val debts: Set<String>)
internal data class FollowupPending(val case: ManualFollowupCase, val revision: String)

internal fun applyManualFollowups(accounts: List<DebtFollowupEngine.Account>, cases: List<ManualFollowupCase>) : List<DebtFollowupEngine.Account> {
    val marked=cases.associate {it.customer to it.debts}
    return accounts.map {it.copy(manuallyFollowed=it.balance>0 && it.unpaidDebtKeys.any {key->key in marked[it.customer.id].orEmpty()})}
}
internal fun inFollowup(account: DebtFollowupEngine.Account): Boolean = account.balance>0 &&
    (account.manuallyFollowed || account.specialOverdueAmount>0 || account.bucket!=DebtFollowupEngine.Bucket.NOT_OVERDUE)

/** Isolated metadata; no Room migrations and no access to financial database files. */
internal class ManualFollowupLocal(context: Context, name: String=java.io.File(context.noBackupFilesDir,"manual-followup-v27.db").absolutePath):
    SQLiteOpenHelper(context, name, null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {db.execSQL("CREATE TABLE cases(owner TEXT NOT NULL,pharmacy TEXT NOT NULL,customer TEXT NOT NULL,debts TEXT NOT NULL,pending INTEGER NOT NULL,revision TEXT NOT NULL,PRIMARY KEY(owner,pharmacy,customer))")}
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported follow-up metadata version")
    private fun rows(scope: FollowupIdentity, pending: Boolean=false): List<FollowupPending> = readableDatabase.query("cases",null,
        "owner=? AND pharmacy=?"+if(pending) " AND pending=1" else "", arrayOf(scope.owner,scope.pharmacy),null,null,"customer").use {c->
        buildList {while(c.moveToNext()) add(FollowupPending(ManualFollowupCase(c.getString(c.getColumnIndexOrThrow("customer")),
            Json.decodeFromString(c.getString(c.getColumnIndexOrThrow("debts")))),c.getString(c.getColumnIndexOrThrow("revision"))))}
    }
    fun list(scope: FollowupIdentity)=rows(scope).map {it.case}
    fun pending(scope: FollowupIdentity)=rows(scope,true)
    private fun write(scope: FollowupIdentity, row: ManualFollowupCase, pending: Boolean, revision: String) {
        val v=ContentValues().apply {put("owner",scope.owner);put("pharmacy",scope.pharmacy);put("customer",row.customer)
            put("debts",Json.encodeToString(row.debts));put("pending",if(pending) 1 else 0);put("revision",revision)}
        check(writableDatabase.insertWithOnConflict("cases",null,v,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
    }
    fun add(scope: FollowupIdentity, cases: List<ManualFollowupCase>) {
        require(cases.isNotEmpty() && cases.all {it.customer.isNotBlank() && it.debts.isNotEmpty()})
        val db=writableDatabase;db.beginTransaction()
        try {val previous=list(scope).associateBy {it.customer}
            cases.groupBy {it.customer}.forEach {(id,rows)->
                write(scope,ManualFollowupCase(id,rows.flatMap {it.debts}.toSet()+previous[id]?.debts.orEmpty()),true,UUID.randomUUID().toString())}
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun merge(scope: FollowupIdentity, snapshot: ManualFollowupSnapshot) {
        check(snapshot.owner==scope.owner && snapshot.pharmacy==scope.pharmacy)
        val db=writableDatabase;db.beginTransaction()
        try {val held=pending(scope).associateBy {it.case.customer}
            // The server contract is append-only; a stale snapshot cannot delete a mark.
            val old=list(scope).associateBy {it.customer}
            snapshot.cases.forEach {row->val op=held[row.customer]
                write(scope,row.copy(debts=row.debts+old[row.customer]?.debts.orEmpty()),op!=null,op?.revision ?: "server")}
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun acknowledge(scope: FollowupIdentity, op: FollowupPending, receipt: ManualFollowupReceipt) {
        check(receipt.owner==scope.owner && receipt.pharmacy==scope.pharmacy && receipt.customer==op.case.customer && receipt.debts.containsAll(op.case.debts))
        val db=writableDatabase;db.beginTransaction()
        try {val latest=rows(scope).firstOrNull {it.case.customer==op.case.customer}
            if(latest!=null && latest.revision==op.revision) write(scope,latest.case.copy(debts=latest.case.debts+receipt.debts),false,op.revision)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}
