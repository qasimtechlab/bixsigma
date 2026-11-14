package ai.bixsigma.mobile.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Locale
import ai.bixsigma.mobile.data.BixAction

class AndroidActionExecutor(private val context: Context) {
    fun execute(action: BixAction): String {
        return try {
            when (action.type.lowercase()) {
                "list_apps" -> listInstalledApps()
                "open_app" -> openApp(action.appName.orEmpty(), action.query)
                "open_settings" -> openSettings(action.title.orEmpty())
                "send_sms" -> prepareSms(action.phoneNumber, action.message)
                "open_website" -> openWebsite(action.url.orEmpty())
                "phone_call" -> dialPhone(action.phoneNumber, action.contactName, action.callType)
                "youtube_play" -> openYouTube(action.query)
                "set_alarm" -> setAlarm(action)
                "set_timer" -> setTimer(action)
                "create_calendar_event" -> createCalendarEvent(action)
                else -> "Yeh action abhi Android app mein supported nahi hai: ${action.type}"
            }
        } catch (_: ActivityNotFoundException) {
            "Is action ke liye required app ya activity phone mein available nahi hai."
        } catch (e: Exception) {
            "Action perform nahi ho saka: ${e.message ?: "unknown error"}"
        }
    }

    private fun listInstalledApps(): String {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.loadLabel(context.packageManager).toString().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase(Locale.ROOT) }
        if (names.isEmpty()) return "Android ne koi launchable app list nahi ki."
        return "Phone mein ${names.size} launchable apps mili hain: ${names.joinToString(", ")}"
    }

    private fun openApp(rawName: String, query: String?): String {
        val name = rawName.lowercase().trim()
        if (name.contains("youtube")) return openYouTube(query)
        if (name.contains("settings")) {
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Phone Settings khol di gayi hain."
        }
        if (name.contains("camera")) {
            context.startActivity(Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Camera open kar diya gaya hai."
        }
        if (name.contains("phone") || name.contains("dialer")) {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Phone dialer open kar diya gaya hai."
        }
        if (name.contains("calculator")) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Calculator open kar diya gaya hai."
        }

        val packageName = when {
            name.contains("whatsapp") -> "com.whatsapp"
            name.contains("instagram") -> "com.instagram.android"
            name.contains("facebook") || name == "fb" -> "com.facebook.katana"
            name.contains("tiktok") -> "com.zhiliaoapp.musically"
            name.contains("telegram") -> "org.telegram.messenger"
            name.contains("snapchat") -> "com.snapchat.android"
            name.contains("spotify") -> "com.spotify.music"
            name.contains("gmail") || name.contains("email") -> "com.google.android.gm"
            name.contains("chrome") || name.contains("browser") -> "com.android.chrome"
            name.contains("maps") || name.contains("google maps") -> "com.google.android.apps.maps"
            name.contains("messages") || name.contains("sms") -> "com.google.android.apps.messaging"
            name.contains("free fire max") -> "com.dts.freefiremax"
            name.contains("free fire") -> "com.dts.freefireth"
            name.contains("discord") -> "com.discord"
            name.contains("play store") || name.contains("google play") -> "com.android.vending"
            else -> null
        }
        if (packageName != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "$rawName open kar diya gaya hai."
            }
        }
        // Resolve any other installed launcher app by the label actually shown on the phone.
        val wanted = rawName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(launcherIntent, 0)
        val match = matches.firstOrNull { info ->
            val label = info.loadLabel(context.packageManager).toString().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")
            label == wanted || label.contains(wanted) || wanted.contains(label)
        }
        if (match != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "$rawName open kar diya gaya hai."
            }
        }
        if (packageName != null) {
            return "$rawName install nahi hai. Play Store se install kar sakte ho."
        }
        return "\"$rawName\" naam ki app installed launcher list mein nahi mili. App ka exact display name batao."
    }

    private fun openSettings(section: String): String {
        val normalized = section.lowercase(Locale.ROOT)
        val action = when {
            normalized.contains("wifi") || normalized.contains("wi-fi") || normalized.contains("network") -> android.provider.Settings.ACTION_WIFI_SETTINGS
            normalized.contains("bluetooth") -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            normalized.contains("display") || normalized.contains("screen") -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            normalized.contains("sound") || normalized.contains("volume") -> android.provider.Settings.ACTION_SOUND_SETTINGS
            normalized.contains("location") || normalized.contains("gps") -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            normalized.contains("battery") -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            normalized.contains("app") || normalized.contains("application") -> android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            normalized.contains("accessibility") -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Phone ki $section settings khol di hain. System setting tum khud confirm/change kar sakte ho."
    }

    private fun prepareSms(number: String?, message: String?): String {
        val clean = (number ?: "").trim()
        if (clean.isBlank()) return "SMS recipient ka phone number missing hai."
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(clean)}"))
            .putExtra("sms_body", message.orEmpty())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "SMS draft khol diya hai. Message check karke Send tum khud dabana."
    }

    private fun openWebsite(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isBlank()) return "Website URL missing hai."
        if (!url.startsWith("https://") && !url.startsWith("http://")) url = "https://$url"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Website browser mein open kar di gayi hai."
    }

    private fun openYouTube(query: String?): String {
        val target = if (query.isNullOrBlank()) "https://www.youtube.com" else "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return if (query.isNullOrBlank()) "YouTube open kar diya gaya hai." else "YouTube par $query search khol di hai."
    }


    private fun setAlarm(action: BixAction): String {
        val hour = action.hour ?: return "Alarm ka hour specify nahi hua."
        val minute = action.minute ?: 0
        if (hour !in 0..23 || minute !in 0..59) return "Alarm ka time valid nahi hai."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, action.message ?: "BixSigma alarm")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Clock app mein ${String.format(Locale.US, "%02d:%02d", hour, minute)} ka alarm form khol diya hai. Save tum confirm karna."
    }

    private fun setTimer(action: BixAction): String {
        val seconds = (action.seconds ?: 0).coerceIn(1, 86400)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, action.message ?: "BixSigma timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Clock app mein $seconds-second timer ready hai. Start tum confirm karna."
    }

    private fun createCalendarEvent(action: BixAction): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val start = action.startTime?.let { formatter.parse(it)?.time } ?: return "Calendar event ka start time valid nahi. Format: YYYY-MM-DDTHH:mm:ss"
        val end = action.endTime?.let { formatter.parse(it)?.time } ?: return "Calendar event ka end time valid nahi. Format: YYYY-MM-DDTHH:mm:ss"
        if (end <= start) return "Calendar event ka end time start time ke baad hona chahiye."
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.TITLE, action.title ?: "BixSigma event")
            .putExtra(CalendarContract.Events.DESCRIPTION, action.description ?: "")
            .putExtra(CalendarContract.Events.EVENT_LOCATION, action.location ?: "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Calendar mein event ka draft khol diya hai. Save karne se pehle details check kar lena."
    }

    private fun dialPhone(number: String?, contact: String?, callType: String?): String {
        val clean = (number ?: "").trim()
        if (callType.equals("whatsapp", true) && clean.isNotBlank()) {
            val uri = Uri.parse("https://wa.me/${clean.filter { it.isDigit() || it == '+' }.replace("+", "")}")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "WhatsApp conversation open kar di hai. Call confirm karne ke liye WhatsApp mein action lo."
        }
        val uri = if (clean.isNotBlank()) Uri.parse("tel:${Uri.encode(clean)}") else Uri.parse("tel:")
        context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "${contact ?: clean.ifBlank { "Phone dialer" }} ke liye dialer khol diya hai. Call tum khud confirm kar sakte ho."
    }
}
