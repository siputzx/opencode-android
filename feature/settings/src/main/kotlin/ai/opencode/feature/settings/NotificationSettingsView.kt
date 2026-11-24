package ai.opencode.feature.settings

import ai.opencode.core.R as CoreR
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.PushChoice
import ai.opencode.feature.system.notifications.PushDuration
import ai.opencode.feature.system.notifications.PushMode
import ai.opencode.feature.system.notifications.PushServer
import ai.opencode.feature.system.notifications.PushSharedKeys
import ai.opencode.feature.system.notifications.PushStatus
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import android.text.format.DateUtils
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics

/**
 * Settings → Notifications: this phone's permission, this server's notifications (on or off, what
 * to hear, a pause for everyone and the phones it knows), and the In-App Notices picker.
 */
@Composable
fun NotificationSettingsView(store: SettingsStore) {
    val permission by store.observe { it.notificationPermission }
    val isRequesting by store.observe { it.isRequestingNotifications }
    val setupError by store.observe { it.notificationSetupError }
    val server by store.observe { it.server }
    val failure by store.observe { it.pushFailure }
    val noticeMode by remember { shared(InAppNoticeMode.key).flow }.collectAsState()
    val choices by remember { shared(PushSharedKeys.choices).flow }.collectAsState(initial = shared(PushSharedKeys.choices).value)
    val servers by remember { shared(PushSharedKeys.servers).flow }.collectAsState(initial = shared(PushSharedKeys.servers).value)
    LaunchedEffect(store) {
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotificationSettingsRequested))
    }
    val enabled = permission == NotificationPermission.Enabled
    val serverID = server?.persistenceID
    val choice = serverID?.let { choices[it] } ?: PushChoice()
    val push = serverID?.let { servers[it] } ?: PushServer()
    val pushState = push.state
    val isReady = choice.enabled && push.status == PushStatus.Ready && pushState != null
    val permissionHeader = stringResource(R.string.settings_notifications_device_header, ThisDeviceName)
    val permissionFooter = stringResource(R.string.settings_notifications_device_footer, ThisDeviceName)
    val serverHeader = stringResource(R.string.settings_notifications_server_header)
    val serverFooter = pushStatusFooter(push.status)
    val pauseFooter = stringResource(R.string.settings_notifications_pause_footer)
    val devicesHeader = stringResource(R.string.settings_notifications_devices_header)
    val inAppFooter = stringResource(R.string.settings_notifications_in_app_footer)
    val send = { action: PushSettingsFeature.Action -> store.send(SettingsFeature.Action.Push(action)) }

    SettingsPageScaffold(title = stringResource(R.string.settings_notifications_title), store = store) {
        settingsSection(
            key = "permission",
            header = permissionHeader,
            footer = if (permission == NotificationPermission.Disabled) {
                null
            } else {
                footerText(permissionFooter)
            },
        ) {
            SettingsRowFrame(Modifier.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.settings_notifications_allow), style = SettingsText.title, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                Text(if (enabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off), style = SettingsText.title, color = OpenCodeTheme.colors.secondaryText)
            }
            if (!enabled) {
                SettingsDivider()
                SettingsButtonRow(
                    if (permission == NotificationPermission.Disabled) stringResource(R.string.settings_notifications_open_android_settings) else stringResource(R.string.settings_notifications_turn_on),
                    onClick = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.EnableNotificationsTapped))
                    },
                    enabled = !isRequesting,
                )
            }
            setupError?.let { error ->
                SettingsDivider()
                SettingsRowFrame {
                    OpenCodeErrorText(error, style = SettingsText.summary)
                }
            }
        }
        if (server != null && push.status != PushStatus.Unavailable) {
            settingsSection(
                key = "server",
                header = serverHeader,
                footer = if (choice.enabled && serverFooter != null) footerText(serverFooter) else null,
            ) {
                val canTurnOn = push.status != PushStatus.Outdated && push.status != PushStatus.RelayNotAllowed
                SettingsToggleRow(
                    title = stringResource(R.string.settings_notifications_server_toggle),
                    checked = choice.enabled && canTurnOn,
                    onCheckedChange = { send(PushSettingsFeature.Action.EnabledChanged(it)) },
                    enabled = canTurnOn,
                )
                if (isReady) {
                    val modes = pushModes(push.levels)
                    SettingsDivider()
                    SettingsMenuRow(
                        title = stringResource(R.string.settings_notifications_mode),
                        value = pushModeTitle(choice.mode),
                        selection = choice.mode,
                        options = modes.map { SettingsMenuOption(it, pushModeTitle(it), pushModeCaption(it)) },
                        onSelect = { send(PushSettingsFeature.Action.ModeChanged(it)) },
                    )
                }
            }
        }
        if (isReady && pushState != null) {
            settingsSection(key = "pause", footer = footerText(pauseFooter)) {
                val now = System.currentTimeMillis()
                val until = pushState.pausedUntil
                val isPaused = pushState.paused && (until == null || until > now)
                val value = when {
                    !isPaused -> stringResource(R.string.settings_value_off)
                    until != null -> stringResource(R.string.settings_notifications_pause_until, pushTime(until))
                    else -> stringResource(R.string.settings_notifications_pause_on)
                }
                val options = mutableListOf<SettingsMenuOption<PushDuration?>>()
                if (isPaused) options.add(SettingsMenuOption(null, stringResource(R.string.settings_notifications_resume)))
                options.add(SettingsMenuOption(PushDuration.hour, stringResource(R.string.settings_notifications_pause_hour)))
                options.add(SettingsMenuOption(PushDuration.tomorrowMorning, stringResource(R.string.settings_notifications_pause_tomorrow)))
                options.add(SettingsMenuOption(PushDuration.forever, stringResource(R.string.settings_notifications_pause_forever)))
                SettingsMenuRow(
                    title = stringResource(R.string.settings_notifications_pause),
                    value = value,
                    selection = when {
                        !isPaused -> null
                        until == null -> PushDuration.forever
                        until - now <= 60 * 60 * 1000 -> PushDuration.hour
                        else -> PushDuration.tomorrowMorning
                    },
                    options = options,
                    onSelect = { send(PushSettingsFeature.Action.PauseChosen(it)) },
                )
            }
            settingsSection(key = "devices", header = devicesHeader) {
                var first = true
                for (device in pushState.devices) {
                    if (!first) SettingsDivider()
                    first = false
                    DeviceRow(device, isThisPhone = device.id == push.deviceID, send = send)
                }
            }
        }
        settingsSection(
            key = "in-app",
            footer = footerText(inAppFooter),
        ) {
            SettingsMenuRow(
                title = stringResource(R.string.settings_notifications_in_app_notices),
                value = noticeMode.title,
                selection = noticeMode,
                options = InAppNoticeMode.entries.map { SettingsMenuOption(it, it.title) },
                onSelect = { store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.InAppNoticeModeChanged(it))) },
            )
        }
    }

    failure?.let {
        OpenCodeAlertDialog(
            onDismissRequest = { send(PushSettingsFeature.Action.FailureDismissed) },
            title = { Text(it.title) },
            text = { Text(it.message) },
            confirmButton = {
                TextButton(onClick = { send(PushSettingsFeature.Action.FailureDismissed) }) { Text(stringResource(CoreR.string.common_ok)) }
            },
        )
    }
}

