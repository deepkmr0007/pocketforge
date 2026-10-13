package dev.pocketforge.ide.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.pocketforge.engine.common.Json
import dev.pocketforge.engine.common.JsonString
import dev.pocketforge.engine.storage.AtomicFileWriter
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (AI API keys, Build Host pairing tokens) encrypted with AES-256-GCM under a
 * non-exportable Android Keystore key. Ciphertext lives in app-private storage, excluded from
 * backups. Uses only platform crypto — no custom cryptography.
 */
class SecretVault(private val file: File) {
    private val alias = "pocketforge_vault_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized private fun readAll(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            Json.parseObject(file.readText()).fields.mapValues { (it.value as JsonString).value }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    @Synchronized fun put(name: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = Base64.getEncoder().encodeToString(cipher.iv + ct)
        val all = readAll().apply { put(name, blob) }
        AtomicFileWriter.write(file, Json.stringify(Json.wrap(all)).toByteArray())
    }

    @Synchronized fun get(name: String): String? {
        val blob = readAll()[name] ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(blob)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull() // key invalidated (e.g. after device reset) -> treated as "not set"
    }

    fun has(name: String) = readAll().containsKey(name)

    @Synchronized fun remove(name: String) {
        val all = readAll()
        if (all.remove(name) != null) AtomicFileWriter.write(file, Json.stringify(Json.wrap(all)).toByteArray())
    }

    /** Deletes all ciphertext and the Keystore key itself. */
    @Synchronized fun destroy() {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    companion object {
        const val AI_KEY = "ai.apiKey"
        const val HOST_TOKEN = "buildHost.token"
    }
}
