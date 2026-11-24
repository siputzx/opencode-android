package ai.opencode.feature.settings

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import android.content.pm.PackageManager

/**
 * What's running, “Version 0.2.3 (226) · ec7d822”. Android reads the package's version name and
 * code; the commit comes from the `opencode.commit` manifest meta-data when the app stamps one.
 */
data class AppVersion(val version: String, val build: String, val commit: String?) {
    val label: String get() = "Version $version ($build)" + (commit?.let { " · $it" } ?: "")

    /** [label] in the app's language, for the Settings footer ([label] stays English for the launch log). */
    val displayLabel: String
        get() {
            if (commit == null) return localized(R.string.settings_app_version, version, build)
            return localized(R.string.settings_app_version_commit, version, build, commit)
        }

    companion object {
        val current: AppVersion by lazy { read() }

        private fun read(): AppVersion = runCatching {
            val context = dependency(AppContext)
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
            val commit = info.applicationInfo?.metaData?.getString("opencode.commit")?.takeIf { it.isNotEmpty() }
            AppVersion(info.versionName ?: "?", info.longVersionCode.toString(), commit)
        }.getOrElse { AppVersion("?", "?", null) }
    }
}
