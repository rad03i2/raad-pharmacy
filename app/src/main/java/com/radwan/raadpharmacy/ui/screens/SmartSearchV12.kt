package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.ui.components.CustomerCard
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.ScreenTopBar

enum class SearchIntentV12(val routeValue: String, val title: String) {
    OPEN("open", "البحث"),
    DEBT("debt", "اختر زبونًا لإضافة دين"),
    PAYMENT("payment", "اختر زبونًا للتحصيل");

    companion object {
        fun fromRoute(value: String?): SearchIntentV12 =
            entries.firstOrNull { it.routeValue == value } ?: OPEN
    }
}

@Composable
fun SmartSearchScreenV12(
    vm: PharmacyLedgerViewModel,
    intent: SearchIntentV12,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit,
    onDebt: (String) -> Unit,
    onPayment: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }

    val results = remember(customers, entries, query, intent) {
        val source = if (intent == SearchIntentV12.PAYMENT) {
            customers.filter { vm.balance(it) > 0L }
        } else {
            customers
        }

        if (query.isBlank()) {
            source.sortedByDescending {
                vm.lastEntryFor(it.id)?.createdAt ?: it.createdAt
            }.take(12)
        } else {
            val digits = query.filter(Char::isDigit)
            source.filter {
                it.name.contains(query, true) ||
                    (digits.isNotBlank() && it.phone.orEmpty().contains(digits)) ||
                    it.area.contains(query, true)
            }.sortedByDescending { vm.balance(it) }
        }
    }

    Scaffold(topBar = { ScreenTopBar(intent.title, onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp, 6.dp, 14.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("اسم الزبون، الهاتف أو المنطقة") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { focusManager.clearFocus() }
                    )
                )
            }

            item {
                Text(
                    when {
                        query.isNotBlank() -> "النتائج • " + results.size
                        intent == SearchIntentV12.PAYMENT -> "آخر الحسابات المفتوحة"
                        else -> "آخر الحسابات"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (results.isEmpty()) {
                item {
                    EmptyState(
                        if (intent == SearchIntentV12.PAYMENT) "لا توجد حسابات مفتوحة" else "لا توجد نتيجة",
                        if (intent == SearchIntentV12.PAYMENT)
                            "لا يوجد زبون لديه دين متاح للتحصيل."
                        else
                            "جرّب جزءًا من الاسم أو رقم الهاتف أو المنطقة."
                    )
                }
            } else {
                items(results, key = { it.id }) { customer ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CustomerCard(
                                customer = customer,
                                balance = vm.balance(customer),
                                onClick = {
                                    when (intent) {
                                        SearchIntentV12.OPEN -> onCustomer(customer.id)
                                        SearchIntentV12.DEBT -> onDebt(customer.id)
                                        SearchIntentV12.PAYMENT -> onPayment(customer.id)
                                    }
                                },
                                hideBalance = security.hideAmounts
                            )

                            when (intent) {
                                SearchIntentV12.OPEN -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { onDebt(customer.id) },
                                            modifier = Modifier.weight(1f).height(50.dp),
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Icon(Icons.Rounded.Add, null)
                                            Text("دين", modifier = Modifier.padding(horizontal = 5.dp))
                                        }
                                        Button(
                                            onClick = { onPayment(customer.id) },
                                            enabled = vm.balance(customer) > 0L,
                                            modifier = Modifier.weight(1f).height(50.dp),
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Icon(Icons.Rounded.Payments, null)
                                            Text("تحصيل", modifier = Modifier.padding(horizontal = 5.dp))
                                        }
                                    }
                                }

                                SearchIntentV12.DEBT -> {
                                    Button(
                                        onClick = { onDebt(customer.id) },
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Icon(Icons.Rounded.Add, null)
                                        Text("إضافة دين لهذا الزبون", modifier = Modifier.padding(horizontal = 6.dp))
                                    }
                                }

                                SearchIntentV12.PAYMENT -> {
                                    Button(
                                        onClick = { onPayment(customer.id) },
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Icon(Icons.Rounded.Payments, null)
                                        Text("تسجيل تحصيل لهذا الزبون", modifier = Modifier.padding(horizontal = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
