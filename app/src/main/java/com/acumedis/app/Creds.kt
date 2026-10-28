package com.acumedis.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan email + password supaya tidak perlu diketik tiap login.
 *
 * Password TIDAK disimpan sebagai teks biasa. Dienkripsi AES/GCM dengan kunci
 * yang dibuat di Android Keystore: kunci itu tidak pernah keluar dari perangkat
 * dan tidak bisa diekspor, jadi file prefs yang disalin ke tempat lain tidak
 * berguna. Kalau perangkat di-root, penyerang masih bisa memanggil Keystore —
 * jadi ini kenyamanan, bukan pengganti kunci layar.
 */
object Creds {

    private const val PREFS = "acumedis"
    private const val KEY_EMAIL = "cred_email"
    private const val KEY_PASS = "cred_pass"
    private const val KEY_ALIAS = "acumedis_creds_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val body = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    fun save(ctx: Context, email: String, password: String) {
        prefs(ctx).edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_PASS, encrypt(password))
            .apply()
    }

    fun email(ctx: Context): String = prefs(ctx).getString(KEY_EMAIL, "") ?: ""

    fun password(ctx: Context): String {
        val blob = prefs(ctx).getString(KEY_PASS, null) ?: return ""
        return decrypt(blob) ?: ""
    }

    fun has(ctx: Context): Boolean = email(ctx).isNotEmpty() && password(ctx).isNotEmpty()

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_EMAIL).remove(KEY_PASS).apply()
    }
}
