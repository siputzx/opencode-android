package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Local "the run finished" notifications for builds without a push channel (the open-source APK
 * has no Firebase configuration). Posted while the app is away, on the same Session Updates channel
 * a push would use, so the Android 12 build still tells the person when a reply lands.
 */
class LocalSessionNotifier(private val context: Context? = null) {
    fun done(serverKey: String, sessionID: String, title: String?, preview: String?) =
        post(serverKey, sessionID, title, localized(R.string.system_session_done), preview ?: localized(R.string.system_local_session_done_body))

    fun failed(serverKey: String, sessionID: String, title: String?, preview: String?) =
        post(serverKey, sessionID, title, localized(R.string.system_session_failed), preview ?: localized(R.string.system_local_session_done_body))

    fun needsInput(serverKey: String, sessionID: String, title: String?) =
        post(serverKey, sessionID, title, localized(R.string.system_session_needs_input), localized(R.string.system_local_session_needs_input_body))

    @SuppressLint("MissingPermission") // Checked: areNotificationsEnabled covers POST_NOTIFICATIONS.
    private fun post(serverKey: String, sessionID: String, title: String?, status: String, body: String) {
        val context = context ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val name = title?.takeIf { it.isNotBlank() } ?: localized(R.string.system_local_session_title)
        val tap = tap(context, serverKey, sessionID)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(name)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$body"))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        val public = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(localized(R.string.system_local_session_title))
            .setContentText(localized(R.string.system_notification_placeholder_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        tap?.let {
            builder.setContentIntent(it)
            public.setContentIntent(it)
        }
        builder.setPublicVersion(public.build())
        runCatching { NotificationManagerCompat.from(context).notify(sessionID, NOTIFICATION_ID, builder.build()) }
    }

    private fun tap(context: Context, serverKey: String, sessionID: String): PendingIntent? {
        val url = SessionActivityAttributes(serverKey, sessionID).url ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            sessionID.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object Key : DependencyKey<LocalSessionNotifier> {
        override val liveValue: LocalSessionNotifier by lazy { LocalSessionNotifier(dependency(AppContext)) }
        override val testValue: LocalSessionNotifier = LocalSessionNotifier()

        private const val NOTIFICATION_ID = 4200
    }
}
