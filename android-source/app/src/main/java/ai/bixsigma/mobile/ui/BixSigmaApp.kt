package ai.bixsigma.mobile.ui

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bixsigma.mobile.actions.AndroidActionExecutor
import ai.bixsigma.mobile.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val Bg = Color(0xFF070B16)
private val Panel = Color(0xFF111827)
private val PanelAlt = Color(0xFF182235)
private val Cyan = Color(0xFF35D6E8)
private val Blue = Color(0xFF547BFF)
private val Muted = Color(0xFF94A3B8)
private val White = Color(0xFFF1F5F9)

@Composable
fun BixSigmaApp() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val firebase = remember { FirebaseRestRepository(settings) }
    val api = remember { BixApiClient() }
    var account by remember { mutableStateOf(settings.account) }
    var checking by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        account?.let { saved -> runCatching { firebase.refreshIfNeeded(saved) }.onSuccess { account = it } }
        checking = false
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Blue, background = Bg, surface = Panel, onPrimary = Bg, onBackground = White, onSurface = White)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            when {
                checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cyan) }
                account == null || settings.apiKey.isBlank() || settings.backendUrl.isBlank() -> AuthScreen(settings, firebase, api) { account = it }
                else -> ChatScreen(account!!, settings, firebase, api) {
                    settings.clearAccount()
                    account = null
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(
    settings: SettingsStore,
    firebase: FirebaseRestRepository,
    api: BixApiClient,
    onAuthenticated: (BixAccount) -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var step by remember { mutableIntStateOf(if (settings.apiKey.isNotBlank() && settings.backendUrl.isNotBlank()) 1 else 0) }
    var register by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(settings.apiKey) }
    var backend by remember { mutableStateOf(settings.backendUrl) }
    var showKey by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }

    LaunchedEffect(step) { scrollState.animateScrollTo(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(PanelAlt)
                    .border(1.dp, Cyan.copy(alpha = .75f), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("BΣ", color = Cyan, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("BIXSIGMA AI", color = White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, letterSpacing = 1.3.sp)
                Text("NATIVE MOBILE INTELLIGENCE", color = Cyan, fontSize = 9.sp, letterSpacing = 1.2.sp)
            }
            Surface(color = Color(0xFF102733), shape = RoundedCornerShape(20.dp)) {
                Text("SECURE SETUP", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(
                if (step == 0) "Let's connect your AI" else "One last step",
                color = White,
                fontSize = 25.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (step == 0) "Connect your Gemini key and live BixSigma server. This is a one-time setup."
                else "Create your BixSigma account or sign in to continue.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SetupStepCard(Modifier.weight(1f), "01", "AI connection", step == 0, step > 0)
            SetupStepCard(Modifier.weight(1f), "02", "Your account", step == 1, false)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(3.dp))
            Text("SCROLL DOWN TO COMPLETE SETUP", color = Muted, fontSize = 9.sp, letterSpacing = .8.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .border(1.dp, Color(0xFF26364D), RoundedCornerShape(22.dp))
                .padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step == 0) {
                Text("CONNECT GEMINI + BACKEND", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Enter the credentials used by your BixSigma deployment.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)

                DarkField(
                    value = key,
                    onChange = { key = it; error = "" },
                    label = "Gemini API key",
                    secret = true,
                    secretVisible = showKey,
                    onToggleSecret = { showKey = !showKey },
                    keyboard = KeyboardType.Ascii
                )
                DarkField(
                    value = backend,
                    onChange = { backend = it; error = "" },
                    label = "Live BixSigma backend URL",
                    keyboard = KeyboardType.Uri
                )
                Text(
                    "Use your deployed server's HTTPS address, for example https://your-server.example.com. Google AI Studio editor URL yahan kaam nahi karega.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Surface(color = Color(0xFF0D1C2C), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("A connection test runs before moving to account setup. Your key is stored in encrypted app storage.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                if (error.isNotBlank()) AuthMessage(error, isError = true)
                if (success.isNotBlank()) AuthMessage(success, isError = false)

                Button(
                    onClick = {
                        error = ""
                        success = ""
                        if (key.isBlank() || backend.isBlank()) {
                            error = "Gemini API key aur backend URL dono enter karo."
                        } else {
                            loading = true
                            scope.launch {
                                try {
                                    val status = api.checkBackend(backend.trim())
                                    if (!status.equals("ok", ignoreCase = true)) {
                                        throw IllegalStateException("Backend ne expected OK status return nahi kiya.")
                                    }
                                    settings.apiKey = key.trim()
                                    settings.backendUrl = backend.trim()
                                    success = "Backend connected successfully."
                                    step = 1
                                } catch (e: Exception) {
                                    error = e.message ?: "Connection test failed. URL check karke dobara try karo."
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Bg)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(19.dp), color = Bg, strokeWidth = 2.dp)
                    else Text("TEST CONNECTION  •  NEXT", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("AI CONNECTION READY", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { step = 0; error = ""; success = "" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Muted, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Edit", color = Muted, fontSize = 11.sp)
                    }
                }
                Text("YOUR BIXSIGMA ACCOUNT", color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Your existing Firebase account works here too.", color = Muted, fontSize = 12.sp)

                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Bg).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuthTab("Create account", register, Modifier.weight(1f)) { register = true; error = "" }
                    AuthTab("Login", !register, Modifier.weight(1f)) { register = false; error = "" }
                }
                if (register) {
                    DarkField(name, { name = it; error = "" }, "Full name", keyboard = KeyboardType.Text)
                }
                DarkField(email, { email = it; error = "" }, "Email address", keyboard = KeyboardType.Email)
                DarkField(
                    password,
                    { password = it; error = "" },
                    "Password",
                    secret = true,
                    secretVisible = showPassword,
                    onToggleSecret = { showPassword = !showPassword },
                    keyboard = KeyboardType.Password
                )
                if (register) {
                    DarkField(
                        confirmPassword,
                        { confirmPassword = it; error = "" },
                        "Confirm password",
                        secret = true,
                        secretVisible = showConfirmPassword,
                        onToggleSecret = { showConfirmPassword = !showConfirmPassword },
                        keyboard = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                }
                if (error.isNotBlank()) AuthMessage(error, isError = true)

                Button(
                    onClick = {
                        error = ""
                        val cleanEmail = email.trim()
                        when {
                            cleanEmail.isBlank() || password.isBlank() || (register && name.isBlank()) -> {
                                error = "Required fields fill karo."
                            }
                            !cleanEmail.contains("@") || !cleanEmail.substringAfter("@", "").contains(".") -> {
                                error = "Valid email address enter karo."
                            }
                            register && password.length < 6 -> {
                                error = "Password kam az kam 6 characters ka hona chahiye."
                            }
                            register && password != confirmPassword -> {
                                error = "Dono passwords match nahi karte."
                            }
                            else -> {
                                loading = true
                                scope.launch {
                                    try {
                                        val user = if (register) {
                                            firebase.signUp(name.trim(), cleanEmail, password)
                                        } else {
                                            firebase.signIn(cleanEmail, password)
                                        }
                                        onAuthenticated(user)
                                    } catch (e: Exception) {
                                        error = e.message ?: "Account connection failed. Dobara try karo."
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Bg)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(19.dp), color = Bg, strokeWidth = 2.dp)
                    else Text(if (register) "CREATE ACCOUNT & CONTINUE" else "LOGIN & CONTINUE", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                Text(
                    if (register) "Already registered? Login tab select karo."
                    else "New to BixSigma? Create account tab select karo.",
                    modifier = Modifier.fillMaxWidth(),
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Made with care by Mirza Qasim", color = Muted, fontSize = 11.sp)
        Text("BixSigma AI  •  Native Android", color = Color(0xFF64748B), fontSize = 10.sp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SetupStepCard(modifier: Modifier, number: String, label: String, selected: Boolean, complete: Boolean) {
    Surface(
        modifier = modifier,
        color = if (selected || complete) Color(0xFF102733) else Panel,
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected || complete) Cyan.copy(alpha = .55f) else Color(0xFF26364D))
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (complete) "✓" else number, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(label, color = if (selected || complete) White else Muted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AuthMessage(message: String, isError: Boolean) {
    Surface(
        color = if (isError) Color(0xFF351D29) else Color(0xFF0D2B28),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(10.dp),
            color = if (isError) Color(0xFFFFA3A3) else Color(0xFF7DE3C2),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun AuthTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) PanelAlt else Color.Transparent,
            contentColor = if (selected) Cyan else Muted
        )
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun DarkField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
    secretVisible: Boolean = false,
    onToggleSecret: (() -> Unit)? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp),
        visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (secret && onToggleSecret != null) {
            {
                IconButton(onClick = onToggleSecret) {
                    Icon(
                        if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (secretVisible) "Hide $label" else "Show $label",
                        tint = Muted
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { focusManager.clearFocus() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cyan,
            unfocusedBorderColor = Color(0xFF34445B),
            focusedLabelColor = Cyan,
            unfocusedLabelColor = Muted,
            focusedTextColor = White,
            unfocusedTextColor = White,
            cursorColor = Cyan
        ),
        shape = RoundedCornerShape(13.dp)
    )
}

@Composable
private fun ChatScreen(account: BixAccount, settings: SettingsStore, firebase: FirebaseRestRepository, api: BixApiClient, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val executor = remember { AndroidActionExecutor(context) }
    val chatListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val messages = remember(account.uid) {
        mutableStateListOf<BixMessage>().apply {
            val restored = settings.loadChatMessages(account.uid)
            if (restored.isNotEmpty()) addAll(restored) else add(BixMessage(sender = "bix", text = "Assalam-o-alaikum ${account.displayName.ifBlank { "there" }}! Main BixSigma AI hoon. Gemini ke saath ab Android apps aur supported phone actions bhi perform kar sakta hoon. Batao, kya karna hai?"))
        }
    }
    var input by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<BixAttachment?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var showSettingsKey by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var backend by remember { mutableStateOf(settings.backendUrl) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) chatListState.scrollToItem(messages.lastIndex)
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) input = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx) else "attachment"
                    } ?: "attachment"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("File read nahi hui.")
                    if (bytes.size > 8 * 1024 * 1024) throw IllegalStateException("8 MB se bari file abhi attach nahi ho sakti.")
                    BixAttachment(fileName, mime, "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
                }
                attachment = loaded
            } catch (e: Exception) {
                errorText = e.message ?: "Attachment failed"
            }
        }
    }

    fun send() {
        val typed = input.trim()
        val file = attachment
        if ((typed.isBlank() && file == null) || busy) return
        input = ""; attachment = null; errorText = ""
        val prompt = typed.ifBlank { "Please analyze attached file: ${file?.name}" }
        val userMessage = BixMessage(sender = "user", text = prompt + (file?.let { "\nAttachment: ${it.name}" } ?: ""))
        messages.add(userMessage)
        settings.saveChatMessages(account.uid, messages.toList())
        val botId = java.util.UUID.randomUUID().toString()
        messages.add(BixMessage(id = botId, sender = "bix", text = ""))
        val history = messages.dropLast(1).toList()
        busy = true
        scope.launch {
            try {
                val answer = api.sendMessage(
                    baseUrl = settings.backendUrl,
                    apiKey = settings.apiKey,
                    userName = account.displayName.ifBlank { account.email.substringBefore('@') },
                    message = prompt,
                    history = history,
                    attachments = listOfNotNull(file),
                    onChunk = { part ->
                        val i = messages.indexOfFirst { it.id == botId }
                        if (i >= 0) messages[i] = messages[i].copy(text = messages[i].text + part)
                    },
                    onAction = { action -> notice = executor.execute(action) }
                )
                val i = messages.indexOfFirst { it.id == botId }
                if (i >= 0 && messages[i].text.isBlank()) messages[i] = messages[i].copy(text = answer)
                settings.saveChatMessages(account.uid, messages.toList())
                runCatching { firebase.saveChatHistory(account, prompt, answer) }
            } catch (e: Exception) {
                val i = messages.indexOfFirst { it.id == botId }
                if (i >= 0) messages[i] = messages[i].copy(text = "Connection issue: ${e.message ?: "request failed"}")
                errorText = e.message ?: "Request failed"
                settings.saveChatMessages(account.uid, messages.toList())
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(PanelAlt).border(1.dp, Cyan, CircleShape), contentAlignment = Alignment.Center) { Text("BΣ", color = Cyan, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("BixSigma AI", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("MOBILE INTELLIGENCE • ONLINE", color = Cyan, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            IconButton(onClick = { showSettings = true; errorText = "" }) { Icon(Icons.Filled.Settings, "Settings", tint = Muted) }
            IconButton(onClick = onLogout) { Icon(Icons.Filled.Logout, "Logout", tint = Muted) }
        }
        if (notice.isNotBlank()) Row(Modifier.fillMaxWidth().background(Color(0xFF0D2430)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f), color = Cyan, fontSize = 11.sp)
            TextButton(onClick = { notice = "" }) { Text("OK", color = Cyan) }
        }
        LazyColumn(
            state = chatListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            items(messages, key = { it.id }) { item ->
                MessageBubble(
                    message = item,
                    onSpeak = { text -> if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, item.id) },
                    onCopy = { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        notice = "Response copied to clipboard."
                    }
                )
            }
            if (busy) item { Text("BixSigma soch raha hai...", color = Cyan, fontSize = 11.sp) }
        }
        if (errorText.isNotBlank()) Text(errorText, Modifier.padding(horizontal = 13.dp), color = Color(0xFFFF9292), fontSize = 10.sp)
        attachment?.let { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📎 ${it.name}", Modifier.weight(1f), color = Cyan, fontSize = 11.sp)
            TextButton(onClick = { attachment = null }) { Text("Remove", color = Muted) }
        } }
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 7.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Filled.AttachFile, "Attach", tint = Muted) }
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "BixSigma ko command dein")
                }
                runCatching { speechLauncher.launch(intent) }.onFailure { errorText = "Speech recognition service available nahi." }
            }, enabled = !busy) { Icon(Icons.Filled.Mic, "Voice input", tint = Cyan) }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message BixSigma...", color = Muted, fontSize = 12.sp) }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF334155), focusedTextColor = White, unfocusedTextColor = White, cursorColor = Cyan),
                shape = RoundedCornerShape(17.dp)
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { send() }, enabled = !busy && (input.isNotBlank() || attachment != null), modifier = Modifier.size(46.dp).clip(CircleShape).background(Cyan)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Bg, strokeWidth = 2.dp) else Icon(Icons.Filled.Send, "Send", tint = Bg)
            }
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("BixSigma Settings", color = White) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DarkField(
                    key, { key = it }, "Gemini API key",
                    secret = true,
                    secretVisible = showSettingsKey,
                    onToggleSecret = { showSettingsKey = !showSettingsKey },
                    keyboard = KeyboardType.Ascii
                )
                DarkField(backend, { backend = it }, "Backend URL", keyboard = KeyboardType.Uri)
                if (errorText.isNotBlank()) AuthMessage(errorText, isError = true)
                Text("API key encrypted storage mein save hoti hai. Backend par /api/health endpoint required hai.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !settingsBusy,
                onClick = {
                    errorText = ""
                    if (key.isBlank() || backend.isBlank()) {
                        errorText = "Gemini API key aur backend URL dono zaroori hain."
                    } else {
                        settingsBusy = true
                        scope.launch {
                            try {
                                val status = api.checkBackend(backend.trim())
                                if (!status.equals("ok", ignoreCase = true)) {
                                    throw IllegalStateException("Backend ne expected OK status return nahi kiya.")
                                }
                                settings.apiKey = key.trim()
                                settings.backendUrl = backend.trim()
                                showSettings = false
                                notice = "Settings saved. Backend: $status"
                            } catch (e: Exception) {
                                errorText = e.message ?: "Backend check failed"
                            } finally {
                                settingsBusy = false
                            }
                        }
                    }
                }
            ) {
                if (settingsBusy) CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                else Text("SAVE & TEST", color = Cyan)
            }
        },
        dismissButton = { TextButton(onClick = { showSettings = false }) { Text("CANCEL", color = Muted) } },
        containerColor = Panel
    )
}

@Composable
private fun MessageBubble(message: BixMessage, onSpeak: (String) -> Unit, onCopy: (String) -> Unit) {
    val user = message.sender == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(18.dp)).background(if (user) Color(0xFF17344A) else Panel)
            .border(1.dp, if (user) Color(0xFF24516A) else Color(0xFF28364B), RoundedCornerShape(18.dp)).padding(horizontal = 13.dp, vertical = 10.dp)) {
            Text(if (user) "YOU" else "BIXSIGMA AI", color = if (user) Color(0xFF8FD8F0) else Cyan, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(message.text.ifBlank { "..." }, color = White, fontSize = 14.sp, lineHeight = 20.sp)
            if (!user && message.text.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onSpeak(message.text) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.VolumeUp, "Speak", tint = Muted, modifier = Modifier.size(17.dp)) }
                IconButton(onClick = { onCopy(message.text) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ContentCopy, "Copy response", tint = Muted, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}
