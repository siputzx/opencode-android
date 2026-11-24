package ai.opencode.feature.drawer

import ai.opencode.devtools.traces.TraceUploadsFeature
import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionSheetFeature
import ai.opencode.feature.session.isVoiceAvailable
import ai.opencode.feature.session.modelApplied
import ai.opencode.feature.session.reconcileInvariants
import ai.opencode.feature.session.restoreSessionData
import ai.opencode.feature.session.syncVoiceTarget
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.voice.ReplyWatch
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceWorkClient
import ai.opencode.feature.voice.tracksSessionDelegatorTools
import ai.opencode.server.OpenCodeLiveContentEvent

/*
 * What the open session and pushed subagents did that Home coordinates (sends, receipts, family
 * reads, model picks, delegate requests), matched after the screen has reduced the action, how Home
 * forwards server events to the screens that care, and the root's call (which a pushed subagent's
 * screen shows and targets).
 */

internal fun HomeFeature.reduceSubagent(state: State, id: Int, action: SessionFeature.Action): HomeReduced {
    val now = { dependency(DateClient).now() }
    return when (action) {
        is SessionFeature.Action.Browser, SessionFeature.Action.SendTapped, SessionFeature.Action.QueueTapped,
        is SessionFeature.Action.DeferredSendReady, SessionFeature.Action.SharedSendRequested -> {
            // The drawer lists the root, which is where the activity shows.
            val s = state.notingScreenSendIfAccepted(state.subagentStack.firstOrNull { it.navigationID == id }, action, now).first
            val detail = s.subagentStack.firstOrNull { it.navigationID == id }
            val voice = s.sessionDetail?.voice
            if (action !is SessionFeature.Action.Browser || s.subagentStack.lastOrNull()?.navigationID != id || detail == null ||
                voice == null || voice.target.sessionID != detail.session?.id || voice.viewing == detail.browser.viewing
            ) {
                Reduced(s)
            } else {
                Reduced(s, Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.ViewingChanged(detail.browser.viewing)))))
            }
        }
        is SessionFeature.Action.ModelApplied ->
            Reduced(state.notingModel(action.model, action.sessionID).updatingDetail { it.modelApplied(action.model, action.sessionID) })
        is SessionFeature.Action.SessionSheet -> {
            val compacted = (action.action as? SessionSheetFeature.Action.Delegate)?.delegate as? SessionSheetFeature.Delegate.Compacted
            val model = compacted?.model
            val sessionID = state.subagentStack.firstOrNull { it.navigationID == id }?.sessionSheet?.sessionID
            if (model == null || sessionID == null) Reduced(state)
            else Reduced(state.notingModel(model, sessionID).updatingDetail { it.modelApplied(model, sessionID) })
        }
        is SessionFeature.Action.ModelPicked, is SessionFeature.Action.ModelPickedWithLevel, is SessionFeature.Action.VariantPicked,
        is SessionFeature.Action.PendingRequestsResponse, is SessionFeature.Action.RequestDock,
        -> Reduced(state.syncingTopSubagentVoiceTarget(id))
        is SessionFeature.Action.ViewAcknowledged -> Reduced(state.notingViewAcknowledged(action.sessionID, action.idle))
        is SessionFeature.Action.MissingFolderMoved -> sessionMoved(state, action.sessionID, action.directory)
        is SessionFeature.Action.Delegate -> reduceSessionDelegate(state, action.delegate)
        else -> Reduced(state)
    }
}

/** The call on the root follows the model pick and requests of the subagent on top. */
private fun State.syncingTopSubagentVoiceTarget(id: Int): State {
    if (subagentStack.lastOrNull()?.navigationID != id) return this
    val detail = subagentStack.last()
    return updatingDetail { it.syncVoiceTarget(detail) }
}

/**
 * A call started from a pushed subagent: the root owns it, so it lives as long as the stack, and it
 * starts targeted at the screen on top.
 */
