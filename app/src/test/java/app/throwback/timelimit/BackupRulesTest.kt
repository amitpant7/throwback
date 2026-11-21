package app.throwback.timelimit

import app.throwback.core.Supporter
import app.throwback.timelimit.data.DataStoreStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {

    /**
     * The Auto Backup rules must name exactly the DataStore file the time limit writes and the
     * supporter flag's preferences file (core/Supporter), and nothing else: in each section.
     */
    @Test
    fun `backup rules include the timelimit DataStore and the supporter flag and nothing else`() {
        val timeLimit = "datastore/${DataStoreStateStore.FILE_NAME}.preferences_pb"
        assertEquals("datastore/timelimit.preferences_pb", timeLimit)
        val expected = setOf("file" to timeLimit, "sharedpref" to "${Supporter.PREFS}.xml")
        val sections = mapOf(
            "backup_rules.xml" to listOf("full-backup-content"),
            "data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"),
        )
        for ((name, tags) in sections) {
            val file = File("src/main/res/xml/$name")
            assertTrue("$file missing (tests run from the app module)", file.exists())
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            assertEquals(name, expected.size * tags.size, doc.getElementsByTagName("include").length)
            for (tag in tags) {
                val section = doc.getElementsByTagName(tag)
                assertEquals("$name <$tag>", 1, section.length)
                val includes = (section.item(0) as Element).getElementsByTagName("include")
                val found = (0 until includes.length).map {
                    val el = includes.item(it).attributes
                    el.getNamedItem("domain").nodeValue to el.getNamedItem("path").nodeValue
                }.toSet()
                assertEquals("$name <$tag>", expected, found)
            }
        }
    }
}
