package ai.bixsigma.mobile.data

data class BixMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val time: Long = System.currentTimeMillis()
)

data class BixAttachment(
    val name: String,
    val mimeType: String,
    val dataUrl: String
)

data class BixAccount(
    val uid: String,
    val displayName: String,
    val email: String,
    val idToken: String,
    val refreshToken: String
)

data class BixAction(
    val type: String,
    val appName: String? = null,
    val query: String? = null,
    val url: String? = null,
    val phoneNumber: String? = null,
    val contactName: String? = null,
    val callType: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val seconds: Int? = null,
    val message: String? = null,
    val title: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val description: String? = null,
    val location: String? = null
)
