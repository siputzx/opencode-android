package ai.opencode.feature.drawer

import ai.opencode.core.log.CallTrigger
import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.hasLiveDictation
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.isVoiceAvailable
import ai.opencode.feature.system.liveactivity.DictationCommand
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceCallCommand
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.SystemPublication
import ai.opencode.feature.system.share.ShareCredentials
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.system.share.ShareTargets
import ai.opencode.feature.system.share.ShareTargetsPublication
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareHandoff
import ai.opencode.feature.system.share.resolvedShareTarget
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession

/*
 * The share sheet's credentials, the call's and the recording's ongoing-notification commands,
 * shares arriving, and what Shortcuts, launcher actions and links open (and calls they start), and
 * the list published to the system.
 */

/** The share sheet's credentials. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishShareCredentials(server: OpenCodeServer): Effect<Action> {
    val shareInbox = dependency(ShareInboxClient)
    return Effect.run { shareInbox.publishCredentials(ShareCredentials(server)) }
}

internal fun HomeFeature.reduceSystem(state: State, action: Action): HomeReduced = when (action) {
    is Action.VoiceCallCommandReceived -> {
        val voice = state.sessionDetail?.voice
        if (voice == null) {
            // No call owns the notification the tap came from.
            val client = dependency(VoiceLiveActivityClient)
            Reduced(state.copy(voiceCallActivity = null), Effect.run { client.update(null, null) })
        } else if (voice.phase.isTerminal) {
            // An ended call can still be in state while its bar sweeps out.
            Reduced(state)
        } else {
            val callAction = when (action.command) {
                VoiceCallCommand.End -> VoiceFeature.Action.EndTapped
                VoiceCallCommand.ToggleMute -> VoiceFeature.Action.MuteTapped
            }
            Reduced(state, Effect.send(Action.detail(SessionFeature.Action.voicePresented(callAction))))
        }
    }

    is Action.DictationCommandReceived -> {
        val child = state.subagentStack.lastOrNull { it.hasLiveDictation }
        when {
            child != null -> when (action.command) {
                DictationCommand.Stop -> Reduced(state, Effect.send(Action.SubagentStack(child.navigationID, SessionFeature.Action.dictationStopRequested)))
            }
            state.sessionDetail?.hasLiveDictation != true -> {
                // No recording owns the notification the tap came from.
                val client = dependency(DictationLiveActivityClient)
                Reduced(state.copy(dictationActivity = null), Effect.run { client.update(null, null) })
            }
            else -> when (action.command) {
                DictationCommand.Stop -> Reduced(state, Effect.send(Action.detail(SessionFeature.Action.dictationStopRequested)))
            }
        }
    }

    is Action.SharesReceived -> sharesReceived(state, action.shares)

    is Action.SystemActionOpened ->
        openSystemDestination(
            state, action.destination, startsCall = action.startsCall, inBackground = action.inBackground,
            trigger = CallTrigger.System(action.origin),
        )

    is Action.SystemPublishRequested -> {
        // A change publishes through `systemPublication`'s watch.
        if (state.systemVoiceAvailable == action.voiceAvailable) publishToSystem(state)
        Reduced(state.copy(systemVoiceAvailable = action.voiceAvailable))
    }

    else -> Reduced(state)
}

private fun HomeFeature.sharesReceived(state: State, shares: List<ReceivedShare>): HomeReduced {
    if (shares.isEmpty()) return Reduced(state)
    var s = state
    var newestTarget: SharedDraftTarget = SharedDraftTarget.NewSession
    for (share in shares) {
        val resolved = resolvedShareTarget(share.handoff.target, s.sessions)
        val target = SharedDraftTarget(resolved.sessionID)
        val drafts = s.sharedDrafts
        drafts[target] = drafts.getOrDefault(target) { SharedComposerDraft() }.appending(sharedComposerDraft(share, resolved.projectID))
        newestTarget = target
    }
    s = s.leavingCatchUp()
    dependency(HapticsClient).play(Haptic.success)
    // The composer on screen keeps only what's for it once the newest share's session is open;
    // until then it takes nothing it would lose.
    val onScreen = s.sessionDetail?.let { SharedDraftTarget(it.session?.id) }
    s = s.copy(shareNavigationTarget = if (onScreen == newestTarget) null else newestTarget)
    return when (val newest = newestTarget) {
        is SharedDraftTarget.Session -> Reduced(s, Effect.send(Action.SessionTapped(newest.id)))
        SharedDraftTarget.NewSession -> {
            if (s.sessionDetail?.isDraft != true) s = s.openingDraft(reason = "something was shared to the app")
            Reduced(s)
        }
    }
}

/**
 * The composer half of a share. Built here because `:feature:composer` can't see
 * `:feature:system`'s [ReceivedShare].
 */
private fun sharedComposerDraft(share: ReceivedShare, projectID: String?): SharedComposerDraft {
    val base = SharedComposerDraft(
        text = share.handoff.text.trim(),
        attachments = share.images.map { OpenCodeOutgoingAttachment(id = it.id.toString(), data = it.data, mime = it.mime) },
        files = share.files,
        projectID = projectID,
    )
    return when (share.handoff.resolvedDelivery) {
        ShareHandoff.Delivery.draft -> base
        ShareHandoff.Delivery.openInComposer -> base.copy(focusesComposer = true)
        ShareHandoff.Delivery.send -> base.copy(send = SharedComposerDraft.Send(share.handoff.messageID, share.handoff.newSessionID))
    }
}

