package app.throwback.web.rules

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.throwback.web.webDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Where installed apps fetch the latest filter rules: filters/rules.json on the public repository's
 * default branch, checked at most once a day (DECISIONS.md #5: nothing else leaves the phone). A copy
 * that fails validation, has another schema or can't be fetched changes nothing: the app keeps its
 * cached or bundled rules. Set it to "" to never fetch.
 */
const val RULES_URL = "https://raw.githubusercontent.com/amitpant7/throwback/main/filters/rules.json"

/** How often the remote rules are checked (DECISIONS.md #10: at most once a day). */
const val RULES_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

object RulesSchedule {
    /**
     * Whether a remote check is due. A last-check time in the future (the clock was moved back)
     * counts as due, so changing the clock can't stop updates for long.
     */
    fun isFetchDue(lastCheckMillis: Long?, nowMillis: Long, intervalMillis: Long = RULES_CHECK_INTERVAL_MILLIS): Boolean {
        if (lastCheckMillis == null) return true
        if (lastCheckMillis > nowMillis + CLOCK_SKEW_MILLIS) return true
        return nowMillis - lastCheckMillis >= intervalMillis
    }

    private const val CLOCK_SKEW_MILLIS = 60L * 60 * 1000
}

/**
 * Supplies the filter rules: the downloaded copy in filesDir when it is valid and newer than the
 * bundled one, otherwise the copy bundled in the APK (assets/filters/rules.json, copied from
 * filters/rules.json at build time). A remote copy replaces the cache only after validation.
 */
class RulesRepository internal constructor(
    private val readBundled: () -> String?,
    private val cacheFile: File,
    private val store: DataStore<Preferences>,
    private val fetcher: RulesFetcher,
    private val rulesUrl: String,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _rules = MutableStateFlow<FilterRules?>(null)

    /** Null until [load] finishes; never null afterwards. */
    val rules: StateFlow<FilterRules?> = _rules.asStateFlow()

    private val mutex = Mutex()

    /**
     * Whether [load] found a valid downloaded copy. The stored ETag describes that copy: without it,
     * a "not modified" answer would keep the app on its bundled rules for good.
     */
    @Volatile
    private var cacheUsable = false

    /** Loads the best local rules. Cheap after the first call. */
    suspend fun load(): FilterRules = mutex.withLock {
        _rules.value?.let { return it }
        val chosen = withContext(io) {
            val bundled = RulesParser.parse(readBundled()).also { logInvalid("bundled", it) }.validOrNull()
            val cached = readCache()
            cacheUsable = cached != null
            RulesSelection.choose(bundled, cached)
        } ?: FallbackRules.rules.also { Log.e(TAG, "No usable filter rules; using the built-in minimum") }
        _rules.value = chosen
        chosen
    }

    /** Checks the remote rules if a day has passed since the last check. Never throws. */
    suspend fun refreshIfDue() {
        if (rulesUrl.isBlank() || !rulesUrl.startsWith("https://")) return
        load()
        try {
            val prefs = store.data.first()
            if (!RulesSchedule.isFetchDue(prefs[KEY_LAST_CHECK], now())) return
            // An ETag without the copy it names goes (the cache is missing or invalid).
            val etag = prefs[KEY_ETAG]?.takeIf { cacheUsable }
            // Record the attempt first: a failing server must not be retried on every launch.
            store.edit {
                it[KEY_LAST_CHECK] = now()
                if (etag == null) it.remove(KEY_ETAG)
            }
            when (val result = fetcher.fetch(rulesUrl, etag)) {
                is FetchResult.NotModified -> Unit
                is FetchResult.Failed -> Log.i(TAG, "Rules check failed: ${result.reason}")
                is FetchResult.Fetched -> keepIfNewer(result)
            }
        } catch (e: IOException) { // DataStore's CorruptionException is one
            Log.w(TAG, "Rules check skipped", e)
        }
    }

    /** Validates a downloaded copy (off the main thread) and keeps it if it is newer than the rules in use. */
    private suspend fun keepIfNewer(result: FetchResult.Fetched) {
        when (val parsed = withContext(io) { RulesParser.parse(result.body) }) {
            is RulesParseResult.Invalid -> Log.w(TAG, "Remote rules rejected: ${parsed.errors.take(5)}")
            is RulesParseResult.Valid -> {
                val written = mutex.withLock {
                    val inUse = _rules.value?.version ?: 0L
                    if (parsed.rules.version <= inUse) return
                    withContext(io) { writeCache(result.body) }.also { ok ->
                        _rules.value = parsed.rules
                        cacheUsable = ok
                        Log.i(TAG, "Filter rules updated to ${parsed.rules.version}")
                    }
                }
                // Only an ETag whose copy is on disk: else "not modified" would stop updates for good.
                if (written) result.etag?.let { etag -> store.edit { it[KEY_ETAG] = etag } }
            }
        }
    }

    private fun readCache(): FilterRules? {
        if (!cacheFile.isFile) return null
        val text = try {
            cacheFile.readText()
        } catch (e: IOException) {
            return null
        }
        return RulesParser.parse(text).also { logInvalid("cached", it) }.validOrNull()
    }

    /** Write-then-rename, so a crash mid-write can never leave a half file behind. False if it failed. */
    private fun writeCache(text: String): Boolean = try {
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(cacheFile) || run {
            cacheFile.delete()
            tmp.renameTo(cacheFile)
        }
    } catch (e: IOException) {
        Log.w(TAG, "Could not cache rules", e)
        false
    }

    private fun RulesParseResult.validOrNull() = (this as? RulesParseResult.Valid)?.rules

    private fun logInvalid(which: String, result: RulesParseResult) {
        if (result is RulesParseResult.Invalid && result.errors != listOf("empty")) {
            Log.w(TAG, "Ignoring $which rules: ${result.errors.take(5)}")
        }
    }

    companion object {
        private const val TAG = "ThrowbackRules"
        private const val BUNDLED_ASSET = "filters/rules.json"
        private val KEY_LAST_CHECK = longPreferencesKey("rules_last_check")
        private val KEY_ETAG = stringPreferencesKey("rules_etag")

        @Volatile
        private var instance: RulesRepository? = null

        /** The app-wide repository. */
        fun get(context: Context): RulesRepository = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context) = RulesRepository(
            readBundled = {
                try {
                    app.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
                } catch (e: IOException) {
                    null
                }
            },
            cacheFile = File(app.filesDir, "filters/rules.json"),
            store = app.webDataStore,
            fetcher = HttpRulesFetcher(),
            rulesUrl = RULES_URL,
            now = System::currentTimeMillis,
        )
    }
}

