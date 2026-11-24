package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Each `TestStore` has its own shared store. */
class SessionSheetTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private fun session(id: String = "ses_sheet", title: String = "Sheet") =
        OpenCodeSession(id = id, serverTitle = title, projectID = "prj", directory = "/work/app", updatedAt = Instant.ofEpochSecond(100))

    private fun delegate(d: SessionFeature.Delegate) = HomeFeature.Action.detail(SessionFeature.Action.Delegate(d))

    @Test
    fun homeOpensForksAndRoutesSheetActionsThroughTheDrawerPaths() = runTest {
        val open = session()
        val forked = session("ses_fork", title = "Sheet (fork)")
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open), activeSessionIDs = emptySet()))
        state = state.copy(sessionDetail = SessionFeature.State.session(server = server, session = open, cachedItems = emptyList()))
        state = state.markingReadLocally(open)
        state = state.copy(transcriptCache = state.transcriptCache + (forked.id to emptyList()))
        val deleted = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                viewSession = { _, _, _ -> },
                deleteSession = { _, id -> deleted += id },
            )
        }
        store.exhaustive = false

        store.send(delegate(SessionFeature.Delegate.TogglePin(open.id)))
        store.receive({ it is HomeFeature.Action.TogglePin }, "togglePin")
        assertTrue(store.state.isPinned(open.id))
        store.send(delegate(SessionFeature.Delegate.ToggleRead(open.id)))
        store.receive({ it is HomeFeature.Action.MarkUnread }, "markUnread")
        assertTrue(store.state.isUnread(open))

        store.send(delegate(SessionFeature.Delegate.OpenSession(forked)))
        store.receive({ it is HomeFeature.Action.SessionTapped }, "sessionTapped")
        assertEquals(listOf(forked.id, open.id), store.state.sessions.map { it.id })
        assertEquals(forked.id, store.state.sessionDetail?.session?.id)

        store.send(delegate(SessionFeature.Delegate.DeleteSession(forked.id)))
        store.receive({ it == HomeFeature.Action.DeleteSessionConfirmed }, "deleteSessionConfirmed")
        store.receive({ it is HomeFeature.Action.DeleteSessionResponse }, "deleteSessionResponse")
        assertEquals(listOf(forked.id), deleted.toList())
        assertEquals(listOf(open.id), store.state.sessions.map { it.id })
        assertEquals(true, store.state.sessionDetail?.isDraft)
    }
}