internal fun HomeFeature.startCallOnTop(state: State): HomeReduced {
    val root = state.sessionDetail ?: return Reduced(state)
    val topScreen = state.topDetail ?: return Reduced(state)
    val top = topScreen.session ?: return Reduced(state)
    var s = state
    if (root.voice?.phase?.isTerminal == true) s = s.updatingDetail { it.copy(voice = null) }
    if (s.sessionDetail?.voice != null) return Reduced(s)
    if (!root.isVoiceAvailable) return Reduced(s.showingSettings())
    var voice = VoiceFeature.State(s.server, top)
    voice = voice.copy(replyWatch = voice.replyWatch.copy(activeSessionIDs = s.activeSessionIDs), viewing = topScreen.browser.viewing)
    s = s.updatingDetail { it.copy(voice = voice).reconcileInvariants(targetScreen = topScreen) }
    return Reduced(s)
}

internal fun HomeFeature.reduceSessionDetail(state: State, action: SessionFeature.Action): HomeReduced {
    val now = { dependency(DateClient).now() }
    when (action) {
        is SessionFeature.Action.Voice -> {
            val voiceAction = (action.action as? PresentationAction.Presented)?.action
            val delegate = (voiceAction as? VoiceFeature.Action.Delegate)?.action ?: return Reduced(state)
            return when (delegate) {
                is VoiceFeature.DelegateAction.OpenVoiceSettings -> Reduced(state.showingSettings(voicePage = delegate.page))
                // As the server's attention event does: REST settles every screen's requests (the
                // answered one may be a subagent's) and the drawer's.
                is VoiceFeature.DelegateAction.RequestSettled -> Reduced(
                    state,
                    Effect.merge(refreshAttention(state), forwardToScreens(state, SessionFeature.Action.RequestsChanged) { it.session != null }),
                )
                is VoiceFeature.DelegateAction.DelegatorSessionCreated -> {
                    if (state.sessions.any { it.id == delegate.session.id }) {
                        Reduced(state)
                    } else {
                        Reduced(state.copy(sessions = listOf(delegate.session) + state.sessions))
                    }
                }
                // The root owns the call even when it targets a pushed subagent.
                is VoiceFeature.DelegateAction.PromptAdmitted ->
                    Reduced(state, forwardToSubagents(state, delegate.sessionID, SessionFeature.Action.VoicePromptAdmitted(delegate.sessionID)))
                is VoiceFeature.DelegateAction.PendingModelApplied -> Reduced(state.notingModel(delegate.model, delegate.sessionID))
                is VoiceFeature.DelegateAction.ModelSwitched -> {
                    val sessionID = delegate.sessionID ?: return Reduced(state)
                    Reduced(state.notingModel(delegate.model, sessionID, replacesPick = true))
                }
                is VoiceFeature.DelegateAction.PickWaiting -> {
                    // Heard before the session's screen opened: it starts with the pick.
                    val pushedID = state.subagentStack.firstOrNull { it.session?.id == delegate.sessionID }?.navigationID
                    if (pushedID != null) {
                        val s = state.updatingSubagent(pushedID) { it.copy(currentModel = delegate.model, pendingModel = delegate.model) }
                        val topScreen = s.topDetail
                        Reduced(s.updatingDetail { it.reconcileInvariants(targetScreen = topScreen) })
                    } else if (state.sessionDetail?.session?.id != delegate.sessionID) {
                        Reduced(state.copy(waitingPicks = state.waitingPicks + (delegate.sessionID to delegate.model)))
                    } else {
                        Reduced(state)
                    }
                }
                else -> Reduced(state)
            }
        }

        is SessionFeature.Action.Browser, SessionFeature.Action.SendTapped, SessionFeature.Action.QueueTapped,
        is SessionFeature.Action.DeferredSendReady, SessionFeature.Action.SharedSendRequested -> {
            // The session reducer has already run: an accepted send cleared the draft and is
            // awaiting its reply. Recents lists roots.
            val (s, sessionID) = state.notingScreenSendIfAccepted(state.sessionDetail, action, now)
            if (sessionID != null && action !is SessionFeature.Action.Browser) {
                s.sessions.firstOrNull { it.id == sessionID }?.let { session ->
                    dependency(ShareInboxClient).donateSuggestion(s.server.persistenceID, sessionID, session.title)
                }
            }
            return if (sessionID == null) Reduced(s) else startSessionActivity(s, sessionID)
        }

        is SessionFeature.Action.ViewAcknowledged -> return Reduced(state.notingViewAcknowledged(action.sessionID, action.idle))

        is SessionFeature.Action.MissingFolderMoved -> return sessionMoved(state, action.sessionID, action.directory)

        is SessionFeature.Action.FamilyPicked -> {
            var s = state
            val detail = s.sessionDetail
            val snapshot = s.sessionSnapshots[action.sessionID]
            if (detail != null && detail.session?.id == action.sessionID && snapshot != null &&
                snapshot.session.directory == detail.session?.directory
            ) {
                // What the reader saw there last time, as reopening it would.
                val restored = snapshot.restoreSessionData(detail).noteOpening(seen = snapshot.seenItemIDs, unread = snapshot.hasUnseenItems)
                s = s.copy(sessionDetail = restored.reconcileInvariants())
            }
            if (s.sessionDetail?.readsOnView == true) {
                s.sessionDetail?.familySessions?.firstOrNull { it.id == action.sessionID }?.let { s = s.markingReadLocally(it) }
            }
            return Reduced(s)
        }

        is SessionFeature.Action.FamilyResponse ->
            return Reduced(if (action.result.isSuccess) state.syncingBusyAncestryFromFamily() else state)

        is SessionFeature.Action.FamilySessionDeleted -> return Reduced(state.syncingBusyAncestryFromFamily(action.sessionID))

        SessionFeature.Action.Task -> {
            val detail = state.sessionDetail ?: return Reduced(state)
            val sessionID = detail.session?.id
            if (detail.initialTranscriptRequestIsExternal || sessionID == null) return Reduced(state)
            return cancelBackgroundTranscriptReads(state, setOf(sessionID))
        }

        is SessionFeature.Action.RefreshRequested -> {
            if (action.reason !in foregroundRefreshReasons) return Reduced(state)
            return cancelBackgroundTranscriptReads(state, setOfNotNull(state.sessionDetail?.session?.id))
        }

        is SessionFeature.Action.VisibilityChanged -> {
            if (action.isVisible || state.selectedTranscriptFetch == null) return Reduced(state)
            val s = state.copy(selectedTranscriptFetch = null)
                .updatingDetail { it.copy(initialTranscriptRequestIsExternal = false, isLoading = false) }
            return Reduced(s, Effect.cancel(HomeFeature.CancelID.SelectedTranscript))
        }

        is SessionFeature.Action.AcceptedTranscriptUpdated -> {
            val s = state.learningFirstPrompt(state.sessionDetail?.session?.takeIf { it.id == action.sessionID })
                .publishingTranscript(action.sessionID, action.items).persistingTranscriptCache(action.sessionID)
                .syncingSeenItemsFromOpenDetail(action.sessionID)
            return Reduced(s, prefetchTranscripts(s))
        }

        is SessionFeature.Action.NewMessagesPassed, is SessionFeature.Action.NewMessagesFit -> {
            val sessionID = state.sessionDetail?.session?.id ?: return Reduced(state)
            return Reduced(state.syncingSeenItemsFromOpenDetail(sessionID))
        }

        is SessionFeature.Action.ModelApplied -> return Reduced(state.notingModel(action.model, action.sessionID))

        is SessionFeature.Action.SessionSheet -> {
            val compacted = (action.action as? SessionSheetFeature.Action.Delegate)?.delegate as? SessionSheetFeature.Delegate.Compacted
            val model = compacted?.model ?: return Reduced(state)
            val sessionID = state.sessionDetail?.sessionSheet?.sessionID ?: return Reduced(state)
            return Reduced(state.notingModel(model, sessionID))
        }

        is SessionFeature.Action.SessionCreated -> {
            // The screen's copy knows the first prompt it was created with.
            val session = state.sessionDetail?.session?.takeIf { it.id == action.session.id } ?: action.session
            // The event stream may have listed it already.
            val s = state.copy(sessions = listOf(session) + state.sessions.filterNot { it.id == session.id })
                .updatingSessionReadDates { it + (state.sessionKey(session.id) to session.updatedAt) }
                .updatingLastViewedSessionIDs { it + (state.server.persistenceID to session.id) }
            // `core` persists the snapshot after this action. A draft's send has a session to follow
            // only now.
            val detail = s.sessionDetail
            if (session.parentID != null || detail?.session?.id != session.id || !detail.isAwaitingReply) return Reduced(s)
            return startSessionActivity(s, session.id)
        }

        is SessionFeature.Action.Delegate -> return reduceSessionDelegate(state, action.delegate)

        else -> return Reduced(state)
    }
}

