package ai.opencode.feature.settings

import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.combine
import ai.opencode.core.store.ifLet
import ai.opencode.core.store.map
import ai.opencode.core.store.scope
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature
import ai.opencode.feature.settings.serverdetails.ServerDetailsFeature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.settings.whatsnew.canTryWhatsNew
import ai.opencode.feature.settings.whatsnew.whatsNewVoiceAvailable
import ai.opencode.core.sharing.shared
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.voice.VoiceCallLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName

/**
 * Home's connection, as Settings shows it. Home owns the stream and passes the status in
 * ([SettingsFeature.Action.HomeChanged]).
 */
enum class ConnectionStatus { reconnecting, connected, disconnected }

/**
 * One page on Settings' own stack. [SettingsFeature.State.path] is the stack above the root page.
 */
sealed interface SettingsPage {
    /** Connection card › the server page (status, address, version, Servers, Rename Server…). */
    data object Server : SettingsPage
    data object Appearance : SettingsPage
    data object Themes : SettingsPage
    data object Language : SettingsPage
    /** Settings › Sessions: the transcript and composer preferences. */
    data object Sessions : SettingsPage
    /** Settings › Models (experimental `hideModels`). */
    data object Models : SettingsPage
    /** Settings › Models › one provider (`ModelVisibilityProviderView`). */
    data class ModelProvider(val providerID: String) : SettingsPage
    data object Voice : SettingsPage
    /** Settings › Voice › Voice Provider; [opensAccount] pushes Your Account over it. */
    data class VoiceProvider(val opensAccount: Boolean = false) : SettingsPage
    /** Settings › Voice › Voice Provider › API Key. */
    data object VoiceAccount : SettingsPage
    /** Settings › Voice › Delegator. */
    data object VoiceDelegator : SettingsPage
    /** Settings › Voice › Voice (`CallVoicePicker`). */
    data object CallVoicePicker : SettingsPage
    /** Settings › Voice › Call History (`VoiceCallLogList`, `:feature:voice`). */
    data object CallHistory : SettingsPage
    /** Settings › Voice › Call History › one call (`VoiceCallReplayView`, `:feature:voice`). */
    data class CallReplay(val log: VoiceCallLog) : SettingsPage
    data object Notifications : SettingsPage
    data object WhatsNew : SettingsPage
    /** One entry's page in What's New. */
    data class WhatsNewDetail(val entryID: String) : SettingsPage
    /** What's New › Smaller Changes. */
    data object WhatsNewSmallerChanges : SettingsPage
    /** Settings › Developer › Server Details. */
    data object ServerDetails : SettingsPage
    /** Settings › Experimental; [focus] scrolls to and tints that experiment's row (What's New). */
    data class Experimental(val focus: String? = null) : SettingsPage
    /** Settings › Developer. */
    data object Developer : SettingsPage
    data object Playgrounds : SettingsPage
    /** Settings › Playgrounds › Motion. */
    data object MotionPlayground : SettingsPage
}

/**
 * Home presents it (`HomeFeature.State.settings`) over the drawer and session: an overlay with its
 * own stack, never a push over Home.
 *
 * Home keeps owning the connection and server management: it passes the server and its health
 * in through [Action.HomeChanged] and handles every [Action.Delegate] (switch, rename, edit,
 * pair, add, remove, retry, notifications, What's New's try actions, close).
 */
