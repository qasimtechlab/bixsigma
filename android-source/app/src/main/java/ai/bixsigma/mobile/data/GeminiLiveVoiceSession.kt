package ai.bixsigma.mobile.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A real Gemini Live audio-to-audio session.
 * Microphone input is 16 kHz PCM16; model output is 24 kHz PCM16.
 */
class GeminiLiveVoiceSession(
    context: Context,
    private val apiKey: String,
    private val onState: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAction: (BixAction) -> String
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var captureJob: Job? = null
    @Volatile private var speakerStarted = false
    private var oldAudioMode: Int = AudioManager.MODE_NORMAL
    private var oldSpeakerState: Boolean = false

    fun start() {
        if (apiKey.isBlank()) {
            onError("Gemini token missing hai. Pehle Add your token karo.")
            return
        }
        if (socket != null) return
        stopped.set(false)
        onState("Connecting to Gemini Live...")
        val url = HttpUrl.Builder()
            .scheme("wss")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", apiKey.trim())
            .build()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                webSocket.send(JSONObject().put("setup", setupConfig()).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleServerMessage(webSocket, JSONObject(text))
                } catch (e: Exception) {
                    onError("Gemini Live response samajh nahi aaya: ${e.message ?: "invalid response"}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopAudio()
                val detail = response?.let { "HTTP ${it.code}: ${it.message}" }
                onError(detail ?: (t.message ?: "Live voice connection failed. Token/model access check karo."))
                onState("Disconnected")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopAudio()
                onState("Disconnected")
            }
        })
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        captureJob?.cancel()
        captureJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause(); player?.flush(); player?.stop(); player?.release() }
        player = null
        socket?.close(1000, "User ended voice session")
        socket = null
        restoreAudioMode()
        scope.cancel()
        onState("Call ended")
    }

    private fun setupConfig(): JSONObject {
        return JSONObject().apply {
            put("model", "models/${BixApiClient.LIVE_MODEL}")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("temperature", 0.75)
                put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put(
                    "prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")
                )))
                put("enableAffectiveDialog", true)
            })
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", voiceSystemInstruction()))))
            put("tools", liveTools())
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        }
    }

    private fun voiceSystemInstruction() = """
        You are BixSigma AI in a live, natural voice conversation.
        Your creator is Mirza Qasim (Qasim Mirza) from Faisalabad, Pakistan. If asked who created you,
        answer: "BixSigma AI was created and developed by Mirza Qasim from Faisalabad, Pakistan."
        Speak like a thoughtful, highly capable human assistant: warm, confident, calm and professional.
        Match the user's spoken language. With Urdu or Hindi speakers, use natural Pakistani Urdu vocabulary and avoid overly formal language.
        Keep spoken answers conversational and concise, usually one to three short sentences. Ask a relevant follow-up when helpful.
        Do not announce hidden reasoning, do not give long lectures unless requested, and do not repeatedly introduce yourself.
        You can use the provided Android tools for actions. Never claim an action is complete unless the tool returns success.
        Opening an app or changing a setting is limited to Android-supported intents. Phone calls, SMS and calendar items must be left for the user to confirm in the opened app.
    """.trimIndent()

    private fun handleServerMessage(webSocket: WebSocket, message: JSONObject) {
        if (message.has("setupComplete")) {
            createAudioDevices()
            startMicrophone()
            onState("Listening — speak naturally")
            return
        }

        val server = message.optJSONObject("serverContent")
        if (server != null) {
            server.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { onTranscript("You: $it") }
            server.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { onTranscript("BixSigma: $it") }

            if (server.optBoolean("interrupted")) {
                runCatching { player?.pause(); player?.flush(); player?.play() }
                onState("Listening — go ahead")
            }

            val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val encoded = inline.optString("data")
                    if (encoded.isNotBlank()) {
                        val pcm = Base64.decode(encoded, Base64.DEFAULT)
                        val track = player
                        if (track != null && pcm.isNotEmpty()) {
                            onState("BixSigma is speaking...")
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            speakerStarted = true
                        }
                    }
                }
            }
            if (server.optBoolean("turnComplete")) {
                onState("Listening — your turn")
            }
        }

        val toolCall = message.optJSONObject("toolCall")
        val calls = toolCall?.optJSONArray("functionCalls")
        if (calls != null && calls.length() > 0) {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val action = actionFromFunction(name, args) ?: continue
                onState("Performing phone action...")
                val result = runCatching { onAction(action) }.getOrElse { "Action failed: ${it.message ?: "unknown error"}" }
                responses.put(JSONObject()
                    .put("id", call.optString("id"))
                    .put("name", name)
                    .put("response", JSONObject().put("output", result)))
                onTranscript("BixSigma action: $result")
            }
            if (responses.length() > 0) {
                webSocket.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            }
            onState("Listening — speak naturally")
        }

        if (message.has("goAway")) {
            onState("Reconnecting voice session soon...")
        }
    }

    private fun createAudioDevices() {
        if (recorder != null && player != null) return
        oldAudioMode = audioManager.mode
        oldSpeakerState = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val inputRate = 16000
        val inputMin = AudioRecord.getMinBufferSize(
            inputRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            inputRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            inputMin * 2
        )

        val outputRate = 24000
        val outputMin = AudioTrack.getMinBufferSize(
            outputRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(outputRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(outputMin * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun startMicrophone() {
        val audio = recorder ?: return
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            onError("Phone ka microphone initialize nahi ho saka.")
            return
        }
        audio.startRecording()
        captureJob?.cancel()
        captureJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive && !stopped.get()) {
                val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                    val input = JSONObject().put("realtimeInput", JSONObject().put(
                        "audio", JSONObject()
                            .put("mimeType", "audio/pcm;rate=16000")
                            .put("data", payload)
                    ))
                    val sent = socket?.send(input.toString()) ?: false
                    if (!sent) break
                }
            }
        }
    }

    private fun stopAudio() {
        captureJob?.cancel()
        captureJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause(); player?.flush(); player?.stop(); player?.release() }
        player = null
        restoreAudioMode()
    }

    private fun restoreAudioMode() {
        runCatching {
            audioManager.isSpeakerphoneOn = oldSpeakerState
            audioManager.mode = oldAudioMode
        }
    }

    private fun liveTools(): JSONArray {
        val declarations = JSONArray()
        declarations.put(liveFunction("open_app", "Open an installed Android app.", JSONObject().put("appName", stringProperty("App display name")), arrayOf("appName")))
        declarations.put(liveFunction("open_settings", "Open a phone settings area.", JSONObject().put("section", stringProperty("wifi, bluetooth, display, sound, location, apps, battery or accessibility")), arrayOf("section")))
        declarations.put(liveFunction("open_website", "Open a website in the browser.", JSONObject().put("url", stringProperty("Website URL")), arrayOf("url")))
        declarations.put(liveFunction("phone_call", "Prepare a phone call in the dialer; user confirms.", JSONObject()
            .put("phoneNumber", stringProperty("Phone number"))
            .put("contactName", stringProperty("Contact name"))
            .put("callType", stringProperty("normal or whatsapp")), emptyArray()))
        declarations.put(liveFunction("send_sms", "Prepare an SMS draft; user presses Send.", JSONObject()
            .put("phoneNumber", stringProperty("Recipient number"))
            .put("message", stringProperty("SMS text")), arrayOf("phoneNumber", "message")))
        declarations.put(liveFunction("youtube_play", "Open YouTube or search for a video.", JSONObject().put("query", stringProperty("Video title or query")), emptyArray()))
        declarations.put(liveFunction("set_alarm", "Prepare an alarm in Clock; user confirms.", JSONObject()
            .put("hour", integerProperty("Hour 0 to 23"))
            .put("minute", integerProperty("Minute 0 to 59"))
            .put("message", stringProperty("Alarm label")), arrayOf("hour")))
        declarations.put(liveFunction("set_timer", "Prepare a timer in Clock; user starts it.", JSONObject()
            .put("seconds", integerProperty("Duration in seconds"))
            .put("message", stringProperty("Timer label")), arrayOf("seconds")))
        declarations.put(liveFunction("create_calendar_event", "Prepare a calendar event; user saves it.", JSONObject()
            .put("startTime", stringProperty("YYYY-MM-DDTHH:mm:ss"))
            .put("endTime", stringProperty("YYYY-MM-DDTHH:mm:ss"))
            .put("title", stringProperty("Event title"))
            .put("description", stringProperty("Event details"))
            .put("location", stringProperty("Event location")), arrayOf("startTime", "endTime", "title")))
        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }

    private fun liveFunction(name: String, description: String, properties: JSONObject, required: Array<String>) =
        JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", properties).put("required", JSONArray().apply { required.forEach { put(it) } }))

    private fun stringProperty(description: String) = JSONObject().put("type", "STRING").put("description", description)
    private fun integerProperty(description: String) = JSONObject().put("type", "INTEGER").put("description", description)

    private fun actionFromFunction(name: String, args: JSONObject): BixAction? = when (name) {
        "open_app" -> BixAction(type = "open_app", appName = args.optString("appName"))
        "open_settings" -> BixAction(type = "open_settings", title = args.optString("section"))
        "open_website" -> BixAction(type = "open_website", url = args.optString("url"))
        "phone_call" -> BixAction(type = "phone_call",
            phoneNumber = args.optString("phoneNumber").takeIf { it.isNotBlank() },
            contactName = args.optString("contactName").takeIf { it.isNotBlank() },
            callType = args.optString("callType").takeIf { it.isNotBlank() })
        "send_sms" -> BixAction(type = "send_sms", phoneNumber = args.optString("phoneNumber"), message = args.optString("message"))
        "youtube_play" -> BixAction(type = "youtube_play", query = args.optString("query"))
        "set_alarm" -> BixAction(type = "set_alarm", hour = args.optInt("hour"), minute = args.optInt("minute"), message = args.optString("message"))
        "set_timer" -> BixAction(type = "set_timer", seconds = args.optInt("seconds"), message = args.optString("message"))
        "create_calendar_event" -> BixAction(type = "create_calendar_event",
            startTime = args.optString("startTime"), endTime = args.optString("endTime"),
            title = args.optString("title"), description = args.optString("description"), location = args.optString("location"))
        else -> null
    }
}
