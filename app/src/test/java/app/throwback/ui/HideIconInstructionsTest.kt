package app.throwback.ui

import app.throwback.ui.messages.HideIconInstructions
import app.throwback.ui.messages.PhoneBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class HideIconInstructionsTest {

    @Test
    fun `maps manufacturers to launcher families, ignoring case and spaces`() {
        val cases = mapOf(
            "samsung" to PhoneBrand.Samsung,
            "SAMSUNG" to PhoneBrand.Samsung,
            " Samsung " to PhoneBrand.Samsung,
            "OnePlus" to PhoneBrand.OnePlusOppo,
            "OPPO" to PhoneBrand.OnePlusOppo,
            "realme" to PhoneBrand.OnePlusOppo,
            "Xiaomi" to PhoneBrand.Xiaomi,
            "Redmi" to PhoneBrand.Xiaomi,
            "POCO" to PhoneBrand.Xiaomi,
            "Google" to PhoneBrand.Other,
            "motorola" to PhoneBrand.Other,
            "" to PhoneBrand.Other,
        )
        cases.forEach { (manufacturer, expected) ->
            assertEquals("manufacturer '$manufacturer'", expected, HideIconInstructions.brandFor(manufacturer))
        }
        assertEquals(PhoneBrand.Other, HideIconInstructions.brandFor(null))
    }

    @Test
    fun `this phone's brand comes first, the rest keep their order`() {
        assertEquals(
            listOf(PhoneBrand.Xiaomi, PhoneBrand.Samsung, PhoneBrand.OnePlusOppo, PhoneBrand.Other),
            HideIconInstructions.ordered("Xiaomi"),
        )
        assertEquals(
            listOf(PhoneBrand.Other, PhoneBrand.Samsung, PhoneBrand.OnePlusOppo, PhoneBrand.Xiaomi),
            HideIconInstructions.ordered("Google"),
        )
        assertEquals(PhoneBrand.entries.toList(), HideIconInstructions.ordered("samsung"))
    }

    @Test
    fun `every brand is listed exactly once`() {
        listOf("samsung", "oneplus", "xiaomi", "google", null).forEach { m ->
            val ordered = HideIconInstructions.ordered(m)
            assertEquals(PhoneBrand.entries.toSet(), ordered.toSet())
            assertEquals(PhoneBrand.entries.size, ordered.size)
        }
    }
}