private fun HomeFeature.reduceSessionDelegate(state: State, delegate: SessionFeature.Delegate): HomeReduced = when (delegate) {
    is SessionFeature.Delegate.OpenSession -> openSessionFromDelegate(state, delegate)
    is SessionFeature.Delegate.OpenSubagent -> {
        // A Catch Up card swaps its family in place: its swipe is the deck's, so nothing is pushed over it.
        if (state.catchUp != null) {
            Reduced(state, Effect.send(Action.detail(SessionFeature.Action.FamilyPicked(delegate.session.id))))
        } else if (state.topDetail?.session?.id == delegate.session.id) {
            Reduced(state)
        } else {
            cancelBackgroundTranscriptReads(state.pushingSubagent(delegate.session), setOf(delegate.session.id))
        }
    }
    is SessionFeature.Delegate.ResolveSessionLinks -> resolveSessionLinks(state, delegate.ids)
    is SessionFeature.Delegate.OpenSessionLink -> openSessionLink(state, delegate.id)
    SessionFeature.Delegate.CallRequested -> startCallOnTop(state)
    is SessionFeature.Delegate.TogglePin -> Reduced(state, Effect.send(Action.TogglePin(delegate.sessionID)))
    is SessionFeature.Delegate.SetPinned -> {
        // From the voice agent: the session may be outside Recents.
        var s = state
        if (s.sessions.none { it.id == delegate.session.id }) s = s.copy(sessions = listOf(delegate.session) + s.sessions)
        if (s.isPinned(delegate.session.id) == delegate.pinned) {
            Reduced(s)
        } else {
            // No finger on a control: no haptic, unlike `togglePin`.
            val key = s.sessionKey(delegate.session.id)
            Reduced(s.updatingPinnedSessionKeys { keys -> if (delegate.pinned) keys + key else keys.filterNot { it == key } })
        }
    }
    is SessionFeature.Delegate.OpenDraft ->
        if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) Reduced(state)
        else Reduced(state.leavingCatchUp().openingDraft(delegate.directory, reason = "the voice agent opened a draft"))
    is SessionFeature.Delegate.KeepDraft -> {
        val drafts = state.sharedDrafts
        drafts[delegate.target] = drafts.getOrDefault(delegate.target) { SharedComposerDraft() }.appending(delegate.draft)
        Reduced(state)
    }
    is SessionFeature.Delegate.ToggleRead -> {
        val session = state.sessions.firstOrNull { it.id == delegate.sessionID }
        if (session == null) Reduced(state)
        else Reduced(state, Effect.send(if (state.isUnread(session)) Action.MarkRead(delegate.sessionID) else Action.MarkUnread(delegate.sessionID)))
    }
    is SessionFeature.Delegate.DeleteSession -> {
        // The sheet already confirmed; the drawer's deletion path does the rest.
        val session = state.sessions.firstOrNull { it.id == delegate.sessionID }
        if (session == null) Reduced(state)
        else Reduced(state.copy(pendingDeletion = session, deletionError = null), Effect.send(Action.DeleteSessionConfirmed))
    }
    SessionFeature.Delegate.ReportProblem -> {
        val context = state.problemReportContext
        Reduced(state, Effect.send(Action.Traces(TraceUploadsFeature.Action.ReportProblemTapped(context, state.problemReportDestination(context)))))
    }
}

