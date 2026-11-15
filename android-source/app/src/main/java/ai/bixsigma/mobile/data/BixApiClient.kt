package ai.bixsigma.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Direct Gemini Developer API client. No user-supplied backend URL is required.
 * For public production apps, use a protected backend or Firebase AI Logic/App Check:
 * a key entered on a device can still be extracted from that device by a determined attacker.
 */
class BixApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var cachedKeyFingerprint: String? = null
    @Volatile private var cachedTextModel: String? = null

    /**
     * Verifies authentication using the lightweight Models API rather than spending
     * a generation request just to check a token. It also selects a model that this
     * exact key can call with generateContent.
     */
    suspend fun checkApiKey(apiKey: String): String = withContext(Dispatchers.IO) {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) throw IOException("Token khali hai. Google AI Studio se Auth API key paste karo.")
        val model = resolveTextModel(key, forceRefresh = true)
        model
    }

    private fun normalizeApiKey(value: String): String {
        var key = value.trim()
        if (key.length >= 2 && (
                (key.first() == '"' && key.last() == '"') ||
                (key.first() == '\'' && key.last() == '\'') ||
                (key.first() == '`' && key.last() == '`')
            )) {
            key = key.substring(1, key.length - 1).trim()
        }
        return key.replace(Regex("\\s+"), "")
    }

    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun resolveTextModel(apiKey: String, forceRefresh: Boolean = false): String {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) throw IOException("Gemini token missing hai.")
        val hash = fingerprint(key)
        if (!forceRefresh && cachedKeyFingerprint == hash && !cachedTextModel.isNullOrBlank()) {
            return cachedTextModel!!
        }

        val request = Request.Builder()
            .url("$API_BASE/v1beta/models?pageSize=100")
            .header("x-goog-api-key", key)
            .header("Accept", "application/json")
            .get()
            .build()

        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        val responseData = call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                throw IOException(formatApiError(response.code, data, raw))
            }
            data ?: throw IOException("Google Gemini ne valid JSON response nahi di. Dobara try karo.")
        }

        val models = responseData.optJSONArray("models") ?: JSONArray()
        val available = linkedSetOf<String>()
        for (index in 0 until models.length()) {
            val item = models.optJSONObject(index) ?: continue
            val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            var supportsGenerate = false
            for (methodIndex in 0 until methods.length()) {
                if (methods.optString(methodIndex) == "generateContent") {
                    supportsGenerate = true
                    break
                }
            }
            if (!supportsGenerate) continue
            val id = item.optString("baseModelId").ifBlank {
                item.optString("name").removePrefix("models/")
            }
            if (id.isNotBlank()) available.add(id)
        }

        val preferences = listOf(
            TEXT_MODEL, FALLBACK_TEXT_MODEL, "gemini-3.8-flash",
            "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash",
            "gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-flash-lite"
        )
        val selected = preferences.firstOrNull { it in available }
            ?: available.firstOrNull { it.contains("flash", ignoreCase = true) }

        if (selected == null) {
            throw IOException(
                "Token authenticate ho gaya, lekin is Google project par koi supported Gemini Flash model " +
                    "nahi mila. AI Studio > Projects mein project import/enable karo aur API Keys mein " +
                    "Auth key ka Key Type check karo."
            )
        }

        cachedKeyFingerprint = hash
        cachedTextModel = selected
        return selected
    }

    private fun formatApiError(code: Int, data: JSONObject?, raw: String): String {
        val error = data?.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: raw.take(500).ifBlank { "No error details returned." }
        val status = error?.optString("status").orEmpty()
        val reason = runCatching {
            val details = error?.optJSONArray("details") ?: JSONArray()
            (0 until details.length()).firstNotNullOfOrNull { i ->
                details.optJSONObject(i)?.optString("reason")?.takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")

        return when {
            code == 400 && (message.contains("API key not valid", true) || reason.contains("API_KEY_INVALID", true)) ->
                "API token invalid hai. AI Studio se poori Auth key dobara copy karo; quotes ya API_KEY= text paste mat karo. ($code)"
            code == 400 && status.equals("FAILED_PRECONDITION", true) ->
                "Google project ki prerequisite poori nahi. AI Studio Projects/Billing mein Gemini API access aur region eligibility check karo. ($code)"
            code == 401 ->
                "Google ne token authenticate nahi kiya. AI Studio mein Key Type = Auth check karo; purani Standard key September 2026 ke baad reject ho sakti hai. ($code)"
            code == 403 ->
                "Token mila hai lekin Google project ko Gemini API ki permission nahi. AI Studio mein sahi project import karo aur API Keys ka Key Type/Generative Language API access check karo. ($code: $message)"
            code == 404 ->
                "Is Google project ke liye model/API endpoint available nahi mila. Project ke available models check karo. ($code: $message)"
            code == 429 ->
                "Gemini quota ya rate limit poori ho gayi. Nayi key banane se project quota reset nahi hota; quota reset ka intezar karo. ($code: $message)"
            code == 402 ->
                "Google project ka prepaid balance khatam hai. Billing status check karo. ($code)"
            code == 503 || code == 502 || code == 500 ->
                "Google Gemini service filhal busy hai. Internet check karke kuch der baad dobara try karo. ($code)"
            else -> "Gemini API error HTTP $code: $message"
        }
    }

    suspend fun sendMessage(
        apiKey: String,
        userName: String,
        message: String,
        history: List<BixMessage>,
        attachments: List<BixAttachment>,
        onChunk: suspend (String) -> Unit,
        onAction: suspend (BixAction) -> String
    ): String = withContext(Dispatchers.IO) {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) throw IOException("Gemini token missing hai. Settings mein Add your token karo.")
        val selectedModel = resolveTextModel(key)

        val contents = JSONArray()
        history.takeLast(24).forEach { item ->
            val text = item.text.trim()
            if (text.isNotBlank()) {
                contents.put(
                    JSONObject()
                        .put("role", if (item.sender == "user") "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }
        }
        if (contents.length() == 0) {
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", message))))
        }

        // Attach the selected document/image to the latest user turn.
        if (attachments.isNotEmpty()) {
            var lastUser = -1
            for (index in 0 until contents.length()) {
                if (contents.optJSONObject(index)?.optString("role") == "user") lastUser = index
            }
            if (lastUser < 0) {
                contents.put(JSONObject().put("role", "user").put("parts", JSONArray()))
                lastUser = contents.length() - 1
            }
            val parts = contents.getJSONObject(lastUser).optJSONArray("parts") ?: JSONArray()
            attachments.forEach { attachment ->
                val encoded = attachment.dataUrl.substringAfter("base64,", "")
                if (encoded.isBlank()) throw IOException("File data read nahi hui: ${attachment.name}")
                parts.put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", attachment.mimeType)
                    .put("data", encoded)))
                parts.put(JSONObject().put("text", "Attached file: ${attachment.name}"))
            }
            contents.getJSONObject(lastUser).put("parts", parts)
        }

        val completeText = StringBuilder()
        var round = 0
        while (round < 4) {
            round++
            val requestBody = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction(userName)))))
                put("contents", contents)
                put("tools", deviceTools())
                put("generationConfig", JSONObject()
                    .put("temperature", 0.72)
                    .put("topP", 0.92)
                    .put("maxOutputTokens", 4096)
                    .put("thinkingConfig", when {
                        selectedModel == TEXT_MODEL -> JSONObject().put("thinkingLevel", "medium")
                        selectedModel.startsWith("gemini-2.5") -> JSONObject().put("thinkingBudget", 0)
                        else -> JSONObject().put("thinkingLevel", "low")
                    }))
            }
            val response = executeGenerate(key, requestBody, selectedModel)
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw IOException(response.optJSONObject("error")?.optString("message") ?: "Gemini se response nahi mili.")
            val modelContent = candidate.optJSONObject("content") ?: JSONObject()
            val parts = modelContent.optJSONArray("parts") ?: JSONArray()
            val functions = JSONArray()
            val functionResponses = JSONArray()

            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    completeText.append(text)
                    withContext(Dispatchers.Main) { onChunk(text) }
                }
                val functionCall = part.optJSONObject("functionCall") ?: continue
                val name = functionCall.optString("name")
                val args = functionCall.optJSONObject("args") ?: JSONObject()
                val action = actionFromFunction(name, args) ?: continue
                val result = withContext(Dispatchers.Main) { onAction(action) }
                functions.put(JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", result)))
            }

            if (functions.length() == 0) break
            // Continue the same Gemini turn after the Android action has completed.
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val responseParts = JSONArray()
            for (i in 0 until functions.length()) {
                responseParts.put(JSONObject().put("functionResponse", functions.getJSONObject(i)))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responseParts))
        }

        val finalText = completeText.toString().trim()
        if (finalText.isBlank()) throw IOException("Gemini se text response nahi aaya. Token/model access check karo.")
        finalText
    }

    private fun executeGenerate(apiKey: String, body: JSONObject, model: String): JSONObject {
        val request = Request.Builder()
            .url("$API_BASE/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(38, TimeUnit.SECONDS)
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val detail = data?.optJSONObject("error")?.optString("message")
                val message = detail?.takeIf { it.isNotBlank() } ?: "Gemini API error"
                throw IOException(formatApiError(response.code, data, message))
            }
            return data ?: throw IOException("Gemini ka response JSON format mein nahi tha.")
        }
    }

    private fun extractText(response: JSONObject): String {
        val parts = response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .filter { it.isNotBlank() }.joinToString("")
    }

    private fun systemInstruction(userName: String) = """
        IDENTITY:
        You are Bix Sigma AI, an advanced conversational intelligence created by Mirza Qasim in Faisalabad, Pakistan.
        The signed-in user's display name is ${userName.ifBlank { "Friend" }}.
        If asked about your creator, credit Mirza Qasim from Faisalabad, Pakistan. Do not claim to be human.

        RESPONSE STYLE:
        - Answer directly, naturally and confidently. Avoid unnecessary greetings, filler and repetitive disclaimers.
        - Match the user's language and script. Support English, Urdu, Roman Urdu, Hindi and Punjabi.
        - Keep simple answers concise; structure detailed technical answers with clear headings and steps.
        - Be warm, professional and conversational.

        REASONING AND ACCURACY:
        - Use your own knowledge and reasoning first. Do not open Google or a browser for ordinary questions.
        - For current facts, use built-in Google Search grounding silently when appropriate.
        - Never invent live facts, prices, dates, statistics, installed-app status or action results.
        - If information cannot be verified, say so clearly.
        - Provide useful solutions for coding, mathematics, science and analysis.

        ANDROID ACTIONS:
        - Use native Android tools only when the user requests an action or it directly fulfils the request.
        - Never claim an action succeeded until Android returns success.
        - Calls, SMS and calendar events must remain user-confirmed in the relevant Android app.
        - If an app cannot be found or an action fails, explain the actual result.
    """.trimIndent()

    private fun deviceTools(): JSONArray {
        val declarations = JSONArray()
        declarations.put(functionDeclaration("list_installed_apps", "List launchable apps installed on this phone.", JSONObject(), emptyArray()))
        declarations.put(functionDeclaration("open_app", "Open an installed Android application by its visible app name.", obj(
            "appName" to prop("STRING", "Visible name of the app to open"),
            "query" to prop("STRING", "Optional search text, if opening a search screen")
        ), arrayOf("appName")))
        declarations.put(functionDeclaration("open_settings", "Open an Android settings area such as Wi-Fi, Bluetooth, display, sound or location.", obj(
            "section" to prop("STRING", "Settings area: wifi, bluetooth, display, sound, location, apps, battery, accessibility")
        ), arrayOf("section")))
        declarations.put(functionDeclaration("open_website", "Open a website in the phone browser.", obj("url" to prop("STRING", "Full website address")), arrayOf("url")))
        declarations.put(functionDeclaration("phone_call", "Open the phone dialer with a phone number. The user confirms the call.", obj(
            "phoneNumber" to prop("STRING", "Phone number, if known"),
            "contactName" to prop("STRING", "Contact name, if known"),
            "callType" to prop("STRING", "Optional call type such as normal or whatsapp")
        ), emptyArray()))
        declarations.put(functionDeclaration("send_sms", "Prepare an SMS draft. Never send a message without the user confirming in the messaging app.", obj(
            "phoneNumber" to prop("STRING", "Recipient phone number"),
            "message" to prop("STRING", "SMS text")
        ), arrayOf("phoneNumber", "message")))
        declarations.put(functionDeclaration("youtube_play", "Open YouTube or search for a video on YouTube.", obj("query" to prop("STRING", "Video title or search phrase")), emptyArray()))
        declarations.put(functionDeclaration("set_alarm", "Open the Clock app with a proposed alarm; user confirms saving.", obj(
            "hour" to prop("INTEGER", "Hour from 0 to 23"),
            "minute" to prop("INTEGER", "Minute from 0 to 59"),
            "message" to prop("STRING", "Alarm label")
        ), arrayOf("hour")))
        declarations.put(functionDeclaration("set_timer", "Prepare a countdown timer in the Clock app; user confirms starting it.", obj(
            "seconds" to prop("INTEGER", "Timer duration in seconds"),
            "message" to prop("STRING", "Timer label")
        ), arrayOf("seconds")))
        declarations.put(functionDeclaration("create_calendar_event", "Open a calendar event draft for the user to review and save.", obj(
            "startTime" to prop("STRING", "Start local time in YYYY-MM-DDTHH:mm:ss format"),
            "endTime" to prop("STRING", "End local time in YYYY-MM-DDTHH:mm:ss format"),
            "title" to prop("STRING", "Event title"),
            "description" to prop("STRING", "Event details"),
            "location" to prop("STRING", "Event location")
        ), arrayOf("startTime", "endTime", "title")))
        return JSONArray()
            .put(JSONObject().put("googleSearch", JSONObject()))
            .put(JSONObject().put("functionDeclarations", declarations))
    }

    private fun functionDeclaration(name: String, description: String, properties: JSONObject, required: Array<String>) =
        JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", properties).put("required", JSONArray().apply { required.forEach { put(it) } }))

    private fun obj(vararg fields: Pair<String, JSONObject>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    private fun actionFromFunction(name: String, args: JSONObject): BixAction? = when (name) {
        "list_installed_apps" -> BixAction(type = "list_apps")
        "open_app" -> BixAction(type = "open_app", appName = args.optString("appName"), query = args.optString("query").takeIf { it.isNotBlank() })
        "open_settings" -> BixAction(type = "open_settings", title = args.optString("section"))
        "open_website" -> BixAction(type = "open_website", url = args.optString("url"))
        "phone_call" -> BixAction(type = "phone_call", phoneNumber = args.optString("phoneNumber").takeIf { it.isNotBlank() }, contactName = args.optString("contactName").takeIf { it.isNotBlank() }, callType = args.optString("callType").takeIf { it.isNotBlank() })
        "send_sms" -> BixAction(type = "send_sms", phoneNumber = args.optString("phoneNumber"), message = args.optString("message"))
        "youtube_play" -> BixAction(type = "youtube_play", query = args.optString("query"))
        "set_alarm" -> BixAction(type = "set_alarm", hour = args.optInt("hour"), minute = args.optInt("minute"), message = args.optString("message"))
        "set_timer" -> BixAction(type = "set_timer", seconds = args.optInt("seconds"), message = args.optString("message"))
        "create_calendar_event" -> BixAction(type = "create_calendar_event", startTime = args.optString("startTime"), endTime = args.optString("endTime"), title = args.optString("title"), description = args.optString("description"), location = args.optString("location"))
        else -> null
    }

    companion object {
        const val API_BASE = "https://generativelanguage.googleapis.com"
        const val TEXT_MODEL = "gemini-4-hydrogen"
        const val FALLBACK_TEXT_MODEL = "gemini-3.1-flash-lite"
        const val LIVE_MODEL = "gemini-3.8-live"
        const val FALLBACK_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val TOKEN_URL = "https://aistudio.google.com/apikey"
    }
}
