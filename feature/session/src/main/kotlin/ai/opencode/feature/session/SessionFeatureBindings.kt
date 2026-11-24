package ai.opencode.feature.session

import ai.opencode.feature.system.notifications.PushSync
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.feature.composer.ComposerCommandOutcome
import ai.opencode.feature.composer.ComposerAutocompleteOutcome
import ai.opencode.feature.composer.ComposerAutocompleteEnvironment
import ai.opencode.feature.composer.ComposerAutocompleteHost
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.log.CallTrigger
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompleteReducer
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.voice.DotScopeSimulation
import ai.opencode.feature.transcript.markdown.MarkdownQuote
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeProject
import kotlin.time.Duration.Companion.seconds

// The call, Raise to Talk, bindings, and the Project sheet's folders.

/**
 * The view's two-way bindings, as explicit setter actions. [bindingReducer] writes the field first,
 * then the core chain reacts.
 */
sealed interface BindingAction {
    /** `$store.draft`: the composer's text. Written through [withDraft]. */
    data class Draft(val value: String) : BindingAction
    /** `$store.activePicker`: a sheet presented or dismissed. */
    data class ActivePicker(val value: State.ActivePicker?) : BindingAction
    /** `$store.modelFilter`: the model picker's search. */
    data class ModelFilter(val value: String) : BindingAction
    /** `$store.projectFilter`: the Project sheet's search. */
    data class ProjectFilter(val value: String) : BindingAction
}

/** Applies a binding's value before the rest of the body sees the action. */
internal val bindingReducer: Reducer<State, Action> = Reducer { state, action ->
    val binding = (action as? Action.Binding)?.action ?: return@Reducer Reduced(state)
    Reduced(
        when (binding) {
            is BindingAction.Draft -> state.withDraft(binding.value)
            is BindingAction.ActivePicker -> state.copy(activePicker = binding.value)
            is BindingAction.ModelFilter -> state.copy(modelFilter = binding.value)
            is BindingAction.ProjectFilter -> state.copy(projectFilter = binding.value)
        },
    )
}

