package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.share.ShareConversation
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app icon's badge counts, left in the app's preferences where the push handler reads it.
 * A push arriving while the app isn't live adds its session, so the count stays close to Catch Up
 * until the app opens and counts again.
 *
 * Session IDs, a hash of the server's identity and its random notification `ref` only: no titles,
 * usernames, addresses or credentials.
 */
@Serializable
data class AppBadgeLedger(
    /** The server whose sessions are counted: [ShareConversation.serverKey] of its persistence ID, never the ID itself. */
    val serverKey: String,
    /** That server's notification `ref`; pushes carrying another are left alone. */
    val ref: String? = null,
    /** The sessions the badge counts: Catch Up's. */
    val sessionIDs: List<String>,
    /** Sessions on screen in the foreground, already being read. */
    val viewingSessionIDs: List<String>,
) {
    val count: Int get() = sessionIDs.size

    /**
     * The ledger and badge after a push for [sessionID], minted under [ref]; null leaves the
     * badge as it is. Records the session so a second push doesn't count it again.
     */
    fun badgeAfterPush(sessionID: String, ref: String): Pair<AppBadgeLedger, Int>? {
        val ownRef = this.ref ?: return null
        if (ownRef != ref || sessionID in viewingSessionIDs) return null
        val updated = if (sessionID in sessionIDs) this else copy(sessionIDs = sessionIDs + sessionID)
        return updated to updated.count
    }

    fun save(defaults: SharedPreferences?) {
        if (!isHashedKey(serverKey)) return
        val data = runCatching { json.encodeToString(serializer(), this) }.getOrNull() ?: return
        defaults?.edit()?.putString(KEY, data)?.apply()
    }

    companion object {
        const val KEY = "app-badge-ledger"
        private const val GROUP = "app-group"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /**
         * A server key as [ShareConversation.serverKey] makes it: twelve lowercase hex digits.
         * Anything else is never kept.
         */
        fun isHashedKey(key: String): Boolean =
            key.length == 12 && key.all { it in '0'..'9' || it in 'a'..'f' }

        /** The hashed key a server's ledger is saved under, as the system's session IDs use. */
        fun serverKey(persistenceID: String): String = ShareConversation.serverKey(persistenceID)

        /** Stored in the app's own preferences file. */
        fun groupDefaults(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(GROUP, Context.MODE_PRIVATE)

        /** The saved ledger. One whose server key isn't a hash is removed. */
        fun load(defaults: SharedPreferences?): AppBadgeLedger? {
            defaults ?: return null
            val data = defaults.getString(KEY, null) ?: return null
            val ledger = runCatching { json.decodeFromString(serializer(), data) }.getOrNull()
            if (ledger == null || !isHashedKey(ledger.serverKey)) {
                defaults.edit().remove(KEY).apply()
                return null
            }
            return ledger
        }

        fun remove(defaults: SharedPreferences?) {
            defaults?.edit()?.remove(KEY)?.apply()
        }
    }
}
