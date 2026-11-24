package ai.opencode.feature.settings

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.text.Normalizer
import java.util.Locale

/**
 * The app's own language, through Android's per-app language setting, so Settings › Apps › OpenCode ›
 * Language shows the same choice.
 */
class AppLanguageClient(
    /** The languages the app is translated into, from its generated locale config. */
    val supported: () -> List<Locale> = { emptyList() },
    /** The app's language, or null when it follows the system. */
    val current: () -> Locale? = { null },
    /** The phone's languages, in the order set in Android's settings. */
    val systemLocales: () -> List<Locale> = { listOf(Locale.ENGLISH) },
    /** Null follows the system again. Android recreates the activity when the language changes. */
    val set: (Locale?) -> Unit = {},
) {
    companion object Key : DependencyKey<AppLanguageClient> {
        override val liveValue: AppLanguageClient by lazy {
            val context = dependency(AppContext)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Android 12 has no per-app LocaleManager: follow the system language.
                AppLanguageClient(systemLocales = { listOf(Locale.getDefault()) })
            } else {
                api33Client(context)
            }
        }
        override val testValue: AppLanguageClient = AppLanguageClient()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun api33Client(context: Context): AppLanguageClient {
    val manager = context.getSystemService(LocaleManager::class.java)
    return AppLanguageClient(
        supported = {
            val locales = LocaleConfig(context).supportedLocales
            val list = mutableListOf<Locale>()
            if (locales != null) {
                for (index in 0 until locales.size()) list.add(locales.get(index))
            }
            list
        },
        current = {
            val locales = manager.applicationLocales
            if (locales.isEmpty) null else locales.get(0)
        },
        systemLocales = {
            val locales = manager.systemLocales
            val list = mutableListOf<Locale>()
            for (index in 0 until locales.size()) list.add(locales.get(index))
            if (list.isEmpty()) list.add(Locale.getDefault())
            list
        },
        set = { locale ->
            manager.applicationLocales = if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        },
    )
}

/** The language's name in itself (“Deutsch”, “日本語”, “简体中文”). */
val Locale.nativeName: String get() = languageName(this)

fun Locale.languageName(display: Locale): String {
    if (language == "zh") {
        val script = ULocale.addLikelySubtags(ULocale.forLocale(this)).script
        return Locale.Builder().setLanguage(language).setScript(script).build().getDisplayScript(display)
    }
    return getDisplayName(display).replaceFirstChar { it.titlecase(display) }
}

object AppLanguages {
    fun suggested(system: List<Locale>, supported: List<Locale>): List<Locale> {
        val result = mutableListOf<Locale>()
        for (wanted in system) {
            for (language in supported) {
                if (covers(language, wanted)) {
                    if (language !in result) result.add(language)
                    break
                }
            }
        }
        return result
    }

    fun matching(languages: List<Locale>, query: String, appLocale: Locale): List<Locale> {
        val wanted = folded(query.trim())
        if (wanted.isEmpty()) return languages
        val result = mutableListOf<Locale>()
        for (language in languages) {
            val names = listOf(
                language.nativeName,
                language.languageName(appLocale),
                language.languageName(Locale.ENGLISH),
                language.getDisplayLanguage(appLocale),
                language.getDisplayLanguage(Locale.ENGLISH),
                language.toLanguageTag(),
            )
            for (name in names) {
                if (folded(name).contains(wanted)) {
                    result.add(language)
                    break
                }
            }
        }
        return result
    }

    private fun covers(supported: Locale, wanted: Locale): Boolean {
        val full = ULocale.addLikelySubtags(ULocale.forLocale(supported))
        val wantedFull = ULocale.addLikelySubtags(ULocale.forLocale(wanted))
        if (full.language != wantedFull.language || full.script != wantedFull.script) return false
        return supported.country.isEmpty() || supported.country == wanted.country
    }

    private fun folded(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}
