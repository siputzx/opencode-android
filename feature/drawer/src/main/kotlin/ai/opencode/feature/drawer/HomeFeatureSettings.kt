package ai.opencode.feature.drawer

import ai.opencode.core.log.CallTrigger
import ai.opencode.feature.session.*
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.map
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.DelegateAction
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.sessionComposer
import ai.opencode.feature.session.typeBelow
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.feature.settings.SettingsFeature.SettingsDelegate
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.voice.VoiceRoutePreferences

/*
 * And the Settings half of `HomeFeature` and `SettingsPresentation`: Home presents
 * `:feature:settings`' [SettingsFeature] ([State.settings]), keeps it told about the connection and
 * notifications, and answers what it asks for ([SettingsDelegate]) through Home's own actions and
 * delegates. What's New's Try It leaves the page and the drawer for the session, then opens where
 * the feature is.
 */

/**
 * Settings opened with Home's current connection and notification state already in it, and Home's
 * sessions for Voice's Delegator pick, so that page doesn't wait on its own load.
 */
internal fun State.presentingSettings(settings: SettingsFeature.State): State {
    val home = settingsHome
    val notifications = settingsNotifications
    return copy(
        settings = settings.copy(
            voice = settings.voice.copy(server = home.server ?: settings.voice.server, sessions = sessions),
            server = home.server,
            connectionStatus = home.connectionStatus,
            switchingTo = home.switchingTo,
            connectionErrorMessage = home.connectionErrorMessage,
            credentialsRejected = home.credentialsRejected,
            notificationPermission = notifications.permission,
            isRequestingNotifications = notifications.isRequesting,
            notificationSetupError = notifications.setupError,
        ),
    )
}

private val State.settingsHome: SettingsFeature.Action.HomeChanged
    get() = SettingsFeature.Action.HomeChanged(
        server = server,
        connectionStatus = when (connectionStatus) {
            HomeConnectionFeature.State.ConnectionStatus.reconnecting -> ConnectionStatus.reconnecting
            HomeConnectionFeature.State.ConnectionStatus.connected -> ConnectionStatus.connected
            HomeConnectionFeature.State.ConnectionStatus.disconnected -> ConnectionStatus.disconnected
        },
        switchingTo = switchingTo,
        connectionErrorMessage = connectionErrorMessage,
        credentialsRejected = credentialsRejected,
    )

/** What Settings shows of Home's connection while it's up (null while it isn't). */
internal val State.settingsHomeInput: SettingsFeature.Action.HomeChanged?
    get() = if (settings == null) null else settingsHome

private val State.settingsNotifications: SettingsFeature.Action.NotificationsChanged
    get() = SettingsFeature.Action.NotificationsChanged(
        permission = notificationPermission,
        isRequesting = isRequestingNotifications,
        setupError = notificationSetupError,
    )

/** What Settings shows of Home's notification state while it's up (null while it isn't). */
internal val State.settingsNotificationsInput: SettingsFeature.Action.NotificationsChanged?
    get() = if (settings == null) null else settingsNotifications

/** The drawer's What's New row, read again from What's New's shared keys. */
internal fun State.refreshingWhatsNewButton(): State {
    val shows = WhatsNewFeature.State().showsButton
    return if (shows == showsWhatsNewButton) this else copy(showsWhatsNewButton = shows)
}

/**
 * Settings closed, by whatever closed it: what was read in What's New is seen now, so its dots stay
 * while you read and the drawer's row leaves on the way back.
 */
internal fun settingsClosed(whatsNew: WhatsNewFeature.State, state: State): State {
    WhatsNewFeature().reduce(whatsNew, WhatsNewFeature.Action.Closed)
    return state.refreshingWhatsNewButton()
}