class SettingsFeature : Reducer<SettingsFeature.State, SettingsFeature.Action> {
    data class State(
        /** The server in use; null only before Home hands it over. */
        val server: OpenCodeServer? = null,
        /** Kept for callers that only know the name (pre-port Home). */
        val serverName: String = "",
        val connectionStatus: ConnectionStatus = ConnectionStatus.connected,
        /** The server a switch is connecting to; Settings stays open through it. */
        val switchingTo: OpenCodeServer? = null,
        val connectionErrorMessage: String? = null,
        val credentialsRejected: Boolean = false,
        /** This phone's notification permission (`:feature:system`); null when this build offers no notifications (the row is hidden). */
        val notificationPermission: NotificationPermission? = null,
        val isRequestingNotifications: Boolean = false,
        val notificationSetupError: String? = null,
        /** A push control the server didn't take. */
        val pushFailure: PushSettingsFeature.Failure? = null,
        /** A server choice on screen while the server takes it. */
        val pushPendingChoice: PushChoice? = null,
        /** What the push shows, fixed as it opens: Settings, or What's New from the drawer's row. */
        val root: Root = Root.settings,
        /** Pages pushed above the root, innermost last. */
        val path: List<SettingsPage> = emptyList(),
        val whatsNew: WhatsNewFeature.State = WhatsNewFeature.State(),
        val serverDetails: ServerDetailsFeature.State? = null,
        val modelVisibility: ModelVisibilityFeature.State? = null,
        val voice: VoiceSettingsFeature.State = VoiceSettingsFeature.State(),
        val onboardingPlayground: OnboardingPlaygroundFeature.State? = null,
    ) {
        enum class Root { settings, whatsNew }

        val displayName: String get() = server?.friendlyName ?: serverName

        companion object {
            /**
             * Opened from the drawer's Manage Servers…: Settings arrives showing the server page.
             */
            fun focusingServers(server: OpenCodeServer?) = State(server = server, path = listOf(SettingsPage.Server))

            /** What's New opened from the drawer's row, pushed the same way as Settings. */
            fun whatsNew(server: OpenCodeServer?) = State(server = server, root = Root.whatsNew)
        }
    }

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
        /** Servers: leave this server to pick or pair another (Home forwards it to `AppFeature`). */
        data object ServersTapped : Action

        /** Home's connection changed; Home sends it whenever these values change while Settings is up. */
        data class HomeChanged(
            val server: OpenCodeServer?,
            val connectionStatus: ConnectionStatus,
            val switchingTo: OpenCodeServer?,
            val connectionErrorMessage: String?,
            val credentialsRejected: Boolean,
        ) : Action

        /** Home's notification state changed. */
        data class NotificationsChanged(
            val permission: NotificationPermission?,
            val isRequesting: Boolean,
            val setupError: String?,
        ) : Action

        /** Push a page. */
        data class PagePushed(val page: SettingsPage) : Action
        /** Back from the top page; at the root it closes Settings (`SettingsNavigator.escape`). */
        data object BackTapped : Action
        /**
         * Settings asked for while What's New is the page up cross-fades to Settings' root, keeping
         * what was read.
         */
        data object ShowSettingsRoot : Action

        data class Settings(val action: HomeSettingsFeature.Action) : Action
        data class Push(val action: PushSettingsFeature.Action) : Action
        data class WhatsNew(val action: WhatsNewFeature.Action) : Action
        data class ServerDetails(val action: ServerDetailsFeature.Action) : Action
        data class ModelVisibility(val action: ModelVisibilityFeature.Action) : Action
        data class Voice(val action: VoiceSettingsFeature.Action) : Action
        data class OnboardingPlayground(val action: PresentationAction<OnboardingPlaygroundFeature.Action>) : Action

