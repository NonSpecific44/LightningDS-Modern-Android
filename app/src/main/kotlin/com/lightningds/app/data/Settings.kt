package com.lightningds.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TextAlign { START, CENTER }

data class AppSettings(
    val rendererUdn: String? = null,
    val serverUdn: String? = null,
    val sortDescending: Boolean = false,
    val libraryCategory: String = "ALBUMS",
    val keepScreenOn: Boolean = false,
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val textAlign: TextAlign = TextAlign.START,
    val qobuzAppId: String = "",
    val tidalClientId: String = "",
    val tidalClientSecret: String = "",
    val qobuzSession: String? = null,
    val tidalSession: String? = null,
    val streamingService: String = "TIDAL",
    /** Renderers the user already unlocked with their passcode, as "udn=passcode" entries. */
    val unlocked: Set<String> = emptySet(),
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val renderer = stringPreferencesKey("renderer_udn")
        val server = stringPreferencesKey("server_udn")
        val sortDesc = booleanPreferencesKey("sort_desc")
        val category = stringPreferencesKey("library_category")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val align = stringPreferencesKey("text_align")
        val qobuzAppId = stringPreferencesKey("qobuz_app_id")
        val tidalClientId = stringPreferencesKey("tidal_client_id")
        val tidalClientSecret = stringPreferencesKey("tidal_client_secret")
        val qobuzSession = stringPreferencesKey("qobuz_session")
        val tidalSession = stringPreferencesKey("tidal_session")
        val streaming = stringPreferencesKey("streaming_service")
        val unlocked = androidx.datastore.preferences.core.stringSetPreferencesKey("unlocked")
    }

    private fun Preferences.toSettings() = AppSettings(
        rendererUdn = this[K.renderer],
        serverUdn = this[K.server],
        sortDescending = this[K.sortDesc] ?: false,
        libraryCategory = this[K.category] ?: "ALBUMS",
        keepScreenOn = this[K.keepOn] ?: false,
        onboardingDone = this[K.onboarding] ?: false,
        themeMode = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[K.dynamic] ?: true,
        textAlign = this[K.align]?.let { runCatching { TextAlign.valueOf(it) }.getOrNull() } ?: TextAlign.START,
        qobuzAppId = this[K.qobuzAppId].orEmpty(),
        tidalClientId = this[K.tidalClientId].orEmpty(),
        tidalClientSecret = this[K.tidalClientSecret].orEmpty(),
        qobuzSession = this[K.qobuzSession],
        tidalSession = this[K.tidalSession],
        streamingService = this[K.streaming] ?: "TIDAL",
        unlocked = this[K.unlocked].orEmpty(),
    )

    /** Loaded synchronously once at start so the first frame has the right theme and device. */
    private val initial: AppSettings = runBlocking { context.dataStore.data.first().toSettings() }

    val settings: StateFlow<AppSettings> = context.dataStore.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    val current: AppSettings get() = settings.value

    private val writeScope = scope

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit(block) }
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    fun setRenderer(udn: String?) = edit { it.setOrRemove(K.renderer, udn) }
    fun setServer(udn: String?) = edit { it.setOrRemove(K.server, udn) }
    fun setSortDescending(v: Boolean) = edit { it[K.sortDesc] = v }
    fun setLibraryCategory(v: String) = edit { it[K.category] = v }
    fun setKeepScreenOn(v: Boolean) = edit { it[K.keepOn] = v }
    fun setOnboardingDone(v: Boolean) = edit { it[K.onboarding] = v }
    fun setThemeMode(v: ThemeMode) = edit { it[K.theme] = v.name }
    fun setDynamicColor(v: Boolean) = edit { it[K.dynamic] = v }
    fun setTextAlign(v: TextAlign) = edit { it[K.align] = v.name }
    fun setQobuzAppId(v: String) = edit { it[K.qobuzAppId] = v.trim() }
    fun setTidalClient(id: String, secret: String) = edit { it[K.tidalClientId] = id.trim(); it[K.tidalClientSecret] = secret.trim() }
    fun setQobuzSession(json: String?) = edit { it.setOrRemove(K.qobuzSession, json) }
    fun setTidalSession(json: String?) = edit { it.setOrRemove(K.tidalSession, json) }
    fun setStreamingService(v: String) = edit { it[K.streaming] = v }

    fun setUnlocked(udn: String, passcode: String?) = edit { prefs ->
        val others = prefs[K.unlocked].orEmpty().filterNot { it.startsWith("$udn=") }.toSet()
        prefs[K.unlocked] = if (passcode == null) others else others + "$udn=$passcode"
    }

    fun unlockedPasscode(udn: String): String? = current.unlocked.firstOrNull { it.startsWith("$udn=") }?.substringAfter('=')
}
