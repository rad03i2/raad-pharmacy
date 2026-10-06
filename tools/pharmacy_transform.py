#!/usr/bin/env python3
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, content):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")

def replace(rel, old, new, required=False):
    p = ROOT / rel
    s = p.read_text(encoding="utf-8")
    if required and old not in s:
        raise RuntimeError(f"Expected text not found in {rel}: {old[:80]}")
    p.write_text(s.replace(old, new), encoding="utf-8")

def regex_replace(rel, pattern, repl, flags=0, count=0, required=False):
    p = ROOT / rel
    s = p.read_text(encoding="utf-8")
    out, n = re.subn(pattern, repl, s, count=count, flags=flags)
    if required and n == 0:
        raise RuntimeError(f"Pattern not found in {rel}: {pattern[:100]}")
    p.write_text(out, encoding="utf-8")
    return n

# Preserve historical release notes instead of rewriting history.
legacy = ROOT / "docs" / "legacy"
legacy.mkdir(parents=True, exist_ok=True)
for p in sorted((ROOT / "docs").glob("RELEASE_*.md")):
    target = legacy / p.name
    if not target.exists():
        shutil.move(str(p), str(target))

# Preserve the old exported Room schema as migration/reference history.
old_schema = ROOT / "app" / "schemas" / "com.radwan.abosmra.data.GasLedgerDatabase"
if old_schema.exists():
    target = legacy / "room-schema-v1-gas-ledger"
    if target.exists():
        shutil.rmtree(target)
    shutil.move(str(old_schema), str(target))

# Move source/test package directories to the independent pharmacy namespace.
moves = [
    ("app/src/main/java/com/radwan/abosmra", "app/src/main/java/com/radwan/raadpharmacy"),
    ("app/src/test/java/com/radwan/abosmra", "app/src/test/java/com/radwan/raadpharmacy"),
]
for src_rel, dst_rel in moves:
    src = ROOT / src_rel
    dst = ROOT / dst_rel
    if src.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            shutil.rmtree(dst)
        shutil.move(str(src), str(dst))

# Rename core Kotlin files to match the new product identity.
renames = [
    ("app/src/main/java/com/radwan/raadpharmacy/GasLedgerApp.kt", "app/src/main/java/com/radwan/raadpharmacy/PharmacyLedgerApp.kt"),
    ("app/src/main/java/com/radwan/raadpharmacy/GasLedgerViewModel.kt", "app/src/main/java/com/radwan/raadpharmacy/PharmacyLedgerViewModel.kt"),
    ("app/src/main/java/com/radwan/raadpharmacy/data/GasLedgerDatabase.kt", "app/src/main/java/com/radwan/raadpharmacy/data/PharmacyLedgerDatabase.kt"),
    ("app/src/test/java/com/radwan/raadpharmacy/data/GasLedgerDaoTest.kt", "app/src/test/java/com/radwan/raadpharmacy/data/PharmacyLedgerDaoTest.kt"),
]
for src_rel, dst_rel in renames:
    src = ROOT / src_rel
    dst = ROOT / dst_rel
    if src.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))

# Repository-wide current-code identity cleanup. Legacy docs are intentionally excluded.
text_exts = {".kt", ".kts", ".xml", ".md", ".json", ".yml", ".yaml", ".properties", ".pro"}
literal_replacements = [
    ("com.radwan.abosmra", "com.radwan.raadpharmacy"),
    ("GasLedgerViewModel", "PharmacyLedgerViewModel"),
    ("GasLedgerDatabase", "PharmacyLedgerDatabase"),
    ("GasLedgerDao", "PharmacyLedgerDao"),
    ("GasLedgerTheme", "PharmacyLedgerTheme"),
    ("GasLedgerApp", "PharmacyLedgerApp"),
    ("Theme.Abosmra", "Theme.RaadPharmacy"),
    ("gas_ledger_data", "raad_pharmacy_data"),
    ("gas_ledger.db", "raad_pharmacy_ledger.db"),
    ("دفتر دين الغاز - ابو سمرة", "صيدلية رعد"),
    ("دفتر دين الغاز — مثال على الخط", "دفتر صيدلية رعد — مثال على الخط"),
    ("إشعارات دفتر الغاز", "إشعارات دفتر صيدلية رعد"),
    ("فتح دفتر الغاز", "فتح دفتر صيدلية رعد"),
    ("دفتر الغاز مقفل", "دفتر الصيدلية مقفل"),
    ("تصدير نسخة بيانات دفتر الغاز", "تصدير نسخة بيانات دفتر صيدلية رعد"),
    ("حول دفتر الغاز", "حول دفتر صيدلية رعد"),
    ("إشعارات دفتر الغاز", "إشعارات دفتر صيدلية رعد"),
    ("دفتر الغاز", "دفتر صيدلية رعد"),
    ("قناني غاز", "أدوية"),
    ("قنينة غاز", "أدوية"),
    ("موزع الغاز", "الصيدلية"),
]
for p in ROOT.rglob("*"):
    if not p.is_file() or p.suffix.lower() not in text_exts:
        continue
    if legacy in p.parents:
        continue
    if ".git" in p.parts:
        continue
    s = p.read_text(encoding="utf-8")
    for old, new in literal_replacements:
        s = s.replace(old, new)
    # Clean stray user-facing gas words in current code/docs, while leaving database legacy field names alone.
    s = re.sub(r"(?<![\w])غاز(?![\w])", "صيدلية", s)
    s = re.sub(r"\bgas_ledger\b", "raad_pharmacy_ledger", s, flags=re.I)
    p.write_text(s, encoding="utf-8")

