package ai.opencode.server.model

import kotlinx.serialization.Serializable

/** How much a phone hears from a session: everything, only questions and permissions, or nothing. */
@Serializable
enum class OpenCodePushLevel {
    all, attention, none;

    companion object {
        fun of(value: String): OpenCodePushLevel? {
            for (level in entries) {
                if (level.name == value) return level
            }
            return null
        }
    }
}

/** `capabilities.push` from `GET /api/info`. */
data class OpenCodePushCapability(
    val version: Int,
    val relays: List<String>,
    val levels: List<OpenCodePushLevel>,
)

/** A bell on one session; [until] is in epoch milliseconds, null for forever. */
@Serializable
data class OpenCodePushBell(val level: OpenCodePushLevel, val until: Long? = null)

@Serializable
data class OpenCodePushDevice(
    val id: String,
    val name: String,
    val platform: String,
    val expiresAt: Long,
    val defaultLevel: OpenCodePushLevel,
    val paused: Boolean,
    val sessions: Map<String, OpenCodePushBell>,
)

/** `GET /api/experimental/push`: everything the server keeps about push. */
@Serializable
data class OpenCodePushState(
    val paused: Boolean = false,
    val pausedUntil: Long? = null,
    val devices: List<OpenCodePushDevice> = emptyList(),
    /** Shared bells, set from any device for everyone. */
    val sessions: Map<String, OpenCodePushBell> = emptyMap(),
)

/** What `PUT /api/experimental/push/device/<id>` sends. */
data class OpenCodePushRegistration(
    val callbackURL: String,
    val expiresAt: Long,
    val publicKey: String,
    val name: String,
    val defaultLevel: OpenCodePushLevel,
)
