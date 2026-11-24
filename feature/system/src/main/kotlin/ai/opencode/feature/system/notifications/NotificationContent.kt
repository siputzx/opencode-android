package ai.opencode.feature.system.notifications

import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A notification is rebuilt from authenticated fields only, never from clear routing siblings.
 *
 * FCM data values are strings, so a push carries the envelope as a JSON object string under
 * `opencode` (`{"v":2,"ref":…,"box":…}`), as APNs carries it as a dictionary. [Shown] stands in
 * for `UNMutableNotificationContent`: the text, the thread (the notification group) and the
 * envelope the tap carries, so the app authenticates the box again when it's opened.
 */
object NotificationContent {
    const val ENVELOPE_KEY = "opencode"
    const val PLACEHOLDER_TITLE = "OpenCode"
    val PLACEHOLDER_BODY: String get() = localized(R.string.system_notification_placeholder_body)

    data class Verified(val ref: String, val box: String, val payload: NotificationCrypto.Payload)

    /** The v2 fields as they arrived, not yet authenticated. */
    data class Envelope(val ref: String, val box: String) {
        /** The envelope as a tap carries it again. */
        val json: String get() = encoded(this).toString()

        companion object {
            fun from(data: Map<String, String>): Envelope? = from(data[ENVELOPE_KEY])

            fun from(json: String?): Envelope? = objectOf(json)?.let(::from)

            fun from(envelope: JsonObject): Envelope? {
                val version = (envelope["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (version != 2) return null
                val ref = (envelope["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                val box = (envelope["box"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                return Envelope(ref, box)
            }
        }
    }

    /** What a session notification shows; [type] is null for the generic notification. */
    data class Shown(val title: String, val body: String, val thread: String?, val envelope: JsonObject?, val type: String? = null)

    fun placeholder(): Shown = Shown(PLACEHOLDER_TITLE, PLACEHOLDER_BODY, thread = null, envelope = null)

    /** Used again by the app when a notification is tapped. */
    fun verifiedPayload(
        data: Map<String, String>,
        sharedKey: (String) -> ByteArray = { NotificationKeyStore.live.sharedKey(it) },
    ): Verified? = Envelope.from(data)?.let { verify(it, sharedKey) }

    fun verify(
        envelope: Envelope,
        sharedKey: (String) -> ByteArray = { NotificationKeyStore.live.sharedKey(it) },
    ): Verified? {
        val payload = runCatching { NotificationCrypto.open(envelope.box, envelope.ref, sharedKey(envelope.ref)) }.getOrNull()
            ?: return null
        return Verified(envelope.ref, envelope.box, payload)
    }

    /**
     * The session [content] wrote into the thread after verifying, read without the key store.
     * Only for clearing delivered notifications: presentation and taps still authenticate the box.
     */
    fun threadSessionID(thread: String?, envelope: Envelope?): String? {
        envelope ?: return null
        val prefix = "${envelope.ref}:"
        if (thread == null || !thread.startsWith(prefix)) return null
        val sessionID = thread.removePrefix(prefix)
        return if (NotificationCrypto.isValid(sessionID = sessionID)) sessionID else null
    }

    /** Null when nothing should show: a message of a type this version doesn't know, without a title. */
    fun content(data: Map<String, String>, verified: Verified?): Shown? {
        // Keep the encrypted envelope so the app authenticates independently on tap.
        // No clear siblings (including the separate local-share router) survive.
        val kept = objectOf(data[ENVELOPE_KEY])?.let { original -> JsonObject(original.filterKeys { it in setOf("v", "ref", "box") }) }
        if (verified == null) return placeholder().copy(envelope = kept)
        val payload = verified.payload
        if (payload.title.isEmpty()) return null
        val thread = if (payload.sessionID != null) "${verified.ref}:${payload.sessionID}" else null
        return Shown(
            title = payload.title,
            body = payload.body,
            thread = thread,
            envelope = encoded(Envelope(verified.ref, verified.box)),
            type = payload.type,
        )
    }

    private fun encoded(envelope: Envelope) = JsonObject(
        mapOf("v" to JsonPrimitive(2), "ref" to JsonPrimitive(envelope.ref), "box" to JsonPrimitive(envelope.box)),
    )

    private fun objectOf(json: String?): JsonObject? =
        json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
}
