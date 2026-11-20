package app.throwback.web

import app.throwback.core.WebMode
import app.throwback.web.rules.FilterRules
import app.throwback.web.rules.RulesJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the script injected into Instagram pages: assets/inject/throwback.js with its
 * configuration (mode, rules, base CSS) substituted for [CONFIG_TOKEN].
 *
 * The configuration is re-encoded from the validated [FilterRules] model, never pasted from the
 * downloaded text, so only known, checked fields reach the page and the result is always valid JS.
 */
internal object PageScript {
    const val CONFIG_TOKEN = "__THROWBACK_CONFIG__"

    fun build(template: String, css: String, rules: FilterRules, mode: WebMode): String {
        require(CONFIG_TOKEN in template) { "throwback.js has no $CONFIG_TOKEN" }
        return template.replace(CONFIG_TOKEN, config(rules = rules, css = css, mode = mode))
    }

    /** The argument for `window.__throwback.configure(...)`; any part may be left out. */
    fun config(rules: FilterRules? = null, css: String? = null, mode: WebMode? = null, clock: String? = PageClock.json()): String {
        val fields = linkedMapOf<String, JsonElement>()
        clock?.let { fields["clock"] = kotlinx.serialization.json.Json.parseToJsonElement(it) }
        fields["createInApp"] = JsonPrimitive(PageFeatures.createInApp)
        fields["storyInApp"] = JsonPrimitive(PageFeatures.storyInApp)
        fields["supportAsk"] = JsonPrimitive(PageFeatures.supportAsk)
        mode?.let { fields["mode"] = JsonPrimitive(it.name) }
        css?.let { fields["css"] = JsonPrimitive(it) }
        rules?.let { fields["rules"] = RulesJson.encodeToJsonElement(FilterRules.serializer(), it) }
        return jsSafe(JsonObject(fields).toString())
    }

    /**
     * JSON is valid JavaScript except that engines before ES2019 reject raw U+2028/U+2029 inside
     * string literals; they can only occur inside strings, so escaping them is always safe.
     */
    private fun jsSafe(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