# Application identity and release metadata.
replace("app/build.gradle.kts", 'namespace = "com.radwan.raadpharmacy"', 'namespace = "com.radwan.raadpharmacy"')
replace("app/build.gradle.kts", 'applicationId = "com.radwan.raadpharmacy"', 'applicationId = "com.radwan.raadpharmacy"')
replace("app/build.gradle.kts", 'versionCode = 26', 'versionCode = 27')
replace("app/build.gradle.kts", 'versionName = "2.12.0"', 'versionName = "3.0.0"')
replace(".github/workflows/android.yml", 'versionName = "2.12.0"', 'versionName = "3.0.0"')
replace(".github/workflows/android.yml", "versionCode = 26", "versionCode = 27")
replace(".github/workflows/android.yml", "abosmra-v2.12.0-qa-reports", "raad-pharmacy-v3.0.0-qa-reports")
replace(".github/workflows/android.yml", "abosmra-v2.12.0-stable-apk", "raad-pharmacy-v3.0.0-stable-apk")

# Medical Material 3 palette and Cairo typography are kept in the existing theme architecture.
theme = read("app/src/main/java/com/radwan/raadpharmacy/ui/theme/Theme.kt")
theme = theme.replace("GasGreenDark", "MedicalBlueDark").replace("GasGreen", "MedicalBlue")
theme = theme.replace("GasMint", "MedicalCyan").replace("GasCream", "AppBackground")
theme = theme.replace("GasOrange", "MedicalTurquoise")
theme = re.sub(r"val MedicalBlue = Color\(0x[0-9A-Fa-f]+\)", "val MedicalBlue = Color(0xFF2563EB)", theme)
theme = re.sub(r"val MedicalBlueDark = Color\(0x[0-9A-Fa-f]+\)", "val MedicalBlueDark = Color(0xFF1E3A8A)", theme)
theme = re.sub(r"val MedicalCyan = Color\(0x[0-9A-Fa-f]+\)", "val MedicalCyan = Color(0xFFE6F7F8)", theme)
theme = re.sub(r"val AppBackground = Color\(0x[0-9A-Fa-f]+\)", "val AppBackground = Color(0xFFF7F9FC)", theme)
theme = re.sub(r"val MedicalTurquoise = Color\(0x[0-9A-Fa-f]+\)", "val MedicalTurquoise = Color(0xFF0EA5A8)", theme)
theme = re.sub(r"val DebtRed = Color\(0x[0-9A-Fa-f]+\)", "val DebtRed = Color(0xFFDC2626)", theme)
theme = re.sub(r"val PaidGreen = Color\(0x[0-9A-Fa-f]+\)", "val PaidGreen = Color(0xFF16A34A)", theme)
theme = re.sub(r"val SoftSurface = Color\(0x[0-9A-Fa-f]+\)", "val SoftSurface = Color(0xFFF1F5F9)", theme)
theme = re.sub(r"val Ink = Color\(0x[0-9A-Fa-f]+\)", "val Ink = Color(0xFF0F172A)", theme)
theme = re.sub(r"val MutedInk = Color\(0x[0-9A-Fa-f]+\)", "val MutedInk = Color(0xFF64748B)", theme)
theme = theme.replace("secondary = MedicalTurquoise,\n    onSecondary = Color(0xFF352300),\n    secondaryContainer = Color(0xFFFFEBC8),\n    onSecondaryContainer = Color(0xFF4E3500),\n    tertiary = Color(0xFF47645B),",
'''secondary = MedicalTurquoise,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF7F7),
    onSecondaryContainer = Color(0xFF064E52),
    tertiary = Color(0xFF3B82F6),''')
theme = theme.replace("outline = Color(0xFFD4DDDA)", "outline = Color(0xFFE2E8F0)")
theme = theme.replace("outlineVariant = Color(0xFFE5EAE8)", "outlineVariant = Color(0xFFF1F5F9)")
theme = theme.replace("errorContainer = Color(0xFFFFE7E4)", "errorContainer = Color(0xFFFEE2E2)")
theme = theme.replace("onErrorContainer = Color(0xFF611815)", "onErrorContainer = Color(0xFF7F1D1D)")
theme = re.sub(
    r"private val DarkColors = darkColorScheme\([\s\S]*?\n\)\n\nprivate val CairoFontFamily",
    '''private val DarkColors = darkColorScheme(
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

private val CairoFontFamily''',
    theme,
    count=1
)
write("app/src/main/java/com/radwan/raadpharmacy/ui/theme/Theme.kt", theme)

