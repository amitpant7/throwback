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

    /** [clock]: the trusted clock read now (PageClock.json()), or null to start the page without one. */
    fun build(template: String, css: String, rules: FilterRules, mode: WebMode, clock: JsonObject?, features: PageFeatures): String {
        require(CONFIG_TOKEN in template) { "throwback.js has no $CONFIG_TOKEN" }
        return template.replace(CONFIG_TOKEN, config(rules = rules, css = css, mode = mode, clock = clock, features = features))
    }

    /**
     * The argument for `window.__throwback.configure(...)`; any part may be left out, and the page
     * keeps what a config leaves out.
     */
    fun config(
        rules: FilterRules? = null,
        css: String? = null,
        mode: WebMode? = null,
        clock: JsonObject? = null,
        features: PageFeatures? = null,
    ): String {
        val fields = linkedMapOf<String, JsonElement>()
        clock?.let { fields["clock"] = it }
        features?.let { fields.putAll(it.configFields()) }
        mode?.let { fields["mode"] = JsonPrimitive(it.name) }
        css?.let { fields["css"] = JsonPrimitive(it) }
        rules?.let { fields["rules"] = encoded(it) }
        return jsSafe(JsonObject(fields).toString())
    }

    /** The last rules encoded, by instance: the script is rebuilt for every new clock, the rules rarely change. */
    @Volatile
    private var lastEncoded: Pair<FilterRules, JsonElement>? = null

    private fun encoded(rules: FilterRules): JsonElement {
        lastEncoded?.let { (r, json) -> if (r === rules) return json }
        return RulesJson.encodeToJsonElement(FilterRules.serializer(), rules).also { lastEncoded = rules to it }
    }

    /**
     * JSON is valid JavaScript except that engines before ES2019 reject raw U+2028/U+2029 inside
     * string literals; they can only occur inside strings, so escaping them is always safe.
     */
    private fun jsSafe(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