private enum class DeviceChoice { on, paused, remove }

@Composable
private fun DeviceRow(device: OpenCodePushDevice, isThisPhone: Boolean, send: (PushSettingsFeature.Action) -> Unit) {
    val onTitle = stringResource(R.string.settings_notifications_device_on)
    val pausedTitle = stringResource(R.string.settings_notifications_device_paused)
    val sections = mutableListOf<Pair<String?, List<SettingsMenuOption<DeviceChoice>>>>()
    sections.add(null to listOf(SettingsMenuOption(DeviceChoice.on, onTitle), SettingsMenuOption(DeviceChoice.paused, pausedTitle)))
    if (!isThisPhone) {
        sections.add(null to listOf(SettingsMenuOption(DeviceChoice.remove, stringResource(R.string.settings_notifications_device_remove))))
    }
    val title = if (isThisPhone) stringResource(R.string.settings_notifications_device_this, device.name) else device.name
    SettingsMenuRow(
        title = title,
        value = if (device.paused) pausedTitle else onTitle,
        selection = if (device.paused) DeviceChoice.paused else DeviceChoice.on,
        sections = sections,
        onSelect = { picked ->
            when (picked) {
                DeviceChoice.on -> if (device.paused) send(PushSettingsFeature.Action.DevicePausedChanged(device.id, false))
                DeviceChoice.paused -> if (!device.paused) send(PushSettingsFeature.Action.DevicePausedChanged(device.id, true))
                DeviceChoice.remove -> send(PushSettingsFeature.Action.DeviceRemoved(device))
            }
        },
    )
}

/** The phone's choices the server's levels allow; Only Sessions I Use needs both Everything and Nothing. */
private fun pushModes(levels: List<OpenCodePushLevel>): List<PushMode> {
    val modes = mutableListOf<PushMode>()
    if (OpenCodePushLevel.all in levels) modes.add(PushMode.all)
    if (OpenCodePushLevel.attention in levels) modes.add(PushMode.attention)
    if (OpenCodePushLevel.all in levels && OpenCodePushLevel.none in levels) modes.add(PushMode.fromThisPhone)
    if (OpenCodePushLevel.none in levels) modes.add(PushMode.none)
    return modes
}

@Composable
private fun pushModeTitle(mode: PushMode): String = when (mode) {
    PushMode.all -> stringResource(R.string.settings_notifications_mode_all)
    PushMode.attention -> stringResource(R.string.settings_notifications_mode_attention)
    PushMode.fromThisPhone -> stringResource(R.string.settings_notifications_mode_from_this_phone)
    PushMode.none -> stringResource(R.string.settings_notifications_mode_none)
}

@Composable
private fun pushModeCaption(mode: PushMode): String? = when (mode) {
    PushMode.all -> stringResource(R.string.settings_notifications_mode_all_caption)
    PushMode.attention -> stringResource(R.string.settings_notifications_mode_attention_caption)
    PushMode.fromThisPhone -> stringResource(R.string.settings_notifications_mode_from_this_phone_caption, ThisDeviceName)
    PushMode.none -> null
}

@Composable
private fun pushStatusFooter(status: PushStatus): String? = when (status) {
    PushStatus.Unknown -> stringResource(R.string.settings_notifications_status_checking)
    PushStatus.NoPermission -> stringResource(R.string.settings_notifications_status_no_permission)
    PushStatus.Outdated -> stringResource(R.string.settings_notifications_status_outdated)
    PushStatus.RelayNotAllowed -> stringResource(R.string.settings_notifications_status_relay)
    PushStatus.Failed -> stringResource(R.string.settings_notifications_status_failed)
    PushStatus.Unavailable, PushStatus.Off, PushStatus.Ready -> null
}

@Composable
private fun pushTime(until: Long): String {
    val context = LocalContext.current
    var flags = DateUtils.FORMAT_SHOW_TIME
    if (!DateUtils.isToday(until)) flags = flags or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return DateUtils.formatDateTime(context, until, flags)
}

/** “phone” or “tablet”. */
val ThisDeviceName: String get() = ai.opencode.core.ui.ThisDevice.name
