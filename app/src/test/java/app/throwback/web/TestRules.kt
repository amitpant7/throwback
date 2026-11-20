package app.throwback.web

import app.throwback.web.rules.FilterRules
import app.throwback.web.rules.RulesParseResult
import app.throwback.web.rules.RulesParser
import java.io.File

/** The real filters/rules.json and small helpers shared by the web tests. */
object TestRules {
    /** Unit tests run with the app module as working directory; the rules live at the repo root. */
    val bundledFile: File = listOf(File("../filters/rules.json"), File("filters/rules.json")).first { it.isFile }

    val bundledText: String by lazy { bundledFile.readText() }

    val bundled: FilterRules by lazy {
        when (val result = RulesParser.parse(bundledText)) {
            is RulesParseResult.Valid -> result.rules
            is RulesParseResult.Invalid -> error("filters/rules.json is invalid: ${result.errors}")
        }
    }

    /** The bundled rules as JSON with [edit] applied to the text, for negative tests. */
    fun bundledWith(edit: (String) -> String): String {
        val edited = edit(bundledText)
        check(edited != bundledText) { "edit did not change the rules" }
        return edited
    }

    fun mainAsset(path: String): File =
        listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path")).first { it.isFile }

    /** The page script as the build joins it (app/build.gradle.kts): the parts in file-name order. */
    val pageScript: String by lazy {
        val dir = listOf(File("src/main/pagescript"), File("app/src/main/pagescript")).first { it.isDirectory }
        dir.listFiles { f -> f.extension == "js" }!!.sortedBy { it.name }.joinToString("") { it.readText() }
    }
}
