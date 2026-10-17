package com.radwan.raadpharmacy.backup

import com.radwan.raadpharmacy.cloud.CloudSyncEngine
import com.radwan.raadpharmacy.data.*
import org.json.JSONObject
import java.util.UUID

internal class RecoveryImporter(private val dao: PharmacyLedgerDao,
    private val safety: suspend () -> Unit) {
    private suspend fun deletes() = dao.cloudOutbox().filter { it.action == "DELETE" }.map { it.kind to it.entityId }.toSet()
    suspend fun compare(archive: RestoredArchive) = RecoveryPlan.build(dao.captureBackup(), archive, deletes())

    suspend fun confirm(archive: RestoredArchive, expectedSequence: Long, owner: String, pharmacy: String, source: String = "") =
        CloudSyncEngine.withLedgerSyncLock {
            require(RecoveryRecord.uuid(owner) && RecoveryRecord.uuid(pharmacy)) { "يجب تهيئة الحساب والصيدلية قبل الاسترداد؛ الملف المختار محفوظ." }
            val current = dao.captureBackup()
            check(dao.recoveryControls().none { it.id.startsWith(RecoveryKeys.ITEM) &&
                RecoveryRecord.parse(it.value).let { r -> r.state == "PENDING" && r.pharmacy.isNotBlank() && r.pharmacy != pharmacy } }) {
                "يوجد استرداد معلق لصيدلية أخرى؛ أكمله في حسابه قبل بدء استرداد جديد."
            }
            check(current.sequence == expectedSequence) { "تغيرت البيانات أثناء المعاينة؛ أعد المقارنة قبل التأكيد." }
            val plan = RecoveryPlan.build(current, archive, deletes())
            // Verified durable protection must finish before the single Room merge transaction begins.
            safety()
            val controls = mutableListOf<BackupControlEntity>()
            val session = UUID.randomUUID().toString()
            for (raw in plan.records) {
                val record = raw.copy(pharmacy = pharmacy)
                controls += BackupControlEntity(RecoveryKeys.item(owner, record), record.json(owner))
                if (record.added) controls += BackupControlEntity(RecoveryKeys.guard(record.kind, record.id), record.id)
            }
            if (current.restoreHeld) {
                // Legacy replacements held the entire ledger and cleared its queue. Recheck all
                // those actual rows, not just old local "synced" flags, before releasing that hold.
                val held = RestoredArchive(BackupPayload(current.customers.map { it.toModel() },
                    current.entries.map { it.toModel() }, System.currentTimeMillis(), 1), emptyList(), JSONObject(), 0)
                for (raw in RecoveryPlan.build(current, held).records) {
                    val record = raw.copy(pharmacy = pharmacy)
                    controls += BackupControlEntity(RecoveryKeys.item(owner, record) + ":held", record.json(owner))
                    controls += BackupControlEntity(RecoveryKeys.guard(record.kind, record.id), record.id)
                }
            }
            controls += BackupControlEntity(RecoveryKeys.SESSION, JSONObject().put("id", session).put("owner", owner)
                .put("added", plan.additions).put("existing", plan.existing).put("conflicts", plan.conflicts)
                .put("source", source)
                .put("completedAt", System.currentTimeMillis()).toString())
            val acceptedParents = plan.records.filter { it.kind == "CUSTOMER" && it.state == "PENDING" }.map { it.id }.toSet()
            dao.mergeRecovery(expectedSequence, plan.customers, plan.entries,
                archive.photos.filter { it.customerId in acceptedParents }, controls)
            plan
        }
}