# Resource palette and Android themes.
write("app/src/main/res/values/colors.xml", '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="pharmacy_blue">#2563EB</color>
    <color name="pharmacy_blue_dark">#1E3A8A</color>
    <color name="pharmacy_turquoise">#0EA5A8</color>
    <color name="pharmacy_background">#F7F9FC</color>
</resources>
''')
write("app/src/main/res/values/themes.xml", '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.RaadPharmacy" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:navigationBarColor">@color/pharmacy_background</item>
        <item name="android:statusBarColor">@color/pharmacy_background</item>
        <item name="android:windowActionModeOverlay">true</item>
    </style>

    <style name="Theme.RaadPharmacy.Starting" parent="Theme.SplashScreen.IconBackground">
        <item name="windowSplashScreenBackground">@color/pharmacy_background</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="windowSplashScreenIconBackgroundColor">@color/pharmacy_blue</item>
        <item name="postSplashScreenTheme">@style/Theme.RaadPharmacy</item>
    </style>
</resources>
''')
for rel in ["app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"]:
    s = read(rel).replace("@color/gas_green", "@color/pharmacy_blue")
    write(rel, s)

# Clean medical cross + capsule placeholder identity.
logo = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#2563EB" android:pathData="M18,12h72a12,12 0,0 1,12 12v60a12,12 0,0 1,-12 12h-72a12,12 0,0 1,-12 -12v-60a12,12 0,0 1,12 -12z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M48,27h12v18h18v12h-18v18h-12v-18h-18v-12h18z"/>
    <path android:fillColor="#5EEAD4" android:pathData="M31,78c0,-6 5,-11 11,-11h24c6,0 11,5 11,11s-5,11 -11,11h-24c-6,0 -11,-5 -11,-11z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M54,67h12c6,0 11,5 11,11s-5,11 -11,11h-12z"/>
</vector>
'''
write("app/src/main/res/drawable/ic_app_logo.xml", logo)
write("app/src/main/res/drawable/ic_launcher_foreground.xml", '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M48,24h12v20h20v12h-20v20h-12v-20h-20v-12h20z"/>
    <path android:fillColor="#5EEAD4" android:pathData="M33,78c0,-5 4,-9 9,-9h24c5,0 9,4 9,9s-4,9 -9,9h-24c-5,0 -9,-4 -9,-9z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M54,69h12c5,0 9,4 9,9s-4,9 -9,9h-12z"/>
</vector>
''')
notif = ROOT / "app/src/main/res/drawable/ic_notification.xml"
if notif.exists():
    write("app/src/main/res/drawable/ic_notification.xml", '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M9,3h6v6h6v6h-6v6h-6v-6h-6v-6h6z"/>
</vector>
''')

# New application strings.
write("app/src/main/res/values/strings.xml", '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">دفتر صيدلية رعد</string>
</resources>
''')

# Backup format: write the new identity but continue accepting the old app format and legacy JSON.
backup_rel = "app/src/main/java/com/radwan/raadpharmacy/data/BackupJson.kt"
backup = read(backup_rel)
backup = backup.replace('const val FORMAT = "abosmra-backup"', 'const val FORMAT = "raad-pharmacy-backup"\\n    private const val LEGACY_FORMAT = "abosmra-backup"')
backup = backup.replace('put("app", "دفتر صيدلية رعد")', 'put("app", "دفتر صيدلية رعد")')
backup = backup.replace(
    'val modern = root.optString("backupFormat") == FORMAT\\n        val legacy = !modern && root.has("customers") && root.has("entries")\\n        require(modern || legacy) {',
    'val format = root.optString("backupFormat")\\n        val modern = format == FORMAT\\n        val legacyFormat = format == LEGACY_FORMAT\\n        val legacy = format.isBlank() && root.has("customers") && root.has("entries")\\n        require(modern || legacyFormat || legacy) {'
)
backup = backup.replace('val schema = if (modern) root.optInt("schemaVersion", 0) else 0', 'val schema = if (modern || legacyFormat) root.optInt("schemaVersion", 0) else 0')
backup = backup.replace('if (modern) {', 'if (modern || legacyFormat) {', 1)
backup = backup.replace("هذا الملف ليس نسخة احتياطية معروفة لدفتر صيدلية رعد.", "هذا الملف ليس نسخة احتياطية معروفة لدفتر صيدلية رعد.")
write(backup_rel, backup)

# Keep legacy v1 database columns for compatibility, but use new class/file/database identity.
db_rel = "app/src/main/java/com/radwan/raadpharmacy/data/PharmacyLedgerDatabase.kt"
db = read(db_rel)
db = db.replace('"gas_ledger.db"', '"raad_pharmacy_ledger.db"')
db = db.replace('"raad_pharmacy_ledger.db"', '"raad_pharmacy_ledger.db"')
write(db_rel, db)

# Repository API now accepts pharmacy purchase details instead of injecting gas details.
vm_rel = "app/src/main/java/com/radwan/raadpharmacy/PharmacyLedgerViewModel.kt"
vm = read(vm_rel)
old_vm = '''suspend fun addDebt(
        customerId: String,
        amount: Long,
        bottles: Int?,
        bottlePrice: Long?,
        allowRecentDuplicate: Boolean = false
    ): DebtCreateResult = writeMutex.withLock {
        val result = repository.addDebt(
            customerId = customerId,
            amount = amount,
            bottles = bottles,
            bottlePrice = bottlePrice,
            details = "أدوية",
            allowRecentDuplicate = allowRecentDuplicate
        )'''
