package ai.opencode.feature.composer

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.OpenCodeButtonMenu
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.modelpicker.OnePickerChipRoll
import ai.opencode.feature.composer.plusmenu.findActivity
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import android.os.Build
import android.os.SystemClock
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Agent Selection (`SharedKeys.agentSelection`): choose the primary agent (Build, Plan,
// or a custom one) from the composer.

object AgentPickerSharedKeys {
    /** The agent new sessions start with (an agent ID); empty is the server's default. Declared in `:core` (Settings writes it). */
    val defaultAgentID = ai.opencode.core.sharing.SharedKeys.defaultAgentID

    /** The agent picked in each session (session ID → agent ID). Declared in `:server`. */
    val sessionAgents = ai.opencode.server.ServerSharedKeys.sessionAgents

    /** The primary agents last listed, for Settings' Default Agent. Declared in `:server`. */
    val knownPrimaryAgents = ai.opencode.server.ServerSharedKeys.knownPrimaryAgents
}

/**
 * The agent chip beside the model chip, only while Agent Selection is on ([isEnabled]). [isFolded]
 * hides it as the composer folds.
 */
@Composable
fun ComposerAgentChip(
    isEnabled: Boolean,
    name: String,
    currentID: String?,
    agents: List<OpenCodeAutocompleteAgent>,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFolded: Boolean = false,
    targetDrop: Dp = 0.dp,
) {
    if (!isEnabled) return
    AgentChip(
        name = name,
        currentID = currentID,
        agents = agents,
        onOpen = onOpen,
        onPick = onPick,
        modifier = if (isFolded) modifier.alpha(0f).clearAndSetSemantics {} else modifier,
        enabled = !isFolded,
        targetDrop = targetDrop,
    )
}

/**
 * The current agent's name, opening a menu of the server's primary agents. [targetDrop]: how far
 * its target sits below its ink (the composer's `pickerTargetDrop`).
 */
@Composable
fun AgentChip(
    name: String,
    currentID: String?,
    agents: List<OpenCodeAutocompleteAgent>,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    targetDrop: Dp = 0.dp,
) {
    var open by remember { mutableStateOf(false) }
    var closedAt by remember { mutableLongStateOf(0L) }
    // Mounted as the composer expands: the list is fresh before it opens.
    LaunchedEffect(Unit) { onOpen() }
    val chooseAgentDescription = stringResource(R.string.composer_agent_choose_description)
    val secondary = OpenCodeTheme.colors.secondaryText
    val reduceMotion = rememberReduceMotion()
    AgentMenuBackHandler(enabled = open) { open = false }
    // The chip moves its target down and its ink back up; the menu hangs from the ink.
    Box(modifier.offset(y = targetDrop)) {
        Row(
            Modifier
                .clickable(enabled = enabled, role = Role.Button) {
                    // A tap on the chip while the menu is open has already closed it as an outside tap.
                    if (SystemClock.uptimeMillis() - closedAt > 500) open = !open
                }
                .heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = chooseAgentDescription
                    stateDescription = name
                    role = Role.DropdownList
                }
                .testTag("composer-agent")
                .offset(y = -targetDrop),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)
            OnePickerChipRoll(name, animates = true, reduceMotion, label = "agentName") { rolled ->
                Text(rolled, style = style, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            }
            OpenCodeIcon(SFSymbol.ChevronDown, null, size = 11.dp, tint = secondary)
        }
        Box(Modifier.matchParentSize().offset(y = -targetDrop)) {
            AgentMenu(
                open = open,
                agents = agents,
                currentID = currentID,
                onDismiss = {
                    open = false
                    closedAt = SystemClock.uptimeMillis()
                },
                onPick = { id ->
                    open = false
                    onPick(id)
                },
            )
        }
    }
}

/** The agents in the composer's button menu, scrolled to the current one. */
@Composable
private fun AgentMenu(
    open: Boolean,
    agents: List<OpenCodeAutocompleteAgent>,
    currentID: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val density = LocalDensity.current
    val currentIndex = agents.indexOfFirst { it.id == currentID }
    OpenCodeButtonMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        initialScroll = { viewportPx ->
            val rowPx = with(density) { OpenCodeMinTapTarget.roundToPx() }
            var initial = (currentIndex + 1) * rowPx - viewportPx + rowPx / 2
            initial = initial.coerceAtMost(currentIndex * rowPx)
            maxOf(0, initial)
        },
    ) {
        if (agents.isEmpty()) {
            AgentMenuRow(stringResource(R.string.composer_agent_loading), isOn = false, enabled = false) {}
        }
        for (agent in agents) {
            AgentMenuRow(agent.displayName, isOn = agent.id == currentID, enabled = true) {
                onPick(agent.id)
            }
        }
    }
}

@Composable
private fun AgentMenuRow(
    title: String,
    isOn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Box(Modifier.size(OpenCodeIconSize.small)) { if (isOn) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small) } },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { selected = isOn },
        contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
    )
}

// The menu's popup doesn't take focus, so back goes to the activity; overlay priority runs it before the keyboard's.
@Composable
private fun AgentMenuBackHandler(enabled: Boolean, onBack: () -> Unit) {
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

/**
 * Settings › Composer › Default Agent, the agent new sessions start with, only while Agent
 * Selection is on. Reads [AgentPickerSharedKeys.defaultAgentID] and
 * [AgentPickerSharedKeys.knownPrimaryAgents]; [onChange] hands the choice to the settings reducer,
 * which writes the key.
 *
 * Note: Settings sits beside (not above) this module, so the settings area needs these keys and
 * this section moved to a module it can see (`:server`); see the composer parity row.
 */
@Composable
fun DefaultAgentSection(
    isEnabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isEnabled) return
    val selectionShared = remember { shared(AgentPickerSharedKeys.defaultAgentID) }
    val agentsShared = remember { shared(AgentPickerSharedKeys.knownPrimaryAgents) }
    val selection by selectionShared.flow.collectAsState()
    val agents by agentsShared.flow.collectAsState()
    var open by remember { mutableStateOf(false) }
    val serverDefault = stringResource(R.string.composer_agent_server_default)
    val defaultAgentTitle = stringResource(R.string.composer_agent_default_title)

    data class Option(val id: String, val title: String)
    val options = buildList {
        add(Option("", serverDefault))
        agents.forEach { add(Option(it.id, it.displayName)) }
        // A saved agent this server doesn't list stays choosable.
        if (selection.isNotEmpty() && agents.none { it.id == selection }) add(Option(selection, AgentDisplayName.titleCased(selection)))
    }
    val current = options.firstOrNull { it.id == selection }?.title ?: serverDefault

    Column(modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.DropdownList) { open = true }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp)
                    .clearAndSetSemantics {
                        contentDescription = defaultAgentTitle
                        stateDescription = current
                        role = Role.DropdownList
                    }
                    .testTag("settings-default-agent"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(defaultAgentTitle, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                Text(current, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                OpenCodeIcon(SFSymbol.ChevronUpChevronDown, null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    val isOn = option.id == selection
                    DropdownMenuItem(
                        text = { Text(option.title) },
                        leadingIcon = { Box(Modifier.width(24.dp)) { if (isOn) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small) } },
                        modifier = Modifier.semantics { selected = isOn },
                        onClick = {
                            open = false
                            onChange(option.id)
                        },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.composer_agent_default_footer),
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
