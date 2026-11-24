package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalFeatures
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sound.SoundEffect
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.feature.session.InAppNotice
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.feature.drawer.notices.InAppNoticeSettings
import ai.opencode.feature.drawer.notices.InAppNoticeSignal
import ai.opencode.feature.drawer.notices.shows
import ai.opencode.feature.system.notifications.AppBadgeLedger
import ai.opencode.feature.system.notifications.AppIconBadgeClient
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant

/*
 * What Home does as notifications change (before `HomeNotificationsFeature` settles the same
 * action), the app icon's badge, and In-App Notices' rules:
 * Home's live state in, the drawer button's label out.
 *
 * Home's composition watches [noticeInput] and calls [inAppNoticeSignalChanged] with the old and
 * new inputs.
 */

internal fun HomeFeature.reduceNotifications(state: State, action: Action): HomeReduced = when (action) {
    is Action.NotificationPermissionLoaded -> {
        val changed = state.notificationPermission != action.permission
        // Share credentials and the app badge belong to Home's cross-feature publication.
        val credentials = if (changed && state.hasConnected) publishShareCredentials(state.server) else Effect.none()
        Reduced(state, Effect.merge(credentials, if (changed && action.permission == NotificationPermission.Enabled) publishAppBadge(state) else Effect.none()))
    }

    else -> Reduced(state)
}

/** Shows Catch Up's count on the icon, once it can be trusted. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishAppBadge(state: State, ledger: AppBadgeLedger? = null): Effect<Action> {
    val counted = ledger ?: state.appBadgeLedger ?: return Effect.none()
    val appIconBadge = dependency(AppIconBadgeClient)
    val persistenceID = state.server.persistenceID
    return Effect.run { appIconBadge.publish(counted, persistenceID) }
}

/**
 * What the icon's badge counts: the sessions Catch Up holds (unread or needing input, never muted),
 * matching its row in the drawer. Null until the list and its attention read have landed on a live
 * connection, so a reconnect, an outage, or the app leaving keeps the last count rather than
 * clearing it.
 */
internal val State.appBadgeLedger: AppBadgeLedger? get() = appBadgePublication.ledger

/** Change-detection key for [appBadgeLedger]. */
internal val State.appBadgePublication: AppBadgePublication
    get() {
        val isReady = isConnected && snapshotLoaded && noticeReadiness == State.NoticeReadiness.settled
        if (!isReady) return AppBadgePublication.notReady
        return AppBadgePublication(
            isReady = true,
            server = server,
            sessions = sessions,
            activeSessionIDs = activeSessionIDs,
            parents = parentSources,
            attentionSessionIDs = attentionSessionIDs,
            sessionReadDates = sessionReadDates,
            manuallyUnreadSessionKeys = manuallyUnreadSessionKeys,
            mutedSessionKeys = mutedSessionKeys,
            viewing = viewing,
        )
    }

/** Home's half of the badge, which `:feature:system` can't see. */
internal data class AppBadgePublication(
    val isReady: Boolean,
    val server: OpenCodeServer?,
    val sessions: List<OpenCodeSession>,
    val activeSessionIDs: Set<String>,
    val parents: State.ParentSources,
    val attentionSessionIDs: Set<String>,
    val sessionReadDates: Map<String, Instant>,
    val manuallyUnreadSessionKeys: Set<String>,
    val mutedSessionKeys: Set<String>,
    val viewing: State.Viewing,
) {
    val ledger: AppBadgeLedger?
        get() {
            if (!isReady || server == null) return null
            val serverID = server.persistenceID
            val status = State.StatusSnapshot(
                serverID = serverID,
                showsActivity = true,
                busy = parents.busy(activeSessionIDs),
                attention = attentionSessionIDs,
                mutedKeys = mutedSessionKeys,
                manuallyUnreadKeys = manuallyUnreadSessionKeys,
                readDates = sessionReadDates,
            )
            val counted = sessions.filter { status.needsCatchUp(it) }
            val prefix = "$serverID|"
            val muted = mutedSessionKeys.mapNotNull { key -> if (key.startsWith(prefix)) key.removePrefix(prefix) else null }
            return AppBadgeLedger(
                serverKey = AppBadgeLedger.serverKey(serverID),
                ref = null,
                sessionIDs = counted.map { it.id }.sorted(),
                mutedSessionIDs = muted.sorted(),
                viewingSessionIDs = viewing.threads.sorted(),
            )
        }

    companion object {
        val notReady = AppBadgePublication(
            isReady = false, server = null, sessions = emptyList(), activeSessionIDs = emptySet(),
            parents = State.ParentSources.empty, attentionSessionIDs = emptySet(),
            sessionReadDates = emptyMap(), manuallyUnreadSessionKeys = emptySet(), mutedSessionKeys = emptySet(),
            viewing = State.Viewing(),
        )
    }
}

/**
 * In-App Notices' input from Home's state: compared on every action, so an off mode or a Home that
 * isn't live reduces to small constant values.
 */