new_vm = '''suspend fun addDebt(
        customerId: String,
        amount: Long,
        bottles: Int? = null,
        bottlePrice: Long? = null,
        details: String = "",
        allowRecentDuplicate: Boolean = false
    ): DebtCreateResult = writeMutex.withLock {
        val result = repository.addDebt(
            customerId = customerId,
            amount = amount,
            bottles = bottles,
            bottlePrice = bottlePrice,
            details = details.trim(),
            allowRecentDuplicate = allowRecentDuplicate
        )'''
if old_vm not in vm:
    raise RuntimeError("PharmacyLedgerViewModel.addDebt pattern changed unexpectedly")
vm = vm.replace(old_vm, new_vm)
write(vm_rel, vm)

# Active Add Debt screen: amount-first pharmacy workflow + optional purchases/medicines + voice review.
fin_rel = "app/src/main/java/com/radwan/raadpharmacy/ui/screens/PremiumFinancialV12.kt"
fin = read(fin_rel)
fin = re.sub(
    r'''private data class DebtDraftV29\([\s\S]*?\n\)\n\nprivate enum class DebtModeV12[\s\S]*?\n\}\n\n@Composable\nfun AddDebtScreenV12\([\s\S]*?(?=\n@Composable\nfun AddPaymentScreenV12)''',
    r'''private data class DebtDraftV29(
    val amount: Long,
    val details: String,
    val balanceBefore: Long
)

@Composable
fun AddDebtScreenV12(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        V12MissingCustomer(onBack)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val financialFeedback = rememberFinancialFeedbackHandler(vm)
    val speechRecognizer = remember(context) { DebtSpeechRecognizer(context) }
    val previousBalance = vm.balance(customer)

    var amountText by rememberSaveable { mutableStateOf("") }
    var detailsText by rememberSaveable { mutableStateOf("") }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by rememberSaveable { mutableStateOf(false) }
    var savedAmount by rememberSaveable { mutableStateOf<Long?>(null) }

    var pendingAnomaly by remember { mutableStateOf<DebtAnomalyWarning?>(null) }
    var pendingAnomalyDraft by remember { mutableStateOf<DebtDraftV29?>(null) }
    var pendingDuplicate by remember { mutableStateOf<DebtCreateResult.DuplicateDetected?>(null) }
    var pendingDuplicateDraft by remember { mutableStateOf<DebtDraftV29?>(null) }

    var voiceListening by remember { mutableStateOf(false) }
    var voicePreparing by remember { mutableStateOf(false) }
    var voiceProcessing by remember { mutableStateOf(false) }
    var voiceTranscript by remember { mutableStateOf("") }
    var voiceFeedback by remember { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }

    val amount = amountText.toLongOrNull() ?: 0L
    val canSubmit = amount > 0L && !isSaving && !voiceListening

    fun applyVoiceResult(parsed: SpeechAmountParseResult, heard: String?) {
        heard?.takeIf { it.isNotBlank() }?.let { voiceTranscript = it }
        when (parsed) {
            is SpeechAmountParseResult.Success -> {
                if (parsed.amount > 999_999_999_999L) {
                    voiceFeedback = null
                    voiceError = "المبلغ الذي تم سماعه أكبر من الحد المسموح."
                } else {
                    amountText = parsed.amount.toString()
                    errorText = null
                    voiceError = null
                    voiceFeedback = "تم التعرف على المبلغ: " + formatMoney(parsed.amount)
                }
            }
            SpeechAmountParseResult.Ambiguous -> {
                voiceFeedback = null
                voiceError = "سمعت أكثر من مبلغ محتمل. أعد نطق مبلغ واحد فقط."
            }
            SpeechAmountParseResult.NotFound -> {
                voiceFeedback = null
                voiceError = "لم أتمكن من تحديد المبلغ. أعد المحاولة أو أدخل المبلغ يدويًا."
            }
        }
        voiceListening = false
        voicePreparing = false
        voiceProcessing = false
        speechRecognizer.cancel()
    }

    fun beginVoiceCapture() {
        if (voiceListening || isSaving) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        voiceTranscript = ""
        voiceFeedback = null
        voiceError = null
        voiceListening = true
        voicePreparing = true
        voiceProcessing = false

        val started = speechRecognizer.start(
            DebtSpeechRecognizer.Callbacks(
                onPreparing = {
                    voicePreparing = true
                    voiceProcessing = false
                },
                onReady = { voicePreparing = false },
                onEndOfSpeech = { voiceProcessing = true },
                onPartial = { voiceTranscript = it },
                onFinal = { alternatives ->
                    applyVoiceResult(
                        ArabicDebtAmountParser.parseAlternatives(alternatives),
                        alternatives.firstOrNull()
                    )
                },
                onError = { speechError ->
                    voiceListening = false
                    voicePreparing = false
                    voiceProcessing = false
                    voiceFeedback = null
                    voiceError = when (speechError) {
                        DebtSpeechError.PERMISSION -> "يحتاج التسجيل الصوتي إلى إذن استخدام الميكروفون."
                        DebtSpeechError.BUSY -> "الميكروفون مشغول حاليًا. حاول مرة أخرى."
                        DebtSpeechError.UNAVAILABLE -> "خدمة التعرف على الكلام غير متاحة. فعّل خدمة الإدخال الصوتي على الهاتف ثم أعد المحاولة."
                        DebtSpeechError.START_FAILED -> "لم يبدأ الميكروفون بالاستماع. تحقق من خدمة الإدخال الصوتي وإذن الميكروفون ثم أعد المحاولة."
                        DebtSpeechError.LANGUAGE -> "خدمة التعرف على الكلام لا تدعم العربية حاليًا. فعّل العربية في إعدادات الإدخال الصوتي."
                        DebtSpeechError.NETWORK -> "تعذر الاتصال بخدمة التعرف على الكلام. تحقق من الإنترنت أو توفر العربية دون اتصال."
                        DebtSpeechError.AUDIO -> "تعذر التقاط الصوت من الميكروفون. أغلق أي تطبيق يستخدمه ثم أعد المحاولة."
                        DebtSpeechError.SERVER -> "خدمة التعرف على الكلام لم تستجب. أعد المحاولة."
                        DebtSpeechError.TIMEOUT -> "انتهت مهلة الاستماع دون نتيجة. أعد المحاولة وانطق مبلغًا واحدًا بوضوح."
                        DebtSpeechError.NO_MATCH -> "لم أتمكن من تحديد المبلغ. أعد المحاولة أو أدخل المبلغ يدويًا."
                        DebtSpeechError.OTHER -> "تعذر تشغيل التعرف على الكلام. أعد المحاولة أو أدخل المبلغ يدويًا."
                    }
                }
            )
        )
        if (!started) voiceListening = false
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceCapture()
        else {
            voiceListening = false
            voiceError = "يحتاج التسجيل الصوتي إلى إذن استخدام الميكروفون."
        }
    }

    fun onMicrophoneClick() {
        if (voiceListening) {
            speechRecognizer.cancel()
            voiceListening = false
            voicePreparing = false
            voiceProcessing = false
            voiceError = null
            return
        }
        if (ContextCompat.checkSelfPermission(context, MICROPHONE_PERMISSION_V29) == PackageManager.PERMISSION_GRANTED) {
            beginVoiceCapture()
        } else {
            microphonePermissionLauncher.launch(MICROPHONE_PERMISSION_V29)
        }
    }

    DisposableEffect(speechRecognizer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                speechRecognizer.cancel()
                voiceListening = false
                voicePreparing = false
                voiceProcessing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            speechRecognizer.destroy()
        }
    }

    suspend fun persistDraft(draft: DebtDraftV29, allowRecentDuplicate: Boolean) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                vm.addDebt(
                    customerId = customerId,
                    amount = draft.amount,
                    details = draft.details,
                    allowRecentDuplicate = allowRecentDuplicate
                )
            }
        }
        isSaving = false
        result.onSuccess { outcome ->
            when (outcome) {
                is DebtCreateResult.Created -> {
                    savedAmount = draft.amount
                    pendingDuplicate = null
                    pendingDuplicateDraft = null
                    financialFeedback.onSaved(
                        FinancialOperationReceipt(
                            kind = FinancialOperationKind.DEBT,
                            customerId = customer.id,
                            customerName = customer.name,
                            amount = draft.amount,
                            balanceAfter = draft.balanceBefore + draft.amount
                        )
                    )
                }
                is DebtCreateResult.DuplicateDetected -> {
                    pendingDuplicate = outcome
                    pendingDuplicateDraft = draft
                }
            }
        }.onFailure {
            errorText = "تعذر حفظ الدين. حاول مرة أخرى."
        }
    }

    fun submit() {
        if (isSaving) return
        if (amount <= 0L) {
            errorText = "أدخل مبلغًا صحيحًا."
            return
        }
        val draft = DebtDraftV29(
            amount = amount,
            details = detailsText.trim(),
            balanceBefore = previousBalance
        )
        focusManager.clearFocus()
        isSaving = true
        errorText = null
        scope.launch {
            val anomaly = runCatching {
                withContext(Dispatchers.IO) {
                    vm.debtAnomalyWarning(customerId, draft.amount)
                }
            }.getOrNull()
            if (anomaly != null) {
                isSaving = false
                pendingAnomaly = anomaly
                pendingAnomalyDraft = draft
                return@launch
            }
            persistDraft(draft, allowRecentDuplicate = false)
        }
    }

    pendingAnomaly?.let { warning ->
        V29DebtAnomalyDialog(
            customerName = customer.name,
            warning = warning,
            onReview = {
                pendingAnomaly = null
                pendingAnomalyDraft = null
            },
            onConfirm = {
                val draft = pendingAnomalyDraft
                pendingAnomaly = null
                pendingAnomalyDraft = null
                if (draft != null && !isSaving) {
                    isSaving = true
                    scope.launch { persistDraft(draft, allowRecentDuplicate = false) }
                }
            }
        )
    }

    pendingDuplicate?.let { duplicate ->
        V29DuplicateDebtDialog(
            customerName = customer.name,
            amount = pendingDuplicateDraft?.amount ?: duplicate.previousEntry.amount,
            secondsAgo = duplicate.secondsAgo,
            onCancel = {
                pendingDuplicate = null
                pendingDuplicateDraft = null
            },
            onForceSave = {
                val draft = pendingDuplicateDraft
                pendingDuplicate = null
                pendingDuplicateDraft = null
                if (draft != null && !isSaving) {
                    isSaving = true
                    scope.launch { persistDraft(draft, allowRecentDuplicate = true) }
                }
            }
        )
    }

    savedAmount?.let { saved ->
        V12SuccessDialog(
            title = "تم تسجيل الدين",
            message = "أضيف " + formatMoney(saved) + " إلى حساب " + customer.name,
            onDone = {
                financialFeedback.onConfirmed()
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(if (isSaving) "جاري الحفظ..." else "إضافة دين", if (isSaving) null else onBack)
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Button(
                    onClick = ::submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp, 10.dp, 12.dp, 12.dp).height(58.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text("جاري تسجيل الدين...", modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        Text("تسجيل الدين", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                V12FinanceHero(
                    customerId = customer.id,
                    customerName = customer.name,
                    label = "الدين الحالي",
                    amount = previousBalance,
                    positive = false
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    V12AmountInput(
                        value = amountText,
                        onValueChange = {
                            amountText = it.filter(Char::isDigit).take(12)
                            errorText = null
                            voiceFeedback = null
                        },
                        label = "المبلغ",
                        helper = errorText ?: "أدخل قيمة الأدوية أو المشتريات، أو استخدم الميكروفون ثم راجع المبلغ.",
                        isError = errorText != null,
                        enabled = !isSaving && !voiceListening,
                        onDone = ::submit,
                        trailingIcon = {
                            V29DebtMicrophoneButton(
                                listening = voiceListening,
                                enabled = !isSaving,
                                onClick = ::onMicrophoneClick
                            )
                        }
                    )
                    if (voiceListening || voiceTranscript.isNotBlank() || voiceFeedback != null || voiceError != null) {
                        V29VoiceStatus(
                            listening = voiceListening,
                            preparing = voicePreparing,
                            processing = voiceProcessing,
                            transcript = voiceTranscript,
                            feedback = voiceFeedback,
                            error = voiceError
                        )
                    }
                }
            }
            item {
                V12QuickAmounts(
                    values = listOf(5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 50_000L),
                    selected = amountText.toLongOrNull(),
                    enabled = !isSaving && !voiceListening
                ) {
                    amountText = it.toString()
                    errorText = null
                    voiceFeedback = null
                }
            }
            item {
                OutlinedTextField(
                    value = detailsText,
                    onValueChange = { detailsText = it.take(160) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("المشتريات / الأدوية (اختياري)") },
                    placeholder = { Text("مثال: دواء ضغط + فيتامينات") },
                    enabled = !isSaving,
                    minLines = 2,
                    maxLines = 3,
                    shape = MaterialTheme.shapes.large
                )
            }
            item {
                V12Equation(
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
''',
    fin,
    count=1
)
write(fin_rel, fin)