/** The call, Raise to Talk, bindings, and the Project sheet's folders. Null: the action passes on. */
internal fun SessionFeature.reduceCallAndBindings(state: State, action: Action): Reduced<State, Action>? = when (action) {
    Action.RaisedToEar -> when {
        // Starts a call, never ends one: the ear isn't a toggle.
        state.voice != null || !state.isVoiceAvailable || state.isCovered -> Reduced(state)
        // The phone comes near the face while dictating too; a call would take the mic and end the recording.
        state.dictationBlocksRaise { date.now() } -> Reduced(state)
        else -> {
            CallTrigger.RaiseToTalk.log()
            // In order: the call must exist before it hears it's at the ear.
            callAfterDictation(state, atEar = true)
                ?: Reduced(state, Effect.concatenate(Effect.send(Action.VoiceTapped), Effect.send(Action.AtEarChanged(true))))
        }
    }

    Action.StopLongPressed ->
        if (state.voice != null || !state.isVoiceAvailable || !state.isRunning || state.hasPromptContent) {
            Reduced(state)
        } else {
            CallTrigger.StopHeld.log()
            Reduced(state, Effect.send(Action.VoiceTapped))
        }

    Action.CallButtonTapped -> {
        CallTrigger.CallButton.log()
        Reduced(state, Effect.send(Action.VoiceTapped))
    }

    is Action.AtEarChanged ->
        if (state.voice == null) Reduced(state)
        else Reduced(state, Effect.send(Action.voicePresented(VoiceFeature.Action.AtEarChanged(action.atEar))))

    is Action.CopyTextTapped -> {
        if (action.text.isEmpty()) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            val pasteboard = pasteboard
            val text = action.text
            Reduced(state, Effect.run { pasteboard.setString(text) })
        }
    }

    is Action.QuoteTextTapped -> {
        // A request's answer is plain text, so it takes the quote as Markdown.
        if (state.isAnsweringRequest) {
            val quoted = MarkdownQuote.blockquote(action.text)
            if (quoted == null) {
                Reduced(state)
            } else {
                val base = if (state.hasLiveDictation) state.composerWithoutDictation.text else state.draft
                var next = state.setTyped(MarkdownQuote.append(quoted, base)).applyBarEdit()
                next = next.copy(composerFocusRequest = next.composerFocusRequest + 1)
                haptics.play(Haptic.tap)
                Reduced(next)
            }
        } else {
            val cleaned = ComposerQuote.cleaned(action.text)
            if (cleaned == null) {
                Reduced(state)
            } else {
                val quote = ComposerQuote(id = uuid.next().toString(), text = cleaned)
                var next = state.withPendingQuotes(state.pendingQuotes + quote).withUndoSnapshot(null)
                next = next.copy(composerFocusRequest = next.composerFocusRequest + 1)
                haptics.play(Haptic.tap)
                Reduced(next)
            }
        }
    }

    Action.VoiceTapped ->
        if (state.voice != null) Reduced(state, Effect.send(Action.voicePresented(VoiceFeature.Action.EndTapped)))
        else callAfterDictation(state, atEar = false) ?: startCall(state)

    is Action.CallAudioYielded -> {
        if (state.voice != null) {
            Reduced(state)
        } else {
            val start = startCall(state)
            if (!action.atEar || start.state.voice == null) start
            else Reduced(start.state, Effect.merge(start.effect, Effect.send(Action.AtEarChanged(true))))
        }
    }

    is Action.CallSessionResponse -> {
        if (state.callSessionID == null) {
            Reduced(state)
        } else {
            val next = state.copy(callSessionID = null)
            val session = action.result.getOrNull()
            if (session == null || next.session != null) Reduced(next) else Reduced(next, Effect.send(Action.SessionCreated(session)))
        }
    }

    is Action.Voice -> reduceVoice(state, action.action)

    is Action.VoicePromptAdmitted -> {
        if (state.session?.id != action.sessionID) {
            Reduced(state)
        } else {
            // Spoken into the session, it reads what's above it, as a send does.
            // Reuse the outbox's delivery/echo guards: an early SSE delivery stays delivered, and a
            // late admission never duplicates an existing row.
            val restored = restoreOutbox(state.noteOwnSend())
            Reduced(
                restored.state,
                Effect.merge(
                    restored.effect,
                    fetchQueuedInputs(restored.state.server, action.sessionID),
                    Effect.send(Action.RefreshRequested(RefreshReason.Invalidation)),
                    Effect.run { _ -> PushSync.sentFromPhone(restored.state.server, action.sessionID) },
                ),
            )
        }
    }

    is Action.ModelApplied -> Reduced(state.modelApplied(action.model, action.sessionID))

    Action.VoiceExitFinished ->
        Reduced(if (state.voice?.phase == VoiceFeature.State.Phase.Ended) state.copy(voice = null) else state)

    is Action.Binding -> when (action.action) {
        is BindingAction.Draft -> draftEdited(state)
        else -> Reduced(state)
    }

    is Action.Autocomplete -> {
        val edit = action.action
        if (edit is ComposerAutocompleteAction.TextEdited) {
            // The binding's edit and the caret it left, in one pass.
            val edited = draftEdited(if (state.draft != edit.text) state.withDraft(edit.text) else state)
            val caret = edit.caret
            if (caret is ComposerAutocompleteAction.CaretUpdate.Moved) {
                val moved = reduceAutocomplete(ComposerAutocompleteAction.CaretChanged(caret.offset), edited.state)
                Reduced(moved.state, Effect.merge(edited.effect, moved.effect))
            } else {
                edited
            }
        } else {
            reduceAutocomplete(edit, state)
        }
    }

    // Folders are browsed only while the Project sheet is open. Cleared once it has gone, so its
    // stack never changes while it slides away.
    Action.ProjectPickerDismissed ->
        Reduced(if (state.activePicker != State.ActivePicker.project) state.copy(folderBrowser = null) else state)

    is Action.FolderBrowser -> {
        val delegate = ((action.action as? PresentationAction.Presented)?.action as? FolderBrowserFeature.Action.Delegate)?.delegate
        if (delegate is FolderBrowserFeature.Delegate.Selected) {
            Reduced(state, Effect.send(Action.ProjectPicked(OpenCodeProject.folder(delegate.directory, state.projects))))
        } else {
            Reduced(state)
        }
    }

    Action.ComposerSwipedUp -> {
        if (state.hasLiveDictation || state.dictationHold != null) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            Reduced(state.copy(composerFocusRequest = state.composerFocusRequest + 1))
        }
    }

    // Everything else goes on down the chain.
    else -> null
}

