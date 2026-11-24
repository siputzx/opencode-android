package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Android notification channels for Live Updates and notifications. Created on first use
 * (idempotent). One per kind, so each can be silenced on its own.
 */
object SystemNotificationChannels {
    /** A voice call's Live Update. */
    const val VOICE_CALL = "voice-call"
    /** A hands-free recording's Live Update. */
    const val DICTATION = "dictation"
    /** A run sent from this phone, while the app is away. */
    const val SESSION_RUN = "session-run"
    /** A session finished. */
    const val SESSION_FINISHED = "session-finished"
    /** A session asks a question. */
    const val SESSION_QUESTIONS = "session-questions"
    /** A session asks for permission. */
    const val SESSION_PERMISSIONS = "session-permissions"
    /** Any other session notification, including ones this version doesn't know. */
    const val SESSION_OTHER = "session-other"

    /** The channel for a message type; null (unreadable) goes to [SESSION_OTHER]. */
    fun forType(type: String?): String = when (type) {
        "session.idle" -> SESSION_FINISHED
        "question" -> SESSION_QUESTIONS
        "permission" -> SESSION_PERMISSIONS
        else -> SESSION_OTHER
    }
    /** The share sheet's “sent” and “continue” notices. */
    const val SHARE = "share"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(VOICE_CALL, context.getString(R.string.system_channel_voice_calls), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(DICTATION, context.getString(R.string.system_channel_recording), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(SESSION_RUN, context.getString(R.string.system_channel_session_runs), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(SESSION_FINISHED, context.getString(R.string.system_channel_session_finished), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(SESSION_QUESTIONS, context.getString(R.string.system_channel_session_questions), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(SESSION_PERMISSIONS, context.getString(R.string.system_channel_session_permissions), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(SESSION_OTHER, context.getString(R.string.system_channel_session_other), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(SHARE, context.getString(R.string.system_channel_shared_items), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                },
            ),
        )
        manager.deleteNotificationChannel(LEGACY_SESSIONS)
    }

    private const val LEGACY_SESSIONS = "sessions"
}
