package io.github.ciurlaro.codexmobile.extension.host

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import io.github.ciurlaro.codexmobile.agent.ProviderSecretStore
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecrets
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class AndroidProviderSecretStore(
    context: Context,
    private val pluginId: String,
) : ProviderSecretStore {
    private val file: AtomicFile
    private val keyAlias: String

    init {
        require(pluginId.matches(Regex("[a-z0-9-]+@[a-z0-9-]+"))) { "Provider plugin ID is invalid" }
        val digest = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8)).toHex()
        file = AtomicFile(File(context.noBackupFilesDir, "provider-secrets/$digest"))
        keyAlias = "codex-mobile-provider-$digest"
    }

    @Synchronized
    override fun snapshot(): ProviderSecrets {
        val values = read()
        return ProviderSecrets(values::get)
    }

    @Synchronized
    fun configured(name: String): Boolean = read()[name] != null

    @Synchronized
    override fun replace(values: Map<String, String>) {
        require(values.size <= MAX_VALUES && values.keys.all { it.matches(NAME) }) {
            "Provider secret names are invalid"
        }
        require(values.values.all { it.isNotBlank() && it.length <= MAX_VALUE_CHARS }) {
            "Provider secret values are invalid"
        }
        val plaintext = JSONObject(values.toSortedMap() as Map<*, *>).toString().toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Provider secrets exceed the size limit" }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(pluginId.toByteArray(Charsets.UTF_8))
        }
        val envelope = JSONObject()
            .put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
        file.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val output = file.startWrite()
        try {
            output.write(envelope)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
        keyStore().let { store -> if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias) }
    }

    private fun read(): Map<String, String> {
        if (!file.baseFile.isFile) return emptyMap()
        return try {
            val envelope = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
            check(envelope.getInt("version") == 1)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    checkNotNull(keyStore().getKey(keyAlias, null) as? SecretKey),
                    GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)),
                )
                updateAAD(pluginId.toByteArray(Charsets.UTF_8))
            }
            val plaintext = cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP))
            check(plaintext.size <= MAX_PLAINTEXT_BYTES)
            val values = JSONObject(String(plaintext, Charsets.UTF_8))
            check(values.length() <= MAX_VALUES)
            values.keys().asSequence().associateWith { name ->
                check(name.matches(NAME))
                values.getString(name).also { check(it.isNotBlank() && it.length <= MAX_VALUE_CHARS) }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Provider secrets are unreadable", error)
        }
    }

    private fun key(): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_VALUES = 32
        const val MAX_VALUE_CHARS = 16 * 1024
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        val NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
