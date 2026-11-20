package app.throwback.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewSetupTest {

    @Test
    fun `user agent loses the WebView markers`() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36",
            WebViewConfig.cleanUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36",
            ),
        )
        assertEquals(
            "Mozilla/5.0 (Linux; Android 8.0.0; SM-G950F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            WebViewConfig.cleanUserAgent(
                "Mozilla/5.0 (Linux; Android 8.0.0; SM-G950F Build/R16NW; wv) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36",
            ),
        )
    }

    @Test
    fun `a chrome user agent is left alone`() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, WebViewConfig.cleanUserAgent(chrome))
    }

    @Test
    fun `wv only goes when it is the WebView token`() {
        assertEquals("Mozilla/5.0 (Linux; Android 14; wvx)", WebViewConfig.cleanUserAgent("Mozilla/5.0 (Linux; Android 14; wvx)"))
        assertEquals("Mozilla/5.0 (Linux; Android 14)", WebViewConfig.cleanUserAgent("Mozilla/5.0 (Linux; Android 14; wv)"))
    }

    @Test
    fun `file chooser accept types`() {
        assertEquals(PickKind.IMAGES, FileChooserKinds.classify(arrayOf("image/*")))
        assertEquals(PickKind.IMAGES, FileChooserKinds.classify(arrayOf("image/jpeg,image/png", ".heic")))
        assertEquals(PickKind.VIDEOS, FileChooserKinds.classify(arrayOf("video/mp4", "video/quicktime")))
        assertEquals(
            PickKind.IMAGES_AND_VIDEOS,
            FileChooserKinds.classify(arrayOf("image/jpeg,image/png,image/heic,image/heif,video/mp4,video/quicktime")),
        )
        assertEquals(PickKind.OTHER, FileChooserKinds.classify(arrayOf("")))
        assertEquals(PickKind.OTHER, FileChooserKinds.classify(null))
        assertEquals(PickKind.OTHER, FileChooserKinds.classify(arrayOf("*/*")))
        assertEquals(PickKind.OTHER, FileChooserKinds.classify(arrayOf("image/*", "application/pdf")))
    }
}
