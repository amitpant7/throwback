package app.throwback.billing

/**
 * The three tips (DECISIONS.md #4b), one-time products in Play Console with these ids. The ids name
 * the size, not the tip, so a renamed tip keeps its product (Play never reuses an id). [fallbackUsd]
 * is shown until Google Play has given the price in the user's currency.
 */
enum class TipProduct(val productId: String, val fallbackUsd: Int) {
    SMALL("tip_small", 3),
    MEDIUM("tip_medium", 10),
    LARGE("tip_large", 25),
    ;

    companion object {
        fun byId(id: String): TipProduct? = entries.firstOrNull { it.productId == id }
    }
}

/** A purchase as Google Play reports it, reduced to what the rules need. */
internal data class TipPurchase(
    val products: List<String>,
    val token: String,
    /** Paid; false while the payment is pending (cash at a shop, a slow bank). */
    val purchased: Boolean,
    val acknowledged: Boolean,
)

/** What to do with the purchases Google Play reports (DECISIONS.md #4d). Pure, so it is unit-tested. */
internal object TipRules {

    /** Any paid tip unlocks Make It Yours; a pending one doesn't yet. */
    fun tipped(purchases: List<TipPurchase>): Boolean = purchases.any { it.purchased && it.isTip() }

    /** Paid tips not acknowledged yet: Google refunds a purchase that isn't acknowledged within 3 days. */
    fun toAcknowledge(purchases: List<TipPurchase>): List<String> =
        purchases.filter { it.purchased && !it.acknowledged && it.isTip() }.map { it.token }

    /**
     * The owned purchase of [tip] to use up before buying it again: Google Play won't sell a product
     * that is still owned. Only then, so the latest purchase of each tip stays on record, and a
     * reinstall or a new phone unlocks Make It Yours again.
     */
    fun toConsumeBefore(tip: TipProduct, owned: List<TipPurchase>): String? =
        owned.firstOrNull { it.purchased && tip.productId in it.products }?.token

    private fun TipPurchase.isTip(): Boolean = products.any { TipProduct.byId(it) != null }
}