/** The drawer's What's New row. */
internal fun HomeFeature.reduceWhatsNew(state: State, action: WhatsNewFeature.Action): HomeReduced = when (action) {
    // Pushed over the open drawer, like Settings.
    WhatsNewFeature.Action.ButtonTapped ->
        if (state.settings != null) {
            Reduced(state)
        } else {
            val whatsNew = SettingsFeature.State.whatsNew(state.server)
            Reduced(state.copy(settingsVoicePage = null).presentingSettings(whatsNew.copy(whatsNew = whatsNew.whatsNew.copy(isPresented = true))))
        }
    WhatsNewFeature.Action.HideTapped -> {
        WhatsNewFeature().reduce(WhatsNewFeature.State(), action)
        Reduced(state.refreshingWhatsNewButton())
    }
    else -> Reduced(state)
}

/**
 * Preferences. Home's sidebar mirrors `SharedKeys.sidebarShown`, so its toggle is handled here (the
 * mirror and the key move together); the rest go to `:feature:settings`' [HomeSettingsFeature].
 */
internal fun reducePreferences(state: State, action: HomeSettingsFeature.Action): HomeReduced = when (action) {
    HomeSettingsFeature.Action.SidebarToggled -> {
        val next = !state.isSidebarShown
        // A further window's sidebar is its own (`HomeFeature.State.isWindowLocal`).
        if (!state.isWindowLocal) shared(SharedKeys.sidebarShown).set(next)
        Reduced(state.copy(isSidebarShown = next))
    }
    else -> {
        val reduced = HomeSettingsFeature().reduce(HomeSettingsFeature.State(server = state.server), action)
        Reduced(state, reduced.effect.map { Action.Settings(it) })
    }
}

/** What the presented Settings asks of Home. Its own actions are reduced by [SettingsFeature] first. */
internal fun HomeFeature.reduceSettingsScreen(state: State, action: PresentationAction<SettingsFeature.Action>): HomeReduced {
    val presented = (action as? PresentationAction.Presented)?.action ?: return Reduced(state)
    return when (presented) {
        // Nothing sends it.
        SettingsFeature.Action.ServersTapped -> Reduced(state)
        // Settings › Experimental › Show Welcome: the first-run welcome, over Settings and Home.
        is SettingsFeature.Action.Settings ->
            when {
                presented.action == HomeSettingsFeature.Action.WelcomeOpened ->
                    Reduced(state.copy(welcome = WelcomeFeature.State(isPreview = true)))
                presented.action.switchesOffSessionLiveActivity -> stopSessionActivity(state)
                else -> Reduced(state)
            }
        is SettingsFeature.Action.Delegate -> when (val delegate = presented.action) {
            SettingsDelegate.Close -> Reduced(state.copy(settings = null))
            is SettingsDelegate.SwitchServer -> Reduced(state, Effect.send(Action.Delegate(DelegateAction.ServerSwitchTapped(delegate.server))))
            is SettingsDelegate.RenameServer ->
                Reduced(state, Effect.send(Action.Delegate(DelegateAction.ServerRenamed(delegate.server, delegate.name))))
            SettingsDelegate.RetryConnection -> Reduced(state, Effect.send(Action.RetryConnectionTapped))
            is SettingsDelegate.EditServer -> Reduced(state, Effect.send(Action.Delegate(DelegateAction.EditServerRequested(delegate.server))))
            SettingsDelegate.PairServer -> Reduced(state, Effect.send(Action.Delegate(DelegateAction.PairServerRequested)))
            SettingsDelegate.AddServer -> Reduced(state, Effect.send(Action.Delegate(DelegateAction.AddServerRequested)))
            is SettingsDelegate.RemoveServer ->
                Reduced(state, Effect.send(Action.Delegate(DelegateAction.ServerRemovalConfirmed(delegate.server))))
            SettingsDelegate.EnableNotificationsTapped -> Reduced(state, Effect.send(Action.EnableNotificationsTapped))
            SettingsDelegate.NotificationSettingsRequested -> Reduced(state, Effect.send(Action.NotificationSettingsRequested))
            SettingsDelegate.PushSyncRequested -> Reduced(state, Effect.send(Action.PushSyncRequested))
            is SettingsDelegate.TryWhatsNew -> tryWhatsNew(delegate.destination, delegate.entryID, state)
        }
        else -> Reduced(state)
    }
}

