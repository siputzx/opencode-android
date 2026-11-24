package ai.opencode.feature.transcript.list

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerPanSelectionHandles
import ai.opencode.feature.media.AgentImageViewport
import ai.opencode.feature.media.AgentImageVisibility
import ai.opencode.feature.media.LinkPreviewStore
import ai.opencode.feature.media.LocalAgentImageRow
import ai.opencode.feature.media.LocalAgentImageViewport
import ai.opencode.feature.media.LocalLinkPreviewRevision
import ai.opencode.feature.media.LocalLinkPreviewStore
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.TranscriptRowContext
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.links.LinkPreviewArrivals
import ai.opencode.feature.transcript.markdown.ui.LocalInlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.LocalLinkPreviewRow
import ai.opencode.feature.transcript.markdown.ui.LocalTranscriptTextReveal
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.SharedTextContextMenus
import ai.opencode.feature.transcript.markdown.ui.TranscriptTextReveal
import ai.opencode.feature.transcript.rows.DisclosureMotion
import ai.opencode.feature.transcript.rows.LocalRowHeightMotion
import ai.opencode.feature.transcript.rows.NewMessagesDivider
import ai.opencode.feature.transcript.rows.RowHeightMotion
import ai.opencode.feature.transcript.selection.LocalTranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptReplySelectionMenu
import ai.opencode.feature.transcript.selection.transcriptReplySelectionGestures
import ai.opencode.feature.transcript.selection.transcriptReplySelectionHandles
import ai.opencode.feature.transcript.user.TranscriptAgentMessageReveal
import ai.opencode.feature.transcript.user.TranscriptMessageRow
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.feature.transcript.user.UserMessageRun
import ai.opencode.feature.transcript.user.findActivity
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.magnifier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalFoundationApi::class)
private fun transcriptListState(index: Int, offset: Int) = LazyListState(
    // Prepare a viewport in the scroll direction rather than only the next row;
    // retain half a viewport behind so a short reversal can reuse measured Markdown.
    cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f),
    firstVisibleItemIndex = index,
    firstVisibleItemScrollOffset = offset,
)

/**
 * The list's state and, once restored, the reader's row by key with the offset within it: the
 * rows can differ after a process death (a shorter history window, a row more or less), so the
 * saved index alone would land on another row. The index stays the fallback.
 */
private class TranscriptListPosition(val state: LazyListState, anchor: Pair<String, Int>? = null) {
    var anchor by mutableStateOf(anchor)

    /**
     * Layouts left to settle a row put back: the composer's height reaches the list's padding a
     * frame late, so the first layout can clamp a row near the end, and it's asked for again.
     */
    var settleLayouts = 0
}

/**
 * Android: while a reply's text is selected, Back ends the selection before the
 * screen's own Back, as it dismisses any platform text selection and as the user bubble's
 * selection does (iOS has no Back; a tap elsewhere ends it there). Its own scope, so the
 * selection starting or ending doesn't recompose the list. Not while the keyboard is up: Back
 * puts the keys away first, then ends the selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplySelectionBackHandler(selection: TranscriptReplySelection) {
    if (!selection.isSelected || WindowInsets.isImeVisible) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        BackHandler { selection.clear(byReader = true) }
        return
    }
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, selection) {
        val callback = OnBackInvokedCallback { selection.clear(byReader = true) }
        // At the app's own priority, registered as the selection begins: what opens over it later
        // (the drawer, a menu, an in-window sheet) registers after it and takes Back first.
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/** Whether the list hides while a row restored after process death loads ([decide]d on the list's first composition). */
private class RestoreHide {
    var decide = true
    var hides by mutableStateOf(false)
}

/** Longest a restoring list stays invisible waiting for its row's page; then it shows where it is and still goes back to the row when it lands. */
private const val RESTORE_HIDE_LIMIT_MS = 2_500L

/** `LazyListState.Saver` for a state with the list's cache window (Compose keeps that saver internal), plus the bottom row's key. */
private val transcriptListPositionSaver = listSaver<TranscriptListPosition, Any>(
    save = { position ->
        val state = position.state
        // A row not reached yet (its history still loading) stays the one to go back to.
        position.anchor?.let { (key, offset) -> return@listSaver listOf(state.firstVisibleItemIndex, offset, key) }
        val key = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.key as? String
        listOfNotNull(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, key)
    },
    restore = { saved ->
        val index = saved[0] as Int
        val offset = saved[1] as Int
        val atEnd = index == 0 && offset == 0
        val anchor = if (atEnd) null else (saved.getOrNull(2) as? String)?.let { it to offset }
        TranscriptListPosition(transcriptListState(index, offset), anchor)
    },
)