# Editing old movements never exposes legacy bottle fields.
mgmt_rel = "app/src/main/java/com/radwan/raadpharmacy/ui/screens/PremiumManagementV6.kt"
mgmt = read(mgmt_rel)
mgmt = re.sub(
    r'''@Composable\nprivate fun EditEntryDialog\([\s\S]*?(?=\n@Composable\nprivate fun V6AccountHero)''',
    r'''@Composable
private fun EditEntryDialog(
    entry: LedgerEntry,
    onDismiss: () -> Unit,
    onSave: (Long, Int?, Long?, String) -> Unit
) {
    var amount by remember(entry.id, entry.amount) { mutableStateOf(entry.amount.toString()) }
    var details by remember(entry.id, entry.details) { mutableStateOf(entry.details) }
    val parsedAmount = amount.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.type == EntryType.DEBT) "تعديل الدين" else "تعديل التحصيل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "تاريخ الحركة: " + formatDate(entry.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("المبلغ") },
                    suffix = { Text("د.ع") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (entry.type == EntryType.DEBT) {
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("المشتريات / الأدوية") },
                        maxLines = 3
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "لن يُحفظ أي تعديل يفسد الرصيد التاريخي للحساب.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(parsedAmount, null, null, details) },
                enabled = parsedAmount > 0L
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
''',
    mgmt,
    count=1
)
write(mgmt_rel, mgmt)

