package com.radwan.raadpharmacy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radwan.raadpharmacy.R

val MedicalBlue = Color(0xFF2563EB)
val MedicalBlueDark = Color(0xFF1E3A8A)
val MedicalCyan = Color(0xFFE6F7F8)
val AppBackground = Color(0xFFF7F9FC)
val MedicalTurquoise = Color(0xFF0EA5A8)
val DebtRed = Color(0xFFDC2626)
val PaidGreen = Color(0xFF16A34A)
val SoftSurface = Color(0xFFF1F5F9)
val Ink = Color(0xFF0F172A)
val MutedInk = Color(0xFF64748B)

private val LightColors = lightColorScheme(
    primary = MedicalBlue,
    onPrimary = Color.White,
    primaryContainer = MedicalCyan,
    onPrimaryContainer = MedicalBlueDark,
    secondary = MedicalTurquoise,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF7F7),
    onSecondaryContainer = Color(0xFF064E52),
    tertiary = Color(0xFF3B82F6),
    background = AppBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFF1F5F9),
    error = DebtRed,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF0B2A5B),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFFCA5A5),
    errorContainer = Color(0xFF7F1D1D)
)

private val CairoFontFamily = FontFamily(
    Font(R.font.cairo_variable, weight = FontWeight.ExtraLight),
    Font(R.font.cairo_variable, weight = FontWeight.Light),
    Font(R.font.cairo_variable, weight = FontWeight.Normal),
    Font(R.font.cairo_variable, weight = FontWeight.Medium),
    Font(R.font.cairo_variable, weight = FontWeight.SemiBold),
    Font(R.font.cairo_variable, weight = FontWeight.Bold),
    Font(R.font.cairo_variable, weight = FontWeight.ExtraBold)
)

private val TajawalFontFamily = FontFamily(
    Font(R.font.tajawal_regular, weight = FontWeight.Light),
    Font(R.font.tajawal_regular, weight = FontWeight.Normal),
    Font(R.font.tajawal_medium, weight = FontWeight.Medium),
    Font(R.font.tajawal_bold, weight = FontWeight.SemiBold),
    Font(R.font.tajawal_bold, weight = FontWeight.Bold),
    Font(R.font.tajawal_bold, weight = FontWeight.ExtraBold)
)

private val NotoKufiArabicFontFamily = FontFamily(
    Font(R.font.noto_kufi_arabic_variable, weight = FontWeight.Light),
    Font(R.font.noto_kufi_arabic_variable, weight = FontWeight.Normal),
    Font(R.font.noto_kufi_arabic_variable, weight = FontWeight.Medium),
    Font(R.font.noto_kufi_arabic_variable, weight = FontWeight.SemiBold),
    Font(R.font.noto_kufi_arabic_variable, weight = FontWeight.Bold)
)

private val NotoSansArabicFontFamily = FontFamily(
    Font(R.font.noto_sans_arabic_variable, weight = FontWeight.Light),
    Font(R.font.noto_sans_arabic_variable, weight = FontWeight.Normal),
    Font(R.font.noto_sans_arabic_variable, weight = FontWeight.Medium),
    Font(R.font.noto_sans_arabic_variable, weight = FontWeight.SemiBold),
    Font(R.font.noto_sans_arabic_variable, weight = FontWeight.Bold)
)

internal fun appFontFamily(font: AppFontPreset): FontFamily =
    when (font) {
        AppFontPreset.CAIRO -> CairoFontFamily
        AppFontPreset.TAJAWAL -> TajawalFontFamily
        AppFontPreset.NOTO_KUFI_ARABIC -> NotoKufiArabicFontFamily
        AppFontPreset.NOTO_SANS_ARABIC -> NotoSansArabicFontFamily
    }

private fun buildTypography(
    font: AppFontPreset,
    scale: Float
): Typography {
    val family = appFontFamily(font)
    val safeScale = scale.coerceIn(
        TypographySettings.MIN_TEXT_SCALE,
        TypographySettings.MAX_TEXT_SCALE
    )

    fun size(value: Float) = (value * safeScale).sp
    fun line(value: Float) = (value * safeScale).sp

    return Typography(
        headlineLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = size(36f),
            lineHeight = line(45f)
        ),
        headlineMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = size(28f),
            lineHeight = line(37f)
        ),
        headlineSmall = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = size(23f),
            lineHeight = line(32f)
        ),
        titleLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = size(21f),
            lineHeight = line(30f)
        ),
        titleMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = size(17f),
            lineHeight = line(25f)
        ),
        titleSmall = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = size(15f),
            lineHeight = line(22f)
        ),
        bodyLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = size(17f),
            lineHeight = line(28f)
        ),
        bodyMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = size(15f),
            lineHeight = line(24f)
        ),
        bodySmall = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = size(13f),
            lineHeight = line(20f)
        ),
        labelLarge = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.SemiBold,
            fontSize = size(15f),
            lineHeight = line(22f)
        ),
        labelMedium = TextStyle(
            fontFamily = family,
            fontWeight = FontWeight.Medium,
            fontSize = size(13f),
            lineHeight = line(19f)
        )
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun PharmacyLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    font: AppFontPreset = AppFontPreset.CAIRO,
    textScale: Float = TypographySettings.DEFAULT_TEXT_SCALE,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = buildTypography(font, textScale),
        shapes = AppShapes,
        content = content
    )
}
