package app.throwback.timelimit

import app.throwback.timelimit.data.DataStoreStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {

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
