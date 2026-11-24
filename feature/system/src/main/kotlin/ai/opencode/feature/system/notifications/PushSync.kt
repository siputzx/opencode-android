package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What this phone asks of a server when a session has no bell. */
@Serializable
enum class PushMode {
    all, attention, none,
    /** `none` by default, and Everything for each session this phone sends to. */
    fromThisPhone,
}

/** This phone's choices for one server; only the phone keeps them. */
@Serializable
data class PushChoice(val enabled: Boolean = true, val mode: PushMode = PushMode.all) {
    /** The `settings.default` this phone registers with. */
    val defaultLevel: OpenCodePushLevel
        get() = when (mode) {
            PushMode.all -> OpenCodePushLevel.all
            PushMode.attention -> OpenCodePushLevel.attention
            PushMode.none, PushMode.fromThisPhone -> OpenCodePushLevel.none
        }
}

@Serializable
enum class PushStatus {
    /** Not asked yet on this connection. */
    Unknown,
    /** This build has no push channel. */
    Unavailable,
    /** The app can't post notifications. */
    NoPermission,
    /** The server has no `capabilities.push` version 1. */
    Outdated,
    /** The server doesn't accept this build's relay. */
    RelayNotAllowed,
    /** Turned off for this server. */
    Off,
    /** A step failed; the next connect tries again. */
    Failed,
    Ready,
}

/** The server's copy of push, kept only to draw controls; replaced whole on every fetch. */
@Serializable
data class PushServer(
    val status: PushStatus = PushStatus.Unknown,
    val levels: List<OpenCodePushLevel> = emptyList(),
    /** This phone's device ID there (its ref). */
    val deviceID: String? = null,
    val state: OpenCodePushState? = null,
) {
    val device: OpenCodePushDevice?
        get() {
            val state = state ?: return null
            for (device in state.devices) {
                if (device.id == deviceID) return device
            }
            return null
        }

    fun withDevicePaused(id: String, paused: Boolean): PushServer {
        val state = state ?: return this
        val devices = mutableListOf<OpenCodePushDevice>()
        for (device in state.devices) {
            devices.add(if (device.id == id) device.copy(paused = paused) else device)
        }
        return copy(state = state.copy(devices = devices))
    }

    fun withoutDevice(id: String): PushServer {
        val state = state ?: return this
        val devices = mutableListOf<OpenCodePushDevice>()
        for (device in state.devices) {
            if (device.id != id) devices.add(device)
        }
        return copy(state = state.copy(devices = devices))
    }

    fun withPaused(paused: Boolean, until: Long?): PushServer {
        val state = state ?: return this
        return copy(state = state.copy(paused = paused, pausedUntil = if (paused) until else null))
    }
}

/** How long a pause lasts. */
enum class PushDuration {
    forever, hour, tomorrowMorning;

    /** The end in epoch milliseconds, or null for forever. Tomorrow morning is 9 a.m. tomorrow. */
    fun until(now: Instant, zone: ZoneId): Long? = when (this) {
        forever -> null
        hour -> now.toEpochMilli() + 60 * 60 * 1000
        tomorrowMorning -> now.atZone(zone).toLocalDate().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    }
}

object PushSharedKeys {
    /** Server persistence ID → this phone's choices. */
    val choices = SharedKey.appStorage("push-server-choices", emptyMap<String, PushChoice>())

    /** Server persistence ID → the server's copy, written by the drawer and Settings. */
    val servers = SharedKey.inMemory("push-servers", emptyMap<String, PushServer>())
}

object PushBells {
    fun isActive(until: Long?, now: Long): Boolean = until == null || until > now
}

/** Registration and the server's copy, as the drawer runs them on every connect. */
object PushSync {
    /** Checks the server, registers (or removes) this phone, then fetches the server's copy. */
    suspend fun sync(server: OpenCodeServer, choice: PushChoice): PushServer {
        val notifications = dependency(SessionNotificationsClient)
        if (!notifications.isAvailable) return PushServer(PushStatus.Unavailable)
        val capability = try {
            dependency(OpenCodeClient).pushCapability(server)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.app.error { "Push capability unread: ${describe(error)}" }
            return PushServer(PushStatus.Failed)
        }
        if (capability == null || capability.version != 1) {
            AppLog.app.info("Push not offered by this server")
            return PushServer(PushStatus.Outdated)
        }
        if (!isListed(notifications.relay, capability.relays)) {
            AppLog.app.info("Push relay not accepted by this server")
            return PushServer(PushStatus.RelayNotAllowed)
        }
        val levels = capability.levels
        if (!choice.enabled) {
            if (!notifications.unregister(server)) return PushServer(PushStatus.Failed, levels)
            return PushServer(PushStatus.Off, levels)
        }
        if (dependency(NotificationPermissionClient).status() != NotificationPermission.Enabled) {
            return PushServer(PushStatus.NoPermission, levels)
        }
        if (!notifications.register(server, choice.defaultLevel)) return PushServer(PushStatus.Failed, levels)
        val state = fetchState(server) ?: return PushServer(PushStatus.Failed, levels)
        return PushServer(PushStatus.Ready, levels, notifications.ref(server.persistenceID), state)
    }

