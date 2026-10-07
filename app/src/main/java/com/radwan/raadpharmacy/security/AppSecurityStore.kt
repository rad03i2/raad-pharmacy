package com.radwan.raadpharmacy.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class SecurityState(
    val pinEnabled: Boolean,
    val biometricEnabled: Boolean,
    val hideAmounts: Boolean,
    val secureScreen: Boolean,
    val lockTimeoutSeconds: Int
)

data class SecurityMutationResult(
    val success: Boolean,
    val message: String
)

class AppSecurityStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(): SecurityState = SecurityState(
        pinEnabled = isPinEnabled(),
        biometricEnabled = prefs.getBoolean(KEY_BIOMETRIC, false) && isPinEnabled(),
        hideAmounts = prefs.getBoolean(KEY_HIDE_AMOUNTS, false),
        secureScreen = prefs.getBoolean(KEY_SECURE_SCREEN, false),
        lockTimeoutSeconds = prefs.getInt(KEY_LOCK_TIMEOUT, DEFAULT_LOCK_TIMEOUT_SECONDS)
    )

    fun isPinEnabled(): Boolean =
        !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_PIN_SALT, null).isNullOrBlank()

    fun setPin(pin: String): SecurityMutationResult {
        val validation = validateNewPin(pin)
        if (validation != null) return SecurityMutationResult(false, validation)

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)

        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()

        return SecurityMutationResult(true, "تم تفعيل قفل PIN.")
    }

    fun changePin(currentPin: String, newPin: String): SecurityMutationResult {
        if (!verifyPin(currentPin)) {
            return SecurityMutationResult(false, "PIN الحالي غير صحيح.")
        }
        val validation = validateNewPin(newPin)
        if (validation != null) return SecurityMutationResult(false, validation)
        return setPin(newPin).copy(message = "تم تغيير PIN بنجاح.")
    }

    fun disablePin(currentPin: String): SecurityMutationResult {
        if (!verifyPin(currentPin)) {
            return SecurityMutationResult(false, "PIN الحالي غير صحيح.")
        }

        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .putBoolean(KEY_BIOMETRIC, false)
            .remove(KEY_LAST_BACKGROUND_AT)
            .apply()

        return SecurityMutationResult(true, "تم إلغاء قفل التطبيق.")
    }

    fun verifyPin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{4,6}"))) return false
        val saltText = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val hashText = prefs.getString(KEY_PIN_HASH, null) ?: return false

        return runCatching {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            val actual = derive(pin, salt)
            MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC, enabled && isPinEnabled())
            .apply()
    }

    fun setHideAmounts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_AMOUNTS, enabled).apply()
    }

    fun setSecureScreen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SECURE_SCREEN, enabled).apply()
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        val safe = seconds.coerceIn(0, 3600)
        prefs.edit().putInt(KEY_LOCK_TIMEOUT, safe).apply()
    }

    fun markBackgrounded(now: Long = System.currentTimeMillis()) {
        if (!isPinEnabled()) return
        prefs.edit().putLong(KEY_LAST_BACKGROUND_AT, now).apply()
    }

    fun shouldLockOnForeground(now: Long = System.currentTimeMillis()): Boolean {
        if (!isPinEnabled()) return false
        val last = prefs.getLong(KEY_LAST_BACKGROUND_AT, 0L)
        if (last <= 0L) return false
        val timeoutMs = state().lockTimeoutSeconds * 1000L
        return now - last >= timeoutMs
    }

    private fun validateNewPin(pin: String): String? = when {
        !pin.matches(Regex("\\d{4,6}")) -> "PIN يجب أن يتكون من 4 إلى 6 أرقام إنجليزية."
        pin.toSet().size == 1 -> "اختر PIN أقل سهولة في التخمين."
        else -> null
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val PREFS_NAME = "gas_ledger_security"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_HIDE_AMOUNTS = "hide_amounts"
        private const val KEY_SECURE_SCREEN = "secure_screen"
        private const val KEY_LOCK_TIMEOUT = "lock_timeout_seconds"
        private const val KEY_LAST_BACKGROUND_AT = "last_background_at"
        private const val DEFAULT_LOCK_TIMEOUT_SECONDS = 60
        private const val PBKDF2_ITERATIONS = 120_000
    }
}