/** The call's presented actions that this screen answers (its delegate, and Try Again). */
private fun SessionFeature.reduceVoice(state: State, action: PresentationAction<VoiceFeature.Action>): Reduced<State, Action> {
    val presented = (action as? PresentationAction.Presented)?.action ?: return Reduced(state)
    if (presented == VoiceFeature.Action.Start) {
        // Try Again on a failed call: like starting one, it takes the mic, and the words heard so far wait in the draft.
        val yielded = yieldDictation(state)
        return Reduced(yielded.state, Effect.merge(Effect.cancel(SessionFeature.CancelID.VoiceExit), yielded.effect))
    }
    val delegate = (presented as? VoiceFeature.Action.Delegate)?.action ?: return Reduced(state)
    return when (delegate) {
        // The call's first request on this draft created its session.
        is VoiceFeature.DelegateAction.SessionCreated ->
            if (state.session != null) Reduced(state) else Reduced(state, Effect.send(Action.SessionCreated(delegate.session)))

        is VoiceFeature.DelegateAction.PromptAdmitted ->
            if (state.session?.id != delegate.sessionID) Reduced(state)
            else Reduced(state, Effect.send(Action.VoicePromptAdmitted(delegate.sessionID)))

        // The delegator moved the screen; Home switches sessions, and the call follows.
        is VoiceFeature.DelegateAction.OpenSession ->
            Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.OpenSession(delegate.session, toolID = delegate.toolID))))

        // App arbitrates it with the MCP request (`VoiceOpenSessionFeature`) before any lookup.
        is VoiceFeature.DelegateAction.OpenSessionRequested -> Reduced(state)

        is VoiceFeature.DelegateAction.SetPinned ->
            Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.SetPinned(delegate.session, delegate.pinned))))

        is VoiceFeature.DelegateAction.OpenDraft ->
            Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.OpenDraft(delegate.directory))))

        // The delegator already saved it on the server (a draft keeps it for its first send); only
        // the model button follows, and a pick waiting for the next send gives way to it.
        is VoiceFeature.DelegateAction.ModelSwitched -> {
            val sessionID = delegate.sessionID
            if (sessionID != state.session?.id) {
                // A family member off screen: its pick gives way too.
                val index = if (sessionID == null) -1 else state.familySessions.indexOfFirst { it.id == sessionID }
                if (sessionID != null && index >= 0) {
                    Reduced(
                        state.copy(
                            familySessions = state.familySessions.toMutableList().also { it[index] = it[index].copy(model = delegate.model) },
                            familyPendingModels = state.familyPendingModels - sessionID,
                        ),
                    )
                } else {
                    Reduced(state)
                }
            } else {
                var next = state.copy(currentModel = delegate.model, pendingModel = null)
                if (sessionID != null) next = next.modelApplied(delegate.model, sessionID)
                Reduced(next)
            }
        }

        // A delegation applied the pick before its prompt, as a typed send would; one picked since still waits.
        is VoiceFeature.DelegateAction.PendingModelApplied -> Reduced(state.modelApplied(delegate.model, delegate.sessionID))

        // A session the call created couldn't take the draft's model: it waits here, shown on the
        // model button, for the next send.
        is VoiceFeature.DelegateAction.PickWaiting ->
            if (state.session?.id != delegate.sessionID) Reduced(state)
            else Reduced(state.copy(currentModel = delegate.model, pendingModel = delegate.model))

        // Home answers Open Voice Settings and a delegator's new session; support changes are the call's own.
        is VoiceFeature.DelegateAction.OpenVoiceSettings, is VoiceFeature.DelegateAction.ServerVoiceSupportChanged,
        is VoiceFeature.DelegateAction.RequestSettled, is VoiceFeature.DelegateAction.DelegatorSessionCreated -> Reduced(state)

        // A call hung up from its bar stays while the scope sweeps out, then the composer returns;
        // a dismissed failure was never showing the bar.
        VoiceFeature.DelegateAction.Ended -> {
            if (state.voice?.phase != VoiceFeature.State.Phase.Ended) {
                Reduced(state.copy(voice = null))
            } else {
                val clock = clock
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        clock.sleep(DotScopeSimulation.exitDuration.seconds)
                        send(Action.VoiceExitFinished)
                    }.cancellable(SessionFeature.CancelID.VoiceExit, cancelInFlight = true),
                )
            }
        }
    }
}

