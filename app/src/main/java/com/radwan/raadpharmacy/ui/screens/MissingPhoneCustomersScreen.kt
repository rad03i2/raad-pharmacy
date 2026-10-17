package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.MutationResult
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun MissingPhoneCustomersScreen(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    MissingPhoneCustomersList(customers, security.hideAmounts, { vm.balance(it) }, vm::addMissingPhone, onBack, onCustomer)
}

@Composable
internal fun MissingPhoneCustomersList(
    customers: List<Customer>, hideAmounts: Boolean, balance: (Customer) -> Long,
    savePhone: suspend (String, String) -> MutationResult,
    onBack: () -> Unit, onCustomer: (String) -> Unit,
    contactPicker: @Composable ((String) -> Unit, () -> Unit) -> Unit = { selected, dismissed ->
        ContactPhonePickerV3211(onSelected = selected, onDismiss = dismissed)
    }
) {
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pickingId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorId by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorIndex by rememberSaveable { mutableIntStateOf(0) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var restorePosition by rememberSaveable { mutableStateOf(false) }
    val missingPhone = customers.filter { it.phone.isNullOrBlank() }
    val normalizedQuery = query.trim()
    val visible = missingPhone.filter {
        normalizedQuery.isBlank() ||
            it.name.contains(normalizedQuery, ignoreCase = true) ||
            it.area.contains(normalizedQuery, ignoreCase = true)
    }.sortedBy { it.name }

    LaunchedEffect(visible.map { it.id }, restorePosition) {
        if (restorePosition) {
            val found = visible.indexOfFirst { it.id == anchorId }
            val index = if (found >= 0) found + 2 else anchorIndex.coerceAtMost((visible.size + 1).coerceAtLeast(0))
            listState.scrollToItem(index.coerceAtLeast(0), anchorOffset)
            restorePosition = false
        }
    }
    pickingId?.let { id ->
        contactPicker({ phone ->
            if (!saving) {
                saving = true
                scope.launch {
                    try {
                        val result = savePhone(id, phone)
                        pickingId = null
                        if (result.success) restorePosition = true else message = result.message
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "تعذر حفظ الرقم؛ بقيت بيانات الزبون كما هي."; pickingId = null }
                    finally { saving = false }
                }
            }
        }, { if (!saving) pickingId = null })
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } }) }

    Scaffold(topBar = { ScreenTopBar("زبائن ليس لديهم رقم هاتف", onBack) }) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("missing-phone-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item(key = "summary") {
                Text(
                    "عدد الزبائن بلا رقم هاتف: ${missingPhone.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("بحث بالاسم أو المنطقة") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }
            if (visible.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Text(
                            if (missingPhone.isEmpty()) "جميع الزبائن لديهم رقم هاتف."
                            else "لا توجد نتائج مطابقة للبحث.",
                            modifier = Modifier.padding(22.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(visible, key = { it.id }) { customer ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("missing-phone:${customer.id}").clickable { onCustomer(customer.id) },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            CustomerAvatar(customerId = customer.id, size = 44.dp)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    customer.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    customer.area.ifBlank { "لم تُحدد المنطقة" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "لا يوجد رقم هاتف",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                OutlinedButton(enabled = !saving, onClick = {
                                    anchorIndex = listState.firstVisibleItemIndex
                                    anchorOffset = listState.firstVisibleItemScrollOffset
                                    anchorId = visible.getOrNull(anchorIndex - 2)?.id
                                    pickingId = customer.id
                                }) {
                                    Text("إضافة رقم هاتف")
                                }
                            }
                            Spacer(Modifier.size(2.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (hideAmounts) "•••• د.ع" else formatMoney(balance(customer)),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "الرصيد الحالي",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
