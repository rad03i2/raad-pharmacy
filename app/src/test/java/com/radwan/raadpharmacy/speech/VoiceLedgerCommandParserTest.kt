package com.radwan.raadpharmacy.speech

import com.radwan.raadpharmacy.data.Customer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLedgerCommandParserTest {
    private val customers = listOf(
        Customer(id = "a", name = "أحمد محمد", phone = "07700000000"),
        Customer(id = "b", name = "محمد علي", phone = "07711111111")
    )

    @Test
    fun debtCommand_extractsOperationAmountAndExactCustomer() {
        val result = VoiceLedgerCommandParser.parse(
            listOf("سجل دين خمسة آلاف دينار عراقي الى حساب الزبون أحمد محمد"),
            customers
        )

        val draft = (result as VoiceLedgerParseResult.Success).draft
        assertEquals(VoiceLedgerOperation.DEBT, draft.operation)
        assertEquals(5_000L, draft.amount)
        assertEquals("a", draft.customerId)
    }

    @Test
    fun paymentCommand_extractsCollectionAndAmount() {
        val result = VoiceLedgerCommandParser.parse(
            listOf("سجل تحصيل سبعة آلاف وخمسمائة دينار للزبون محمد علي"),
            customers
        )

        val draft = (result as VoiceLedgerParseResult.Success).draft
        assertEquals(VoiceLedgerOperation.PAYMENT, draft.operation)
        assertEquals(7_500L, draft.amount)
        assertEquals("b", draft.customerId)
    }

    @Test
    fun ambiguousCustomerName_doesNotGuess() {
        val result = VoiceLedgerCommandParser.parse(
            listOf("سجل دين خمسة آلاف دينار للزبون محمد"),
            customers
        )

        val draft = (result as VoiceLedgerParseResult.Success).draft
        assertNull(draft.customerId)
    }

    @Test
    fun commandWithoutDebtOrPaymentWord_isRejected() {
        val result = VoiceLedgerCommandParser.parse(
            listOf("خمسة آلاف دينار للزبون أحمد محمد"),
            customers
        )
        assertTrue(result is VoiceLedgerParseResult.Error)
    }
}
