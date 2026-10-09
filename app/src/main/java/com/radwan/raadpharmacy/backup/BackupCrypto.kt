package com.radwan.raadpharmacy.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Portable 256-bit recovery key; on-device copy is wrapped with Android Keystore. */
object BackupCrypto {
    private val magic = "RAADBK02".toByteArray(Charsets.US_ASCII)
    const val MAX_FILE_BYTES = 128 * 1024 * 1024
    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun recoveryCode(key: ByteArray): String = key.joinToString("") { "%02X".format(it) }.chunked(8).joinToString("-")
    fun parseCode(code: String): ByteArray {
        val hex = code.filterNot { it.isWhitespace() || it == '-' }
        require(hex.matches(Regex("[a-fA-F0-9]{64}"))) { "مفتاح الاسترداد يجب أن يتكون من 64 رمزًا." }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun seal(bytes: ByteArray, key: ByteArray): ByteArray {
        require(bytes.size <= MAX_FILE_BYTES)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        return magic + nonce + cipher.doFinal(bytes)
    }
    fun open(bytes: ByteArray, key: ByteArray): ByteArray {
        require(bytes.size in 36..(MAX_FILE_BYTES + 36) && bytes.copyOfRange(0, 8).contentEquals(magic)) {
            "ملف النسخة ناقص أو غير معروف."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(8, 20)))
        cipher.updateAAD(magic)
        return cipher.doFinal(bytes.copyOfRange(20, bytes.size))
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun isEncrypted(bytes: ByteArray) = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(magic)
}

class BackupKeyStore(context: Context) {
    private val app = context.applicationContext
    private val keyFile = File(app.noBackupFilesDir, "raad_backup_key.bin")
    private val prefs = app.getSharedPreferences("raad_backup_key_status", Context.MODE_PRIVATE)
    @Synchronized
    fun key(): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "raad_backup_wrapping_v2"
        if (!store.containsAlias(alias)) {
            check(!keyFile.exists()) { "مفتاح الهاتف مفقود. أدخل مفتاح الاسترداد قبل متابعة النسخ." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        val wrapping = store.getKey(alias, null) as SecretKey
        if (keyFile.exists()) {
            val saved = keyFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, saved.copyOfRange(0, 12)))
            return cipher.doFinal(saved.copyOfRange(12, saved.size))
        }
        val key = BackupCrypto.newKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        val sealed = cipher.iv + cipher.doFinal(key)
        val temporary = File(keyFile.parentFile, keyFile.name + ".tmp")
        FileOutputStream(temporary).use { it.write(sealed); it.fd.sync() }
        check(temporary.renameTo(keyFile))
        return key
    }
    fun confirmed() = prefs.getBoolean("confirmed", false)
    fun confirm() { check(prefs.edit().putBoolean("confirmed", true).commit()) }
}