/** Sends pushed subagents' screens what Home sends the session's, to those [matching] picks: they stay live while covered. */
internal fun forwardToSubagents(
    state: State,
    action: SessionFeature.Action,
    matching: (SessionFeature.State) -> Boolean = { true },
): Effect<Action> = Effect.merge(
    state.subagentStack.filter(matching).map { Effect.send(Action.SubagentStack(it.navigationID, action)) },
)

internal fun forwardToSubagents(state: State, sessionID: String, action: SessionFeature.Action): Effect<Action> =
    forwardToSubagents(state, action) { it.session?.id == sessionID }

/** Sends [action] to the root session detail and every pushed screen matching [matching]. */
internal fun forwardToScreens(
    state: State,
    action: SessionFeature.Action,
    matching: (SessionFeature.State) -> Boolean = { true },
): Effect<Action> {
    val pushed = forwardToSubagents(state, action, matching)
    val detail = state.sessionDetail
    if (detail == null || !matching(detail)) return pushed
    return Effect.merge(Effect.send(Action.detail(action)), pushed)
}

internal fun forwardToSession(state: State, sessionID: String, action: SessionFeature.Action): Effect<Action> =
    forwardToScreens(state, action) { it.session?.id == sessionID }

/** `.send(.sessionDetail(.presented(action)))` while a session screen is open. */
internal fun sendToDetail(state: State, action: SessionFeature.Action): Effect<Action> =
    if (state.sessionDetail == null) Effect.none() else Effect.send(Action.detail(action))

