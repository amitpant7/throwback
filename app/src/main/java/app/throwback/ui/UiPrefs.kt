package app.throwback.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import app.throwback.core.UiPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One DataStore per process for the ui area (file `ui_prefs`, see docs/ARCHITECTURE.md). */
private val Context.uiPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = UiPrefs.FILE_NAME)

/** Extra Instagram notification types the user can opt into. Messages and calls are always on. */
internal enum class ExtraNotification(val key: String) {
    LIKES("notify_likes"),
    COMMENTS("notify_comments"),
    NEW_FOLLOWERS("notify_new_followers"),
    STORY_MENTIONS_AND_TAGS("notify_story_mentions_and_tags"),
    LIVE_VIDEOS("notify_live_videos"),
    ;

    val prefKey: Preferences.Key<Boolean> get() = booleanPreferencesKey(key)
}

/**
 * The ui area's persisted flags. Implements the [UiPreferences] contract and adds what the
 * ui screens need internally. Everything defaults to off / not done.
 */
internal class UiPrefs(private val store: DataStore<Preferences>) : UiPreferences {

    private val data: Flow<Preferences> = store.data.catch { e ->
        // A corrupt or unreadable file falls back to defaults instead of crashing the app.
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private fun flag(key: Preferences.Key<Boolean>): Flow<Boolean> =
        data.map { it[key] ?: false }.distinctUntilChanged()

    override val onboardingDone: Flow<Boolean> = flag(ONBOARDING_DONE)
    /** On unless turned off (owner: default on; Instagram loads underneath, so it costs no time). */
    override val mindfulPause: Flow<Boolean> = data.map { it[MINDFUL_PAUSE] ?: true }.distinctUntilChanged()

    /** The "Instagram app found" choice has been shown once. */
    val instagramPromptShown: Flow<Boolean> = flag(INSTAGRAM_PROMPT_SHOWN)

    /** The user finished the Messages & calls wizard (and hasn't turned the feature off). */
    val messagesSetupDone: Flow<Boolean> = flag(MESSAGES_SETUP_DONE)

    /** The one-time "how to open Throwback's settings" hint was shown (or settings were found anyway). */
    val settingsHintShown: Flow<Boolean> = flag(SETTINGS_HINT_SHOWN)

    /** Extra notification types the user turned on. Empty by default. */
    val extraNotifications: Flow<Set<ExtraNotification>> = data.map { prefs ->
        ExtraNotification.entries.filterTo(mutableSetOf()) { prefs[it.prefKey] ?: false }
    }.distinctUntilChanged()

    override suspend fun setOnboardingDone() = set(ONBOARDING_DONE, true)

    suspend fun setMindfulPause(enabled: Boolean) = set(MINDFUL_PAUSE, enabled)

    suspend fun setInstagramPromptShown() = set(INSTAGRAM_PROMPT_SHOWN, true)

    suspend fun setMessagesSetupDone(done: Boolean) = set(MESSAGES_SETUP_DONE, done)

    suspend fun setSettingsHintShown() = set(SETTINGS_HINT_SHOWN, true)

    suspend fun setExtraNotification(type: ExtraNotification, enabled: Boolean) = set(type.prefKey, enabled)

    /**
     * Fire-and-forget write for UI callbacks. Runs in a process-wide scope so the write still
     * happens if the screen that asked for it closes straight away.
     */
    fun write(block: suspend UiPrefs.() -> Unit) {
        writeScope.launch { block() }
    }

    private suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    companion object {
        const val FILE_NAME = "ui_prefs"

        private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val MINDFUL_PAUSE = booleanPreferencesKey("mindful_pause")
        val INSTAGRAM_PROMPT_SHOWN = booleanPreferencesKey("instagram_prompt_shown_after_login")
        val MESSAGES_SETUP_DONE = booleanPreferencesKey("messages_setup_done")
        val SETTINGS_HINT_SHOWN = booleanPreferencesKey("settings_hint_shown")

        /** Cheap wrapper around the process-wide DataStore; safe to call from any screen. */
        fun from(context: Context): UiPrefs = UiPrefs(context.applicationContext.uiPrefsDataStore)
    }
}