# Hide legacy bottle metadata from all visible movement rows and statement images.
common_rel = "app/src/main/java/com/radwan/raadpharmacy/ui/components/CommonComponents.kt"
common = read(common_rel)
old_common = '''            if (entry.bottles != null) {
                Text(
                    entry.bottles.toString() + " قنينة" +
                        (entry.bottlePrice?.let {
                            " • " + (if (hideAmounts) "•••• د.ع" else formatMoney(it)) + " للقنينة"
                        } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
'''
common = common.replace(old_common, "")
write(common_rel, common)

statement_rel = "app/src/main/java/com/radwan/raadpharmacy/util/StatementDocumentRenderer.kt"
statement = read(statement_rel)
statement = statement.replace("private val green = Color.rgb(12, 117, 99)", "private val green = Color.rgb(37, 99, 235)")
statement = statement.replace("private val greenDark = Color.rgb(6, 60, 52)", "private val greenDark = Color.rgb(30, 58, 138)")
statement = statement.replace("private val greenSoft = Color.rgb(225, 244, 236)", "private val greenSoft = Color.rgb(230, 247, 248)")
statement = statement.replace("private val gold = Color.rgb(232, 166, 58)", "private val gold = Color.rgb(14, 165, 168)")
statement = statement.replace("private val goldSoft = Color.rgb(255, 243, 220)", "private val goldSoft = Color.rgb(221, 247, 247)")
statement = statement.replace("private val red = Color.rgb(185, 67, 56)", "private val red = Color.rgb(220, 38, 38)")
statement = statement.replace("private val redSoft = Color.rgb(253, 233, 230)", "private val redSoft = Color.rgb(254, 226, 226)")
statement = statement.replace("private val ink = Color.rgb(23, 33, 30)", "private val ink = Color.rgb(15, 23, 42)")
statement = statement.replace("private val muted = Color.rgb(102, 115, 110)", "private val muted = Color.rgb(100, 116, 139)")
statement = statement.replace("private val line = Color.rgb(225, 231, 228)", "private val line = Color.rgb(226, 232, 240)")
statement = statement.replace("private val warmBackground = Color.rgb(248, 246, 241)", "private val warmBackground = Color.rgb(247, 249, 252)")
statement = statement.replace('value = "صيدلية رعد"', 'value = "صيدلية رعد"')
statement = statement.replace(
'''            val details = entry.bottles
                ?.let { "  •  " + it.toString() + " قنينة" }
                .orEmpty()

            text(
                value = (if (isDebt) "دين" else "تحصيل") +
                    "  •  " + formatDate(entry.createdAt) + details,''',
'''            text(
                value = (if (isDebt) "دين" else "تحصيل") +
                    "  •  " + formatDate(entry.createdAt),'''
)
write(statement_rel, statement)