/**
 * Hands the list to Shortcuts, launcher actions and search, never before it has loaded: an empty
 * list then means "not yet", not "none".
 */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishToSystem(state: State) {
    if (!state.snapshotLoaded) return
    dependency(SystemIntegrationClient).publish(state.systemPublication.systemDirectoryEntry, state.systemVoiceAvailable)
}

/** What Shortcuts, launcher actions and search are handed. */
internal val State.systemPublication: SystemPublication
    get() = SystemPublication(
        server = server,
        loaded = snapshotLoaded,
        sessions = sessions,
        projects = projects,
        projectsByRecency = projectsByRecency,
        pinnedSessionKeys = pinnedSessionKeys,
        lastViewedSessionIDs = lastViewedSessionIDs,
        voiceAvailable = systemVoiceAvailable,
    )

/**
 * What the share sheet's targets are built from: Catch Up's card isn't the session you'd share to,
 * the one it was opened from is.
 */
internal val State.shareTargetsPublication: ShareTargetsPublication
    get() = ShareTargetsPublication(
        hasConnected = hasConnected,
        server = server,
        sessions = sessions,
        projects = projects,
        pinnedSessionKeys = pinnedSessionKeys,
        lastViewedSessionID = if (catchUp == null) sessionDetail?.session?.id else (catchUp?.returnSessionID ?: lastViewedSessionIDs[server.persistenceID]),
        draftProject = if (catchUp == null && sessionDetail?.isDraft == true) sessionDetail.draftProject else null,
    )

/** Pinned and recent sessions in drawer order, without the drawer's search. */
internal val State.shareTargets: ShareTargets get() = shareTargetsPublication.targets()

/**
 * Opens what a shortcut, launcher action, search result or link names, and starts a call there
 * through the call button's own path when asked. `AppFeature` sends this only once the list has loaded.
 */
internal fun HomeFeature.openSystemDestination(
    state: State,
    destination: SystemAction.Destination,
    startsCall: Boolean,
    inBackground: Boolean = false,
    trigger: CallTrigger,
): HomeReduced {
    var s = state.copy(settings = null)
    val session: OpenCodeSession? = when (destination) {
        is SystemAction.Destination.Session -> s.sessions.firstOrNull { it.id == destination.id } ?: return Reduced(s)
        SystemAction.Destination.LastViewed -> s.lastViewedOrNewestSession
        is SystemAction.Destination.Draft -> null
    }

    if (session == null) {
        // A draft, as New Session makes one; one already open keeps its text.
        if (s.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(s)
        s = s.withCatchUp(null)
        val projectID = (destination as? SystemAction.Destination.Draft)?.projectID
        val project = projectID?.let { id -> s.projects.firstOrNull { it.id == id } }
        val effects = mutableListOf<Effect<Action>>()
        if (s.sessionDetail?.isDraft != true) {
            s = s.openingDraft(directory = project?.directory, reason = "a shortcut started a new session")
        } else if (project != null && s.sessionDetail?.draftProject != project) {
            // The picker's own path, which also moves a live call's draft.
            effects += Effect.send(Action.detail(SessionFeature.Action.ProjectPicked(project)))
        }
        // A project that's gone is asked for again, never swapped for another.
        if (projectID != null && project == null) s = s.updatingDetail { it.copy(draftProject = null) }
        if (startsCall) {
            val call = startSystemCall(s, inBackground, trigger)
            s = call.state
            effects += call.effect
        }
        return Reduced(s, Effect.concatenate(effects))
    }

    if (!startsCall) return Reduced(s, Effect.send(Action.SessionTapped(session.id)))
    // Opened at once rather than after its transcript loads, so the call starts on this session;
    // the session loads its own transcript. A subagent of it on screen gives way to the session itself.
    s = s.withCatchUp(null).copy(pendingSubagentPush = null)
    val opened = openViewedSession(s, session, cachedItems = s.cachedTranscriptItems(session), reason = "a shortcut started a call")
    val call = startSystemCall(opened.state, inBackground, trigger)
    return Reduced(call.state, Effect.merge(opened.effect, call.effect))
}

/**
 * The call button's path. A call already live has moved here with the screen; one that ended is
 * cleared first, as the screen does when it changes; without Voice set up, Settings opens where
 * it's set up. In the background no call view appears to start the call (`startIfNeeded`), so
 * it's started here. [trigger] is logged only once the call really starts.
 */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.startSystemCall(state: State, inBackground: Boolean = false, trigger: CallTrigger): HomeReduced {
    val detail = state.sessionDetail ?: return Reduced(state)
    var s = state
    if (detail.voice?.phase?.isTerminal == true) s = s.updatingDetail { it.copy(voice = null) }
    if (s.sessionDetail?.voice != null) return Reduced(s)
    if (!detail.isVoiceAvailable) return Reduced(s.showingSettings())
    trigger.log()
    val tapped = Effect.send<Action>(Action.detail(SessionFeature.Action.VoiceTapped))
    if (!inBackground) return Reduced(s, tapped)
    return Reduced(s, Effect.concatenate(tapped, Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.StartIfNeeded)))))
}

/** Tells the open screens their requests lost the connection. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.requestsDisconnected(state: State): Effect<Action> = Effect.merge(
    sendToDetail(state, SessionFeature.Action.RequestsConnectionChanged(false)),
    forwardToSubagents(state, SessionFeature.Action.RequestsConnectionChanged(false)),
)
