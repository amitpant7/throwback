package app.throwback.web

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** The web area's own DataStore file (docs/ARCHITECTURE.md: one file per area, never shared). */
internal val Context.webDataStore: DataStore<Preferences> by preferencesDataStore(name = "web")
