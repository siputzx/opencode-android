package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.FamilyDrafts
import ai.opencode.feature.composer.PersistedComposerWatch
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.RestoredComposerDraft
import ai.opencode.feature.composer.RestoredOutboxEntry
import ai.opencode.feature.composer.persistedDraftDirectory
import ai.opencode.feature.composer.reconcilePersistedComposer
import ai.opencode.feature.composer.restorePersistedComposer
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.PersistedSessionWatch
import ai.opencode.feature.composer.PersistedSessionState
import ai.opencode.feature.composer.reconcilePersistedSession
import ai.opencode.feature.composer.restorePersistedAnswers
import ai.opencode.feature.composer.RequestDockFeature

// The client, records and the pure logic are `:feature:composer`'s (`ComposerPersistence`,
// `ComposerPersistenceIntegration.kt`); these members read and write `SessionFeature.State`, so
// they live here.

/** The session's draft and where it belongs. */
val State.persistedComposerWatch: PersistedComposerWatch
    get() = PersistedComposerWatch.of(
        serverID = server.persistenceID,
        session = session,
        readsOnView = readsOnView,
        draftProject = draftProject,
        composer = sessionComposer,
    )

/** Saves the composer as it changes. */
internal fun SessionFeature.reconcilePersistedComposer(old: PersistedComposerWatch, state: State): Reduced<State, Action> {
    if (!state.readsOnView) return Reduced(state)
    composerPersistence.reconcilePersistedComposer(old, state.persistedComposerWatch, state.sessionComposer)
    return Reduced(state)
}

/** The screen's waiting model pick and request answers. */
val State.persistedSessionWatch: PersistedSessionWatch
    get() {
        val knowsRequests = requestDock.permissionRequests.isNotEmpty() || requestDock.formRequests.isNotEmpty()
        return PersistedSessionWatch(
            serverID = server.persistenceID,
            sessionID = session?.id,
            readsOnView = readsOnView,
            update = PersistedSessionState.Update(
                pendingModel = pendingModel,
                requests = if (knowsRequests) {
                    PersistedSessionState.RequestAnswers(
                        formAnswers = requestDock.formAnswers,
                        answerDrafts = answerDraftsIncludingBar,
                        questionPageIndices = requestDock.questionPageIndices,
                    )
                } else {
                    null
                },
            ),
        )
    }

/** The same screen's pick or answers changed. */
internal fun SessionFeature.reconcilePersistedSession(old: PersistedSessionWatch, state: State): Reduced<State, Action> {
    composerPersistence.reconcilePersistedSession(old, state.persistedSessionWatch)
    return Reduced(state)
}

/** Requests that just arrived take back the answers saved before a relaunch. */
internal fun SessionFeature.restoringPersistedAnswers(
    dock: RequestDockFeature.State<DraftState>,
    state: State,
): RequestDockFeature.State<DraftState> =
    composerPersistence.restorePersistedAnswers(dock, state.server.persistenceID, state.session, state.readsOnView)

/** Where a retried draft send creates its session. */
internal fun SessionFeature.persistedDraftDirectory(messageID: String, isRetry: Boolean, state: State): String? =
    persistedDraftDirectory(messageID, isRetry, state.server.persistenceID, promptOutbox, composerPersistence)

/**
 * Binds the outbox and failed sends to persistence, restores the screen's saved draft, and seeds
 * sends a previous process left unsent into the outbox.
 */
internal fun SessionFeature.restorePersistedComposer(state: State): State {
    val restore = restorePersistedComposer(
        serverID = state.server.persistenceID,
        readsOnView = state.readsOnView,
        sessionComposer = state.sessionComposer,
        session = state.session,
        draftProject = state.draftProject,
        projects = state.projects,
        outboxDraftID = state.outboxDraftID,
        existingMessageIDs = SessionFeature.promptIDs(state.items) - state.optimisticPromptIDs,
        persistence = composerPersistence,
        outbox = promptOutbox,
        undelivered = undeliveredPrompts,
    ) { entry, sessionID, directory -> restoredDeliveryJob(entry, sessionID, directory, state) }
    var next = state
    restore.restoredDraft?.let { next = next.applyPersistedDraft(it) }
    next = next.copy(draftProject = restore.draftProject, outboxDraftID = restore.outboxDraftID)
    val became = restore.draftBecameSession
    if (became != null) {
        next = next.copy(
            draftBecameSessionID = became.id,
            session = became,
            familySessions = listOf(became),
            familyRootID = became.id,
            outboxDraftID = null,
            pendingModel = next.currentModel,
        )
    }
    return next
}

/** The entry's session, or a new one in its directory, reconciling first. */
private fun SessionFeature.restoredDeliveryJob(
    @Suppress("UNUSED_PARAMETER") entry: RestoredOutboxEntry,
    sessionID: String?,
    newSessionDirectory: String,
    state: State,
): PromptOutbox.Job {
    val target = if (sessionID != null) SessionFeature.SendTarget.Existing(sessionID) else SessionFeature.SendTarget.NewSession(newSessionDirectory)
    return SessionFeature.deliveryJob(
        target = target,
        server = state.server,
        reconcileFirst = true,
        openCodeClient = openCodeClient,
        clock = clock,
        networkPath = networkPath,
        undeliveredPrompts = undeliveredPrompts,
        pageReader = pageReader,
    )
}

/** Saves the composer now and writes it at once (backgrounding). */
internal fun SessionFeature.syncAndFlushPersistedComposer(state: State): Effect<Action> {
    if (state.readsOnView) {
        val current = state.persistedComposerWatch
        val composer = state.sessionComposer
        if (!composer.isEmpty) composerPersistence.scheduleDraft(current.serverID, current.sessionID, current.project, composer)
    }
    val persistence = composerPersistence
    return Effect.run { persistence.flush() }
}

/**
 * A family member's draft, in composer persistence while the screen reads on view, else in
 * [State.catchUpFamilyDrafts] (a Catch Up card). Write back with [withFamilyDrafts].
 */
val State.familyDrafts: FamilyDrafts
    get() = FamilyDrafts(server.persistenceID, readsOnView, catchUpFamilyDrafts, dependency(ComposerPersistence))

fun State.withFamilyDrafts(value: FamilyDrafts): State = copy(catchUpFamilyDrafts = value.catchUpDrafts)

fun State.settingFamilyDraft(sessionID: String, value: DraftState?): State {
    val drafts = familyDrafts
    drafts[sessionID] = value
    return withFamilyDrafts(drafts)
}

/** The saved draft, and a draft's saved project. */
fun State.applyPersistedDraft(restored: RestoredComposerDraft): State {
    var next = restoreDraft(restored.draft)
    val project = restored.project
    if (next.session == null && project != null) {
        next = next.copy(draftProject = next.projects.firstOrNull { it.directory == project.directory } ?: project)
    }
    return next
}
