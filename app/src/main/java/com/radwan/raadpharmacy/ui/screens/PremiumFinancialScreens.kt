package com.radwan.raadpharmacy.ui.screens

import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SoftDivider
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.launch

private enum class DebtModeV3(val label: String, val icon: ImageVector) {
    AMOUNT("مبلغ مباشر", Icons.Rounded.Payments),
    BOTTLES("مبلغ مباشر", Icons.Rounded.LocalShipping)
}

@Composable
fun AddDebtScreenV3(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        V3MissingCustomer(onBack)
        return
    }

    val previousBalance = vm.balance(customer)
    var mode by remember { mutableStateOf(DebtModeV3.AMOUNT) }
    var amountText by remember { mutableStateOf("") }
    var bottles by remember { mutableStateOf("") }
    var bottlePrice by remember { mutableStateOf("25000") }
    var error by remember { mutableStateOf(false) }
    var savedAmount by remember { mutableStateOf<Long?>(null) }

    val bottleCount = bottles.toIntOrNull() ?: 0
    val price = bottlePrice.toLongOrNull() ?: 0L
    val amount = if (mode == DebtModeV3.AMOUNT) {
        amountText.toLongOrNull() ?: 0L
    } else {
        if (bottleCount > 0 && price > 0) bottleCount * price else 0L
    }

    savedAmount?.let { saved ->
        V3SuccessDialog(
            title = "تم تسجيل الدين",
            message = "أضيف " + formatMoney(saved) + " إلى حساب " + customer.name,
            onDone = onBack
        )
    }

    Scaffold(
        topBar = { ScreenTopBar("إضافة دين", onBack) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                Button(
                    onClick = {
                        if (amount <= 0L) {
                            error = true
                        } else {
                            scope.launch {
                                vm.addDebt(
                                    customerId = customerId,
                                    amount = amount,
                                    bottles = bottleCount.takeIf { mode == DebtModeV3.BOTTLES && it > 0 },
                                    bottlePrice = price.takeIf { mode == DebtModeV3.BOTTLES && it > 0 }
                                )
                                savedAmount = amount
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("تسجيل الدين", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                V3FinanceHero(
                    customerName = customer.name,
                    label = "الدين الحالي",
                    amount = previousBalance,
                    positive = false,
                    icon = Icons.Rounded.Wallet
                )
            }

            item {
                Text(
                    "كيف تريد تسجيل الدين؟",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DebtModeV3.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = {
                                mode = item
                                error = false
                            },
                            label = { Text(item.label) },
                            leadingIcon = {
                                Icon(item.icon, null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }

            if (mode == DebtModeV3.AMOUNT) {
                item {
                    V3AmountInput(
                        value = amountText,
                        onValueChange = {
                            amountText = it.filter(Char::isDigit).take(12)
                            error = false
                        },
                        label = "مبلغ الدين",
                        isError = error,
                        helper = if (error) "أدخل مبلغًا صحيحًا" else "أدخل المبلغ بالدينار العراقي"
                    )
                }
                item {
                    V3QuickAmounts(
                        listOf(5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 50_000L),
                        amountText.toLongOrNull()
                    ) {
                        amountText = it.toString()
                        error = false
                    }
                }
            } else {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("تفاصيل المشتريات", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = bottles,
                                    onValueChange = {
                                        bottles = it.filter(Char::isDigit).take(3)
                                        error = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("العدد") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = MaterialTheme.shapes.large
                                )
                                OutlinedTextField(
                                    value = bottlePrice,
                                    onValueChange = {
                                        bottlePrice = it.filter(Char::isDigit).take(9)
                                        error = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("السعر") },
                                    suffix = { Text("د.ع") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = MaterialTheme.shapes.large
                                )
                            }
                            SoftDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("الإجمالي", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    formatMoney(amount),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = DebtRed
                                )
                            }
                            if (error) {
                                Text("أدخل عدد العناصر وسعرها.", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                V3Equation(
                    firstLabel = "الرصيد السابق",
                    first = previousBalance,
                    operator = "+",
                    secondLabel = "الدين الجديد",
                    second = amount,
                    resultLabel = "الرصيد بعد التسجيل",
                    result = previousBalance + amount,
                    positive = false
                )
            }
        }
    }
}

@Composable
fun AddPaymentScreenV3(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        V3MissingCustomer(onBack)
        return
    }

    val currentBalance = vm.balance(customer)
    var amountText by remember { mutableStateOf("") }
    var savedAmount by remember { mutableStateOf<Long?>(null) }
    val amount = amountText.toLongOrNull() ?: 0L
    val tooHigh = amount > currentBalance && amount > 0
    val remaining = (currentBalance - amount).coerceAtLeast(0L)

    savedAmount?.let { saved ->
        V3SuccessDialog(
            title = if (saved == currentBalance) "تم تسديد الحساب" else "تم تسجيل التحصيل",
            message = if (saved == currentBalance) {
                "أصبح رصيد " + customer.name + " صفرًا."
            } else {
                "تم تحصيل " + formatMoney(saved) + " من " + customer.name
            },
            onDone = onBack
        )
    }

    Scaffold(
        topBar = { ScreenTopBar("تسجيل تحصيل", onBack) },
        bottomBar = {
            if (currentBalance > 0) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (vm.addPayment(customerId, amount)) savedAmount = amount
                            }
                        },
                        enabled = amount > 0 && amount <= currentBalance,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("تأكيد التحصيل", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                V3FinanceHero(
                    customerName = customer.name,
                    label = "المبلغ المطلوب",
                    amount = currentBalance,
                    positive = currentBalance == 0L,
                    icon = Icons.Rounded.Payments
                )
            }

            if (currentBalance == 0L) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Check, null, tint = PaidGreen)
                            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text("الحساب مسدد بالكامل", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "لا يوجد مبلغ مطلوب من هذا الزبون.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    V3AmountInput(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit).take(12) },
                        label = "المبلغ المستلم",
                        isError = tooHigh,
                        helper = if (tooHigh) {
                            "لا يمكن أن يتجاوز " + formatMoney(currentBalance)
                        } else {
                            "اكتب المبلغ الذي استلمته من الزبون"
                        }
                    )
                }

                item {
                    V3QuickAmounts(
                        listOf(5_000L, 10_000L, 25_000L, currentBalance).distinct(),
                        amountText.toLongOrNull(),
                        currentBalance
                    ) {
                        amountText = it.coerceAtMost(currentBalance).toString()
                    }
                }

                item {
                    V3Equation(
                        firstLabel = "الدين الحالي",
                        first = currentBalance,
                        operator = "-",
                        secondLabel = "المبلغ المستلم",
                        second = amount,
                        resultLabel = "المتبقي",
                        result = remaining,
                        positive = remaining == 0L && amount > 0
                    )
                }

                if (remaining == 0L && amount > 0 && !tooHigh) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Check, null, tint = PaidGreen)
                                Text(
                                    "هذه الدفعة ستغلق الحساب بالكامل.",
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    color = PaidGreen
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V3FinanceHero(
    customerName: String,
    label: String,
    amount: Long,
    positive: Boolean,
    icon: ImageVector
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(customerName, style = MaterialTheme.typography.titleLarge)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            null,
                            tint = if (positive) PaidGreen else DebtRed,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
            Text(
                formatMoney(amount),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V3AmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    helper: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text("د.ع") },
        singleLine = true,
        isError = isError,
        supportingText = {
            Text(
                helper,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Start),
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun V3QuickAmounts(
    values: List<Long>,
    selected: Long?,
    fullAmount: Long? = null,
    onSelect: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("اختيار سريع", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            if (fullAmount != null && value == fullAmount) {
                                "تسديد كامل • " + formatMoney(value)
                            } else {
                                formatMoney(value)
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun V3Equation(
    firstLabel: String,
    first: Long,
    operator: String,
    secondLabel: String,
    second: Long,
    resultLabel: String,
    result: Long,
    positive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            V3EquationLine(firstLabel, formatMoney(first))
            V3EquationLine(operator + " " + secondLabel, formatMoney(second))
            SoftDivider()
            V3EquationLine(
                resultLabel,
                formatMoney(result),
                true,
                if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V3EquationLine(
    label: String,
    value: String,
    bold: Boolean = false,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = color,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun V3SuccessDialog(
    title: String,
    message: String,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Rounded.Check,
                    null,
                    tint = PaidGreen,
                    modifier = Modifier.padding(10.dp)
                )
            }
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDone) {
                Text("العودة للحساب")
            }
        }
    )
}

@Composable
private fun V3MissingCustomer(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("تعذر العثور على الزبون.")
        }
    }
}
