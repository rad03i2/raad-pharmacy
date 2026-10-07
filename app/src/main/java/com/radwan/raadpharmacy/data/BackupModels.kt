package com.radwan.raadpharmacy.data

enum class AutoBackupInterval(val storageValue: String) {
    OFF("off"),
    DAILY("daily"),
    WEEKLY("weekly");

    companion object {
        fun fromStorage(value: String?): AutoBackupInterval =
            entries.firstOrNull { it.storageValue == value } ?: WEEKLY
    }
}

data class BackupPreview(
    val valid: Boolean,
    val message: String,
    val customerCount: Int = 0,
    val entryCount: Int = 0,
    val createdAt: Long = 0L,
    val schemaVersion: Int = 0
)

data class BackupRestoreResult(
    val success: Boolean,
    val message: String,
    val customerCount: Int = 0,
    val entryCount: Int = 0
)

data class BackupPayload(
    val customers: List<Customer>,
    val entries: List<LedgerEntry>,
    val createdAt: Long,
    val schemaVersion: Int
)