/**
 * Dictation must let go of the audio session before a call configures it. While it's recording,
 * finalizing or holding the session, the call starts only once it has (`callAudioYielded`);
 * otherwise at once (null).
 */
private fun SessionFeature.callAfterDictation(state: State, atEar: Boolean): Reduced<State, Action>? {
    if (state.dictationPhase == DictationFeature.Phase.Idle && !dictation.holdsAudio()) return null
    if (!(state.callsFromRoot || state.session != null || state.draftProject != null)) return null
    val yielded = yieldDictation(state)
    return Reduced(yielded.state, Effect.concatenate(yielded.effect, Effect.send(Action.CallAudioYielded(atEar))))
}

/** Starts a call on this screen's session or draft, or asks the root to. */
private fun SessionFeature.startCall(state: State): Reduced<State, Action> {
    if (state.callsFromRoot) {
        // The call takes the mic, as below; the root owns the call.
        val yielded = yieldDictation(state)
        return Reduced(yielded.state, Effect.merge(yielded.effect, Effect.send(Action.Delegate(SessionFeature.Delegate.CallRequested))))
    }
    val own = startOwnCall(state)
    return Reduced(own.state, Effect.merge(warmPhoneConnection(state), own.effect))
}

private fun SessionFeature.warmPhoneConnection(state: State): Effect<Action> {
    if (!state.voiceRoute.warmsPhoneConnection) return Effect.none()
    val key = state.openAIAPIKey.value?.trim()?.takeIf { it.isNotEmpty() } ?: return Effect.none()
    val warmer = liveSocketWarmer
    return Effect.run { warmer.warm(key) }
}

private fun SessionFeature.startOwnCall(state: State): Reduced<State, Action> {
    val session = state.session
    if (session != null) {
        // The call takes the mic: like any interruption, the words heard so far wait in the draft.
        val yielded = yieldDictation(state)
        val stopped = yielded.state
        val active = if (stopped.isRunning) stopped.activeFamilySessionIDs + session.id else stopped.activeFamilySessionIDs
        val voice = VoiceFeature.State(stopped.server, session).let { it.copy(
            replyWatch = it.replyWatch.copy(activeSessionIDs = active),
            viewing = stopped.browser.viewing,
        ) }
        return Reduced(stopped.copy(voice = voice).syncVoiceTarget(), yielded.effect)
    }
    // On a draft the call starts at once and an empty session is created in the draft's project for
    // it; the call moves onto it when it lands. Should that fail, the call's first request creates it.
    val project = state.draftProject ?: return Reduced(state.openProjectPicker())
    val yielded = yieldDictation(state)
    var next = yielded.state
    val voice = VoiceFeature.State(next.server, project.directory, next.currentModel).let { it.copy(
        replyWatch = it.replyWatch.copy(activeSessionIDs = next.activeFamilySessionIDs),
        viewing = next.browser.viewing,
    ) }
    next = next.copy(voice = voice)
    if (!next.canChooseProject) return Reduced(next, yielded.effect)
    val sessionID = serverID.sessionID()
    // A request the call makes before it lands creates the same session.
    next = next.copy(callSessionID = sessionID, voice = next.voice?.copy(draftSessionID = sessionID))
    val server = next.server
    val client = openCodeClient
    val create = Effect.run<Action> { send ->
        try {
            send(Action.CallSessionResponse(Result.success(client.createSession(server, project.directory, sessionID))))
        } catch (error: OpenCodeClientError) {
            send(Action.CallSessionResponse(Result.failure(error)))
        }
    }
    return Reduced(next, Effect.merge(yielded.effect, create))
}

