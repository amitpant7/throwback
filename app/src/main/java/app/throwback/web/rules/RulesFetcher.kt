package app.throwback.web.rules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface FetchResult {
    data class Fetched(val body: String, val etag: String?) : FetchResult
    data object NotModified : FetchResult
    data class Failed(val reason: String) : FetchResult
}

fun interface RulesFetcher {
    suspend fun fetch(url: String, etag: String?): FetchResult
}

/**
 * Plain HttpURLConnection: no extra dependency, no cookies, and a fixed User-Agent instead of the
 * default one (which names the Android version and the phone model).
 */
internal class HttpRulesFetcher(
    private val timeoutMillis: Int = 15_000,
    private val maxBytes: Int = 512 * 1024,
) : RulesFetcher {

    override suspend fun fetch(url: String, etag: String?): FetchResult = withContext(Dispatchers.IO) {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext FetchResult.Failed("open: ${e.message}")
        }
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Throwback")
            connection.setRequestProperty("Accept", "application/json")
            etag?.let { connection.setRequestProperty("If-None-Match", it) }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > maxBytes) return@withContext FetchResult.Failed("too large")
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                    FetchResult.Fetched(body, connection.getHeaderField("ETag"))
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult.NotModified
                else -> FetchResult.Failed("HTTP $code")
            }
        } catch (e: IOException) {
            FetchResult.Failed(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}
