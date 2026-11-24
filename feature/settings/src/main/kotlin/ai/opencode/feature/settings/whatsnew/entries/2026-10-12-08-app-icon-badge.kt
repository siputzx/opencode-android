package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.appIconBadge: WhatsNewEntry
    get() = feature(
        id = "app-icon-badge",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_app_icon_badge_title),
        symbol = "app.badge",
        tint = Tint.red,
        summary = localized(R.string.settings_whatsnew_entry_app_icon_badge_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_app_icon_badge_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_app_icon_badge_example),
        tryIt = Destination.notifications,
    )
