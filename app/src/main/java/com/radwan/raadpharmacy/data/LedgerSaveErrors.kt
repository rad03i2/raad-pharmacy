package com.radwan.raadpharmacy.data

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Never encourage repeating an operation when the real problem is a restore hold. */
fun ledgerSaveError(error: Throwable, fallback: String): String {
    if (error is CancellationException) throw error
    Log.e("LedgerSave", "Local financial save failed", error)
    return if (generateSequence(error) { it.cause }.any {
            it.message?.contains("التعديل معلق بعد الاستعادة") == true
        }) "الحفظ موقوف لأن نسخة مستعادة تنتظر المراجعة. افتح التخزين والنسخ الاحتياطي ثم اختر العودة إلى بيانات الصيدلية الحالية بعد مراجعة النسخة."
    else fallback
}
