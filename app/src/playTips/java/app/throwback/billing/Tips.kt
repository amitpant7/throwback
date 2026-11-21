package app.throwback.billing

import android.app.Activity
import android.content.Context
import app.throwback.core.Supporter
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Tips through Google Play Billing (DECISIONS.md #4, #4d), one client for the app's process. No
 * server: Google Play handles the payment and remembers the purchases; Throwback only learns that a
 * tip went through.
 *
 * - [refresh] (at start and when Support opens): owned tips unlock Make It Yours again (reinstall,
 *   new phone), paid tips get acknowledged, and the prices load.
 * - [prices]: each tip's price in the user's currency; empty until Google Play has answered, and when
 *   it can't sell (not installed from Play, products not set up, no Play Store).
 * - [buy] opens Google Play's purchase sheet; how it ended arrives in [events].
 */
object Tips {

    enum class Event { THANKS, PENDING, FAILED }

    private const val CONNECT_TIMEOUT_MS = 10_000L

    // Billing calls back on the main thread; everything here runs there too.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _prices = MutableStateFlow<Map<TipProduct, String>>(emptyMap())
    val prices: StateFlow<Map<TipProduct, String>> = _prices.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var client: BillingClient? = null
    private var connecting: CompletableDeferred<Boolean>? = null
    private var details: Map<TipProduct, ProductDetails> = emptyMap()

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val c = connected(app) ?: return@launch
            queryPurchases(c)?.let { handle(c, app, it) }
            loadDetails(c)
        }
    }

    /**
     * Opens Google Play's purchase sheet for [tip]. False when Google Play can't sell it now (the
     * caller says so); otherwise the result comes later in [events].
     */
    suspend fun buy(activity: Activity, tip: TipProduct): Boolean {
        val c = connected(activity.applicationContext) ?: return false
        if (details[tip] == null) loadDetails(c)
        val product = details[tip] ?: return false
        // Google Play won't sell a product that is still owned: use up the last purchase of this tip.
        queryPurchases(c)?.let { owned -> TipRules.toConsumeBefore(tip, owned.map { it.toTip() })?.let { consume(c, it) } }
        val offer = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
            .apply { product.oneTimePurchaseOfferDetails?.offerToken?.let { setOfferToken(it) } }
            .build()
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(offer)).build()
        return c.launchBillingFlow(activity, params).responseCode == BillingResponseCode.OK
    }

    private fun client(app: Context): BillingClient = client ?: BillingClient.newBuilder(app)
        .setListener { result, purchases -> onPurchasesUpdated(app, result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()
        .also { client = it }

    /** The client once connected to Google Play's billing service, or null (no Play Store, refused, timed out). */
    private suspend fun connected(app: Context): BillingClient? {
        val c = client(app)
        if (c.isReady) return c
        val pending = connecting ?: CompletableDeferred<Boolean>().also { deferred ->
            connecting = deferred
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    deferred.complete(result.responseCode == BillingResponseCode.OK)
                    if (connecting === deferred) connecting = null
                }

                override fun onBillingServiceDisconnected() {
                    deferred.complete(false)
                    if (connecting === deferred) connecting = null
                }
            })
        }
        return c.takeIf { withTimeoutOrNull(CONNECT_TIMEOUT_MS) { pending.await() } == true }
    }

    private fun onPurchasesUpdated(app: Context, result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch {
                val c = client(app)
                val list = purchases.orEmpty()
                handle(c, app, list)
                val tips = list.map { it.toTip() }
                when {
                    TipRules.tipped(tips) -> _events.tryEmit(Event.THANKS)
                    tips.isNotEmpty() -> _events.tryEmit(Event.PENDING)
                }
            }
            BillingResponseCode.USER_CANCELED -> Unit
            else -> _events.tryEmit(Event.FAILED)
        }
    }

    /** Paid tips unlock Make It Yours (a refund leaves it unlocked, #4d) and get acknowledged. */
    private suspend fun handle(c: BillingClient, app: Context, purchases: List<Purchase>) {
        val tips = purchases.map { it.toTip() }
        if (TipRules.tipped(tips)) Supporter.markTipped(app)
        // One that fails is acknowledged at the next start (Google allows 3 days).
        TipRules.toAcknowledge(tips).forEach { acknowledge(c, it) }
    }

    private suspend fun queryPurchases(c: BillingClient): List<Purchase>? = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        c.queryPurchasesAsync(params) { result, list ->
            if (cont.isActive) cont.resume(list.takeIf { result.responseCode == BillingResponseCode.OK })
        }
    }

    private suspend fun loadDetails(c: BillingClient) {
        val products = TipProduct.entries.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it.productId).setProductType(ProductType.INAPP).build()
        }
        val found = suspendCancellableCoroutine { cont ->
            c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, answer ->
                if (cont.isActive) {
                    cont.resume(if (result.responseCode == BillingResponseCode.OK) answer.productDetailsList else emptyList())
                }
            }
        }
        if (found.isEmpty()) return // keep what an earlier answer gave
        details = found.mapNotNull { d -> TipProduct.byId(d.productId)?.let { it to d } }.toMap()
        _prices.value = details.mapNotNull { (tip, d) -> d.oneTimePurchaseOfferDetails?.formattedPrice?.let { tip to it } }.toMap()
    }

    private suspend fun acknowledge(c: BillingClient, token: String) = suspendCancellableCoroutine { cont ->
        c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) {
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun consume(c: BillingClient, token: String) = suspendCancellableCoroutine { cont ->
        c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { _, _ ->
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun Purchase.toTip() = TipPurchase(
        products = products,
        token = purchaseToken,
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        acknowledged = isAcknowledged,
    )
}
