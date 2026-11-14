package ai.bixsigma.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Uses the existing BixSigma Firebase project's Authentication and Firestore REST APIs.
 * This keeps the same Firebase user pool/database as the website and avoids requiring
 * a second Firebase project. The Firebase Web API key is a public project identifier,
 * not a server secret; Firebase Security Rules must remain enabled.
 */
class FirebaseRestRepository(private val settings: SettingsStore) {
    companion object {
        private const val FIREBASE_API_KEY = "AIzaSyAi6kbp-uoyGe7D_oHwzsk-QRyApwyDrX8"
        private const val PROJECT_ID = "bix-sigma"
        private const val AUTH_BASE = "https://identitytoolkit.googleapis.com/v1"
        private const val TOKEN_URL = "https://securetoken.googleapis.com/v1/token"
        private const val FIRESTORE_BASE = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun signUp(name: String, email: String, password: String): BixAccount = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true)
        val result = postJson("$AUTH_BASE/accounts:signUp?key=$FIREBASE_API_KEY", body)
        var account = accountFrom(result, name)
        settings.account = account
        if (name.isNotBlank()) {
            val update = JSONObject().put("idToken", account.idToken).put("displayName", name.trim()).put("returnSecureToken", true)
            val updated = postJson("$AUTH_BASE/accounts:update?key=$FIREBASE_API_KEY", update)
            account = account.copy(
                displayName = updated.optString("displayName", name.trim()),
                idToken = updated.optString("idToken", account.idToken),
                refreshToken = updated.optString("refreshToken", account.refreshToken)
            )
        }
        settings.account = account
        account
    }

    suspend fun signIn(email: String, password: String): BixAccount = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true)
        val result = postJson("$AUTH_BASE/accounts:signInWithPassword?key=$FIREBASE_API_KEY", body)
        val account = accountFrom(result, result.optString("displayName", email.substringBefore('@')))
        settings.account = account
        account
    }

    suspend fun saveChatHistory(account: BixAccount, userText: String, assistantText: String) = withContext(Dispatchers.IO) {
        val latest = settings.account?.takeIf { it.uid == account.uid } ?: account
        val valid = refreshIfNeeded(latest)
        val summary = "You: ${userText.take(450)}\nBixSigma: ${assistantText.take(450)}"
        val fields = JSONObject().apply {
            put("timestamp", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
            put("summary", JSONObject().put("stringValue", summary))
            put("userTone", JSONObject().put("stringValue", "Sigma Adaptive"))
            put("notes", JSONObject().put("stringValue", "Saved from BixSigma Android"))
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            put("createdAt", JSONObject().put("timestampValue", iso))
        }
        val body = JSONObject().put("fields", fields)
        val request = Request.Builder()
            .url("$FIRESTORE_BASE/users/${valid.uid}/history")
            .header("Authorization", "Bearer ${valid.idToken}")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Firestore history save failed (${response.code}): ${response.body?.string().orEmpty()}")
        }
    }

    suspend fun refreshIfNeeded(account: BixAccount): BixAccount = withContext(Dispatchers.IO) {
        // A refresh attempt is cheap; on invalid refresh token keep the current account and
        // let Firebase return an actionable auth error on the next protected operation.
        val form = "grant_type=refresh_token&refresh_token=${java.net.URLEncoder.encode(account.refreshToken, "UTF-8")}".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder().url("$TOKEN_URL?key=$FIREBASE_API_KEY").post(form).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext account
            val j = JSONObject(raw)
            val refreshed = account.copy(
                idToken = j.optString("id_token", account.idToken),
                refreshToken = j.optString("refresh_token", account.refreshToken)
            )
            settings.account = refreshed
            refreshed
        }
    }

    private fun accountFrom(j: JSONObject, fallbackName: String): BixAccount = BixAccount(
        uid = j.getString("localId"),
        displayName = j.optString("displayName").ifBlank { fallbackName },
        email = j.optString("email"),
        idToken = j.getString("idToken"),
        refreshToken = j.getString("refreshToken")
    )

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (!response.isSuccessful) {
                val code = result.optJSONObject("error")?.optString("message") ?: "HTTP_${response.code}"
                throw IOException(friendlyFirebaseError(code))
            }
            return result
        }
    }

    private fun friendlyFirebaseError(code: String): String = when {
        code.contains("EMAIL_EXISTS") -> "Ye email pehle se registered hai. Login select karo."
        code.contains("EMAIL_NOT_FOUND") || code.contains("INVALID_PASSWORD") || code.contains("INVALID_LOGIN_CREDENTIALS") -> "Email ya password sahi nahi hai."
        code.contains("WEAK_PASSWORD") -> "Password kam az kam 6 characters ka hona chahiye."
        code.contains("INVALID_EMAIL") -> "Email address valid nahi lagta."
        code.contains("TOO_MANY_ATTEMPTS") -> "Bohat zyada attempts hue hain. Kuch dair baad try karo."
        else -> "Firebase authentication error: $code"
    }
}
