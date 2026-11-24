package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.NotificationDestination
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.feature.system.BuildConfig
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushRegistration
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

enum class NotificationPermission { NotDetermined, Disabled, Enabled }

class NotificationPermissionClient(
    val status: suspend () -> NotificationPermission,
    val request: suspend () -> NotificationPermission,
    val openSettings: suspend () -> Boolean,
) {
    companion object Key : DependencyKey<NotificationPermissionClient> {
        override val liveValue: NotificationPermissionClient by lazy {
            NotificationPermissionClient(
                status = { SessionNotifications.shared.permissionStatus() },
                request = { SessionNotifications.shared.requestPermission() },
                openSettings = {
                    val context = dependency(AppContext)
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                },
            )
        }
        override val testValue = NotificationPermissionClient(
            status = { NotificationPermission.Enabled },
            request = { NotificationPermission.Enabled },
            openSettings = { true },
        )
    }
}

/**
 * This phone's notification keys, off the main thread. The live value is
 * [NotificationKeyStore.live]; the test value has no keys.
 */
class NotificationKeysClient(
    /** This phone's public key for `ref`, made the first time. */
    val prepare: suspend (ref: String) -> String,
    val establish: suspend (ref: String, serverPublicKey: String) -> Unit,
    val remove: suspend (ref: String) -> Unit,
    /** The authenticated payload, or null when the box or key doesn't hold. */
    val verify: suspend (NotificationContent.Envelope) -> NotificationContent.Verified?,
) {
    companion object Key : DependencyKey<NotificationKeysClient> {
        override val liveValue: NotificationKeysClient by lazy {
            NotificationKeysClient(
                prepare = { ref -> withContext(Dispatchers.IO) { NotificationKeyStore.live.prepare(ref) } },
                establish = { ref, serverKey -> withContext(Dispatchers.IO) { NotificationKeyStore.live.establish(ref, serverKey) } },
                remove = { ref -> withContext(Dispatchers.IO) { NotificationKeyStore.live.remove(ref) } },
                verify = { envelope -> withContext(Dispatchers.IO) { NotificationContent.verify(envelope) } },
            )
        }
        override val testValue = NotificationKeysClient(
            prepare = { throw NotificationKeyStore.Failure.MissingKey },
            establish = { _, _ -> throw NotificationKeyStore.Failure.MissingKey },
            remove = {},
            verify = { null },
        )
    }
}

/**
 * This phone's half of push: registering with a server, the `ref` its pushes carry, and what the
 * foreground shows. Notifications never delay or fail anything else.
 */
class SessionNotificationsClient(
    /** Whether this build has a relay and a push channel to offer at all. */
    val isAvailable: Boolean,
    /** The relay this build registers with, as a server lists it in `capabilities.push.relays`. */
    val relay: String = SessionNotifications.RELAY_URL,
    /** Registers this phone with the server, hearing [OpenCodePushLevel] by default; false when a step fails. */
    val register: suspend (OpenCodeServer, OpenCodePushLevel) -> Boolean = { _, _ -> false },
    /** Removes this phone from the server, when it was ever registered there. */
    val unregister: suspend (OpenCodeServer) -> Unit = {},
    /** Drops a removed server's ref, callback and key (by persistence ID). */
    val forget: suspend (String) -> Unit = {},
    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. It is also this phone's device ID there. */
    val ref: (String) -> String = { "" },
    /** Emits when the push channel hands this phone a new token. */
    val tokenChanges: Flow<Unit> = emptyFlow(),
    /** Android grants the badge count with notifications. */
    val includeBadges: suspend () -> Unit = {},
    /** Tells notifications which session is on screen so its own don't show. */
    val setVisibleSession: (shown: Boolean, sessionID: String?) -> Unit = { _, _ -> },
    /** Whether the drawer button can show In-App Notices' label. */
    val setShowsDrawerLabel: (Boolean) -> Unit = {},
    /**
     * In-App Notices' mode as the drawer reads it (`InAppNoticeMode`, in `:feature:drawer`):
     * true while it's anything but Off. The drawer installs its reader at launch.
     */
    val setInAppNoticesProvider: (() -> Boolean) -> Unit = {},
) {
    companion object Key : DependencyKey<SessionNotificationsClient> {
        override val liveValue: SessionNotificationsClient by lazy {
            SessionNotificationsClient(
                isAvailable = SessionNotifications.isAvailable,
                register = { server, level -> SessionNotifications.shared.register(server, level) },
                unregister = { SessionNotifications.shared.unregister(it) },
                forget = { SessionNotifications.shared.forget(it) },
                ref = { SessionNotifications.shared.ref(it) },
                tokenChanges = SessionNotifications.shared.tokenChanges,
                includeBadges = {},
                setVisibleSession = { shown, sessionID ->
                    val id = if (shown) sessionID else null
                    if (shown || SessionNotifications.shared.visibleSessionID == sessionID) {
                        SessionNotifications.shared.visibleSessionID = id
                    }
                },
                setShowsDrawerLabel = { SessionNotifications.shared.showsDrawerLabel = it },
                setInAppNoticesProvider = { SessionNotifications.shared.inAppNoticesOn = it },
            )
        }
        override val testValue = SessionNotificationsClient(isAvailable = true)
    }
}

