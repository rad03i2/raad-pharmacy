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

/**
 * Strict Iraqi mobile number for direct WhatsApp contact shares.
 * International form has 964 followed by 7 and nine more ASCII digits.
 * A malformed or missing number must never open an arbitrary contact.
 */
fun validatedIraqiWhatsappPhone(raw: String): String? {
    val normalized = normalizeIraqPhone(raw).filter { it in '0'..'9' }
    return normalized.takeIf { Regex("^9647[0-9]{9}$").matches(it) }
}
