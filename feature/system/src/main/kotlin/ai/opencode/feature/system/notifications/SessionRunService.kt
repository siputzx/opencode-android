package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process (and its server stream) alive while a run works, so the local run-finished
 * notification can still be posted after the app goes away. Android freezes cached apps and their
 * sockets; a quiet foreground service is the one thing that holds them. Stops as soon as no run is
 * active. This is the open-source build's stand-in for the relay's push.
 */
class SessionRunService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: localized(R.string.system_local_session_title)
        val url = intent?.getStringExtra(EXTRA_URL)
        SystemNotificationChannels.ensure(this)
        val notification = NotificationCompat.Builder(this, SystemNotificationChannels.SESSION_RUN)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(title)
            .setContentText(localized(R.string.system_session_working))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply { open(this@SessionRunService, url)?.let(::setContentIntent) }
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun open(context: Context, url: String?): PendingIntent? {
        val intent = if (url != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(context.packageName)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val EXTRA_TITLE = "opencode.run.title"
        private const val EXTRA_URL = "opencode.run.url"
        private const val NOTIFICATION_ID = 4201

        fun intent(context: Context, title: String?, url: String?): Intent =
            Intent(context, SessionRunService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_URL, url)
    }
}

/** Starts/stops [SessionRunService]; only called from the foreground, where Android allows it. */
class SessionRunServiceClient(private val context: Context? = null) {
    private var started = false

    fun start(title: String?, url: String?) {
        val context = context ?: return
        val intent = SessionRunService.intent(context, title, url)
        runCatching {
            if (started) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
                started = true
            }
        }.onFailure {
            started = false
            AppLog.app.debug { "Session run service not started: ${it.javaClass.simpleName}" }
        }
    }

    fun stop() {
        val context = context ?: return
        if (!started) return
        started = false
        runCatching { context.stopService(Intent(context, SessionRunService::class.java)) }
    }

    /** Clears a service a previous process (or an app update) left behind. */
    fun stopNow() {
        val context = context ?: return
        started = false
        runCatching { context.stopService(Intent(context, SessionRunService::class.java)) }
    }

    companion object Key : DependencyKey<SessionRunServiceClient> {
        override val liveValue: SessionRunServiceClient by lazy { SessionRunServiceClient(dependency(AppContext)) }
        override val testValue: SessionRunServiceClient = SessionRunServiceClient()
    }
}