// region Composer autocomplete

/** The slice of the session's state the autocomplete reducer reads. */
internal val State.autocompleteHost: ComposerAutocompleteHost
    get() = ComposerAutocompleteHost(
        draft = draft,
        server = server,
        effectiveDirectory = effectiveDirectory,
        isAnsweringRequest = isAnsweringRequest,
        sessionID = session?.id,
        projects = projects,
    )

/** The clients, cancel IDs and session actions every autocomplete reducer call takes. */
internal fun SessionFeature.autocompleteEnvironment(): ComposerAutocompleteEnvironment<Action> = ComposerAutocompleteEnvironment(
    client = openCodeClient,
    clock = clock,
    uuid = uuid,
    haptics = haptics,
    cancelCatalogID = SessionFeature.CancelID.AutocompleteCatalog,
    cancelCommandsID = SessionFeature.CancelID.AutocompleteCommands,
    cancelFilesID = SessionFeature.CancelID.AutocompleteFiles,
    cancelSessionsID = SessionFeature.CancelID.AutocompleteSessions,
    cancelAutoRunID = SessionFeature.CancelID.AutocompleteAutoRun,
    cancelCommandWaitID = SessionFeature.CancelID.AutocompleteCommandWait,
    embed = Action::Autocomplete,
    sendTapped = { Action.SendTapped },
    refreshRequestedOpen = { Action.RefreshRequested(RefreshReason.Open) },
    replyCompleted = { Action.ReplyCompleted(it) },
)

/**
 * Applies one autocomplete reduce to the session: its state, the draft it edited, and the
 * session-field half of `commandFinished`.
 */
internal fun applyAutocomplete(state: State, outcome: ComposerAutocompleteOutcome<Action>): Reduced<State, Action> {
    var next = state.copy(autocomplete = outcome.autocomplete)
    if (outcome.draft != next.draft) next = next.withDraft(outcome.draft)
    when (val command = outcome.command) {
        ComposerCommandOutcome.Succeeded -> if (next.session != null) next = next.copy(pendingModel = null)
        is ComposerCommandOutcome.Failed -> {
            next = next.copy(isAwaitingReply = false)
            (command.failure.draft as? DraftState)?.let { next = next.keepAsDraft(it) }
            next = next.withDictationError(command.failure.error.message)
        }
        null -> Unit
    }
    return Reduced(next, outcome.effect)
}

// endregion

/**
 * The draft changed in the field. A keyboard edit mid-dictation wins: stop transcribing rather than
 * letting the next volatile result clobber what the user typed. Return's newline alone is a send,
 * not a new answer.
 */
private fun SessionFeature.draftEdited(state: State): Reduced<State, Action> {
    var next = state
    if (!next.isOnlyReturn(next.draft)) next = next.applyBarEdit()
    next = next.clearEditedUndoDraft()
    val stop = stopDictation(next)
    val autocomplete = applyAutocomplete(
        stop.state,
        ComposerAutocompleteReducer.draftChanged(stop.state.autocomplete, stop.state.autocompleteHost, autocompleteEnvironment()),
    )
    return Reduced(autocomplete.state, Effect.merge(stop.effect, autocomplete.effect))
}

private fun SessionFeature.reduceAutocomplete(action: ComposerAutocompleteAction, state: State): Reduced<State, Action> =
    applyAutocomplete(state, ComposerAutocompleteReducer.reduce(action, state.autocomplete, state.autocompleteHost, autocompleteEnvironment()))