/** Whether [sessionID] descends from a member of the screen's family by [parents] without being listed in it. */
internal fun joinsUnlisted(screen: SessionFeature.State, sessionID: String, parents: Map<String, String>): Boolean {
    val members = screen.familySessions.mapTo(HashSet()) { it.id }
    if (sessionID in members) return false
    var id = sessionID
    val seen = hashSetOf(id)
    while (true) {
        val parent = parents[id] ?: return false
        if (!seen.add(parent)) return false
        if (parent in members) return true
        id = parent
    }
}

/**
 * Whether [screen] cares about a family run-state change on [sessionID]: members of its family,
 * sessions descending from it by [parents], or unattributed sessions that may belong to any open
 * family until their ancestry resolves. Known roots and descendants of other families are skipped.
 */
internal fun shouldForwardFamilyRunState(
    screen: SessionFeature.State,
    sessionID: String,
    unattributed: Boolean,
    parents: Map<String, String>,
): Boolean {
    val session = screen.session ?: return false
    if (session.id == sessionID || screen.familyRootID == sessionID) return true
    if (screen.familySessions.any { it.id == sessionID }) return true
    if (joinsUnlisted(screen, sessionID, parents)) return true
    return unattributed
}

internal fun forwardToSubagentFamilies(state: State, member: String, action: SessionFeature.Action): Effect<Action> =
    forwardToSubagents(state, action) { screen -> screen.familySessions.any { it.id == member } }

/**
 * A content batch for the screens showing [sessionID], and a step's end for the call's reply watch.
 */