data class InAppNoticeInput(
    val mode: InAppNoticeMode,
    val isLive: Boolean,
    val server: OpenCodeServer?,
    val sessions: List<OpenCodeSession>,
    val activeSessionIDs: Set<String>,
    val parents: State.ParentSources,
    val attentionSessionIDs: Set<String>,
    val sessionReadDates: Map<String, Instant>,
    val manuallyUnreadSessionKeys: Set<String>,
    val mutedSessionKeys: Set<String>,
) {
    val signal: InAppNoticeSignal
        get() {
            if (mode == InAppNoticeMode.off || !isLive || server == null) return InAppNoticeSignal.none
            val status = State.StatusSnapshot(
                serverID = server.persistenceID,
                showsActivity = true,
                busy = parents.busy(activeSessionIDs),
                attention = attentionSessionIDs,
                mutedKeys = mutedSessionKeys,
                manuallyUnreadKeys = manuallyUnreadSessionKeys,
                readDates = sessionReadDates,
            )
            val signals = HashMap<String, InAppNoticeSignal.Session>(sessions.size)
            for (session in sessions) {
                if (session.parentID != null || status.isMuted(session.id)) continue
                val indicator = status.indicator(session)
                signals[session.id] = InAppNoticeSignal.Session(
                    title = session.title,
                    needsInput = indicator == State.Indicator.attention,
                    unread = indicator == State.Indicator.unread,
                    idle = session.idleMilliseconds,
                )
            }
            return InAppNoticeSignal(isLive = true, sessions = signals)
        }

    companion object {
        val off = InAppNoticeInput(
            mode = InAppNoticeMode.off, isLive = false, server = null, sessions = emptyList(), activeSessionIDs = emptySet(),
            parents = State.ParentSources.empty, attentionSessionIDs = emptySet(),
            sessionReadDates = emptyMap(), manuallyUnreadSessionKeys = emptySet(), mutedSessionKeys = emptySet(),
        )
    }
}

/** Home's live state as In-App Notices reads it; nothing while it's off. */
internal fun HomeFeature.noticeInput(state: State): InAppNoticeInput {
    val mode = noticeMode(state)
    if (mode == InAppNoticeMode.off) return InAppNoticeInput.off
    val isLive = state.isConnected && state.hasConnected && state.snapshotLoaded &&
        state.noticeReadiness == State.NoticeReadiness.settled
    if (!isLive) {
        return InAppNoticeInput.off.copy(mode = mode, server = state.server)
    }
    return InAppNoticeInput(
        mode = mode,
        isLive = true,
        server = state.server,
        sessions = state.sessions,
        activeSessionIDs = state.activeSessionIDs,
        parents = state.parentSources,
        attentionSessionIDs = state.attentionSessionIDs,
        sessionReadDates = state.sessionReadDates,
        manuallyUnreadSessionKeys = state.manuallyUnreadSessionKeys,
        mutedSessionKeys = state.mutedSessionKeys,
    )
}

/** Off wherever the drawer button can't show the label. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.noticeMode(state: State): InAppNoticeMode =
    dependency(InAppNoticeSettings).mode().effective(showsDrawerLabel = state.showsDrawerLabel)

/** Most a session's events are remembered for, so repeats stay quiet. */
internal const val noticedEventLimit = 200

@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.inAppNoticeSignalChanged(oldInput: InAppNoticeInput, newInput: InAppNoticeInput, state: State): HomeReduced {
    var s = state
    val mode = newInput.mode
    val old = oldInput.signal
    val new = newInput.signal
    // Turned off, or answered and read anywhere: what it named goes.
    var cleared = false
    val shown = s.inAppNotice
    if (shown != null && (mode == InAppNoticeMode.off || new.isLive)) {
        val events = shown.events.filterNot { mode == InAppNoticeMode.off || !new.stillHolds(it) }
        if (events.isEmpty()) {
            s = s.copy(inAppNotice = null)
            cleared = true
        } else if (events != shown.events) {
            s = s.copy(inAppNotice = shown.copy(events = events))
        }
    }
    var noticed = s.noticedEvents
    if (new.isLive) {
        // A request answered may come back later as a new one.
        noticed = noticed.filterNot { key ->
            key.startsWith("input|") && new.sessions[key.removePrefix("input|")]?.needsInput != true
        }
    }

    val openIDs = s.openSessionIDs
    val canShow = s.canShowInAppNotice
    val fresh = mutableListOf<InAppNotice.Event>()
    for ((event, key) in InAppNoticeSignal.events(from = old, to = new)) {
        if (key in noticed) continue
        // Passed over now (the drawer open, the session on screen) is passed over for good: it
        // never turns up late.
        noticed = noticed + key
        if (!mode.shows(event.kind) || event.sessionID in openIDs || !canShow) continue
        fresh += event
    }
    if (noticed.size > noticedEventLimit) noticed = noticed.drop(noticed.size - noticedEventLimit)
    if (noticed != s.noticedEvents) s = s.copy(noticedEvents = noticed)
    // A new notice's timer replaces the old one in flight.
    if (fresh.isEmpty()) return Reduced(s, if (cleared) Effect.cancel(HomeFeature.CancelID.InAppNotice) else Effect.none())

    val base = s.inAppNotice ?: run {
        s = s.copy(inAppNoticeGeneration = s.inAppNoticeGeneration + 1)
        InAppNotice(id = s.inAppNoticeGeneration, events = emptyList())
    }
    val (notice, added) = base.merge(fresh)
    s = s.copy(inAppNotice = notice)
    if (added.isEmpty()) return Reduced(s)
    val urgent = added.any { it.kind == InAppNotice.Kind.needsInput }
    // A call has its own sounds.
    val inCall = s.sessionDetail?.voice?.let { !it.phase.isTerminal } ?: false
    // The Android app has one task, so the window is always the one in use.
    if (urgent && !inCall) dependency(HapticsClient).play(Haptic.attention)
    if (!inCall && dependency(ExperimentalFeatures)(ExperimentalFeature.soundEffects)) {
        dependency(SoundEffectsClient).play(if (urgent) SoundEffect.needsInput else SoundEffect.replyDone)
    }
    dependency(AccessibilityAnnouncer).announce(notice.announcement, urgent)
    val id = notice.id
    val hold = notice.hold
    val clock = dependency(Clock)
    return Reduced(
        s,
        Effect.run<Action> { send ->
            clock.sleep(hold)
            send(Action.InAppNoticeExpired(id))
        }.cancellable(HomeFeature.CancelID.InAppNotice, cancelInFlight = true),
    )
}