        data class Delegate(val action: SettingsDelegate) : Action
    }

    /** What Settings asks of Home. */
    sealed interface SettingsDelegate {
        /** Back at the root, or the swipe that closes Settings. */
        data object Close : SettingsDelegate
        data class SwitchServer(val server: OpenCodeServer) : SettingsDelegate
        data class RenameServer(val server: OpenCodeServer, val name: String) : SettingsDelegate
        data object RetryConnection : SettingsDelegate
        data class EditServer(val server: OpenCodeServer) : SettingsDelegate
        data object PairServer : SettingsDelegate
        data object AddServer : SettingsDelegate
        data class RemoveServer(val server: OpenCodeServer) : SettingsDelegate
        data object EnableNotificationsTapped : SettingsDelegate
        data object NotificationSettingsRequested : SettingsDelegate
        /** What's New's action that leaves Settings for a session (`HomeFeature.tryWhatsNew`). */
        data class TryWhatsNew(val destination: WhatsNewEntry.Destination, val entryID: String) : SettingsDelegate
    }

    private object OnboardingSlot

    private val core = Reducer<State, Action> { state, action -> reduceCore(state, action) }

    private val body: Reducer<State, Action> = combine(
        scope(
            state = { HomeSettingsFeature.State(server = it.server) },
            setState = { s, _ -> s },
            action = { (it as? Action.Settings)?.action },
            embed = { Action.Settings(it) },
            child = HomeSettingsFeature(),
        ),
        scope(
            state = { PushSettingsFeature.State(server = it.server, failure = it.pushFailure, pendingChoice = it.pushPendingChoice) },
            setState = { s, c -> s.copy(pushFailure = c.failure, pushPendingChoice = c.pendingChoice) },
            action = { (it as? Action.Push)?.action },
            embed = { Action.Push(it) },
            child = PushSettingsFeature(),
        ),
        scope(
            state = { it.whatsNew },
            setState = { s, c -> s.copy(whatsNew = c) },
            action = { (it as? Action.WhatsNew)?.action },
            embed = { Action.WhatsNew(it) },
            child = WhatsNewFeature(),
        ),
        scope(
            state = { it.voice },
            setState = { s, c -> s.copy(voice = c) },
            action = { (it as? Action.Voice)?.action },
            embed = { Action.Voice(it) },
            child = VoiceSettingsFeature(),
        ),
        ifLet(
            parent = ifLet(
                parent = ifLet(
                    id = OnboardingSlot,
                    parent = core,
                    state = { it.onboardingPlayground },
                    setState = { s, c -> s.copy(onboardingPlayground = c) },
                    action = { (it as? Action.OnboardingPlayground)?.action },
                    embed = { Action.OnboardingPlayground(it) },
                    child = OnboardingPlaygroundFeature(),
                ),
                state = { it.serverDetails },
                setState = { s, c -> s.copy(serverDetails = c) },
                action = { (it as? Action.ServerDetails)?.action },
                embed = { Action.ServerDetails(it) },
                child = ServerDetailsFeature(),
                childID = { it.server.persistenceID },
            ),
            state = { it.modelVisibility },
            setState = { s, c -> s.copy(modelVisibility = c) },
            action = { (it as? Action.ModelVisibility)?.action },
            embed = { Action.ModelVisibility(it) },
            child = ModelVisibilityFeature(),
            childID = { it.server.persistenceID },
        ),
    )

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)

    private fun reduceCore(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            // Until Home hands the server over (HomeChanged), Settings shows the last connected one.
            val server = state.server ?: shared(ServerSharedKeys.savedServer).value
            Reduced(state.copy(server = server, voice = state.voice.copy(server = server)))
        }
        Action.ServersTapped -> Reduced(state)
        is Action.HomeChanged -> {
            val serverChanged = state.server?.persistenceID != action.server?.persistenceID
            var next = state.copy(
                server = action.server,
                connectionStatus = action.connectionStatus,
                switchingTo = action.switchingTo,
                connectionErrorMessage = action.connectionErrorMessage,
                credentialsRejected = action.credentialsRejected,
                voice = state.voice.copy(server = action.server),
            )
            // Server Details is keyed by the server: a switch opens a fresh page.
            if (serverChanged && next.serverDetails != null) {
                next = next.copy(serverDetails = action.server?.let { ServerDetailsFeature.State(it) })
            }
            if (serverChanged && next.modelVisibility != null) {
                next = next.copy(modelVisibility = action.server?.let { ModelVisibilityFeature.State(it) })
            }
            Reduced(
                next,
                if (serverChanged) {
                    Effect.merge(
                        leaveServerDetails(state.serverDetails),
                        if (next.modelVisibility != null) Effect.send(Action.ModelVisibility(ModelVisibilityFeature.Action.Task)) else Effect.none(),
                    )
                } else {
                    Effect.none()
                },
            )
        }
        is Action.NotificationsChanged -> Reduced(
            state.copy(
                notificationPermission = action.permission,
                isRequestingNotifications = action.isRequesting,
                notificationSetupError = action.setupError,
            ),
        )
        is Action.PagePushed -> push(state, action.page)
        Action.BackTapped ->
            if (state.path.isEmpty()) close(state) else setPath(state, state.path.dropLast(1))
        Action.ShowSettingsRoot -> Reduced(
            state.copy(root = State.Root.settings, path = emptyList(), whatsNew = state.whatsNew.copy(isPresented = false)),
        )
        is Action.Settings -> when (action.action) {
            HomeSettingsFeature.Action.OnboardingPlaygroundOpened ->
                Reduced(state.copy(onboardingPlayground = OnboardingPlaygroundFeature.State()))
            else -> Reduced(state)
        }
        is Action.Push -> Reduced(state)
        is Action.WhatsNew -> whatsNew(state, action.action)
        is Action.ServerDetails, is Action.ModelVisibility, is Action.Voice -> Reduced(state)
        is Action.OnboardingPlayground -> Reduced(state)
        is Action.Delegate -> when (action.action) {
            else -> Reduced(state)
        }
    }

    private fun close(state: State): Reduced<State, Action> =
        markWhatsNewRead(state, Effect.send(Action.Delegate(SettingsDelegate.Close)))

    /**
     * Settings closing marks what was read in What's New as seen. Reduced inline, before the close
     * delegate, because Home drops this state when it handles the delegate.
     */
    private fun markWhatsNewRead(state: State, then: Effect<Action>): Reduced<State, Action> {
        val closed = WhatsNewFeature().reduce(state.whatsNew, WhatsNewFeature.Action.Closed)
        return Reduced(state.copy(whatsNew = closed.state), Effect.merge(closed.effect.map { Action.WhatsNew(it) }, then))
    }

    private fun push(state: State, page: SettingsPage): Reduced<State, Action> {
        var next = state.copy(path = state.path + page)
        val server = state.server
        when (page) {
            SettingsPage.ServerDetails ->
                if (server != null && next.serverDetails?.server?.persistenceID != server.persistenceID) {
                    next = next.copy(serverDetails = ServerDetailsFeature.State(server))
                }
            SettingsPage.Models ->
                if (server != null && next.modelVisibility?.server?.persistenceID != server.persistenceID) {
                    next = next.copy(modelVisibility = ModelVisibilityFeature.State(server))
                }
            else -> Unit
        }
        return Reduced(next)
    }

    /** Leaving a page drops what only it owned, which cancels its reads. */
    private fun setPath(state: State, path: List<SettingsPage>): Reduced<State, Action> {
        var next = state.copy(path = path)
        if (SettingsPage.ServerDetails !in path) next = next.copy(serverDetails = null)
        if (path.none { it == SettingsPage.Models || it is SettingsPage.ModelProvider }) next = next.copy(modelVisibility = null)
        var effect: Effect<Action> = Effect.none()
        if (SettingsPage.CallVoicePicker in state.path && SettingsPage.CallVoicePicker !in path) {
            effect = Effect.send(Action.Voice(VoiceSettingsFeature.Action.VoicePickerDisappeared))
        }
        if (next.serverDetails == null) effect = Effect.merge(effect, leaveServerDetails(state.serverDetails))
        return Reduced(next, effect)
    }

    /**
     * `ifLet` cancels the removed child's reads and polling. Cancel its server-side sign-in from
     * the parent so that cleanup survives the child's removal.
     */
    private fun leaveServerDetails(old: ServerDetailsFeature.State?): Effect<Action> {
        old ?: return Effect.none()
        return ServerDetailsFeature().cancelSignIn(old).map { Action.ServerDetails(it) }
    }

    private fun whatsNew(state: State, action: WhatsNewFeature.Action): Reduced<State, Action> = when (action) {
        is WhatsNewFeature.Action.TryTapped -> when (val destination = action.destination) {
            // A Settings page pushes over the entry's page; the rest leave through Home.
            is WhatsNewEntry.Destination.Open -> push(state, destination.page.settingsPage())
            // A call before Voice is set up keeps the page and pushes Voice's settings over it.
            WhatsNewEntry.Destination.Call ->
                if (whatsNewVoiceAvailable(state.server)) {
                    Reduced(state, Effect.send(Action.Delegate(SettingsDelegate.TryWhatsNew(destination, action.entryID))))
                } else {
                    push(state, SettingsPage.Voice)
                }
            // A new session needs the server: offline the page stays.
            else -> if (!state.canTryWhatsNew(destination)) {
                Reduced(state)
            } else {
                Reduced(state, Effect.send(Action.Delegate(SettingsDelegate.TryWhatsNew(destination, action.entryID))))
            }
        }
        else -> Reduced(state)
    }
}

/** The Settings page a What's New action opens. */
fun WhatsNewEntry.SettingsDestination.settingsPage(): SettingsPage = when (this) {
    WhatsNewEntry.SettingsDestination.Themes -> SettingsPage.Themes
    WhatsNewEntry.SettingsDestination.Sessions -> SettingsPage.Sessions
    WhatsNewEntry.SettingsDestination.Voice -> SettingsPage.Voice
    WhatsNewEntry.SettingsDestination.Notifications -> SettingsPage.Notifications
    WhatsNewEntry.SettingsDestination.Servers -> SettingsPage.Server
    WhatsNewEntry.SettingsDestination.Experimental -> SettingsPage.Experimental()
    is WhatsNewEntry.SettingsDestination.Experiment -> SettingsPage.Experimental(focus = id)
    WhatsNewEntry.SettingsDestination.Developer -> SettingsPage.Developer
}
