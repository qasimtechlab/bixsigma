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
import java.util.concurrent.TimeUnit

class BixApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun checkBackend(baseUrl: String): String = withContext(Dispatchers.IO) {
        val url = normalizeBase(baseUrl) + "/api/health"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Backend health check failed: HTTP ${response.code}")
            val data = JSONObject(response.body?.string().orEmpty())
            data.optString("status", "unknown")
        }
    }

    suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        userName: String,
        message: String,
        history: List<BixMessage>,
        attachments: List<BixAttachment>,
        onChunk: suspend (String) -> Unit,
        onAction: suspend (BixAction) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val requestData = JSONObject().apply {
            put("message", message)
            put("userName", userName)
            if (apiKey.isNotBlank()) put("apiKey", apiKey)
            val hist = JSONArray()
            history.takeLast(24).forEach { item ->
                hist.put(JSONObject().put("sender", item.sender).put("text", item.text))
            }
            put("history", hist)
            val files = JSONArray()
            attachments.forEach { a ->
                files.put(JSONObject().put("name", a.name).put("type", a.mimeType).put("dataUrl", a.dataUrl))
            }
            put("attachments", files)
        }
        val base = normalizeBase(baseUrl)
        val request = Request.Builder()
            .url("$base/api/chat-stream")
            .post(requestData.toString().toRequestBody(jsonType))
            .header("Accept", "text/event-stream, application/json")
            .build()

        var accumulated = StringBuilder()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                val messageText = runCatching { JSONObject(detail).optString("error") }.getOrNull()
                throw IOException(messageText?.takeIf { it.isNotBlank() } ?: "BixSigma server returned HTTP ${response.code}.")
            }
            val body = response.body ?: throw IOException("Server ne empty response di.")
            val source = body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val raw = trimmed.removePrefix("data:").trim()
                if (raw.isBlank()) continue
                val event = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                if (event.has("action")) {
                    val action = event.optJSONObject("action")
                    if (action != null) withContext(Dispatchers.Main) { onAction(action.toBixAction()) }
                }
                val chunk = event.optString("chunk")
                if (chunk.isNotEmpty()) {
                    accumulated.append(chunk)
                    withContext(Dispatchers.Main) { onChunk(chunk) }
                }
                val error = event.optString("error")
                if (error.isNotBlank() && accumulated.isEmpty()) throw IOException(error)
            }
        }

        // Fallback mirrors the website's existing behavior if the stream produced no text.
        if (accumulated.isBlank()) {
            val fallback = Request.Builder()
                .url("$base/api/chat")
                .post(requestData.toString().toRequestBody(jsonType))
                .build()
            client.newCall(fallback).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val data = runCatching { JSONObject(raw) }.getOrNull()
                if (!response.isSuccessful) throw IOException(data?.optString("error")?.takeIf { it.isNotBlank() } ?: "AI request failed: HTTP ${response.code}")
                val reply = data?.optString("reply")?.takeIf { it.isNotBlank() } ?: data?.optString("response").orEmpty()
                if (reply.isBlank()) throw IOException("BixSigma AI se koi text response nahi mila.")
                accumulated.append(reply)
                withContext(Dispatchers.Main) { onChunk(reply) }
            }
        }
        accumulated.toString()
    }

    private fun normalizeBase(base: String): String {
        val clean = base.trim().trimEnd('/')
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) {
            throw IOException("Backend URL https:// se shuru hona chahiye.")
        }
        return clean
    }

    private fun JSONObject.toBixAction(): BixAction = BixAction(
        type = optString("type"),
        appName = optString("appName").takeIf { it.isNotBlank() },
        query = optString("query").takeIf { it.isNotBlank() },
        url = optString("url").takeIf { it.isNotBlank() },
        phoneNumber = optString("phoneNumber").takeIf { it.isNotBlank() },
        contactName = optString("contactName").takeIf { it.isNotBlank() },
        callType = optString("callType").takeIf { it.isNotBlank() },
        hour = if (has("hour")) optInt("hour") else null,
        minute = if (has("minute")) optInt("minute") else null,
        seconds = if (has("seconds")) optInt("seconds") else null,
        message = optString("message").takeIf { it.isNotBlank() },
        title = optString("title").takeIf { it.isNotBlank() },
        startTime = optString("startTime").takeIf { it.isNotBlank() },
        endTime = optString("endTime").takeIf { it.isNotBlank() },
        description = optString("description").takeIf { it.isNotBlank() },
        location = optString("location").takeIf { it.isNotBlank() }
    )
}