    /** `GET /api/experimental/push`; null when it fails. */
    suspend fun fetchState(server: OpenCodeServer): OpenCodePushState? = try {
        dependency(OpenCodeClient).pushState(server)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        AppLog.app.error { "Push state unread: ${describe(error)}" }
        null
    }

    /** Outlives the screen that sent, so the bell still lands. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A message was sent from this phone: [bellAfterSend] in the background. */
    fun sentFromPhone(server: OpenCodeServer, sessionID: String) {
        scope.launch(DependencyValues.current.asContextElement()) { bellAfterSend(server, sessionID) }
    }

    /**
     * Only sessions this phone uses: a message sent from here turns this phone's bell on for the
     * session, unless it already has one there.
     */
    suspend fun bellAfterSend(server: OpenCodeServer, sessionID: String) {
        val choice = shared(PushSharedKeys.choices).value[server.persistenceID] ?: PushChoice()
        if (!choice.enabled || choice.mode != PushMode.fromThisPhone) return
        val state = fetchState(server) ?: return
        val deviceID = dependency(SessionNotificationsClient).ref(server.persistenceID)
        var device: OpenCodePushDevice? = null
        for (candidate in state.devices) {
            if (candidate.id == deviceID) device = candidate
        }
        if (device == null) return
        val own = device.sessions[sessionID]
        if (own != null && PushBells.isActive(own.until, dependency(DateClient).now().toEpochMilli())) return
        for (attempt in 1..3) {
            try {
                setOwnBell(server, deviceID, sessionID, OpenCodePushBell(OpenCodePushLevel.all))
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppLog.app.error { "Push bell after send failed (attempt $attempt): ${describe(error)}" }
            }
            if (attempt < 3) delay(attempt * 1_000L)
        }
    }

    /** Sets this phone's own bell; a server that doesn't know this phone gets it registered first. */
    suspend fun setOwnBell(server: OpenCodeServer, deviceID: String, sessionID: String, bell: OpenCodePushBell?) {
        val client = dependency(OpenCodeClient)
        try {
            client.setPushSessionBell(server, deviceID, sessionID, bell)
        } catch (error: OpenCodeClientError) {
            if (!isUnregistered(error) || !registerAgain(server)) throw error
            client.setPushSessionBell(server, deviceID, sessionID, bell)
        }
    }

    /** Pauses or resumes a phone; a 404 for this phone registers it first. */
    suspend fun setDevicePaused(server: OpenCodeServer, deviceID: String, paused: Boolean) {
        val client = dependency(OpenCodeClient)
        val notifications = dependency(SessionNotificationsClient)
        try {
            client.setPushDevicePaused(server, deviceID, paused)
        } catch (error: OpenCodeClientError) {
            val isThisPhone = deviceID == notifications.ref(server.persistenceID)
            if (!isUnregistered(error) || !isThisPhone || !registerAgain(server)) throw error
            client.setPushDevicePaused(server, deviceID, paused)
        }
    }

    private suspend fun registerAgain(server: OpenCodeServer): Boolean {
        AppLog.app.info("Push device unknown to the server; registering again")
        val choice = shared(PushSharedKeys.choices).value[server.persistenceID] ?: PushChoice()
        return dependency(SessionNotificationsClient).register(server, choice.defaultLevel)
    }

    /**
     * The copy after the server refused a change shown as [after]: the server's own when it could
     * be read again ([fetched]), otherwise [before] unless something else has changed it since.
     */
    fun afterRefusal(current: PushServer, before: PushServer, after: PushServer, fetched: OpenCodePushState?): PushServer {
        if (fetched != null) return current.copy(state = fetched)
        if (current == after) return before
        return current
    }

    /** A 404 on a device call: the server doesn't know this phone. */
    fun isUnregistered(error: Throwable): Boolean = (error as? OpenCodeClientError)?.statusCode == 404

    fun describe(error: Throwable): String {
        val status = (error as? OpenCodeClientError)?.statusCode
        return status?.toString() ?: error::class.java.simpleName
    }

    /** The server lists relays as exact origins (`https://host` or `https://host:port`). */
    fun isListed(relay: String, relays: List<String>): Boolean {
        val url = relay.toHttpUrlOrNull() ?: return false
        var ours = url.scheme + "://" + url.host
        if (url.port != HttpUrl.defaultPort(url.scheme)) ours += ":" + url.port
        return ours in relays
    }
}