internal fun notifyVoiceContentBatch(state: State, sessionID: String, events: List<OpenCodeLiveContentEvent>): Effect<Action> {
    val hasStepEnded = events.any { it is OpenCodeLiveContentEvent.StepEnded }
    val voiceEffect = if (hasStepEnded && state.sessionDetail?.voice?.replyWatch?.isWatching(sessionID) == true) {
        Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.SseStepEnded(sessionID)))))
    } else {
        Effect.none()
    }
    val toolEvents = if (state.sessionDetail?.voice?.tracksSessionDelegatorTools(sessionID) == true) {
        events.filter { it.isToolLifecycleEvent }
    } else {
        emptyList()
    }
    val delegatorToolsEffect: Effect<Action> = if (toolEvents.isEmpty()) {
        Effect.none()
    } else {
        Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.SessionDelegatorTools(sessionID, toolEvents))))
    }
    val pushed = forwardToSubagents(state, sessionID, SessionFeature.Action.ContentBatch(sessionID, events))
    if (state.sessionDetail?.session?.id != sessionID) return Effect.merge(voiceEffect, delegatorToolsEffect, pushed)
    return Effect.merge(Effect.send(Action.detail(SessionFeature.Action.ContentBatch(sessionID, events))), voiceEffect, delegatorToolsEffect, pushed)
}

/** A run ended: the call's reply watch settles it, or just hears it stopped. */
internal fun notifyVoiceExecutionEnded(state: State, sessionID: String): Reduced<State, Action> {
    val voice = state.sessionDetail?.voice ?: return Reduced(state)
    val relays = voice.usesSessionDelegator && voice.replyWatch.awaitsParentRelay(sessionID)
    if (!voice.replyWatch.isWatching(sessionID) && !relays) {
        return Reduced(state.updatingDetail { it.copy(voice = voice.copy(replyWatch = voice.replyWatch.setSessionActive(sessionID, false))) })
    }
    val voiceWork = dependency(VoiceWorkClient)
    val server = state.server
    val settle = Effect.run<Action> { voiceWork.noteSessionSettled(server, sessionID) }
    val forward = Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.SseExecutionEnded(sessionID)))))
    return Reduced(state, Effect.merge(settle, forward))
}

/** A list snapshot: the call's reply watch settles what stopped running. */
internal fun notifyVoiceSnapshot(state: State, activeIDs: Set<String>): Reduced<State, Action> {
    val voice = state.sessionDetail?.voice ?: return Reduced(state)
    if (!voice.replyWatch.hasSettledWatch(activeIDs)) {
        return Reduced(state.updatingDetail { it.copy(voice = voice.copy(replyWatch = voice.replyWatch.copy(activeSessionIDs = activeIDs))) })
    }
    val voiceWork = dependency(VoiceWorkClient)
    val server = state.server
    val settle = Effect.run<Action> { voiceWork.noteActiveSessions(server, activeIDs) }
    val forward = Effect.send(
        Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.ActiveSessionsSnapshot(activeIDs, ReplyWatch.SettlementSource.snapshot)))),
    )
    return Reduced(state, Effect.merge(settle, forward))
}

/**
 * The voice agent (or a fork) puts a session on screen. Settings, What’s New, a deletion
 * confirmation and every mounted session's sheets go, the drawer closes, and Home's normal path
 * moves there. A voice open (`toolID`) navigates only while App's operation authorizes it, and in
 * this same turn.
 */
private fun HomeFeature.openSessionFromDelegate(state: State, delegate: SessionFeature.Delegate.OpenSession): HomeReduced {
    val toolID = delegate.toolID
    if (toolID != null && state.voiceNavigationToolID != toolID) return Reduced(state)
    val session = delegate.session
    var s = state.copy(settings = null, pendingDeletion = null).clearingSessionCovers()
    val index = s.sessions.indexOfFirst { it.id == session.id }
    if (index >= 0) {
        // The event stream may have listed it before its model was set.
        val model = session.model
        if (model != null) s = s.updatingSession(session.id) { it.copy(model = model) }
    } else {
        s = s.copy(sessions = listOf(session) + s.sessions)
    }
    s = s.copy(nextMoveReason = "the voice agent switched", drawerCloseRequests = s.drawerCloseRequests + 1)
    // The authorized navigation starts in this turn, not an unguarded queued SessionTapped that
    // could outlive a timeout or refusal.
    if (toolID != null) return reduceDrawer(s, Action.SessionTapped(session.id))
    return Reduced(s, Effect.send(Action.SessionTapped(session.id)))
}
