package app.throwback.timelimit

import app.throwback.timelimit.data.DataStoreStateStore
import app.throwback.timelimit.ui.Durations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DurationsAndBackupTest {

    @Test
    fun `countdowns round up so they never show zero while time is left`() {
        assertEquals(Durations.MinSec(3, 28), Durations.minSec(3 * MIN + 27 * SEC + 1))
        assertEquals(Durations.MinSec(5, 0), Durations.minSec(5 * MIN))
        assertEquals(Durations.MinSec(0, 1), Durations.minSec(1))
        assertEquals(Durations.MinSec(0, 0), Durations.minSec(0))
        assertEquals(Durations.MinSec(0, 0), Durations.minSec(-5))
    }

    @Test
    fun `hours and minutes`() {
        assertEquals(Durations.HoursMinutes(6, 42), Durations.hoursMinutes(6 * HOUR + 41 * MIN + 1))
        assertEquals(Durations.HoursMinutes(0, 1), Durations.hoursMinutes(SEC))
        assertEquals(Durations.HoursMinutes(1, 0), Durations.hoursMinutes(HOUR))
    }

    @Test
    fun `days and hours only from a day up`() {
        assertEquals(Durations.DaysHours(2, 23), Durations.daysHours(3 * DAY - 30 * MIN))
        assertEquals(Durations.DaysHours(1, 0), Durations.daysHours(DAY))
        assertNull(Durations.daysHours(DAY - 1))
    }

    @Test
    fun `chip shows minutes, then m-ss in the last minute`() {
        assertEquals(Durations.Chip.Minutes(18), Durations.chip(17 * MIN + 30 * SEC))
        assertEquals(Durations.Chip.Minutes(2), Durations.chip(MIN + 1))
        assertEquals(Durations.Chip.LastMinute(Durations.MinSec(1, 0), 60), Durations.chip(MIN))
        assertEquals(Durations.Chip.LastMinute(Durations.MinSec(0, 42), 42), Durations.chip(42 * SEC))
    }

    /** The Auto Backup rules must name exactly the DataStore file the controller writes. */
    @Test
    fun `backup rules include the timelimit DataStore file and nothing else`() {
        val expected = "datastore/${DataStoreStateStore.FILE_NAME}.preferences_pb"
        assertEquals("datastore/timelimit.preferences_pb", expected)
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val file = File("src/main/res/xml/$name")
            assertTrue("$file missing (tests run from the app module)", file.exists())
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val includes = doc.getElementsByTagName("include")
            assertTrue(includes.length > 0)
            for (i in 0 until includes.length) {
                val el = includes.item(i).attributes
                assertEquals(name, "file", el.getNamedItem("domain").nodeValue)
                assertEquals(name, expected, el.getNamedItem("path").nodeValue)
            }
        }
        val extraction = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/data_extraction_rules.xml"))
        assertEquals(1, extraction.getElementsByTagName("cloud-backup").length)
        assertEquals(1, extraction.getElementsByTagName("device-transfer").length)
    }
}
