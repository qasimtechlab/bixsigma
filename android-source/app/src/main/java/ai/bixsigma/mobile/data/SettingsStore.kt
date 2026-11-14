package ai.bixsigma.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted local settings backed by an AES-256-GCM key stored in Android Keystore.
 * Only the ciphertext is written to private SharedPreferences. This avoids storing
 * Gemini credentials and Firebase refresh tokens as plaintext preferences.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "bixsigma_encrypted_store",
        Context.MODE_PRIVATE
    )

    @Synchronized
    private fun readDocument(): JSONObject {
        val encoded = prefs.getString(DATA_KEY, null) ?: return JSONObject()
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > IV_LENGTH) { "Encrypted settings data is invalid." }
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val cipherText = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        }.getOrElse { JSONObject() }
    }

    @Synchronized
    private fun writeDocument(document: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(document.toString().toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        prefs.edit().putString(DATA_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    var apiKey: String
        @Synchronized get() = readDocument().optString("gemini_api_key")
        @Synchronized set(value) {
            val doc = readDocument()
            doc.put("gemini_api_key", value)
            writeDocument(doc)
        }

    var account: BixAccount?
        @Synchronized get() {
            val account = readDocument().optJSONObject("firebase_account") ?: return null
            return runCatching {
                BixAccount(
                    uid = account.getString("uid"),
                    displayName = account.optString("displayName"),
                    email = account.getString("email"),
                    idToken = account.getString("idToken"),
                    refreshToken = account.getString("refreshToken")
                )
            }.getOrNull()
        }
        @Synchronized set(value) {
            val doc = readDocument()
            if (value == null) doc.remove("firebase_account")
            else {
                doc.put("firebase_account", JSONObject().apply {
                    put("uid", value.uid)
                    put("displayName", value.displayName)
                    put("email", value.email)
                    put("idToken", value.idToken)
                    put("refreshToken", value.refreshToken)
                })
            }
            writeDocument(doc)
        }

    fun clearAccount() {
        account = null
    }

    @Synchronized
    fun loadChatMessages(uid: String): List<BixMessage> {
        val array = readDocument().optJSONArray(chatKey(uid)) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            BixMessage(
                id = item.optString("id"),
                sender = item.optString("sender"),
                text = item.optString("text"),
                time = item.optLong("time", System.currentTimeMillis())
            )
        }
    }

    @Synchronized
    fun saveChatMessages(uid: String, messages: List<BixMessage>) {
        val array = JSONArray()
        messages.takeLast(100).forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("sender", item.sender)
                .put("text", item.text)
                .put("time", item.time))
        }
        val doc = readDocument()
        doc.put(chatKey(uid), array)
        writeDocument(doc)
    }

    private fun chatKey(uid: String) = "chat_history_$uid"

    private companion object {
        const val DATA_KEY = "encrypted_payload_v1"
        const val KEY_ALIAS = "ai.bixsigma.mobile.settings.aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
