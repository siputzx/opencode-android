package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionPose
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.feature.transcript.user.QuoteCard
import ai.opencode.feature.transcript.user.QuotePreview
import ai.opencode.feature.transcript.R as TranscriptR
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ComposerQuoteCard(quote: ComposerQuote, radius: Dp, onOpen: () -> Unit) {
    QuoteCard(
        quote.text,
        quote.comment,
        offersComment = true,
        onOpen = onOpen,
        // The × sits over the top trailing corner.
        titleEndPadding = 26.dp,
        modifier = Modifier.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

private val PanelPadding = 12.dp
private val PanelGap = 8.dp
private val PanelHeaderRoom = 72.dp

/**
 * Desktop's quote comment editor, drawn in the composer's own window so the keyboard stays up as
 * focus moves between its field and the composer's.
 */
@Composable
internal fun QuoteCommentOverlay(store: StoreOf<SessionFeature.State, SessionFeature.Action>, modifier: Modifier) {
    val host = LocalSessionComposerHost.current ?: return
    val quotes by store.observe { it.pendingQuotes }
    val openedID = host.commentingQuoteID
    val quote = quotes.firstOrNull { it.id == openedID }
    LaunchedEffect(openedID, quote == null) {
        if (openedID != null && quote == null) host.commentingQuoteID = null
    }
    fun close() {
        host.commentingQuoteID = null
        host.focusComposer()
    }
    QuoteCommentPanel(
        quote = quote,
        surfaceInWindow = { host.surfaceInWindow },
        onSave = { id, comment ->
            store.send(SessionFeature.Action.quoteCommented(id, comment))
            close()
        },
        onClose = ::close,
        onKeysLeft = { host.commentingQuoteID = null },
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuoteCommentPanel(
    quote: ComposerQuote?,
    surfaceInWindow: () -> androidx.compose.ui.geometry.Rect?,
    onSave: (id: String, comment: String) -> Unit,
    onClose: () -> Unit,
    onKeysLeft: () -> Unit,
    modifier: Modifier,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = quote != null
    var shown by remember { mutableStateOf(quote) }
    if (quote != null) shown = quote
    val current = shown
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }) {
        if (current == null || (!visible.currentState && !visible.targetState)) return@Box
        val surface = surfaceInWindow() ?: return@Box
        val density = LocalDensity.current
        val reduceMotion = rememberReduceMotion()
        val statusBarTop = WindowInsets.statusBars.getTop(density)

        val field = remember(current.id) { TextFieldState(current.comment.orEmpty()).apply { edit { placeCursorAtEnd() } } }
        val saved = current.comment.orEmpty()
        val unchanged = field.text.toString().trim() == saved.trim()
        val open = quote != null
        val currentOnSave by rememberUpdatedState(onSave)
        val save = { if (open) currentOnSave(current.id, field.text.toString()) }

        CommentPanelBackHandler(enabled = open, onBack = onClose)
        // The keys going down on their own (swiped away) take the panel with them.
        val imeVisible = WindowInsets.isImeVisible
        var keysWereUp by remember(current.id) { mutableStateOf(false) }
        LaunchedEffect(imeVisible, open) {
            if (imeVisible) keysWereUp = true else if (keysWereUp && open) onKeysLeft()
        }

        if (open) {
            // A tap outside closes the panel only while that loses nothing; either way it stays here.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(unchanged) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            if (unchanged) onClose()
                        }
                    },
            )
        }

        val transform = TransformOrigin(0.5f, 1f)
        val enter = if (reduceMotion) {
            fadeIn(MotionRole.Reduced.animation(reduceMotion = true))
        } else {
            fadeIn(MotionRole.MicroIn.animation(MotionPhase.Enter)) +
                scaleIn(MotionRole.MicroIn.animation(MotionPhase.Enter), initialScale = MotionPose.microIn.scale, transformOrigin = transform)
        }
        val exit = if (reduceMotion) {
            fadeOut(MotionRole.Reduced.animation(reduceMotion = true))
        } else {
            fadeOut(MotionRole.MicroOut.animation(MotionPhase.Exit)) +
                scaleOut(MotionRole.MicroOut.animation(MotionPhase.Exit), targetScale = MotionPose.swap.scale, transformOrigin = transform)
        }
        val gap = with(density) { PanelGap.roundToPx() }
        val headerRoom = with(density) { PanelHeaderRoom.roundToPx() }
        AnimatedVisibility(
            visibleState = visible,
            enter = enter,
            exit = exit,
            modifier = Modifier.layout { measurable, _ ->
                val bottom = (surface.top - origin.y).roundToInt() - gap
                val room = (surface.top - statusBarTop).roundToInt() - headerRoom - gap
                val width = surface.width.roundToInt()
                val maxHeight = room.coerceAtLeast((OpenCodeMinTapTarget * 2).roundToPx())
                val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight))
                layout(placeable.width, placeable.height) {
                    placeable.place((surface.left - origin.x).roundToInt(), bottom - placeable.height)
                }
            },
        ) {
            Surface(
                shape = OpenCodeSurfaces.popoverShape,
                color = OpenCodeSurfaces.color,
                border = OpenCodeSurfaces.border,
                shadowElevation = OpenCodeSurfaces.popoverShadow,
            ) {
                Column(
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = PanelPadding, top = PanelPadding),
                ) {
                    QuotePreview(current.text, Modifier.padding(end = PanelPadding), maxLines = 4)
                    CommentField(field, saved = saved, onSave = save)
                }
            }
        }
    }
}

// The field's row stands a tap target tall, so it also holds the gaps above and below its line.
@Composable
private fun CommentField(field: TextFieldState, saved: String, onSave: () -> Unit) {
    val colors = OpenCodeTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val placeholder = stringResource(R.string.session_quote_comment_placeholder)
    val saveLabel = when {
        saved.isNotBlank() -> stringResource(CoreR.string.common_save)
        field.text.isNotBlank() -> stringResource(TranscriptR.string.transcript_quote_add_comment)
        else -> stringResource(CoreR.string.common_done)
    }
    Row(Modifier.heightIn(min = OpenCodeMinTapTarget), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
            if (field.text.isEmpty()) {
                Text(placeholder, style = OpenCodeTextStyles.subheadline, color = colors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
            }
            BasicTextField(
                state = field,
                textStyle = OpenCodeTextStyles.subheadline.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                onKeyboardAction = { onSave() },
                modifier = Modifier
                    .focusRequester(focus)
                    // A hardware Enter saves, as desktop's does; Shift+Enter starts a new line.
                    .onPreviewKeyEvent { event ->
                        val saves = event.key == Key.Enter && !event.isShiftPressed
                        if (saves && event.type == KeyEventType.KeyDown) onSave()
                        saves
                    }
                    .semantics { contentDescription = placeholder },
            )
        }
        Box(
            Modifier
                .clickable(role = Role.Button, onClickLabel = saveLabel, onClick = onSave)
                .openCodeTapTarget()
                .semantics { contentDescription = saveLabel },
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 16.dp, tint = colors.secondaryText)
        }
    }
}

// In the composer's window the keys hear back first; overlay priority takes it before them.
@Composable
private fun CommentPanelBackHandler(enabled: Boolean, onBack: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        BackHandler(enabled = enabled, onBack = onBack)
        return
    }
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val callback = OnBackInvokedCallback { currentOnBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
