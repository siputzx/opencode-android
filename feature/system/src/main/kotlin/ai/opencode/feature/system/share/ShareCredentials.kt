package ai.opencode.feature.system.share

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeServer
import kotlinx.serialization.Serializable

/**
 * What the share sheet needs to send on its own: the server it offers sessions on (address,
 * password, custom headers) and the relay callback that tells this phone when the run ends. The app
 * writes it to a keychain item whenever it publishes the share sheet's sessions ([ShareTargets]),
 * and removes it with the server.
 */
@Serializable
data class ShareCredentials(
    val version: Int = CURRENT_VERSION,
    /** `OpenCodeServer.persistenceID`; matches [ShareTargets.serverID]. */
    val serverID: String,
    val server: OpenCodeServer,
) {
    constructor(server: OpenCodeServer) : this(CURRENT_VERSION, server.persistenceID, server)

    companion object {
        // v1 retained a plaintext-capable push callback. The server credentials still work.
        const val CURRENT_VERSION = 2
    }
}

/**
 * The shared keychain item, or an in-memory stand-in for tests. On Android the share target runs in
 * the app's own process, so the item is a [SharedKey.keychain] (Keystore-encrypted, no backup) both
 * read.
 */
class ShareCredentialStore(
    val load: () -> ShareCredentials?,
    val save: (ShareCredentials) -> Unit,
    val remove: () -> Unit,
) {
    companion object {
        val key = SharedKey.keychain<ShareCredentials?>("share-credentials", null)

        val live: ShareCredentialStore by lazy {
            val item = shared(key)
            ShareCredentialStore(
                load = { current(item.value) },
                save = { item.set(it) },
                remove = { item.set(null) },
            )
        }

        /** A v1 item keeps its server; other versions read as none. */
        private fun current(stored: ShareCredentials?): ShareCredentials? {
            val credentials = stored?.let {
                if (it.version == 1) it.copy(version = ShareCredentials.CURRENT_VERSION) else it
            }
            return credentials?.takeIf { it.version == ShareCredentials.CURRENT_VERSION }
        }

        fun inMemory(): ShareCredentialStore {
            var stored: ShareCredentials? = null
            val lock = Any()
            return ShareCredentialStore(
                load = { current(synchronized(lock) { stored }) },
                save = { synchronized(lock) { stored = it } },
                remove = { synchronized(lock) { stored = null } },
            )
        }
    }
}