/**
 * Last-resort rules if both the bundled and the cached file are unusable (should never happen: a
 * unit test validates the bundled file). Keeps the essentials: the home feed, no Reels pages,
 * messages-only mode.
 */
internal object FallbackRules {
    val rules = FilterRules(
        schema = SUPPORTED_RULES_SCHEMA,
        version = 1,
        homeUrl = "https://www.instagram.com/",
        homeVariants = listOf("home", "following", "favorites"),
        messagesUrl = "https://www.instagram.com/direct/inbox/",
        hosts = Hosts(
            instagram = listOf("instagram.com", "www.instagram.com"),
            login = listOf("facebook.com", "*.facebook.com"),
            loginPaths = listOf(
                "^/(login|checkpoint|recover|two_step_verification|two_factor|auth_platform|dialog|privacy/consent|cookie|r|reg|confirmemail|security)(/|\\.php|$)",
                "^/v[0-9.]+/dialog/",
            ),
            linkShims = listOf(LinkShim("l.instagram.com", "u")),
        ),
        paths = Paths(
            blocked = listOf(BlockedPath(pattern = "^/reels?(/|$)", kind = "reels")),
            alwaysAllowed = listOf("^/accounts/(login|onetap|password|two_factor)(/|$)", "^/challenge(/|$)"),
            auth = listOf("^/accounts/", "^/challenge(/|$)"),
            messagesOnly = listOf("^/direct(/|$)"),
        ),
        hide = listOf(HideRule(id = "reels-links", selector = "a[href^='/reels/'], a[href$='/reels/']")),
    )
}