private class NewDividerState {
    var decidedGeneration: Int? by mutableStateOf(null)

    var fitGeneration: Int? by mutableStateOf(null)

    var readGeneration: Int? by mutableStateOf(null)

    var passedGeneration: Int? = null

    var landing: Job? = null

    var glowStart: Double? by mutableStateOf(null)
    var glowGeneration: Int? = null
}

/**
 * Slots for rows whose views live above this module (iOS reads them directly from one module):
 * the Working line (`Session/SessionTranscriptView.swift` `WorkingLineRow` with its activity orb).
 * Null draws a plain fallback.
 */
class TranscriptSlots(
    val workingLine: (@Composable () -> Unit)? = null,
)

/** The transcript list: a reversed `LazyColumn` with the newest row at index 0. [onHidesChanged] reports it staying invisible while a row restored after process death loads. */
@Composable
fun TranscriptList(
    configuration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    jumpRequest: Int = 0,
    onShowsJumpButtonChanged: (Boolean) -> Unit = {},
    slots: TranscriptSlots = TranscriptSlots(),
    layoutTuning: TranscriptLayoutTuning = LocalTranscriptLayoutTuning.current,
    onScrolledUnderHeader: (Float) -> Unit = {},
    borrowedDrag: TranscriptBorrowedDrag? = null,
    onHidesChanged: (Boolean) -> Unit = {},
) {
    // A new session is a new list (iOS resets the controller; the draft becoming its session keeps it).
    key(configuration.sessionID ?: "draft", configuration.historyGeneration) {
        TranscriptListContent(configuration, send, modifier, contentPadding, jumpRequest, onShowsJumpButtonChanged, slots, layoutTuning, onScrolledUnderHeader, borrowedDrag, onHidesChanged)
    }
}

