package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.key
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.voice.DotScopeRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

// The screen's task, visibility and scene phase, and the settings it watches.

/**
 * The screen's visit starts its task, visibility and the scene's phase reach the reducer, and the
 * New Messages Divider and Since You Left settings tell an open session when they change.
 *
 * Android's lifecycle maps to scene phases: RESUMED is active, STARTED inactive, CREATED
 * (stopped) background.
 */
@Composable
internal fun SessionLifecycle(store: StoreOf<State, Action>) {
    val taskIdentity by store.observe { it.taskIdentity }
    // Keyed by visit and session: the drawer swaps the screen in place, a draft opened from another
    // draft starts a fresh task, and a draft becoming its own session continues the one it started.
    // Retained across activity recreation so a theme or font-scale change doesn't re-run `.task`
    // (which reloads the transcript as `.open` and discards pending dictation).
    val startedTaskIdentity = retain { arrayOfNulls<State.TaskIdentity>(1) }
    LaunchedEffect(taskIdentity) {
        if (startedTaskIdentity[0] == taskIdentity) return@LaunchedEffect
        startedTaskIdentity[0] = taskIdentity
        store.send(Action.Task)
    }
    // A held mic's bar shows its scope at once.
    LaunchedEffect(Unit) { DotScopeRenderer.prepare() }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentStore by rememberUpdatedState(store)
    // A configuration change (dark mode, font size, rotation) recreates the activity; the reader
    // never left, so it isn't a leave.
    val activity = androidx.activity.compose.LocalActivity.current
    val changingConfigurations = { activity?.isChangingConfigurations == true }
    DisposableEffect(lifecycleOwner, store) {
        val lifecycle = lifecycleOwner.lifecycle
        // Recreated by a configuration change, the screen comes back before its activity resumes:
        // that's not a leave either, and ON_RESUME follows.
        val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (resumed || !currentStore.state.isVisible) currentStore.send(Action.VisibilityChanged(resumed))
        var catchingUp = true
        val observer = LifecycleEventObserver { _, event ->
            // `addObserver` replays the events up to the current state: the appearance above covers them.
            if (catchingUp || changingConfigurations()) return@LifecycleEventObserver
            val active = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> false
                else -> return@LifecycleEventObserver
            }
            currentStore.send(Action.SceneActiveChanged(active))
            currentStore.send(Action.VisibilityChanged(active))
            if (event == Lifecycle.Event.ON_STOP) currentStore.send(Action.SceneEnteredBackground)
        }
        lifecycle.addObserver(observer)
        catchingUp = false
        onDispose {
            lifecycle.removeObserver(observer)
            if (currentStore.state.isVisible && !changingConfigurations()) currentStore.send(Action.VisibilityChanged(false))
        }
    }

    // Notifications learn which session is on screen, so its own don't show. Told again as the
    // scene, the cover or the session changes; told it left on disappear.
    val notifications = remember { dependency(SessionNotificationsClient) }
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val sceneActive = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val sessionID by store.observe { it.familyRootID ?: it.session?.id }
    val isVisible by store.observe { it.isVisible }
    val isCovered by store.observe { it.isCovered }
    val latestSessionID by rememberUpdatedState(sessionID)
    LaunchedEffect(notifications, sceneActive, isVisible, isCovered, sessionID) {
        notifications.setVisibleSession(sceneActive && isVisible && !isCovered, sessionID)
    }
    DisposableEffect(notifications) {
        onDispose { if (!changingConfigurations()) notifications.setVisibleSession(false, latestSessionID) }
    }
}
