package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.InstallHistory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.adoptNewExperimentsIntoAll
import ai.opencode.core.sharing.installHistory
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.settings.whatsnew.all
import ai.opencode.feature.transcript.SessionSeenWatermarks
import ai.opencode.feature.transcript.sessionSeenWatermarks
import ai.opencode.server.OpenCodeServer

// Preferences saved by earlier builds, moved once at launch.

/** Runs every launch migration. */
internal fun migrateSettingsIfNeeded(savedServers: List<OpenCodeServer>) {
    val store = dependency(SharedStore)
    recordInstallHistory(store, savedServers)
    ExperimentalFeature.removeRetiredSwitches(store)
    ExperimentalFeature.adoptNewExperimentsIntoAll()
    removeRetiredOrbStyles(store)
    store.remove(SharedKey.fileStorage(FileDirectory.Documents, "muted-sessions.json", emptySet<String>()))
}

internal const val RETIRED_ORB_STYLE_KEY = "living-orb-style"

private fun removeRetiredOrbStyles(store: SharedStore) {
    store.remove(SharedKey.appStorage(RETIRED_ORB_STYLE_KEY, ""))
}

/**
 * Records once whether this is a new install. A new one starts with What’s New read; anything an
 * earlier build saved makes it an upgrade.
 */
private fun recordInstallHistory(store: SharedStore, savedServers: List<OpenCodeServer>) {
    val history = store.reference(SharedKeys.installHistory)
    if (history.value != InstallHistory.unrecorded) return
    val seenIDs = store.reference(WhatsNewFeature.seenIDsKey)
    val upgrading = savedServers.isNotEmpty() || seenIDs.value.isNotEmpty() ||
        InstallHistory.legacyMarkers.any { store.contains(SharedKey.appStorage(it, false)) }
    if (upgrading) {
        history.set(InstallHistory.paired)
        return
    }
    val all = WhatsNewEntry.all
    seenIDs.set(all.map { it.id }.toSet())
    history.set(InstallHistory.fresh)
    AppLog.app.info { "fresh install marked What’s New seen entries=${all.size}" }
}

/** Runs in [AppFeature.State.initial], before Home restores the saved server. */
internal fun migrateBeforeRestore() {
    retireNewMessagesDivider(dependency(SharedStore))
}

/** Clears the stale seen watermarks once, so they fall back to the server's viewed time. */
internal fun retireNewMessagesDivider(store: SharedStore) {
    val key = SharedKey.appStorage("experimental-new-messages-divider", false)
    if (!store.contains(key)) return
    if (!store.reference(key).value) {
        val watermarks = store.reference(SharedKeys.sessionSeenWatermarks)
        AppLog.app.notice("Cleared ${watermarks.value.entries.size} seen watermarks saved with the divider off")
        watermarks.set(SessionSeenWatermarks())
    }
    store.remove(key)
}