/**
 * How a session notification arriving in the foreground shows. The session on screen shows nothing;
 * while In-App Notices is on, the drawer button tells of the others, so they go only to the shade.
 */
enum class NotificationPresentation { None, Banner, ListOnly }

/**
 * Push registration with the relay and each server.
 *
 * Privacy: the relay is stateless. It seals the FCM token and a random per-server `ref` into the
 * callback URL itself, so the OpenCode server never sees the token and the relay keeps nothing.
 * The `ref` is random, never derived from the server's address or credentials; it is also this
 * phone's device ID on that server.
 *
 * Messages are encrypted: for each ref this phone makes a P-256 key and swaps public keys with the
 * server when it registers (`PUT /api/experimental/push/device/<ref>`); the server seals each
 * message with the derived key, so the relay and FCM only carry a `v2` envelope.
 *
 * Without a Firebase configuration in the build there is no push channel ([isAvailable] false) and
 * nothing is asked of the relay or the server.
 */
class SessionNotifications internal constructor(
    private val tokens: PushTokenProvider,
    private val mintCallback: (token: String, ref: String) -> Minted? = { token, ref -> mint(token, FCM_ENVIRONMENT, ref) },
) {
    /** The session on screen while the app is in the foreground. Its own notifications aren't shown. */
    @Volatile var visibleSessionID: String? = null
    /** Whether the drawer button can show In-App Notices' label; beside a wide layout's sidebar it can't. */
    @Volatile var showsDrawerLabel = true
    /** In-App Notices' mode, read live; Off until the drawer installs its reader. */
    @Volatile var inAppNoticesOn: () -> Boolean = { false }

    fun presentation(sessionID: String?): NotificationPresentation {
        sessionID ?: return NotificationPresentation.Banner
        if (sessionID == visibleSessionID) return NotificationPresentation.None
        val off = !(inAppNoticesOn() && showsDrawerLabel)
        return if (off) NotificationPresentation.Banner else NotificationPresentation.ListOnly
    }

    /** A tapped notification's destination, delivered once something routes it. */
    var onOpen: ((NotificationDestination) -> Unit)? = null
        set(value) {
            field = value
            val pending = pendingDestination
            if (value != null && pending != null) {
                pendingDestination = null
                value(pending)
            }
        }

    private var pendingDestination: NotificationDestination? = null
    @Volatile private var confirmedToken: String? = null
    private val lock = Any()
    private val registering = Mutex()
    private var tokenRequest: Deferred<String?>? = null
    private val keys get() = dependency(NotificationKeysClient)
    private val saved get() = shared(savedKey)
    private var permissionRequest: CompletableDeferred<Boolean>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tokenChanges: Flow<Unit> get() = tokenChangeEvents

    @Serializable
    data class Saved(
        /** Server persistence ID → its random ref. */
        val refs: Map<String, String> = emptyMap(),
        /** Server persistence ID → its current callback. */
        val callbacks: Map<String, Callback> = emptyMap(),
    )

    @Serializable
    data class Callback(
        val url: String,
        @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
        val token: String,
        val environment: String,
        /** The device public key this callback was registered with. */
        val phonePublicKey: String? = null,
    ) {
        /** Reused while it is for this token and key, on this relay, with more than three days left. */
        fun isReusable(token: String, publicKey: String, relay: HttpUrl?, now: Instant): Boolean =
            this.token == token && environment == FCM_ENVIRONMENT && phonePublicKey == publicKey &&
                expiresAt.toEpochMilli() - now.toEpochMilli() > 3.days.inWholeMilliseconds &&
                isEncryptedCallback(url, relay)
    }

    data class Minted(val url: String, val expiresAt: Instant)

    fun permissionStatus(): NotificationPermission {
        val context = dependency(AppContext)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return NotificationPermission.Enabled
        val asked = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ASKED, false)
        return if (asked) NotificationPermission.Disabled else NotificationPermission.NotDetermined
    }

    /** Asks once (Android's dialog, from a see-through activity). */
    suspend fun requestPermission(): NotificationPermission {
        if (permissionStatus() == NotificationPermission.NotDetermined) {
            val request = synchronized(lock) {
                permissionRequest ?: CompletableDeferred<Boolean>().also { created ->
                    permissionRequest = created
                    val context = dependency(AppContext)
                    context.startActivity(
                        Intent(context, NotificationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            request.await()
        }
        return permissionStatus()
    }

    internal fun permissionAnswered(granted: Boolean) {
        val context = dependency(AppContext)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        val request = synchronized(lock) { permissionRequest.also { permissionRequest = null } }
        request?.complete(granted)
    }

    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    fun ref(key: String): String {
        var made: String? = null
        saved.update { current ->
            val existing = current.refs[key]
            if (existing != null) {
                made = existing
                current
            } else {
                val ref = UUID.randomUUID().toString().lowercase()
                made = ref
                current.copy(refs = current.refs + (key to ref))
            }
        }
        return made ?: saved.value.refs[key].orEmpty()
    }

    /**
     * Registers this phone with [server]: a callback from the relay (reused while it has more than
     * three days left, for this token and key), then `PUT device/<ref>` and the server's key.
     */
    suspend fun register(server: OpenCodeServer, level: OpenCodePushLevel): Boolean = registering.withLock {
        registerNow(server, level)
    }

    private suspend fun registerNow(server: OpenCodeServer, level: OpenCodePushLevel): Boolean {
        val token = token() ?: return false
        val key = server.persistenceID
        val ref = ref(key)
        val publicKey = try {
            keys.prepare(ref)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.app.error { "Push key unavailable: ${error::class.java.simpleName}" }
            return false
        }
        var callback = saved.value.callbacks[key]
        if (callback == null || !callback.isReusable(token, publicKey, RELAY_URL.toHttpUrlOrNull(), dependency(DateClient).now())) {
            val minted = withContext(Dispatchers.IO) { mintCallback(token, ref) } ?: return false
            val made = Callback(minted.url, minted.expiresAt, token, FCM_ENVIRONMENT, publicKey)
            saved.update { it.copy(callbacks = it.callbacks + (key to made)) }
            callback = made
        }
        val registration = OpenCodePushRegistration(
            callbackURL = callback.url,
            expiresAt = callback.expiresAt.toEpochMilli(),
            publicKey = publicKey,
            name = deviceName(),
            defaultLevel = level,
        )
        val serverKey = try {
            dependency(OpenCodeClient).registerPushDevice(server, ref, registration)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.app.error { "Push registration failed: ${describe(error)}" }
            return false
        }
        try {
            keys.establish(ref, serverKey)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.app.error { "Push server key rejected: ${error::class.java.simpleName}" }
            return false
        }
        AppLog.app.info("Push registered")
        return true
    }

    /** `DELETE device/<ref>` on [server]; its ref and key stay, so turning it back on keeps the same device. */
    suspend fun unregister(server: OpenCodeServer) {
        val ref = saved.value.refs[server.persistenceID] ?: return
        try {
            dependency(OpenCodeClient).removePushDevice(server, ref)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppLog.app.error { "Push device not removed: ${describe(error)}" }
        }
    }

    /** Forgets a removed server's ref, callback and notification key. */
    suspend fun forget(serverKey: String) {
        val ref = saved.value.refs[serverKey]
        saved.update { it.copy(refs = it.refs - serverKey, callbacks = it.callbacks - serverKey) }
        val choices = shared(PushSharedKeys.choices)
        if (serverKey in choices.value) choices.update { it - serverKey }
        val servers = shared(PushSharedKeys.servers)
        if (serverKey in servers.value) servers.update { it - serverKey }
        if (ref != null) runCatching { keys.remove(ref) }
    }

    /** The authenticated payload of a push envelope. */
    suspend fun verify(envelope: NotificationContent.Envelope): NotificationContent.Verified? = keys.verify(envelope)

    /** FCM handed out a new token: servers hear of it when they register again. */
    fun didChangeToken(token: String) {
        if (token.isEmpty() || token == confirmedToken) return
        confirmedToken = token
        tokenChangeEvents.tryEmit(Unit)
    }

    /** The saved server a tapped notification's `ref` was minted for, with its session; null when unknown or invalid. */
    fun destination(ref: String, sessionID: String): NotificationDestination? {
        val serverKey = saved.value.refs.entries.firstOrNull { it.value == ref }?.key ?: return null
        return NotificationDestination(serverKey, sessionID).takeIf { it.isValid }
    }

    /** A tapped notification: its envelope is authenticated again, then routed. */
    suspend fun open(envelope: NotificationContent.Envelope) {
        val verified = verify(envelope) ?: return
        val sessionID = verified.payload.sessionID ?: return
        open(verified.ref, sessionID)
    }

    /** Routes a tapped notification's `ref` to the server it was minted for. */
    fun open(ref: String, sessionID: String) {
        val destination = destination(ref, sessionID) ?: return
        val onOpen = onOpen
        if (onOpen != null) onOpen(destination) else pendingDestination = destination
    }

    /** The push token; one request at a time, however many servers ask. */
    private suspend fun token(): String? {
        confirmedToken?.let { return it }
        val request = synchronized(lock) {
            val running = tokenRequest
            if (running != null && running.isActive) {
                running
            } else {
                val started = scope.async { requestToken() }
                tokenRequest = started
                started
            }
        }
        return request.await()
    }

    private suspend fun requestToken(): String? {
        val answer = CompletableDeferred<String?>()
        tokens.register({ answer.complete(it) }, { answer.complete(null) })
        val token = withTimeoutOrNull(10.seconds) { answer.await() }
        if (token == null) {
            AppLog.app.info("Push token unavailable")
            return null
        }
        confirmedToken = token
        return token
    }

    private fun deviceName(): String {
        val context = dependency(AppContext)
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return if (name.isNullOrBlank()) Build.MODEL else name
    }

    private fun describe(error: Exception): String {
        val status = (error as? OpenCodeClientError)?.statusCode
        return status?.toString() ?: error::class.java.simpleName
    }

    companion object {
        /** The relay's origin, set at build time (`notifyRelayURL`). */
        const val RELAY_URL = BuildConfig.NOTIFY_RELAY_URL

        /** The push environment an Android registration asks the relay to seal. */
        const val FCM_ENVIRONMENT = "fcm"

        /**
         * The push environment the relay seals: [FCM_ENVIRONMENT] when the build carries a Firebase
         * configuration (FCM can mint tokens), else null and the build offers no notifications.
         */
        val environment: String? by lazy {
            FCM_ENVIRONMENT.takeIf { FcmPushTokenProvider.isConfigured(dependency(AppContext).applicationContext) }
        }

        /** Whether this build can offer notifications at all. */
        val isAvailable: Boolean get() = environment != null && RELAY_URL.toHttpUrlOrNull()?.isHttps == true

        private const val PREFERENCES = "session-notifications"
        private const val ASKED = "permission-asked"

        private val savedKey = SharedKey.keychain("session-notification-callbacks", Saved())

        val shared: SessionNotifications by lazy { SessionNotifications(FcmPushTokenProvider(dependency(AppContext).applicationContext)) }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Only a `/v2/notify/<token>` URL on the relay's own HTTPS origin, with no user, password,
         * query or fragment, is ever used as a callback. Old (`/v1/`) or foreign capabilities never
         * reach a server.
         */
        fun isEncryptedCallback(url: String, relay: HttpUrl? = RELAY_URL.toHttpUrlOrNull()): Boolean {
            relay ?: return false
            val parts = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            if (parts.scheme != "https" || parts.host?.lowercase() != relay.host.lowercase()) return false
            if ((parts.port.takeIf { it != -1 } ?: 443) != relay.port) return false
            if (parts.rawUserInfo != null || parts.rawQuery != null || parts.rawFragment != null) return false
            val prefix = "/v2/notify/"
            val path = parts.rawPath ?: return false
            if (!path.startsWith(prefix)) return false
            val token = path.removePrefix(prefix).toByteArray(Charsets.UTF_8)
            return token.size in 1..1024 && token.all {
                it in 65..90 || it in 97..122 || it in 48..57 || it.toInt() == 45 || it.toInt() == 95
            }
        }

        /**
         * `POST /v2/register` on the relay. Gives up within three seconds, never follows a
         * redirect, and sends nothing but the token, environment, and ref.
         */
        fun mint(token: String, environment: String, ref: String): Minted? {
            val relay = RELAY_URL.toHttpUrlOrNull() ?: return null
            val body = buildJsonObject {
                put("token", token)
                put("environment", environment)
                put("ref", ref)
            }.toString()
            val client = OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .cache(null)
                .build()
            val request = Request.Builder()
                .url(relay.newBuilder().addPathSegments("v2/register").build())
                .header("Content-Type", "application/json")
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        AppLog.app.error { "Push relay answered ${response.code}" }
                        return null
                    }
                    val output = runCatching { json.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull()
                    val url = output?.get("callbackURL")?.jsonPrimitive?.content
                    val expiresAt = output?.get("expiresAt")?.jsonPrimitive?.content?.let(::parseDate)
                    if (url == null || expiresAt == null || !isEncryptedCallback(url, relay) || !expiresAt.isAfter(Instant.now())) {
                        AppLog.app.error("Push relay reply unusable")
                        return null
                    }
                    Minted(url, expiresAt)
                }
            } catch (error: Exception) {
                AppLog.app.error { "Push relay unreachable: ${error::class.java.simpleName}" }
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

        /** The relay writes `Date.toISOString()`, which carries milliseconds. */
        private fun parseDate(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
}

/**
 * The push channel's token, behind an interface. The live provider is FCM's
 * ([FcmPushTokenProvider]); it reports failure at once when the build has no Firebase configuration.
 */
fun interface PushTokenProvider {
    /** Asks for the token; exactly one of the callbacks runs, now or later. */
    fun register(onToken: (String) -> Unit, onFailure: () -> Unit)

    companion object {
        /** No push channel: every registration fails at once. */
        val inert: PushTokenProvider = PushTokenProvider { _, onFailure -> onFailure() }
    }
}