# Daily debts and advanced reports use pharmacy/account metrics rather than bottle counts.
ops_rel = "app/src/main/java/com/radwan/raadpharmacy/ui/screens/PremiumOperationsV4.kt"
ops = read(ops_rel)
ops = ops.replace("    val bottles = debts.sumOf { it.bottles ?: 0 }\\n", "")
ops = ops.replace('unique.toString() + " زبون • " + bottles + " قنينة"', 'unique.toString() + " زبون • " + debts.size + " عملية"')
write(ops_rel, ops)

reports_rel = "app/src/main/java/com/radwan/raadpharmacy/ui/screens/AdvancedReportsV11.kt"
reports = read(reports_rel)
reports = reports.replace(
'''                    V11Metric(
                        "القناني",
                        report.bottles.toString(),
                        Icons.Rounded.LocalShipping,
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f)
                    )''',
'''                    V11Metric(
                        "الحسابات المفتوحة",
                        report.openAccounts.toString(),
                        Icons.Rounded.AccountBalanceWallet,
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f)
                    )'''
)
write(reports_rel, reports)

# Demo data is pharmacy-native; legacy DB fields are always null for new entries.
repo_rel = "app/src/main/java/com/radwan/raadpharmacy/data/AppRepository.kt"
repo = read(repo_rel)
repo = repo.replace('getSharedPreferences("raad_pharmacy_data", Context.MODE_PRIVATE)', 'getSharedPreferences("raad_pharmacy_data", Context.MODE_PRIVATE)')
repo = re.sub(
    r'''        val entries = listOf\([\s\S]*?\n        \)\n\n        return customers to entries''',
    '''        val entries = listOf(
            LedgerEntry("e1", "c1", EntryType.DEBT, 35_000, details = "أدوية", createdAt = now - 2 * 60 * 60 * 1000L),
            LedgerEntry("e2", "c2", EntryType.DEBT, 75_000, details = "مشتريات صيدلية", createdAt = now - 3 * 60 * 60 * 1000L),
            LedgerEntry("e3", "c2", EntryType.PAYMENT, 25_000, createdAt = now - 90 * 60 * 1000L),
            LedgerEntry("e4", "c3", EntryType.DEBT, 25_000, details = "أدوية", createdAt = now - 5 * day),
            LedgerEntry("e5", "c4", EntryType.DEBT, 50_000, details = "مستلزمات صيدلية", createdAt = now - day),
            LedgerEntry("e6", "c4", EntryType.PAYMENT, 50_000, createdAt = now - 6 * 60 * 60 * 1000L),
            LedgerEntry("e7", "c5", EntryType.DEBT, 150_000, details = "أدوية", createdAt = now - 35 * day),
            LedgerEntry("e8", "c5", EntryType.PAYMENT, 50_000, createdAt = now - 31 * day),
            LedgerEntry("e9", "c6", EntryType.DEBT, 25_000, details = "مشتريات صيدلية", createdAt = now - 4 * 60 * 60 * 1000L),
            LedgerEntry("e10", "c6", EntryType.PAYMENT, 10_000, createdAt = now - 30 * 60 * 1000L)
        )

        return customers to entries''',
    repo,
    count=1
)
write(repo_rel, repo)

