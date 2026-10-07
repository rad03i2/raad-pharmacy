package com.radwan.raadpharmacy.data

object BackupValidator {
    fun preview(raw: String): BackupPreview {
        return runCatching {
            val payload = BackupJson.parse(raw)
            validate(payload)
            BackupPreview(
                valid = true,
                message = "النسخة صالحة للاستعادة.",
                customerCount = payload.customers.size,
                entryCount = payload.entries.size,
                createdAt = payload.createdAt,
                schemaVersion = payload.schemaVersion
            )
        }.getOrElse {
            BackupPreview(
                valid = false,
                message = it.message ?: "ملف النسخة الاحتياطية غير صالح."
            )
        }
    }

    fun parseValid(raw: String): BackupPayload {
        val payload = BackupJson.parse(raw)
        validate(payload)
        return payload
    }

    fun validate(payload: BackupPayload) {
        val customers = payload.customers
        val entries = payload.entries

        val customerIds = customers.map { it.id }
        require(customerIds.size == customerIds.toSet().size) {
            "النسخة تحتوي على زبائن بمعرّفات مكررة."
        }
        require(customers.none {
            it.id.isBlank() ||
                it.name.isBlank() ||
                it.openingDebt < 0L ||
                it.createdAt <= 0L
        }) {
            "توجد بيانات زبائن غير صالحة في النسخة."
        }

        val entryIds = entries.map { it.id }
        require(entryIds.size == entryIds.toSet().size) {
            "النسخة تحتوي على حركات بمعرّفات مكررة."
        }

        val knownCustomers = customerIds.toHashSet()
        require(entries.none {
            it.id.isBlank() ||
                it.customerId !in knownCustomers ||
                it.amount <= 0L ||
                it.createdAt <= 0L
        }) {
            "توجد حركة مالية غير صالحة أو مرتبطة بزبون غير موجود."
        }

        customers.forEach { customer ->
            require(LedgerRules.isChronologicallyValid(customer, entries)) {
                "سجل الحساب للزبون " + customer.name +
                    " يحتوي على تحصيل أكبر من الرصيد المتاح."
            }
        }
    }
}
