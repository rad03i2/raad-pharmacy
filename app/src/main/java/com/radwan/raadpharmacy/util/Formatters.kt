package com.radwan.raadpharmacy.util

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private val moneyFormatter = NumberFormat.getIntegerInstance(Locale.US)
private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.US)

fun formatMoney(amount: Long): String = "${moneyFormatter.format(amount)} د.ع"

fun formatNumber(value: Long): String = moneyFormatter.format(value)

fun formatDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(dateFormatter)

fun formatTime(timestamp: Long): String {
    val time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalTime()
    return time.format(timeFormatter) + if (time.hour < 12) " صباحًا" else " مساءً"
}

fun daysSince(timestamp: Long): Long =
    TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - timestamp).coerceAtLeast(0L))

fun normalizeIraqPhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    return when {
        digits.startsWith("964") -> "+$digits"
        digits.startsWith("0") -> "+964${digits.drop(1)}"
        else -> "+964$digits"
    }
}
