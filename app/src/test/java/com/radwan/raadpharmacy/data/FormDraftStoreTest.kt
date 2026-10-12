package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FormDraftStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("raad_form_drafts_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun customerDraftSurvivesStoreReload() {
        val expected = CustomerFormDraft(
            name = "أحمد",
            phone = "07701234567",
            area = "الموصل",
            openingDebt = "25000",
            notes = "أدوية",
            selectedPhotoUri = "content://photo/1"
        )
        FormDraftStore.saveCustomerDraft(context, expected)
        assertEquals(expected, FormDraftStore.loadCustomerDraft(context))
    }

    @Test fun activeCustomerRouteCanBeRestoredAndExplicitlyCleared() {
        FormDraftStore.saveCustomerDraft(context, CustomerFormDraft(name = "أحمد"))
        FormDraftStore.markActiveRoute(context, "add_customer")
        assertEquals("add_customer", FormDraftStore.activeRoute(context))

        FormDraftStore.clearActiveDraft(context)
        assertNull(FormDraftStore.activeRoute(context))
        assertNull(FormDraftStore.loadCustomerDraft(context))
    }

    @Test fun financialDraftsAreSeparatedByCustomerAndClearWithTheirRoute() {
        val debtDraft = DebtFormDraft("5000", "أدوية", "11111111-1111-4111-8111-111111111111")
        FormDraftStore.saveDebtDraft(context, "customer-1", debtDraft)
        FormDraftStore.markActiveRoute(context, "add_debt/customer-1")
        assertEquals(debtDraft, FormDraftStore.loadDebtDraft(context, "customer-1"))

        FormDraftStore.clearActiveDraft(context)
        assertNull(FormDraftStore.loadDebtDraft(context, "customer-1"))
        assertNull(FormDraftStore.activeRoute(context))

        val paymentDraft = PaymentFormDraft("1000", "22222222-2222-4222-8222-222222222222")
        FormDraftStore.savePaymentDraft(context, "customer-2", paymentDraft)
        assertEquals(paymentDraft, FormDraftStore.loadPaymentDraft(context, "customer-2"))
    }

    @Test fun arbitraryRoutesAreNeverRestored() {
        FormDraftStore.markActiveRoute(context, "https://example.com")
        assertNull(FormDraftStore.activeRoute(context))
    }
}