@Composable
private fun TranscriptListContent(
    rawConfiguration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    jumpRequest: Int,
    onShowsJumpButtonChanged: (Boolean) -> Unit,
    slots: TranscriptSlots,
    layoutTuning: TranscriptLayoutTuning,
    onScrolledUnderHeader: (Float) -> Unit,
    borrowedDrag: TranscriptBorrowedDrag?,
    onHidesChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val touchExploration = rememberTouchExploration()
    val configuration = rememberPresentedConfiguration(rawConfiguration, touchExploration)
    val currentSend by rememberUpdatedState(send)
    val currentConfiguration by rememberUpdatedState(configuration)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val currentResumed by rememberUpdatedState(resumed)

    // Saved under the session's key: a draft's list becomes its session's after process death.
    val saveKey = "transcript-list|${configuration.server?.persistenceID}|${configuration.sessionID}"
    val position = rememberSaveable(saver = transcriptListPositionSaver, key = "$saveKey|position") { TranscriptListPosition(transcriptListState(0, 0)) }
    val listState = position.state
    val disclosure = rememberSaveable(saver = TranscriptDisclosureState.Saver, key = "$saveKey|disclosure") { TranscriptDisclosureState() }
    val replySelection = rememberSaveable(saver = TranscriptReplySelection.Saver, key = "$saveKey|selection") { TranscriptReplySelection() }
    val scroller = remember { TranscriptListScroller(listState, scope, replySelection) }
    scroller.flingBehavior = ScrollableDefaults.flingBehavior()

    val newDivider = remember { NewDividerState() }
    val reduceMotion = rememberReduceMotion()
    val mark = configuration.newMessages
    val builder = remember { TranscriptSnapshotBuilder() }
    val dividerDecision = if (mark != null && newDivider.decidedGeneration != mark.generation) mark.entryID to false else null
    val snapshot = builder.build(configuration, newDivider.fitGeneration, dividerDecision)
    val items = snapshot.items.asReversed()

    val agentReveal = remember { TranscriptAgentMessageReveal() }
    val seenKeys = remember { HashSet<Any>() }
    val arrivingKeys = remember { HashSet<Any>() }
    val firstBuild = seenKeys.isEmpty()
    val newKeys = ArrayList<Any>()
    var showsWorking = false
    for (item in items) {
        if (item == TranscriptItemID.Working) showsWorking = true
        if (item == TranscriptItemID.NewDivider) continue
        if (seenKeys.add(item.key)) newKeys.add(item.key)
    }
    // Rows replacing the Working line appear at full height, or the list dips as it leaves.
    val previousBuild = remember { PreviousBuild() }
    val workingLeft = previousBuild.showedWorking && !showsWorking
    previousBuild.showedWorking = showsWorking
    // Rows under an undecided New mark appear at full height, so the decision measures them.
    val marksArrivals = mark != null && newDivider.decidedGeneration != mark.generation
    if (!firstBuild && newKeys.isNotEmpty() && scroller.isFollowing) {
        listState.requestScrollToItem(0)
        val animates = newKeys.size <= MAX_ARRIVING_ROWS && !marksArrivals && !workingLeft && !reduceMotion
        if (animates) arrivingKeys.addAll(newKeys)
        for (item in items) {
            if (item.key !in newKeys || item !is TranscriptItemID.Message) continue
            val message = when (val row = snapshot.rows[item.stableID]) {
                is MessageRow.Entry -> TranscriptUserMessage.from(row.entry)
                is MessageRow.Queued -> TranscriptUserMessage.from(row.input)
                null -> null
            }
            if (message != null && message.isFromAgent) agentReveal.arrive(message.id)
        }
    }

    // Not following: the top row holds still (the reversed list would hold the bottom one). Read without observing.
    scroller.keys = { currentItems(builder).map { it.key } }
    scroller.reduceMotion = reduceMotion
    if (!scroller.isFollowing && !listState.isScrollInProgress && position.anchor == null) {
        val keys = items.map { it.key }
        Snapshot.withoutReadObservation { scroller.holdTopRow(keys) }
    }

    var lastSendGeneration by remember { mutableIntStateOf(configuration.sendGeneration) }
    var sentWhileBrowsing by remember { mutableIntStateOf(0) }
    if (configuration.sendGeneration != lastSendGeneration) {
        lastSendGeneration = configuration.sendGeneration
        if (replySelection.isSelected) replySelection.clear()
        if (scroller.isAtEnd) {
            listState.requestScrollToItem(0)
        } else {
            sentWhileBrowsing = configuration.sendGeneration
        }
    }
    LaunchedEffect(sentWhileBrowsing) {
        if (sentWhileBrowsing != 0) scroller.scrollToEnd()
    }
    var lastJump by remember { mutableIntStateOf(jumpRequest) }
    LaunchedEffect(jumpRequest) {
        if (jumpRequest != lastJump) {
            lastJump = jumpRequest
            scroller.scrollToEnd()
        }
    }

    // A reader whose row, saved across process death, isn't listed yet (its page is still
    // loading) would see the list clamped somewhere else and then jump. It stays invisible until
    // the row is put back, the restore ends, or RESTORE_HIDE_LIMIT_MS passes.
    val currentHidesChanged by rememberUpdatedState(onHidesChanged)
    val restoreHide = remember { RestoreHide() }
    if (restoreHide.decide) {
        restoreHide.decide = false
        val anchorKey = position.anchor?.first
        restoreHide.hides = anchorKey != null && items.none { it.key == anchorKey }
        if (restoreHide.hides) currentHidesChanged(true)
    }
    val endRestoreHide = {
        if (restoreHide.hides) {
            restoreHide.hides = false
            currentHidesChanged(false)
        }
    }
    if (position.anchor != null) {
        SideEffect {
            val (anchorKey, offset) = position.anchor ?: return@SideEffect
            if (position.settleLayouts > 0) return@SideEffect
            val index = items.indexOfFirst { it.key == anchorKey }
            if (index < 0) {
                // All of the history is listed and the row isn't in it: nothing to go back to. (Not
                // `!hasEarlierItems`: a restored cache says that before its first read lands.)
                if (configuration.listsAllHistory) {
                    position.anchor = null
                    endRestoreHide()
                }
                return@SideEffect
            }
            position.settleLayouts = 3
            listState.requestScrollToItem(index, offset)
            // This frame lays the row out where it was: the list shows from it.
            endRestoreHide()
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo }.collect {
                val (anchorKey, offset) = position.anchor ?: return@collect
                if (position.settleLayouts <= 0) return@collect
                val index = currentItems(builder).indexOfFirst { item -> item.key == anchorKey }
                if (index < 0 || (listState.firstVisibleItemIndex == index && listState.firstVisibleItemScrollOffset == offset) || --position.settleLayouts == 0) {
                    position.anchor = null
                    endRestoreHide()
                } else {
                    listState.requestScrollToItem(index, offset)
                }
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.first { it }
            position.anchor = null
            endRestoreHide()
        }
    }
    if (restoreHide.hides) {
        LaunchedEffect(restoreHide) {
            delay(RESTORE_HIDE_LIMIT_MS)
            endRestoreHide()
        }
    }

    // iOS `TranscriptListViewController+TextSelection`: one selection across a reply's rows.
    val listSelection = remember { TranscriptListSelection(scroller, builder, scope) }
    listSelection.configuration = configuration
    listSelection.style = MarkdownStyle(layoutTuning, OpenCodeTheme.colors)
    listSelection.density = density
    listSelection.linkStyle = LocalInlineLinkStyle.current
    listSelection.topPadding = with(density) { contentPadding.calculateTopPadding().toPx() }
    listSelection.bottomPadding = with(density) { contentPadding.calculateBottomPadding().toPx() }
    replySelection.density = density.density
    DisposableEffect(listSelection) {
        replySelection.host = listSelection
        onDispose {
            // iOS: the list leaving clears the selection.
            replySelection.clear()
            if (replySelection.host === listSelection) replySelection.host = null
        }
    }
    SideEffect { replySelection.restorePending() }
    ReplySelectionBackHandler(replySelection)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect(scroller::scrollInProgressChanged)
    }

    val closingRows = remember { mutableStateMapOf<Any, Boolean>() }
    DisposableEffect(disclosure) {
        disclosure.animateToggle = { entryID, opening, _, toggle ->
            val key = builder.messageItemKey(entryID)
            closingRows[key] = !opening
            scroller.rowToggled(key)
            toggle()
        }
        onDispose { disclosure.animateToggle = null }
    }

    // iOS `scrollEdgeBridge.onBorrowedDrag`: drags on the controls over the list scroll it.
    if (borrowedDrag != null) {
        DisposableEffect(borrowedDrag, scroller) {
            val handler: (TranscriptBorrowedDrag.Event) -> Unit = scroller::handleBorrowedDrag
            borrowedDrag.onEvent = handler
            onDispose {
                if (borrowedDrag.onEvent === handler) borrowedDrag.onEvent = null
                scroller.cancelBorrowedDrag()
            }
        }
    }

    val perfRows = remember { PerfTimeline.makeRowsToken() }
    SideEffect { TranscriptTunables.register() }
    DisposableEffect(perfRows) {
        PerfTimeline.transcriptAppeared(perfRows)
        onDispose {
            PerfTimeline.transcriptDisappeared(perfRows)
            perfRows?.release()
        }
    }
    var hasLaidOutContent by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val c = currentConfiguration
            val laidOut = info.visibleItemsInfo.any { it.contentType == ROW_MESSAGE || it.key == TranscriptItemID.Empty.key }
            if (laidOut && !hasLaidOutContent) {
                hasLaidOutContent = true
                PerfTimeline.rowsShown(c.sessionID, perfRows)
                currentSend(TranscriptAction.FirstContentFrameRendered)
            }
            if (hasLaidOutContent) PerfTimeline.snapshotRendered(c.sessionID, c.snapshotRevision, perfRows)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.layoutInfo, listState.isScrollInProgress, replySelection.isSelected) }.collect { (info) ->
            val current = currentConfiguration.newMessages ?: return@collect
            if (!currentResumed || !hasLaidOutContent) return@collect
            if (newDivider.decidedGeneration != current.generation) {
                decideNewMessages(current.entryID, current.generation, info, newDivider, builder, scroller, currentSend)
                return@collect
            }
            if (newDivider.passedGeneration == current.generation || newDivider.landing?.isActive == true || !scroller.isAtEnd) return@collect
            val dividerIndex = currentItems(builder).indexOf(TranscriptItemID.NewDivider)
            if (dividerIndex < 0) return@collect
            val divider = info.visibleItemsInfo.firstOrNull { it.key == TranscriptItemID.NewDivider.key }
            val visibleHeight = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
            val passed = if (divider != null) {
                NewMessagesPlacement.isPassed(dividerMaxY = (visibleHeight - divider.offset).toFloat(), offsetY = 0f, topInset = 0f)
            } else {
                dividerIndex > lastVisible
            }
            if (passed) {
                newDivider.passedGeneration = current.generation
                currentSend(TranscriptAction.NewMessagesPassed(current.generation))
            } else if (divider != null && !listState.isScrollInProgress && !replySelection.isSelected) {
                newDivider.passedGeneration = current.generation
                newDivider.readGeneration = current.generation
            }
        }
    }

    val currentShowsJump by rememberUpdatedState(onShowsJumpButtonChanged)
    val jumpThreshold = with(density) { JUMP_BUTTON_DISTANCE.dp.toPx() }
    LaunchedEffect(listState) {
        snapshotFlow {
            val awayFromEnd = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > jumpThreshold
            awayFromEnd && !scroller.glidingToEnd
        }.collect { currentShowsJump(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { scroller.isAtEnd }.collect { currentSend(TranscriptAction.TranscriptBrowsingChanged(!it)) }
    }

    // iOS `reportHeaderGeometry`: how far content sits above its resting top (dp), for the
    // session header's backdrop reveal. Until the oldest row is in view it's simply "far".
    val currentScrolledUnderHeader by rememberUpdatedState(onScrolledUnderHeader)
    val scrolledUnderHeader = {
        val info = listState.layoutInfo
        val oldest = info.visibleItemsInfo.lastOrNull()
        if (oldest == null) {
            if (currentConfiguration.entries.isEmpty()) 0f else Float.MAX_VALUE
        } else if (oldest.index < info.totalItemsCount - 1) {
            Float.MAX_VALUE
        } else {
            val visibleHeight = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            maxOf(0f, (oldest.offset + oldest.size - visibleHeight) / density.density)
        }
    }
    // Also before the first frame, so the header's bar draws with the rows.
    DisposableEffect(listState) {
        currentScrolledUnderHeader(scrolledUnderHeader())
        onDispose {}
    }
    LaunchedEffect(listState) {
        snapshotFlow { scrolledUnderHeader() }.collect { currentScrolledUnderHeader(it) }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearTop = info.totalItemsCount > 0 && last >= info.totalItemsCount - EARLIER_HISTORY_ROWS
            val c = currentConfiguration
            val anchorKey = position.anchor?.first
            val restoring = anchorKey != null && c.entries.isNotEmpty() && currentItems(builder).none { it.key == anchorKey }
            val wants = (nearTop && !scroller.holdsEarlierHistory) || restoring
            hasLaidOutContent && wants && c.hasEarlierItems && !c.isLoadingEarlier && c.earlierHistoryError == null
        }.collect { wantsEarlier ->
            if (wantsEarlier) currentSend(TranscriptAction.ShowEarlierTapped)
        }
    }

    val rowContext = remember(disclosure, configuration.server, configuration.sessionID, configuration.directory) {
        TranscriptRowContext(
            server = configuration.server,
            sessionID = configuration.sessionID,
            directory = configuration.directory,
            disclosure = disclosure,
            send = { currentSend(it) },
        )
    }
    val textReveal = remember { TranscriptTextReveal() }
    if (!configuration.isRunning) textReveal.settleAll()
    val linkPreviews = retain { LinkPreviewStore() }
    RetainedEffect(linkPreviews) { onRetire { linkPreviews.close() } }
    val linkRevisions = remember { mutableStateMapOf<String, Int>() }
    DisposableEffect(linkPreviews) {
        linkPreviews.onArrival = { rows -> for (row in rows) linkRevisions[row] = linkPreviews.revision(row) }
        onDispose { linkPreviews.onArrival = null }
    }
    remember(linkPreviews) { LinkPreviewArrivals(linkPreviews) }.update(snapshot, configuration.isRunning, hasLaidOutContent)
    val imageViewport = remember { TranscriptImageViewport() }
    SideEffect {
        // Not read while composing, so a composer resize doesn't recompose the list.
        imageViewport.topInset = with(density) { contentPadding.calculateTopPadding().toPx() }
        imageViewport.bottomInset = with(density) { contentPadding.calculateBottomPadding().toPx() }
    }
    imageViewport.isSettling = !hasLaidOutContent
    imageViewport.isVisible = resumed
    val imageVisibility = remember { dependency(AgentImageVisibility) }
    DisposableEffect(imageViewport) {
        imageVisibility.register(imageViewport)
        onDispose { imageVisibility.unregister(imageViewport) }
    }
    LaunchedEffect(imageViewport) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.size to listState.firstVisibleItemScrollOffset }
            .collect { imageVisibility.setNeedsUpdate() }
    }

    val gutter = layoutTuning.contentGutter.dp
    CompositionLocalProvider(
        LocalAgentImageViewport provides imageViewport,
        LocalTranscriptRowContext provides rowContext,
        LocalTranscriptLayoutTuning provides layoutTuning,
        LocalTranscriptTextReveal provides textReveal,
        LocalLinkPreviewStore provides linkPreviews,
        LocalTranscriptReplySelection provides replySelection,
        // A covering screen isn't composed and a stopped activity's frame clock pauses, so
        // only the lifecycle below RESUMED is left to gate (iOS `emptyPulsePaused`).
        LocalTranscriptTimelinePaused provides TranscriptTimelineGate.pauses(
            listHidden = !resumed,
            sceneInBackground = false,
            covered = false,
            drawn = true,
        ),
    ) {
        val showsMagnifier by remember(replySelection) { derivedStateOf { replySelection.magnifierCenter.isSpecified } }
        val scrollToTopLabel = stringResource(R.string.transcript_scroll_to_top)
        Box(
            modifier
                .nestedScroll(scroller.nestedScrollConnection)
                .onPlaced { replySelection.container = it }
                .transcriptReplySelectionGestures(replySelection)
                .transcriptReplySelectionHandles(replySelection)
                .drawerPanSelectionHandles { point -> replySelection.handleAt(point) != null }
                .then(if (showsMagnifier) Modifier.magnifier(sourceCenter = { replySelection.magnifierCenter }) else Modifier),
        ) {
            SharedTextContextMenus {
                TranscriptReplySelectionMenu(
                    replySelection,
                    isScrolling = { listState.isScrollInProgress },
                    onCopy = { currentSend(TranscriptAction.CopyTextTapped(it)) },
                    onQuote = { currentSend(TranscriptAction.QuoteTextTapped(it)) },
                )
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().testTag("transcript-list")
                        .semantics { customActions = listOf(CustomAccessibilityAction(scrollToTopLabel, scroller::scrollToTop)) }
                        .graphicsLayer { alpha = if (restoreHide.hides) 0f else 1f }
                        .onGloballyPositioned {
                            imageViewport.bounds = it.boundsInWindow()
                            imageVisibility.setNeedsUpdate()
                        },
                    contentPadding = contentPadding,
                ) {
                    items(items, key = { it.key }, contentType = { it.contentType }) { item ->
                        val arrives = remember { arrivingKeys.remove(item.key) }
                        // Centred at the readable width on a wider window (iOS `readableColumn`).
                        val rowModifier = Modifier
                            .fillMaxWidth()
                            .wrapContentWidth()
                            .widthIn(max = TranscriptMetrics.readableColumnWidth)
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                        RowArrival(arrives) {
                            when (item) {
                                TranscriptItemID.History -> TranscriptHistoryStatusView(
                                    isLoading = configuration.isLoadingEarlier,
                                    error = configuration.earlierHistoryError,
                                    retry = { currentSend(TranscriptAction.ShowEarlierTapped) },
                                    modifier = rowModifier,
                                )
                                TranscriptItemID.Empty -> TranscriptEmptyView(
                                    isLoading = configuration.isLoading,
                                    showsEmptyState = configuration.showsEmptyState,
                                    loadingSince = builder.emptyLoadingSince,
                                    pulsePaused = !resumed,
                                    modifier = rowModifier.fillParentMaxHeight(),
                                )
                                TranscriptItemID.NewDivider -> {
                                    val generation = mark?.generation
                                    ClosingDivider(
                                        closes = generation != null && newDivider.readGeneration == generation,
                                        glowStart = if (newDivider.glowGeneration == generation) newDivider.glowStart else null,
                                        onClosed = { if (generation != null) currentSend(TranscriptAction.NewMessagesPassed(generation)) },
                                        modifier = rowModifier.onPlaced {
                                            if (generation != null && newDivider.glowGeneration != generation) {
                                                newDivider.glowGeneration = generation
                                                newDivider.glowStart = if (reduceMotion) null else transcriptNow()
                                            }
                                        },
                                    )
                                }
                                TranscriptItemID.Working -> Box(rowModifier) {
                                    val working = slots.workingLine
                                    if (working != null) {
                                        working()
                                    } else {
                                        WorkingLineFallback(Modifier.padding(vertical = TranscriptMetrics.flowHalf))
                                    }
                                }
                                is TranscriptItemID.Message -> {
                                    val row = snapshot.rows[item.stableID] ?: return@RowArrival
                                    val props = snapshot.props[item.stableID] ?: RowProps()
                                    val run = snapshot.runs[item.stableID] ?: UserMessageRun.alone
                                    val lastHeight = remember { IntArray(1) }
                                    val rowHeight = remember { RowHeightMotion() }
                                    Box(
                                        rowModifier
                                            // The new size is reported a frame before the row moves, so the list can hold the reader.
                                            .then(
                                                if (rowHeight.animatesItself || reduceMotion) Modifier
                                                else Modifier.animateContentSize(
                                                    rowHeightSpring(closingRows[item.key])
                                                        .spec(IntSize.VisibilityThreshold),
                                                ),
                                            )
                                            .onSizeChanged { size ->
                                                val old = lastHeight[0]
                                                lastHeight[0] = size.height
                                                if (old != 0 && old != size.height) scroller.rowResizing(item.key, size.height, jumpThreshold)
                                            },
                                    ) {
                                        CompositionLocalProvider(
                                            LocalRowHeightMotion provides rowHeight,
                                            LocalAgentImageRow provides item.stableID,
                                            LocalLinkPreviewRevision provides (linkRevisions[item.stableID] ?: 0),
                                            LocalLinkPreviewRow provides item.stableID,
                                        ) {
                                            if (row is MessageRow.Queued && row.input.isCompaction) {
                                                TranscriptQueuedInputView(row.input, Modifier.padding(vertical = TranscriptMetrics.sectionHalf))
                                            } else {
                                                val entry = row as? MessageRow.Entry
                                                val queued = row as? MessageRow.Queued
                                                TranscriptMessageRow(
                                                    rowID = item.stableID,
                                                    entry = entry?.entry,
                                                    queued = queued?.input,
                                                    failure = entry?.failure,
                                                    deliveryNote = entry?.note ?: queued?.note,
                                                    props = props,
                                                    run = run,
                                                    revealArrival = (entry?.entry?.userMessageID ?: queued?.input?.id)?.let(agentReveal::arrival),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun currentItems(builder: TranscriptSnapshotBuilder): List<TranscriptItemID> = builder.snapshot.items.asReversed()

private fun decideNewMessages(
    entryID: String,
    generation: Int,
    info: LazyListLayoutInfo,
    state: NewDividerState,
    builder: TranscriptSnapshotBuilder,
    scroller: TranscriptListScroller,
    send: (TranscriptAction) -> Unit,
) {
    val markKey = builder.messageItemKey(entryID)
    val items = currentItems(builder)
    val markIndex = items.indexOfFirst { it.key == markKey }
    if (markIndex < 0) return
    val markRow = info.visibleItemsInfo.firstOrNull { it.key == markKey }
    // A layout from before the rows changed: decide on the next one.
    if (markRow == null && info.totalItemsCount != items.size) return
    val visibleHeight = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    val fits = scroller.isAtEnd && markRow != null && markRow.offset + markRow.size <= visibleHeight
    state.decidedGeneration = generation
    if (fits) {
        state.fitGeneration = generation
        state.passedGeneration = generation
        send(TranscriptAction.NewMessagesFit(generation))
        return
    }
    if (!scroller.isAtEnd) return
    state.landing = scroller.showRowTop(markIndex + 1)
}

@Composable
private fun ClosingDivider(closes: Boolean, glowStart: Double?, onClosed: () -> Unit, modifier: Modifier) {
    val visible = remember { MutableTransitionState(true) }
    visible.targetState = !closes
    val currentOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(visible) {
        snapshotFlow { visible.isIdle && !visible.currentState }.first { it }
        currentOnClosed()
    }
    AnimatedVisibility(
        visibleState = visible,
        exit = shrinkVertically(ARRIVAL_SPRING.spec(IntSize.VisibilityThreshold)) + fadeOut(ARRIVAL_SPRING.spec()),
    ) {
        NewMessagesDivider(modifier, glowStart = glowStart)
    }
}

@Composable
private fun RowArrival(arrives: Boolean, content: @Composable () -> Unit) {
    if (!arrives) {
        content()
        return
    }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = expandVertically(ARRIVAL_SPRING.spec(IntSize.VisibilityThreshold), expandFrom = Alignment.Top) +
            fadeIn(tween(Motion.millis(ARRIVAL_FADE_SECONDS), easing = FastOutLinearInEasing)),
    ) {
        content()
    }
}

/** The composer's send reset runs on this spring too, so the bubble and the card move together. */
internal val ARRIVAL_SPRING = MotionSpring(0.17)
private val GROWTH_SPRING = MotionSpring(0.22)

private fun rowHeightSpring(closing: Boolean?): MotionSpring = when (closing) {
    null -> GROWTH_SPRING
    else -> DisclosureMotion.spring(opening = !closing)
}

/** Accelerating, so the sent words are still faint while the composer's outgoing copy fades. */
private const val ARRIVAL_FADE_SECONDS = 0.2

private const val MAX_ARRIVING_ROWS = 3

private class PreviousBuild {
    var showedWorking = false
}

private const val EARLIER_HISTORY_ROWS = 3

private const val JUMP_BUTTON_DISTANCE = 120

private const val ROW_MESSAGE = "message"

private const val TALKBACK_TAIL_STREAM_INTERVAL_MS = 1_500L

/**
 * Under TalkBack touch exploration, coalesces in-place tail `AssistantMarkdown` streaming updates
 * ([TranscriptConfiguration.differsOnlyInTailAssistantMarkdown]) to [TALKBACK_TAIL_STREAM_INTERVAL_MS]
 * so per-chunk text and scroll updates don't saturate TalkBack's `ProcessorEventQueue`. The first
 * chunk of a reply and any structural change (run start, Stop / completion, tool rows, sends) apply
 * immediately; sighted (`!touchExploration`) returns [configuration] untouched.
 */
@Composable
private fun rememberPresentedConfiguration(
    configuration: TranscriptConfiguration,
    touchExploration: Boolean,
): TranscriptConfiguration {
    if (!touchExploration) return configuration
    val state = remember { PresentedConfigurationState(configuration) }
    val nowMs = SystemClock.uptimeMillis()
    val current = state.presented
    if (configuration == current) {
        state.pending = null
        return current
    }
    if (!configuration.differsOnlyInTailAssistantMarkdown(current)) {
        state.presented = configuration
        state.pending = null
        state.lastTailApplyUptimeMs = nowMs
        return configuration
    }
    val elapsed = nowMs - state.lastTailApplyUptimeMs
    if (elapsed >= TALKBACK_TAIL_STREAM_INTERVAL_MS) {
        state.presented = configuration
        state.pending = null
        state.lastTailApplyUptimeMs = nowMs
        return configuration
    }
    state.pending = configuration
    val waitMs = (TALKBACK_TAIL_STREAM_INTERVAL_MS - elapsed).coerceAtLeast(16L)
    LaunchedEffect(state, state.lastTailApplyUptimeMs, state.pending != null) {
        if (state.pending == null) return@LaunchedEffect
        delay(waitMs)
        val next = state.pending ?: return@LaunchedEffect
        state.pending = null
        state.lastTailApplyUptimeMs = SystemClock.uptimeMillis()
        state.presented = next
    }
    return state.presented
}

private class PresentedConfigurationState(initial: TranscriptConfiguration) {
    var presented by mutableStateOf(initial)
    var pending: TranscriptConfiguration? = null
    var lastTailApplyUptimeMs: Long = 0L
}

@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager.isTouchExplorationEnabled
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** Until the session supplies its Working line (activity orb + title), a quiet label. */
@Composable
internal fun WorkingLineFallback(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.transcript_working), modifier, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
}

/**
 * The transcript list as its image previews' viewport (iOS
 * `TranscriptListViewController+ImagePreviews.swift`): nothing counts as on screen while the
 * session opens, then the list's bounds between the header and the composer. A covered or
 * backgrounded list holds no previews.
 */
private class TranscriptImageViewport : AgentImageViewport {
    var bounds: Rect = Rect.Zero
    var topInset = 0f
    var bottomInset = 0f
    var isSettling = true
    var isVisible = true

    override val imageViewportVisibleRect: Rect
        get() {
            if (isSettling) return Rect.Zero
            return Rect(bounds.left, bounds.top + topInset, bounds.right, (bounds.bottom - bottomInset).coerceAtLeast(bounds.top + topInset))
        }

    override val imageViewportIsSettling: Boolean get() = isSettling

    override val imageViewportIsVisible: Boolean get() = isVisible

    override fun imageContentRevised(row: Any?) {}
}
