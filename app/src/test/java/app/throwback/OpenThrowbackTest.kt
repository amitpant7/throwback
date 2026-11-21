package app.throwback

import app.throwback.core.OpenThrowback
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenThrowbackTest {

    @Test
    fun namesTheMainActivity() {
        assertEquals(MainActivity::class.java.name, OpenThrowback.MAIN_ACTIVITY)
    }
}