/**
 * What's New's Try It: leaves the page and the drawer for the session, then opens where the
 * feature is, through the paths the drawer, Catch Up and the call button use. It only navigates;
 * nothing is sent. What can't go now keeps the page: a new session while offline, and a call
 * before Voice is set up (its settings push on the page instead).
 */
@Suppress("UNUSED_PARAMETER")
internal fun HomeFeature.tryWhatsNew(destination: WhatsNewEntry.Destination, entryID: String, state: State): HomeReduced {
    when (destination) {
        // Settings pages push on What's New's own stack (WhatsNewFeature).
        is WhatsNewEntry.Destination.Open -> return Reduced(state)
        WhatsNewEntry.Destination.NewSession, is WhatsNewEntry.Destination.Prompt ->
            if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(state)
        // Before Voice is set up, Settings pushes Voice's page over the entry instead and never
        // asks (`SettingsFeature`); checked again here.
        WhatsNewEntry.Destination.Call -> if (!VoiceRoutePreferences().isAvailable(state.server)) return Reduced(state)
        WhatsNewEntry.Destination.ModelList -> Unit
    }
    var s = state.copy(settings = null, drawerCloseRequests = state.drawerCloseRequests + 1)
    return when (destination) {
        WhatsNewEntry.Destination.NewSession -> reduceDrawer(s, Action.NewSessionTapped)
        is WhatsNewEntry.Destination.Prompt -> {
            // There's one new-session draft. Words already in it stay, and the prompt goes below
            // them; New Session over a draft would clear it.
            val detail = s.sessionDetail
            if (s.catchUp == null && s.subagentStack.isEmpty() && detail != null && detail.isDraft && detail.readsOnView &&
                !detail.sessionComposer.isEmpty
            ) {
                return Reduced(s.updatingDetail { it.typeBelow(destination.text) })
            }
            val opened = reduceDrawer(s, Action.NewSessionTapped)
            Reduced(opened.state.updatingDetail { it.typeBelow(destination.text) }, opened.effect)
        }
        WhatsNewEntry.Destination.ModelList, WhatsNewEntry.Destination.Call -> {
            // The screen the reader comes back to: Catch Up's card opened as the session (or, with
            // no card left, where Catch Up returns), and no in-app page over it.
            val effects = mutableListOf<Effect<Action>>()
            if (s.catchUp != null) {
                val opened = reduceCatchUp(s, CatchUpFeature.Action.OpenTapped)
                // Onto the session itself: Try It shows the feature there, not the drawer.
                s = opened.state.returningFromCatchUp()
                effects += opened.effect
            }
            if (s.sessionDetail == null) s = s.openingDraft(reason = "What’s New’s Try It")
            val top = s.subagentStack.lastOrNull()
            if (top?.browser?.isOpen ?: (s.sessionDetail?.browser?.isOpen == true)) {
                val close = SessionFeature.Action.Browser(BrowserFeature.Action.CloseTapped)
                effects += Effect.send(if (top != null) Action.SubagentStack(top.navigationID, close) else Action.detail(close))
            }
            if (destination == WhatsNewEntry.Destination.Call) {
                // A pushed subagent's call belongs to the root, aimed at the top.
                val call = if (top == null) startSystemCall(s, trigger = CallTrigger.WhatsNew) else startCallOnTop(s)
                s = call.state
                effects += call.effect
            } else {
                val models = SessionFeature.Action.ModelButtonTapped
                effects += Effect.send(if (top != null) Action.SubagentStack(top.navigationID, models) else Action.detail(models))
            }
            Reduced(s, Effect.concatenate(effects))
        }
        is WhatsNewEntry.Destination.Open -> Reduced(s)
    }
}

internal val HomeSettingsFeature.Action.switchesOffSessionLiveActivity: Boolean
    get() {
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureChanged) {
            return feature == ExperimentalFeature.sessionLiveActivity && !on
        }
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureApplied) {
            return feature == ExperimentalFeature.sessionLiveActivity && !on
        }
        return false
    }
