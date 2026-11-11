package com.pmaker.tillbell

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.billingResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Scripted [PlayBillingClient]: each call returns the next queued result, or OK with nothing
 * when the queue is empty. A SERVICE_DISCONNECTED result drops the connection, as Play does.
 */
internal class FakePlayBillingClient : PlayBillingClient {
    override var isReady = false

    val connectResults = ArrayDeque<BillingResult>()
    val productDetailsResults = ArrayDeque<Pair<BillingResult, List<ProductDetails>>>()
    val purchasesResults = ArrayDeque<Pair<BillingResult, List<Purchase>>>()
    val launchResults = ArrayDeque<BillingResult>()

    /** When set, connect() suspends until it completes, like a slow first connection. */
    var connectGate: CompletableDeferred<Unit>? = null

    /** When set, queryPurchases() throws it, like a Play builder rejecting its input. */
    var failure: RuntimeException? = null

    var connectCalls = 0
    val queriedProductIds = mutableListOf<List<String>>()
    var purchasesQueries = 0
    val launchedFlows = mutableListOf<BillingFlow>()

    override suspend fun connect(): BillingResult {
        connectCalls++
        connectGate?.await()
        return track(connectResults.removeFirstOrNull() ?: ok)
    }

    override suspend fun queryProductDetails(productIds: List<String>): Pair<BillingResult, List<ProductDetails>> {
        queriedProductIds += productIds
        val result = productDetailsResults.removeFirstOrNull() ?: (ok to emptyList())
        track(result.first)
        return result
    }

    override suspend fun queryPurchases(): Pair<BillingResult, List<Purchase>> {
        purchasesQueries++
        failure?.let { throw it }
        val result = purchasesResults.removeFirstOrNull() ?: (ok to emptyList())
        track(result.first)
        return result
    }

    override fun launchBillingFlow(
        activity: Activity,
        flow: BillingFlow,
    ): BillingResult {
        launchedFlows += flow
        return track(launchResults.removeFirstOrNull() ?: ok)
    }

    private fun track(result: BillingResult): BillingResult {
        when (result.responseCode) {
            BillingResponseCode.OK -> isReady = true
            BillingResponseCode.SERVICE_DISCONNECTED -> isReady = false
        }
        return result
    }

    private companion object {
        val ok = billingResult(BillingResponseCode.OK)
    }
}
