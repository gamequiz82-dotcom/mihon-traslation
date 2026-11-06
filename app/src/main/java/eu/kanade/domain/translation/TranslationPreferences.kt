package eu.kanade.domain.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(preferenceStore: PreferenceStore) {
    val apiKey = preferenceStore.getString(Preference.appStateKey("translation_api_key"), "")
    val targetLanguage = preferenceStore.getString("translation_target_language", "Arabic")
}
