package app.throwback.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipRulesTest {

    private fun purchase(id: String, token: String = "t-$id", purchased: Boolean = true, acknowledged: Boolean = true) =
        TipPurchase(listOf(id), token, purchased, acknowledged)

    @Test
    fun aPaidTipUnlocks() {
        assertTrue(TipRules.tipped(listOf(purchase("tip_medium"))))
    }

    @Test
    fun aPendingTipDoesNotUnlockYet() {
        assertFalse(TipRules.tipped(listOf(purchase("tip_small", purchased = false))))
    }

    @Test
    fun somethingElseThanATipDoesNotUnlock() {
        assertFalse(TipRules.tipped(listOf(purchase("some_other_product"))))
        assertFalse(TipRules.tipped(emptyList()))
    }

    @Test
    fun onlyPaidUnacknowledgedTipsAreAcknowledged() {
        val list = listOf(
            purchase("tip_small", token = "a", acknowledged = false),
            purchase("tip_medium", token = "b", acknowledged = true),
            purchase("tip_large", token = "c", purchased = false, acknowledged = false),
            purchase("other", token = "d", acknowledged = false),
        )
        assertEquals(listOf("a"), TipRules.toAcknowledge(list))
    }

    @Test
    fun buyingATipAgainUsesUpItsLastPurchaseOnly() {
        val owned = listOf(purchase("tip_small", token = "s"), purchase("tip_large", token = "l"))
        assertEquals("s", TipRules.toConsumeBefore(TipProduct.SMALL, owned))
        assertNull(TipRules.toConsumeBefore(TipProduct.MEDIUM, owned))
    }

    @Test
    fun aPendingPurchaseIsNotUsedUp() {
        assertNull(TipRules.toConsumeBefore(TipProduct.SMALL, listOf(purchase("tip_small", purchased = false))))
    }

    @Test
    fun productIdsAreStable() {
        // Play Console products carry these ids; a change would orphan every earlier tip.
        assertEquals(listOf("tip_small", "tip_medium", "tip_large"), TipProduct.entries.map { it.productId })
        assertEquals(TipProduct.LARGE, TipProduct.byId("tip_large"))
    }
}
