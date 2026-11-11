package com.pmaker.tillbell

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/**
 * Runs the Google Play side of the bridge contract and answers with its replies. It reports
 * what Play says and decides nothing: the server verifies and acknowledges every purchase.
 *
 * Main thread only, like the Play Billing callbacks.
 */
internal class TillbellBilling(
    private val client: PlayBillingClient,
    private val packageName: String,
) {
    private class PendingPurchase(
        val productId: String,
        val planChange: PlanChange?,
        // Null means a plan change succeeded without its new purchase in the result: see
        // lookUpReplacement.
        val reply: CompletableDeferred<PurchaseReply?>,
    )

    // Play refuses a second startConnection while one is in progress, and the page can send
    // several requests at once (products on load, then a tap), so connections go one at a time.
    private val connectMutex = Mutex()

    // The purchase whose sheet is open, waiting for onPurchasesUpdated.
    private var pendingPurchase: PendingPurchase? = null

    /** Prices of the requested [plans]. Plans Google Play does not know are left out. */
    suspend fun products(plans: List<Plan>): ProductsReply {
        // Play rejects an empty query, and there is nothing to price anyway.
        if (plans.isEmpty()) return ProductsReply(QueryResult.OK, products = emptyList())

        val (result, details) = withConnection { client.queryProductDetails(plans.map { it.productId }.distinct()) }
        if (result.responseCode != BillingResponseCode.OK) return ProductsReply(queryResult(result), message = describe(result))

        val productsById = details.orEmpty().associateBy { it.productId }
        val priced =
            plans.mapNotNull { plan ->
                productsById[plan.productId]?.basePlanOffer(plan.basePlanId)?.let { offer ->
                    // The regular price is the last pricing phase; earlier ones are introductory.
                    val regularPhase = offer.pricingPhases.pricingPhaseList.last()
                    PricedPlan(plan.productId, plan.basePlanId, regularPhase.formattedPrice)
                }
            }
        return ProductsReply(QueryResult.OK, products = priced)
    }

    /**
     * Opens the purchase sheet and suspends until Google Play reports its outcome. Every call
     * returns exactly one reply. [activity] is read just before the launch, since connecting
     * and querying the product can take long enough for the screen to go away.
     */
    suspend fun purchase(
        activity: () -> Activity?,
        request: PurchaseRequest,
    ): PurchaseReply {
        val planChange =
            when {
                request.oldPurchaseToken == null && request.replacementMode == null -> null
                request.oldPurchaseToken != null && request.replacementMode != null ->
                    PlanChange(request.oldPurchaseToken, request.replacementMode.playValue)
                else -> return PurchaseReply(PurchaseResult.ERROR, message = "oldPurchaseToken and replacementMode go together")
            }

        val (queried, details) = withConnection { client.queryProductDetails(listOf(request.productId)) }
        if (queried.responseCode != BillingResponseCode.OK) return failedPurchase(queried)
        val product = details.orEmpty().firstOrNull { it.productId == request.productId }
        val offer =
            product?.basePlanOffer(request.basePlanId)
                ?: return PurchaseReply(
                    PurchaseResult.ERROR,
                    message = "Unknown product or base plan: ${request.productId}/${request.basePlanId}",
                )

        val currentActivity =
            activity() ?: return PurchaseReply(PurchaseResult.ERROR, message = "No activity to show the purchase sheet")

        // A purchase sheet covers the paywall, so a new purchase means the previous sheet is
        // gone without a result (Play always reports one otherwise). Answer the previous
        // request instead of leaving it waiting forever.
        pendingPurchase?.reply?.complete(PurchaseReply(PurchaseResult.ERROR, message = "Replaced by a new purchase"))
        val pending = PendingPurchase(request.productId, planChange, CompletableDeferred())
        pendingPurchase = pending

        val flow = BillingFlow(product, offer.offerToken, request.obfuscatedAccountId, planChange)
        val (launched, _) = withConnection { client.launchBillingFlow(currentActivity, flow) to Unit }
        if (launched.responseCode != BillingResponseCode.OK) {
            if (pendingPurchase === pending) pendingPurchase = null
            return failedPurchase(launched)
        }
        return pending.reply.await() ?: lookUpReplacement(pending)
    }

    /** Subscription purchases of the device's Google account, for the server to check. */
    suspend fun restore(): RestoreReply {
        val (result, purchases) = withConnection { client.queryPurchases() }
        if (result.responseCode != BillingResponseCode.OK) return RestoreReply(queryResult(result), message = describe(result))

        val owned =
            purchases.orEmpty().mapNotNull { purchase ->
                purchase.products.firstOrNull()?.let {
                    OwnedPurchase(it, purchase.purchaseToken)
                }
            }
        return RestoreReply(QueryResult.OK, purchases = owned)
    }

    /** The Play Store page where the customer manages one subscription, or all of them. */
    fun manageUrl(productId: String?): String {
        val page = "https://play.google.com/store/account/subscriptions"
        if (productId == null) return page
        return "$page?sku=${URLEncoder.encode(productId, "UTF-8")}&package=${URLEncoder.encode(packageName, "UTF-8")}"
    }

    /** The purchases listener of the Play Billing client. */
    fun onPurchasesUpdated(
        result: BillingResult,
        purchases: List<Purchase>?,
    ) {
        // Updates outside a purchase flow (a pending purchase completing later, for example)
        // are left to the server, which hears about them from Google's notifications.
        val pending = pendingPurchase ?: return
        pendingPurchase = null
        pending.reply.complete(purchaseReply(result, purchases, pending))
    }

    private fun purchaseReply(
        result: BillingResult,
        purchases: List<Purchase>?,
        pending: PendingPurchase,
    ): PurchaseReply? {
        if (result.responseCode != BillingResponseCode.OK) return failedPurchase(result)

        val purchase = choosePurchase(purchases, pending)
        if (purchase == null) {
            // No purchase of a plan change is not a failure yet: see lookUpReplacement.
            return if (pending.planChange != null) null else PurchaseReply(PurchaseResult.ERROR, message = NO_PURCHASE_MESSAGE)
        }
        return replyFor(purchase, pending.productId)
    }

    // The purchase the customer just made, among [purchases]. The purchase being replaced is never
    // the answer: the server already knows it.
    //
    // Play lists only the purchase of the flow after a plain purchase, so the first one will do. A
    // plan change is stricter, because the lookup after it reads every subscription of the Google
    // account: the new purchase of a deferred replacement stays associated with the product being
    // replaced until the renewal, so it is the purchase of the requested product, or of the product
    // of the replaced purchase. Without the replaced purchase in the list (it appears expired) the
    // only other purchase is taken; with several, there is no telling which is the new one.
    private fun choosePurchase(
        purchases: List<Purchase>?,
        pending: PendingPurchase,
    ): Purchase? {
        val change = pending.planChange
        val all = purchases.orEmpty()
        val candidates = all.filter { it.purchaseToken != change?.oldPurchaseToken }
        candidates.firstOrNull { pending.productId in it.products }?.let { return it }
        if (change == null) return candidates.firstOrNull()

        val replaced = all.firstOrNull { it.purchaseToken == change.oldPurchaseToken }
        return if (replaced == null) {
            candidates.singleOrNull()
        } else {
            candidates.firstOrNull { candidate -> candidate.products.any { it in replaced.products } }
        }
    }

    private fun replyFor(
        purchase: Purchase,
        requestedProductId: String,
    ): PurchaseReply {
        val boughtProductId = purchase.products.firstOrNull() ?: requestedProductId
        return when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseReply(PurchaseResult.PURCHASED, boughtProductId, purchase.purchaseToken)
            Purchase.PurchaseState.PENDING -> PurchaseReply(PurchaseResult.PENDING, boughtProductId, purchase.purchaseToken)
            else -> PurchaseReply(PurchaseResult.ERROR, message = "Unexpected purchase state ${purchase.purchaseState}")
        }
    }

    // A plan change can succeed without its new purchase in the result of the purchase sheet.
    // Google's documentation says a replacement gets a new purchase token, a deferred one included
    // (https://developer.android.com/google/play/billing/subscriptions), so ask Play for the
    // purchases of the account. The old token is no answer: the pending change is reported on the
    // new one. Without a new purchase this is an error, never a success.
    private suspend fun lookUpReplacement(pending: PendingPurchase): PurchaseReply {
        val (result, purchases) = withConnection { client.queryPurchases() }
        if (result.responseCode != BillingResponseCode.OK) return failedPurchase(result)

        val purchase = choosePurchase(purchases, pending) ?: return PurchaseReply(PurchaseResult.ERROR, message = NO_PURCHASE_MESSAGE)
        return replyFor(purchase, pending.productId)
    }

    // Runs a Play call, connecting first if needed. A dropped connection is retried once,
    // then reported: never silently ignored, never retried forever.
    private suspend fun <T> withConnection(call: suspend () -> Pair<BillingResult, T>): Pair<BillingResult, T?> {
        val first = connectThen(call)
        if (first.first.responseCode != BillingResponseCode.SERVICE_DISCONNECTED) return first
        return connectThen(call)
    }

    private suspend fun <T> connectThen(call: suspend () -> Pair<BillingResult, T>): Pair<BillingResult, T?> {
        val setup = connectMutex.withLock { if (client.isReady) null else client.connect() }
        if (setup != null && setup.responseCode != BillingResponseCode.OK) return setup to null
        return call()
    }

    internal companion object {
        private var shared: TillbellBilling? = null

        /**
         * The process-wide instance. The Play Billing client and its purchases listener must
         * outlive any Activity, so a purchase in flight when the Activity is recreated still
         * gets its result. It holds the application context only.
         */
        fun shared(context: Context): TillbellBilling =
            shared ?: run {
                lateinit var billing: TillbellBilling
                val client = GooglePlayBillingClient(context) { result, purchases -> billing.onPurchasesUpdated(result, purchases) }
                billing = TillbellBilling(client, context.packageName)
                billing.also { shared = it }
            }
    }
}

