package com.pmaker.tillbell

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams
import com.android.billingclient.api.TestOffer
import com.android.billingclient.api.billingResult
import com.android.billingclient.api.productDetails
import com.android.billingclient.api.purchase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TillbellBillingTest {
    private val client = FakePlayBillingClient()
    private val billing = TillbellBilling(client, packageName = "app.example")
    private val activity = Activity()

    private val premium =
        productDetails(
            "premium",
            TestOffer("monthly", listOf("€4.99")),
            TestOffer("monthly", listOf("Free", "€4.99"), offerId = "trial"),
            TestOffer("yearly", listOf("€19.99", "€49.99")),
        )

    // Connection

    @Test
    fun `connects before the first call and reuses the connection`() =
        runTest {
            billing.restore()
            billing.restore()

            assertEquals(1, client.connectCalls)
        }

    @Test
    fun `reconnects once when the service is disconnected, then succeeds`() =
        runTest {
            client.purchasesResults += billingResult(BillingResponseCode.SERVICE_DISCONNECTED) to emptyList()

            val reply = billing.restore()

            assertEquals(RestoreReply(QueryResult.OK, purchases = emptyList()), reply)
            assertEquals(2, client.connectCalls)
            assertEquals(2, client.purchasesQueries)
        }

    @Test
    fun `reports a second disconnection instead of retrying forever`() =
        runTest {
            repeat(2) { client.purchasesResults += billingResult(BillingResponseCode.SERVICE_DISCONNECTED) to emptyList() }

            val reply = billing.restore()

            assertEquals(RestoreReply(QueryResult.ERROR, message = "SERVICE_DISCONNECTED"), reply)
            assertEquals(2, client.purchasesQueries)
        }

    @Test
    fun `reconnects once when the connection itself is lost during setup`() =
        runTest {
            client.connectResults += billingResult(BillingResponseCode.SERVICE_DISCONNECTED)

            val reply = billing.restore()

            assertEquals(QueryResult.OK, reply.result)
            assertEquals(2, client.connectCalls)
        }

    @Test
    fun `concurrent calls share one connection attempt`() =
        runTest {
            client.connectGate = CompletableDeferred()
            val first = async { billing.restore() }
            val second = async { billing.restore() }
            runCurrent()
            client.connectGate!!.complete(Unit)

            assertEquals(QueryResult.OK, first.await().result)
            assertEquals(QueryResult.OK, second.await().result)
            assertEquals(1, client.connectCalls)
        }

    @Test
    fun `reports billing unavailable when the connection fails with it`() =
        runTest {
            client.connectResults += billingResult(BillingResponseCode.BILLING_UNAVAILABLE, "no Play Store")

            val reply = billing.products(listOf(Plan("premium", "monthly")))

            assertEquals(ProductsReply(QueryResult.UNAVAILABLE, message = "BILLING_UNAVAILABLE: no Play Store"), reply)
            assertTrue(client.queriedProductIds.isEmpty())
        }

    // Products

    @Test
    fun `prices come from the regular phase of each requested base plan`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)

            val reply = billing.products(listOf(Plan("premium", "monthly"), Plan("premium", "yearly")))

            val expected =
                ProductsReply(
                    QueryResult.OK,
                    products =
                        listOf(
                            PricedPlan("premium", "monthly", "€4.99"),
                            PricedPlan("premium", "yearly", "€49.99"),
                        ),
                )
            assertEquals(expected, reply)
            assertEquals(listOf(listOf("premium")), client.queriedProductIds)
        }

    @Test
    fun `plans unknown to Play are left out`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)

            val reply = billing.products(listOf(Plan("premium", "weekly"), Plan("other", "monthly"), Plan("premium", "monthly")))

            assertEquals(listOf(PricedPlan("premium", "monthly", "€4.99")), reply.products)
        }

    @Test
    fun `a null base plan uses the product's only base plan`() =
        runTest {
            val single = productDetails("pro", TestOffer("monthly", listOf("€9.99")), TestOffer("monthly", listOf("€1"), offerId = "promo"))
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(single, premium)

            val reply = billing.products(listOf(Plan("pro", null), Plan("premium", null)))

            // premium has two base plans, so a null base plan is ambiguous and left out.
            assertEquals(listOf(PricedPlan("pro", null, "€9.99")), reply.products)
        }

    @Test
    fun `an empty request does not call Play`() =
        runTest {
            assertEquals(ProductsReply(QueryResult.OK, products = emptyList()), billing.products(emptyList()))
            assertEquals(0, client.connectCalls)
        }

    @Test
    fun `product query failures map to unavailable or error`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.BILLING_UNAVAILABLE) to emptyList()
            client.productDetailsResults += billingResult(BillingResponseCode.NETWORK_ERROR, "offline") to emptyList()

            assertEquals(QueryResult.UNAVAILABLE, billing.products(listOf(Plan("premium", "monthly"))).result)
            assertEquals(
                ProductsReply(QueryResult.ERROR, message = "NETWORK_ERROR: offline"),
                billing.products(listOf(Plan("premium", "monthly"))),
            )
        }

    // Purchase

    private val monthly = PurchaseRequest("premium", "monthly", obfuscatedAccountId = "6f1c2a8e-3b7d-4e59-9a0c-2d4f8b1e7c35")
    private val deferredChange =
        monthly.copy(basePlanId = "yearly", oldPurchaseToken = "old-token", replacementMode = ReplacementMode.DEFERRED)

    private fun TestScope.startPurchase(request: PurchaseRequest = monthly) =
        async { billing.purchase({ activity }, request) }.also {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)
            runCurrent()
        }

    @Test
    fun `launches the base plan offer with the account id unchanged and no plan change`() =
        runTest {
            val reply = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), listOf(purchase("premium", "token-1")))

            assertEquals(PurchaseReply(PurchaseResult.PURCHASED, productId = "premium", purchaseToken = "token-1"), reply.await())
            val flow = client.launchedFlows.single()
            assertEquals("premium", flow.productDetails.productId)
            assertEquals("token-monthly-base", flow.offerToken)
            assertEquals("6f1c2a8e-3b7d-4e59-9a0c-2d4f8b1e7c35", flow.obfuscatedAccountId)
            assertNull(flow.planChange)
        }

    @Test
    fun `a pending purchase replies pending with its token`() =
        runTest {
            val reply = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), listOf(purchase("premium", "token-2", jsonState = 4)))

            assertEquals(PurchaseReply(PurchaseResult.PENDING, productId = "premium", purchaseToken = "token-2"), reply.await())
        }

    @Test
    fun `OK without a purchase is an error`() =
        runTest {
            val reply = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)

            assertEquals(PurchaseResult.ERROR, reply.await().result)
        }

    @Test
    fun `each Play response code maps to exactly one purchase result`() =
        runTest {
            val expected =
                mapOf(
                    -3 to PurchaseResult.ERROR, // SERVICE_TIMEOUT, a deprecated constant
                    BillingResponseCode.FEATURE_NOT_SUPPORTED to PurchaseResult.UNAVAILABLE,
                    BillingResponseCode.SERVICE_DISCONNECTED to PurchaseResult.ERROR,
                    BillingResponseCode.USER_CANCELED to PurchaseResult.CANCELED,
                    BillingResponseCode.SERVICE_UNAVAILABLE to PurchaseResult.UNAVAILABLE,
                    BillingResponseCode.BILLING_UNAVAILABLE to PurchaseResult.UNAVAILABLE,
                    BillingResponseCode.ITEM_UNAVAILABLE to PurchaseResult.ERROR,
                    BillingResponseCode.DEVELOPER_ERROR to PurchaseResult.ERROR,
                    BillingResponseCode.ERROR to PurchaseResult.ERROR,
                    BillingResponseCode.ITEM_ALREADY_OWNED to PurchaseResult.ALREADY_OWNED,
                    BillingResponseCode.ITEM_NOT_OWNED to PurchaseResult.ERROR,
                    BillingResponseCode.NETWORK_ERROR to PurchaseResult.ERROR,
                )
            for ((code, result) in expected) {
                val reply = startPurchase()
                billing.onPurchasesUpdated(billingResult(code), null)

                assertEquals("response code $code", result, reply.await().result)
            }
        }

    @Test
    fun `only errors carry a message, canceled and already owned carry nothing`() =
        runTest {
            val canceled = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.USER_CANCELED, "closed"), null)
            assertEquals(PurchaseReply(PurchaseResult.CANCELED), canceled.await())

            val owned = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.ITEM_ALREADY_OWNED), null)
            assertEquals(PurchaseReply(PurchaseResult.ALREADY_OWNED), owned.await())

            val failed = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.DEVELOPER_ERROR, "bad offer"), null)
            assertEquals(PurchaseReply(PurchaseResult.ERROR, message = "DEVELOPER_ERROR: bad offer"), failed.await())
        }

    @Test
    fun `a plan change passes the old token and the server's replacement mode through`() =
        runTest {
            for (mode in ReplacementMode.entries) {
                val reply = startPurchase(monthly.copy(basePlanId = "yearly", oldPurchaseToken = "old-token", replacementMode = mode))
                billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), listOf(purchase("premium", "new-token")))
                assertEquals(PurchaseReply(PurchaseResult.PURCHASED, "premium", "new-token"), reply.await())

                assertEquals(PlanChange("old-token", mode.playValue), client.launchedFlows.last().planChange)
            }
            assertEquals(
                listOf(
                    SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION,
                    SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE,
                    SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION,
                    SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE,
                    SubscriptionUpdateParams.ReplacementMode.DEFERRED,
                ),
                ReplacementMode.entries.map { it.playValue },
            )
        }

    @Test
    fun `a plan change confirmed without a purchase is answered with the new purchase Play lists, never the old token`() =
        runTest {
            val reply = startPurchase(deferredChange)
            client.purchasesResults +=
                billingResult(BillingResponseCode.OK) to listOf(purchase("premium", "old-token"), purchase("premium", "new-token"))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)

            assertEquals(PurchaseReply(PurchaseResult.PURCHASED, "premium", "new-token"), reply.await())
            assertEquals(1, client.purchasesQueries)
        }

    @Test
    fun `a plan change confirmed without a purchase is an error when Play knows no new purchase`() =
        runTest {
            val reply = startPurchase(deferredChange)
            client.purchasesResults += billingResult(BillingResponseCode.OK) to listOf(purchase("premium", "old-token"))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), emptyList())

            assertEquals(PurchaseReply(PurchaseResult.ERROR, message = "Google Play returned no purchase"), reply.await())
        }

    @Test
    fun `a plan change confirmed without a purchase reports a failed lookup`() =
        runTest {
            val reply = startPurchase(deferredChange)
            client.purchasesResults += billingResult(BillingResponseCode.SERVICE_UNAVAILABLE, "offline") to emptyList()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)

            assertEquals(PurchaseResult.UNAVAILABLE, reply.await().result)
        }

    @Test
    fun `a plan change confirmed without a purchase never answers with an unrelated subscription of the account`() =
        runTest {
            val reply = startPurchase(deferredChange)
            client.purchasesResults +=
                billingResult(BillingResponseCode.OK) to listOf(purchase("premium", "old-token"), purchase("addon", "addon-token"))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)

            assertEquals(PurchaseReply(PurchaseResult.ERROR, message = "Google Play returned no purchase"), reply.await())
        }

    @Test
    fun `a deferred change finds the new purchase, which stays on the product being replaced until the renewal`() =
        runTest {
            val reply = startPurchase(deferredChange)
            client.purchasesResults += billingResult(BillingResponseCode.OK) to
                listOf(purchase("standard", "old-token"), purchase("standard", "new-token"), purchase("addon", "addon-token"))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)

            assertEquals(PurchaseReply(PurchaseResult.PURCHASED, "standard", "new-token"), reply.await())
        }

    @Test
    fun `without the replaced purchase in the list, the only other purchase is the new one, and several are no answer`() =
        runTest {
            val single = startPurchase(deferredChange)
            client.purchasesResults += billingResult(BillingResponseCode.OK) to listOf(purchase("standard", "new-token"))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)
            assertEquals(PurchaseReply(PurchaseResult.PURCHASED, "standard", "new-token"), single.await())

            val several = startPurchase(deferredChange)
            client.purchasesResults += billingResult(BillingResponseCode.OK) to listOf(purchase("standard", "a"), purchase("addon", "b"))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)
            assertEquals(PurchaseResult.ERROR, several.await().result)
        }

    @Test
    fun `a new purchase found by the lookup can be pending`() =
        runTest {
            val reply = startPurchase(deferredChange)
            client.purchasesResults += billingResult(BillingResponseCode.OK) to listOf(purchase("premium", "new-token", jsonState = 4))
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), null)

            assertEquals(PurchaseReply(PurchaseResult.PENDING, "premium", "new-token"), reply.await())
        }

    @Test
    fun `a canceled plan change is canceled, it does not trigger the lookup`() =
        runTest {
            val reply = startPurchase(deferredChange)
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.USER_CANCELED), null)

            assertEquals(PurchaseReply(PurchaseResult.CANCELED), reply.await())
            assertEquals(0, client.purchasesQueries)
        }

    @Test
    fun `a plan change that lists the replaced purchase too is answered with the new one`() =
        runTest {
            val reply = startPurchase(deferredChange)
            billing.onPurchasesUpdated(
                billingResult(BillingResponseCode.OK),
                listOf(purchase("premium", "old-token"), purchase("premium", "new-token")),
            )

            assertEquals(PurchaseReply(PurchaseResult.PURCHASED, "premium", "new-token"), reply.await())
            assertEquals(0, client.purchasesQueries)
        }

    @Test
    fun `an old token without a replacement mode is refused before Play is called`() =
        runTest {
            val reply = billing.purchase({ activity }, monthly.copy(oldPurchaseToken = "old-token"))

            assertEquals(PurchaseResult.ERROR, reply.result)
            assertTrue(client.launchedFlows.isEmpty())
        }

    @Test
    fun `an unknown base plan is an error and launches nothing`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)

            val reply = billing.purchase({ activity }, monthly.copy(basePlanId = "weekly"))

            assertEquals(PurchaseReply(PurchaseResult.ERROR, message = "Unknown product or base plan: premium/weekly"), reply)
            assertTrue(client.launchedFlows.isEmpty())
        }

    @Test
    fun `a failed launch replies at once`() =
        runTest {
            client.launchResults += billingResult(BillingResponseCode.ITEM_ALREADY_OWNED)
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)

            assertEquals(PurchaseReply(PurchaseResult.ALREADY_OWNED), billing.purchase({ activity }, monthly))
        }

    @Test
    fun `billing unavailable before the launch replies unavailable`() =
        runTest {
            client.connectResults += billingResult(BillingResponseCode.BILLING_UNAVAILABLE)

            assertEquals(PurchaseResult.UNAVAILABLE, billing.purchase({ activity }, monthly).result)
        }

    @Test
    fun `an activity gone before the launch is an error and launches nothing`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)

            assertEquals(PurchaseResult.ERROR, billing.purchase({ null }, monthly).result)
            assertTrue(client.launchedFlows.isEmpty())
        }

    @Test
    fun `purchase updates outside a purchase flow are ignored`() =
        runTest {
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), listOf(purchase("premium", "token-3")))

            val reply = startPurchase()
            billing.onPurchasesUpdated(billingResult(BillingResponseCode.USER_CANCELED), null)
            assertEquals(PurchaseResult.CANCELED, reply.await().result)
        }

    @Test
    fun `a new purchase supersedes one whose result never came, and each gets one reply`() =
        runTest {
            val first = startPurchase()
            val second = startPurchase()

            assertEquals(PurchaseResult.ERROR, first.await().result)
            assertFalse(second.isCompleted)

            billing.onPurchasesUpdated(billingResult(BillingResponseCode.OK), listOf(purchase("premium", "token-4")))
            assertEquals("token-4", second.await().purchaseToken)
        }

    // Restore

    @Test
    fun `restore returns every subscription purchase`() =
        runTest {
            client.purchasesResults +=
                billingResult(BillingResponseCode.OK) to listOf(purchase("premium", "token-a"), purchase("pro", "token-b", jsonState = 4))

            val expected =
                RestoreReply(QueryResult.OK, purchases = listOf(OwnedPurchase("premium", "token-a"), OwnedPurchase("pro", "token-b")))
            assertEquals(expected, billing.restore())
        }

    @Test
    fun `restore failures map to unavailable or error`() =
        runTest {
            client.purchasesResults += billingResult(BillingResponseCode.BILLING_UNAVAILABLE) to emptyList()
            client.purchasesResults += billingResult(BillingResponseCode.ERROR) to emptyList()

            assertEquals(QueryResult.UNAVAILABLE, billing.restore().result)
            assertEquals(RestoreReply(QueryResult.ERROR, message = "ERROR"), billing.restore())
        }

    // Manage

    @Test
    fun `manage opens the Play subscriptions page, for one product or all of them`() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=premium&package=app.example",
            billing.manageUrl("premium"),
        )
        assertEquals("https://play.google.com/store/account/subscriptions", billing.manageUrl(null))
    }

    // The server acknowledges every purchase after verifying it; acknowledging here too is a bug.
    @Test
    fun `the library never acknowledges a purchase`() {
        val sources = File("src/main").walk().filter { it.extension == "kt" }.toList()

        assertTrue(sources.isNotEmpty())
        sources.forEach { assertFalse(it.path, it.readText().contains("acknowledgePurchase")) }
    }
}
