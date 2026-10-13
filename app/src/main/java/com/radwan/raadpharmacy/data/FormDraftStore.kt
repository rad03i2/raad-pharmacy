package com.radwan.raadpharmacy.data

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Durable, local-only drafts for forms that must survive Android background/process recreation.
 * Drafts contain only unsaved form input; they never create or modify ledger records.
 */
data class CustomerFormDraft(
    val operationId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val phone: String = "",
    val area: String = "",
    val openingDebt: String = "",
    val notes: String = "",
    val selectedPhotoUri: String? = null,
    val cameraUri: String? = null
)

data class DebtFormDraft(
    val amount: String = "",
    val details: String = "",
    val repaymentDays: Int? = null,
    val operationId: String = UUID.randomUUID().toString()
)

data class PaymentFormDraft(
    val amount: String = "",
    val operationId: String = UUID.randomUUID().toString()
)

object FormDraftStore {
    private const val PREFS_NAME = "raad_form_drafts_v1"
    private const val KEY_ACTIVE_ROUTE = "active_form_route"
    private const val KEY_CUSTOMER = "draft_add_customer"
    private const val KEY_DEBT_PREFIX = "draft_add_debt_"
    private const val KEY_PAYMENT_PREFIX = "draft_add_payment_"
    private val debtRoute = Regex("^add_debt/[A-Za-z0-9_-]{1,100}$")
    private val paymentRoute = Regex("^add_payment/[A-Za-z0-9_-]{1,100}$")
    private val operationIdPattern = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns only known form routes; arbitrary values are never used as navigation routes. */
    fun activeRoute(context: Context): String? {
        val store = prefs(context)
        val route = store.getString(KEY_ACTIVE_ROUTE, null) ?: return null
        val valid = route == "add_customer" || debtRoute.matches(route) || paymentRoute.matches(route)
        if (!valid) store.edit().remove(KEY_ACTIVE_ROUTE).apply()
        return route.takeIf { valid }
    }

    fun markActiveRoute(context: Context, route: String) {
        if (route == "add_customer" || debtRoute.matches(route) || paymentRoute.matches(route)) {
            prefs(context).edit().putString(KEY_ACTIVE_ROUTE, route).apply()
        }
    }

    fun saveCustomerDraft(context: Context, draft: CustomerFormDraft) {
        val json = JSONObject()
            .put("operationId", draft.operationId)
            .put("name", draft.name)
            .put("phone", draft.phone)
            .put("area", draft.area)
            .put("openingDebt", draft.openingDebt)
            .put("notes", draft.notes)
            .put("selectedPhotoUri", draft.selectedPhotoUri)
            .put("cameraUri", draft.cameraUri)
        prefs(context).edit().putString(KEY_CUSTOMER, json.toString()).apply()
    }

    fun loadCustomerDraft(context: Context): CustomerFormDraft? =
        prefs(context).getString(KEY_CUSTOMER, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                CustomerFormDraft(
                    operationId = validOperationId(json.optString("operationId", null)),
                    name = json.optString("name", ""),
                    phone = json.optString("phone", ""),
                    area = json.optString("area", ""),
                    openingDebt = json.optString("openingDebt", ""),
                    notes = json.optString("notes", ""),
                    selectedPhotoUri = json.optString("selectedPhotoUri", "").takeIf(String::isNotBlank),
                    cameraUri = json.optString("cameraUri", "").takeIf(String::isNotBlank)
                )
            }.getOrNull()
        }

    fun clearCustomerDraft(context: Context) {
        val store = prefs(context)
        store.edit().remove(KEY_CUSTOMER)
            .apply()
        clearActiveRouteIf(context, "add_customer")
    }

    fun saveDebtDraft(context: Context, customerId: String, draft: DebtFormDraft) {
        if (!validId(customerId)) return
        val json = JSONObject().put("amount", draft.amount).put("details", draft.details)
            .put("operationId", draft.operationId)
            .put("repaymentDays", draft.repaymentDays ?: JSONObject.NULL)
        prefs(context).edit().putString(KEY_DEBT_PREFIX + customerId, json.toString()).apply()
    }

    fun loadDebtDraft(context: Context, customerId: String): DebtFormDraft? {
        if (!validId(customerId)) return null
        return prefs(context).getString(KEY_DEBT_PREFIX + customerId, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                DebtFormDraft(
                    amount = json.optString("amount", ""),
                    details = json.optString("details", ""),
                    repaymentDays = json.optInt("repaymentDays", 0).takeIf { it in RepaymentTerms.choices },
                    operationId = validOperationId(json.optString("operationId", null))
                )
            }.getOrNull()
        }
    }

    fun clearDebtDraft(context: Context, customerId: String) {
        if (!validId(customerId)) return
        prefs(context).edit().remove(KEY_DEBT_PREFIX + customerId).apply()
        clearActiveRouteIf(context, "add_debt/$customerId")
    }

    fun savePaymentDraft(context: Context, customerId: String, draft: PaymentFormDraft) {
        if (!validId(customerId)) return
        val json = JSONObject().put("amount", draft.amount).put("operationId", draft.operationId)
        prefs(context).edit().putString(KEY_PAYMENT_PREFIX + customerId, json.toString()).apply()
    }

    fun loadPaymentDraft(context: Context, customerId: String): PaymentFormDraft? {
        if (!validId(customerId)) return null
        return prefs(context).getString(KEY_PAYMENT_PREFIX + customerId, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                PaymentFormDraft(
                    amount = json.optString("amount", ""),
                    operationId = validOperationId(json.optString("operationId", null))
                )
            }.getOrNull()
        }
    }

    fun clearPaymentDraft(context: Context, customerId: String) {
        if (!validId(customerId)) return
        prefs(context).edit().remove(KEY_PAYMENT_PREFIX + customerId).apply()
        clearActiveRouteIf(context, "add_payment/$customerId")
    }

    /** Clears the active form and only that form's draft after an intentional navigation away. */
    fun clearActiveDraft(context: Context) {
        val store = prefs(context)
        val route = store.getString(KEY_ACTIVE_ROUTE, null)
        val editor = store.edit().remove(KEY_ACTIVE_ROUTE)
        when {
            route == "add_customer" -> editor.remove(KEY_CUSTOMER)
            route != null && debtRoute.matches(route) ->
                editor.remove(KEY_DEBT_PREFIX + route.substringAfter('/'))
            route != null && paymentRoute.matches(route) ->
                editor.remove(KEY_PAYMENT_PREFIX + route.substringAfter('/'))
        }
        editor.apply()
    }

    /** Flushes pending SharedPreferences.apply() writes before the activity goes to background. */
    fun flush(context: Context) {
        prefs(context).edit().putLong("last_durable_flush", System.currentTimeMillis()).commit()
    }

    private fun clearActiveRouteIf(context: Context, expected: String) {
        val store = prefs(context)
        if (store.getString(KEY_ACTIVE_ROUTE, null) == expected) {
            store.edit().remove(KEY_ACTIVE_ROUTE).apply()
        }
    }

    private fun validId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_-]{1,100}"))

    private fun validOperationId(value: String?): String =
        value?.takeIf(operationIdPattern::matches) ?: UUID.randomUUID().toString()
}
