package ai.opencode.core.experiments

import ai.opencode.core.sharing.SharedKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the drawer button says about other sessions while the app is open. Off, and no setting
 * changes it. `shows(kind)` stays with the drawer's notice kinds.
 */
@Serializable
enum class InAppNoticeMode(val rawValue: String) {
    @SerialName("off") off("off"),
    @SerialName("needs-input") needsInput("needs-input"),
    @SerialName("all") all("all");

    val id: String get() = rawValue

    /** Off wherever the drawer button can't show the label (beside a large-screen sidebar). */
    fun effective(showsDrawerLabel: Boolean): InAppNoticeMode = if (showsDrawerLabel) this else off

    companion object {
        val key: SharedKey<InAppNoticeMode> by lazy { SharedKey.appStorage("experimental-in-app-notices", off) }
    }
}
