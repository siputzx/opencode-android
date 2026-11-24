package ai.opencode.feature.system

import ai.opencode.core.log.SystemActionOrigin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * Where app shortcuts, Quick Settings tiles, widgets, launcher quick actions, the share sheet's
 * notifications and `opencode://` links take you. Every surface makes one of these, and
 * `AppFeature` routes it like a tapped notification.
 */
data class SystemAction(
    /**
     * `SessionDirectory.serverKey(...)`, or from a link one of the server's addresses; null means
     * the server on screen.
     */
    val serverKey: String? = null,
    val destination: Destination,
    /** Starts a voice call there through the call button's own path. */
    val startsCall: Boolean = false,
    /**
     * The app stays in the background: the call starts without its view appearing (a
     * background Start Voice Call, which has already shown the call's Live Update).
     */
    val startsInBackground: Boolean = false,
    /** Which surface made it, for the call trigger log line. */
    val origin: SystemActionOrigin = SystemActionOrigin.other,
) {
    sealed interface Destination {
        /** The session you'd see on opening the app: the last viewed, else the newest. */
        data object LastViewed : Destination
        data class Session(val id: String) : Destination
        /** A New Session draft, in a project when one is named. */
        data class Draft(val projectID: String?) : Destination
    }

    companion object {
        const val URL_SCHEME = "opencode"

        /**
         * `opencode://session/<id>?server=<key>`, `opencode://last-session?server=<key>` and
         * `opencode://new-session?server=<key>&project=<id>`, where `<key>` may also be an address.
         * `opencode://server/<base64url address>[/session/<id>]` is the web app's session route.
         * Links only open: a web page must never be able to start a call.
         */
        fun fromUrl(url: String): SystemAction? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != URL_SCHEME) return null
            val query = linkedMapOf<String, String>()
            uri.rawQuery?.split("&")?.forEach { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                val name = decode(parts[0]) ?: return@forEach
                val value = decode(parts[1]) ?: return@forEach
                query.putIfAbsent(name, value)
            }
            val path = (uri.rawPath ?: "").split("/").filter { it.isNotEmpty() }.map { decode(it) ?: it }
            return when (uri.host?.lowercase()) {
                "session" -> {
                    if (path.size != 1 || path[0].isEmpty()) return null
                    SystemAction(query["server"], Destination.Session(path[0]))
                }
                "last-session" -> SystemAction(query["server"], Destination.LastViewed)
                "new-session" -> SystemAction(query["server"], Destination.Draft(query["project"]))
                "server" -> {
                    if (path.isEmpty()) return null
                    val address = runCatching { String(Base64.getUrlDecoder().decode(path[0]), Charsets.UTF_8) }.getOrNull() ?: return null
                    if (path.size == 1) return SystemAction(address, Destination.LastViewed)
                    if (path.size != 3 || path[1] != "session" || path[2].isEmpty()) return null
                    SystemAction(address, Destination.Session(path[2]))
                }
                else -> null
            }
        }

        /** Spotlight's identifiers; here a session shortcut's ID: its session's scoped ID. */
        fun fromScopedIdentifier(identifier: String): SystemAction? {
            val scoped = ScopedID.parse(identifier) ?: return null
            return SystemAction(scoped.serverKey, Destination.Session(scoped.id))
        }

        /** Percent-decoding only, as `URLComponents`: a `+` stays a `+`. */
        private fun decode(value: String): String? =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
}

/**
 * Carries actions from shortcuts, tiles, widgets and links (which run in the app's process) to
 * `AppFeature`. Actions posted before anything listens (a shortcut that launched the app) wait for
 * the first listener.
 *
 * Process-wide delivery plumbing, reached only through [SystemIntegrationClient.actions] by
 * reducers; the system entry points post to it directly.
 */
class SystemActionInbox {
    private class Listener(val id: UUID, val channel: SendChannel<SystemAction>)

    private val lock = Any()
    private val waiting = mutableListOf<SystemAction>()
    private var listener: Listener? = null

    /** Whether the app is already listening: it launched with its UI, so an action can route without the app coming to the foreground. */
    val isListening: Boolean get() = synchronized(lock) { listener != null }

    fun post(action: SystemAction) = synchronized(lock) {
        val current = listener
        if (current != null && current.channel.trySend(action).isSuccess) return@synchronized
        listener = null
        waiting += action
    }

    /** The newest stream replaces any earlier one and first receives what was waiting. */
    fun actions(): Flow<SystemAction> = callbackFlow {
        val id = UUID.randomUUID()
        val replaced = synchronized(lock) {
            val replaced = listener
            listener = Listener(id, channel)
            for (action in waiting) channel.trySend(action)
            waiting.clear()
            replaced
        }
        // Finished outside the lock: its termination handler takes the lock.
        replaced?.channel?.close()
        awaitClose {
            synchronized(lock) { if (listener?.id == id) listener = null }
        }
    }.buffer(Channel.UNLIMITED)

    companion object {
        val shared = SystemActionInbox()
    }
}