// Base plans are the offers without an offer id. Other offers (free trials, promotions) are
// not part of the v0.1 contract, so the paywall sells and prices base plans only.
private fun ProductDetails.basePlanOffer(basePlanId: String?): SubscriptionOfferDetails? {
    val basePlans = subscriptionOfferDetails.orEmpty().filter { it.offerId == null }
    return if (basePlanId == null) basePlans.singleOrNull() else basePlans.firstOrNull { it.basePlanId == basePlanId }
}

private fun failedPurchase(result: BillingResult): PurchaseReply =
    when (result.responseCode) {
        BillingResponseCode.USER_CANCELED -> PurchaseReply(PurchaseResult.CANCELED)
        BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseReply(PurchaseResult.ALREADY_OWNED)
        BillingResponseCode.BILLING_UNAVAILABLE,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> PurchaseReply(PurchaseResult.UNAVAILABLE, message = describe(result))
        else -> PurchaseReply(PurchaseResult.ERROR, message = describe(result))
    }

// The contract reserves "unavailable" for products and restore to BILLING_UNAVAILABLE.
private fun queryResult(result: BillingResult): QueryResult =
    if (result.responseCode == BillingResponseCode.BILLING_UNAVAILABLE) QueryResult.UNAVAILABLE else QueryResult.ERROR

// Developer-facing detail for the message field, for example "DEVELOPER_ERROR: <Play's message>".
private fun describe(result: BillingResult): String {
    val name =
        when (result.responseCode) {
            -3 -> "SERVICE_TIMEOUT" // Deprecated constant, still documented as a possible code.
            BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
            BillingResponseCode.OK -> "OK"
            BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
            BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
            BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
            BillingResponseCode.ERROR -> "ERROR"
            BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
            BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
            BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
            else -> "RESPONSE_CODE_${result.responseCode}"
        }
    return if (result.debugMessage.isNullOrEmpty()) name else "$name: ${result.debugMessage}"
}

private const val NO_PURCHASE_MESSAGE = "Google Play returned no purchase"
