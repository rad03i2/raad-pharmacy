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
        FormDraftStore.saveDebtDraft(context, "customer-1", DebtFormDraft("5000", "أدوية"))
        FormDraftStore.markActiveRoute(context, "add_debt/customer-1")
        assertEquals(DebtFormDraft("5000", "أدوية"), FormDraftStore.loadDebtDraft(context, "customer-1"))

        FormDraftStore.clearActiveDraft(context)
        assertNull(FormDraftStore.loadDebtDraft(context, "customer-1"))
        assertNull(FormDraftStore.activeRoute(context))

        FormDraftStore.savePaymentDraft(context, "customer-2", PaymentFormDraft("1000"))
        assertEquals(PaymentFormDraft("1000"), FormDraftStore.loadPaymentDraft(context, "customer-2"))
    }

    @Test fun arbitraryRoutesAreNeverRestored() {
        FormDraftStore.markActiveRoute(context, "https://example.com")
        assertNull(FormDraftStore.activeRoute(context))
    }
}
