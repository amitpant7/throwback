package app.throwback.web

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** throwback.js and throwback.css from assets/inject/, read once per process. */
internal class PageAssets(val template: String, val css: String) {
    companion object {
        @Volatile
        private var cached: PageAssets? = null

        /** Already loaded (MainActivity reads it as it starts), or null. */
        fun loadedOrNull(): PageAssets? = cached

        suspend fun load(context: Context): PageAssets = cached ?: withContext(Dispatchers.IO) {
            val assets = context.applicationContext.assets
            fun read(name: String) = assets.open(name).bufferedReader().use { it.readText() }
            PageAssets(read("inject/throwback.js"), read("inject/throwback.css")).also { cached = it }
        }
    }
}
