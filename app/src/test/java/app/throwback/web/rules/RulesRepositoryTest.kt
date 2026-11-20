package app.throwback.web.rules

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.throwback.web.TestRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RulesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var cacheFile: File
    private var now = 1_000_000_000_000L
    private val fetchCalls = mutableListOf<String?>()
    private var nextFetch: FetchResult = FetchResult.Failed("not set")

    private val bundledText = TestRules.bundledText
    private val bundledVersion = TestRules.bundled.version

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cacheFile = File(tmp.root, "files/filters/rules.json")
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun repository(bundled: String? = bundledText, url: String = "https://example.org/rules.json") = RulesRepository(
        readBundled = { bundled },
        cacheFile = cacheFile,
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "web.preferences_pb") },
        fetcher = { _, etag ->
            fetchCalls += etag
            nextFetch
        },
        rulesUrl = url,
        now = { now },
        io = Dispatchers.Unconfined,
    )

    private fun rulesText(version: Long) = bundledText.replaceFirst("\"version\": $bundledVersion", "\"version\": $version")

    private fun writeCache(text: String) {
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeText(text)
    }

    @Test
    fun `uses the bundled rules when there is no cache`() = runTest {
        assertEquals(bundledVersion, repository().load().version)
    }

    @Test
    fun `uses a newer valid cache`() = runTest {
        writeCache(rulesText(bundledVersion + 5))
        assertEquals(bundledVersion + 5, repository().load().version)
    }

    @Test
    fun `ignores an older cache`() = runTest {
        writeCache(rulesText(bundledVersion - 5))
        assertEquals(bundledVersion, repository().load().version)
    }

    @Test
    fun `ignores a corrupt cache`() = runTest {
        writeCache("{ \"schema\": 1, \"version\": 999999999999")
        assertEquals(bundledVersion, repository().load().version)
    }

    @Test
    fun `falls back to the built-in minimum when nothing is usable`() = runTest {
        assertSame(FallbackRules.rules, repository(bundled = "garbage").load())
    }

    @Test
    fun `never fetches while RULES_URL is empty`() = runTest {
        repository(url = "").refreshIfDue()
        assertTrue(fetchCalls.isEmpty())
        assertEquals("", RULES_URL) // flip this assertion once the repository URL is set
    }

    @Test
    fun `refuses plain http`() = runTest {
        repository(url = "http://example.org/rules.json").refreshIfDue()
        assertTrue(fetchCalls.isEmpty())
    }

    @Test
    fun `a valid newer remote copy replaces the cache and the live rules`() = runTest {
        val repo = repository()
        nextFetch = FetchResult.Fetched(rulesText(bundledVersion + 1), etag = "\"abc\"")
        repo.refreshIfDue()
        assertEquals(1, fetchCalls.size)
        assertEquals(bundledVersion + 1, repo.rules.value?.version)
        assertTrue(cacheFile.readText().contains("${bundledVersion + 1}"))
        assertFalse(File(cacheFile.parentFile, "rules.json.tmp").exists())

        // A new repository (next app start) picks the cached copy up.
        assertEquals(bundledVersion + 1, repository().load().version)
    }

    @Test
    fun `fetches at most once a day and sends the ETag`() = runTest {
        val repo = repository()
        nextFetch = FetchResult.Fetched(rulesText(bundledVersion + 1), etag = "\"abc\"")
        repo.refreshIfDue()
        now += 60_000
        repo.refreshIfDue()
        assertEquals(listOf<String?>(null), fetchCalls)

        now += RULES_CHECK_INTERVAL_MILLIS
        nextFetch = FetchResult.NotModified
        repo.refreshIfDue()
        assertEquals(listOf(null, "\"abc\""), fetchCalls)
        assertEquals(bundledVersion + 1, repo.rules.value?.version)
    }

    @Test
    fun `failed checks also wait a day`() = runTest {
        val repo = repository()
        nextFetch = FetchResult.Failed("HTTP 500")
        repo.refreshIfDue()
        repo.refreshIfDue()
        assertEquals(1, fetchCalls.size)
        assertEquals(bundledVersion, repo.rules.value?.version)
    }

    @Test
    fun `an invalid remote copy is ignored`() = runTest {
        val repo = repository()
        nextFetch = FetchResult.Fetched(rulesText(bundledVersion + 1).replace("\"schema\": 1", "\"schema\": 7"), etag = null)
        repo.refreshIfDue()
        assertEquals(bundledVersion, repo.rules.value?.version)
        assertFalse(cacheFile.exists())
    }

    @Test
    fun `an older remote copy does not replace newer rules`() = runTest {
        val repo = repository()
        nextFetch = FetchResult.Fetched(rulesText(bundledVersion - 1), etag = null)
        repo.refreshIfDue()
        assertEquals(bundledVersion, repo.rules.value?.version)
        assertFalse(cacheFile.exists())
    }
}
