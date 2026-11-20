package io.github.wilsonnet.droidsentenceminer.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.wilsonnet.droidsentenceminer.lang.LookupFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The language lookup options ([io.github.wilsonnet.droidsentenceminer.lang.LookupOption]) the
 * user turned off. Feature names are global (each option belongs to one language), so one set
 * serves every language.
 */
class LookupOptionPrefs(private val context: Context) {
    private val key = stringSetPreferencesKey("lookup_options_off")

    /** The switched-off features. */
    val disabled: Flow<Set<LookupFeature>> =
        context.dataStore.data.map { prefs ->
            prefs[key].orEmpty().mapNotNull { name -> LookupFeature.entries.firstOrNull { it.name == name } }.toSet()
        }

    /** Turns [feature] on or off. */
    suspend fun setEnabled(feature: LookupFeature, isEnabled: Boolean) {
        context.dataStore.edit { prefs ->
            val off = prefs[key].orEmpty()
            prefs[key] = if (isEnabled) off - feature.name else off + feature.name
        }
    }
}
