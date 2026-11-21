package app.throwback.timelimit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.throwback.timelimit.engine.TimeLimitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Where the controller loads and saves the engine state. */
internal interface StateStore {
    /**
     * The saved state, or a blank one (no limit) when nothing is saved. Throws when something is saved
     * but can't be read (an I/O error, or a state this version doesn't understand), so that it isn't
     * taken for "no limit" and written over.
     */
    suspend fun load(): TimeLimitState

    suspend fun save(state: TimeLimitState)
}

/**
 * The `timelimit` DataStore: the file is `files/datastore/timelimit.preferences_pb`, the one the
 * Auto Backup rules include. It holds one key with the whole state as JSON (see [StateCodec]).
 */
internal class DataStoreStateStore(private val dataStore: DataStore<Preferences>) : StateStore {

    override suspend fun load(): TimeLimitState {
        val text = dataStore.data.first()[KEY] ?: return TimeLimitState()
        return StateCodec.decode(text) ?: throw IOException("The saved time-limit state can't be read")
    }

    override suspend fun save(state: TimeLimitState) {
        val text = StateCodec.encode(state.persistent())
        dataStore.edit { it[KEY] = text }
    }

    companion object {
        /** DataStore file name. Must match the Auto Backup rules in res/xml. */
        const val FILE_NAME = "timelimit"
        private val KEY = stringPreferencesKey("state")

        /** Call once per process (TimeLimitModule keeps the single instance). */
        fun create(context: Context): DataStoreStateStore = DataStoreStateStore(
            PreferenceDataStoreFactory.create(
                // An unreadable file means no limit rather than a crash; "clear data" has the same effect.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            ),
        )
    }
}