# Remove remaining user-facing bottle terminology from current sources without renaming legacy DB/API fields.
for p in (ROOT / "app/src/main").rglob("*.kt"):
    s = p.read_text(encoding="utf-8")
    s = s.replace("إدارة الزبائن والديون والتحصيلات لموزّع قناني الغاز.", "إدارة حسابات وديون زبائن الصيدلية.")
    s = s.replace("حسب القناني", "مبلغ مباشر")
    s = s.replace("تفاصيل القناني", "تفاصيل المشتريات")
    s = s.replace("عدد القناني", "عدد العناصر")
    s = s.replace("سعر القنينة", "سعر الوحدة")
    s = s.replace("القناني", "العناصر")
    s = s.replace("قناني", "عناصر")
    s = s.replace("قنينة", "عنصر")
    s = s.replace("للقنينة", "للوحدة")
    p.write_text(s, encoding="utf-8")

# Modern current documentation.
write("README.md", '''# دفتر صيدلية رعد — Raad Pharmacy Ledger

تطبيق Android عربي RTL لإدارة حسابات وديون زبائن صيدلية رعد، مصمم للاستخدام اليومي السريع خلف كاونتر الصيدلية.

## الهوية
- الاسم: دفتر صيدلية رعد
- الاسم المختصر: صيدلية رعد
- الهوية البصرية: Medical Blue + Turquoise + White
- الخط الافتراضي: Cairo
- Material 3 + Jetpack Compose مع RTL وأرقام إنجليزية ودينار عراقي.

## الوظائف الرئيسية
- إضافة وتعديل الزبائن وصورهم.
- تسجيل دين كمبلغ مالي مباشر مع حقل اختياري للمشتريات / الأدوية.
- تسجيل التحصيل ومنع تحصيل مبلغ أكبر من الرصيد الحالي.
- حماية من تسجيل الدين المكرر وتنبيه للمبالغ غير الطبيعية.
- إدخال مبلغ الدين والأوامر المالية بالصوت مع مراجعة المستخدم قبل الحفظ.
- كشف حساب احترافي قابل للمشاركة كصورة عبر WhatsApp.
- سجل الحركات وديون اليوم وتحصيلات اليوم وأعلى المديونيات والمناطق.
- تقارير وإحصائيات ومركز متابعة وتنبيهات.
- نسخ احتياطي واستعادة مع توافق قراءة النسخ القديمة.
- قفل PIN وبصمة ووضع داكن وخطوط عربية قابلة للتغيير.

## التقنية
- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- ViewModel + StateFlow
- Room 3 + SQLite
- KSP
- Android API 26+
- Compile / Target SDK 37

## قاعدة البيانات والتوافق
قاعدة Room بقيت على Schema v1 في هذا التحويل لتجنب Migration غير ضرورية. حقلا bottles و bottle_price القديمـان موجودان فقط كتوافق Legacy لاستعادة البيانات القديمة، ولا يظهران في تجربة الصيدلية الجديدة ولا تستخدمهما عمليات الدين الجديدة.

## الحزمة
- Namespace: com.radwan.raadpharmacy
- Application ID: com.radwan.raadpharmacy

## البناء
يتحقق GitHub Actions من اختبارات الوحدة وRoom وCompose ثم lintRelease ويبني Release APK. الإصدار الحالي بعد التحويل هو v3.0.0.

## ملاحظات تاريخية
ملاحظات إصدارات التطبيق السابق نُقلت إلى docs/legacy للحفاظ على التاريخ التقني دون تقديمها كتوصيف للمنتج الحالي.
''')

write("docs/PHARMACY_TRANSFORMATION.md", '''# Raad Pharmacy transformation

This document records the v3.0.0 conversion to دفتر صيدلية رعد.

- Independent package/application ID: com.radwan.raadpharmacy
- Core names changed to PharmacyLedgerApp, PharmacyLedgerViewModel, PharmacyLedgerDatabase and PharmacyLedgerDao.
- New debt workflow is amount-first and supports optional purchases/medicines details.
- Gas/bottle UI was removed from the active pharmacy workflow.
- Legacy Room fields bottles and bottle_price remain in schema v1 solely for old-data compatibility.
- Backup writer uses raad-pharmacy-backup and parser accepts the previous abosmra-backup format.
- Material 3 visual identity changed to medical blue, turquoise and white with matching dark mode.
- App icon, splash identity, notifications, lock text, statement image and About identity use Raad Pharmacy branding.
- Cairo remains the default with Tajawal, Noto Kufi Arabic and Noto Sans Arabic available.
''')

# Current build validation doc should describe the pharmacy repository, not the predecessor.
bv = ROOT / "docs/BUILD_VALIDATION.md"
if bv.exists():
    s = bv.read_text(encoding="utf-8")
    s = s.replace("Project: دفتر صيدلية رعد", "Project: دفتر صيدلية رعد")
    s = s.replace("Repository: abosmra", "Repository: raad-pharmacy")
    s = s.replace("abosmra", "raad-pharmacy")
    bv.write_text(s, encoding="utf-8")

# About description.
about_rel = "app/src/main/java/com/radwan/raadpharmacy/ui/screens/AboutDialogV15.kt"
if (ROOT / about_rel).exists():
    about = read(about_rel)
    about = about.replace("نظام لإدارة ديون وتحصيلات", "نظام لإدارة حسابات وديون زبائن الصيدلية")
    write(about_rel, about)

# Temporary transformation automation removes itself before the final commit.
print("Pharmacy transformation applied.")
