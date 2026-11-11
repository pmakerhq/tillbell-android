package com.pmaker.tillbell

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.TestOffer
import com.android.billingclient.api.billingResult
import com.android.billingclient.api.productDetails
import com.android.billingclient.api.purchase
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the component's messages against the bridge contract copied in
 * src/test/resources/contract: requests from the page decode, and every reply validates
 * against its JSON Schema (and matches the contract's example when there is one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContractTest {
    private val client = FakePlayBillingClient()
    private val billing = TillbellBilling(client, packageName = "app.example")
    private val premium =
        productDetails("premium", TestOffer("monthly", listOf("€4.99")), TestOffer("yearly", listOf("€49.99")))

    // Requests

    @Test
    fun `request examples decode, ignoring the bridge metadata`() {
        assertEquals(
            ProductsRequest(listOf(Plan("premium", "monthly"), Plan("premium", "yearly"))),
            contractJson.decodeFromString<ProductsRequest>(resource("examples/products.request.json")),
        )
        assertEquals(
            PurchaseRequest("premium", "monthly", "6f1c2a8e-3b7d-4e59-9a0c-2d4f8b1e7c35"),
            contractJson.decodeFromString<PurchaseRequest>(resource("examples/purchase.request.json")),
        )
        assertEquals(
            PurchaseRequest(
                "premium",
                "yearly",
                "6f1c2a8e-3b7d-4e59-9a0c-2d4f8b1e7c35",
                oldPurchaseToken = "old-purchase-token",
                replacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
            ),
            contractJson.decodeFromString<PurchaseRequest>(resource("examples/purchase.request.plan_change.json")),
        )
        assertEquals(ManageRequest("premium"), contractJson.decodeFromString<ManageRequest>(resource("examples/manage.request.json")))
        assertEquals(ManageRequest(null), contractJson.decodeFromString<ManageRequest>("""{"productId":null}"""))
    }

    @Test
    fun `manage and unknown events get no reply`() =
        runTest {
            assertNull(billing.answer("manage", resource("examples/manage.request.json"), activity = { null }))
            assertNull(billing.answer("connect", "{}", activity = { null }))
        }

    // products

    @Test
    fun `products ok matches the contract example`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)

            val reply = answer("products", resource("examples/products.request.json"))

            assertValid("products.reply.json", reply)
            assertSameJson(resource("examples/products.reply.ok.json"), reply)
        }

    @Test
    fun `a null base plan is echoed as null`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.OK) to
                listOf(productDetails("pro", TestOffer("monthly", listOf("€9.99"))))

            val reply = answer("products", """{"products":[{"productId":"pro","basePlanId":null}]}""")

            assertValid("products.reply.json", reply)
            assertSameJson("""{"result":"ok","products":[{"productId":"pro","basePlanId":null,"formattedPrice":"€9.99"}]}""", reply)
        }

    @Test
    fun `products unavailable matches the contract example`() =
        runTest {
            client.connectResults += billingResult(BillingResponseCode.BILLING_UNAVAILABLE)

            val reply = answer("products", resource("examples/products.request.json"))

            assertValid("products.reply.json", reply)
            assertSameJson(resource("examples/products.reply.unavailable.json"), reply)
        }

    @Test
    fun `products errors and malformed requests validate`() =
        runTest {
            client.productDetailsResults += billingResult(BillingResponseCode.NETWORK_ERROR, "offline") to emptyList()

            assertValid("products.reply.json", answer("products", resource("examples/products.request.json")))
            assertValid("products.reply.json", answer("products", """{"plans":[]}"""))
            assertValid("products.reply.json", answer("products", "not json"))
        }

    // purchase

    private fun TestScope.purchaseReply(
        code: Int,
        vararg purchases: com.android.billingclient.api.Purchase,
        request: String = resource("examples/purchase.request.json"),
    ): String {
        client.productDetailsResults += billingResult(BillingResponseCode.OK) to listOf(premium)
        val reply = async { billing.answer("purchase", request) { Activity() } }
        runCurrent()
        billing.onPurchasesUpdated(billingResult(code), purchases.toList())
        runCurrent()
        return reply.getCompleted()!!
    }

    @Test
    fun `purchased and pending match the contract examples`() =
        runTest {
            val purchased = purchaseReply(BillingResponseCode.OK, purchase("premium", "purchase-token"))
            val pending = purchaseReply(BillingResponseCode.OK, purchase("premium", "purchase-token", jsonState = 4))

            assertValid("purchase.reply.json", purchased)
            assertSameJson(resource("examples/purchase.reply.purchased.json"), purchased)
            assertValid("purchase.reply.json", pending)
            assertSameJson(resource("examples/purchase.reply.pending.json"), pending)
        }

    @Test
    fun `canceled, already owned and error match the contract examples`() =
        runTest {
            assertSameJson(resource("examples/purchase.reply.canceled.json"), purchaseReply(BillingResponseCode.USER_CANCELED))
            assertSameJson(resource("examples/purchase.reply.already_owned.json"), purchaseReply(BillingResponseCode.ITEM_ALREADY_OWNED))
            assertSameJson(resource("examples/purchase.reply.error.json"), purchaseReply(BillingResponseCode.DEVELOPER_ERROR))
        }

    @Test
    fun `every purchase reply validates`() =
        runTest {
            val codes =
                listOf(
                    BillingResponseCode.USER_CANCELED,
                    BillingResponseCode.ITEM_ALREADY_OWNED,
                    BillingResponseCode.BILLING_UNAVAILABLE,
                    BillingResponseCode.SERVICE_UNAVAILABLE,
                    BillingResponseCode.FEATURE_NOT_SUPPORTED,
                    BillingResponseCode.ERROR,
                    BillingResponseCode.OK,
                )
            codes.forEach { assertValid("purchase.reply.json", purchaseReply(it)) }

            val planChange = resource("examples/purchase.request.plan_change.json")
            assertValid("purchase.reply.json", purchaseReply(BillingResponseCode.OK, purchase("premium", "new"), request = planChange))
            assertEquals(
                PlanChange("old-purchase-token", ReplacementMode.CHARGE_PRORATED_PRICE.playValue),
                client.launchedFlows.last().planChange,
            )
        }

    @Test
    fun `malformed purchase requests and a missing activity get an error reply`() =
        runTest {
            val missingAccount = answer("purchase", """{"productId":"premium","basePlanId":null}""")
            val unknownMode =
                answer("purchase", """{"productId":"p","obfuscatedAccountId":"a","oldPurchaseToken":"t","replacementMode":"NOPE"}""")
            val noActivity = billing.answer("purchase", resource("examples/purchase.request.json"), activity = { null })!!

            listOf(missingAccount, unknownMode, noActivity).forEach {
                assertValid("purchase.reply.json", it)
                assertTrue(it, it.contains("\"result\":\"error\""))
            }
            assertTrue(client.launchedFlows.isEmpty())
        }

    // restore

    @Test
    fun `restore replies match the contract examples`() =
        runTest {
            client.purchasesResults += billingResult(BillingResponseCode.OK) to listOf(purchase("premium", "purchase-token"))
            client.purchasesResults += billingResult(BillingResponseCode.OK) to emptyList()

            val owned = answer("restore", resource("examples/restore.request.json"))
            val empty = answer("restore", "{}")

            assertValid("restore.reply.json", owned)
            assertSameJson(resource("examples/restore.reply.ok.json"), owned)
            assertValid("restore.reply.json", empty)
            assertSameJson(resource("examples/restore.reply.empty.json"), empty)
        }

    @Test
    fun `restore failures validate`() =
        runTest {
            client.purchasesResults += billingResult(BillingResponseCode.BILLING_UNAVAILABLE) to emptyList()
            client.purchasesResults += billingResult(BillingResponseCode.ERROR, "boom") to emptyList()

            assertValid("restore.reply.json", answer("restore", "{}"))
            assertValid("restore.reply.json", answer("restore", "{}"))
        }

    @Test
    fun `an unexpected exception still gets an error reply`() =
        runTest {
            client.failure = IllegalArgumentException("rejected by Play")

            val reply = answer("restore", "{}")

            assertValid("restore.reply.json", reply)
            assertSameJson("""{"result":"error","message":"rejected by Play"}""", reply)
        }

    // Helpers

    private suspend fun answer(
        event: String,
        jsonData: String,
    ): String = billing.answer(event, jsonData) { Activity() }!!

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream("/contract/$path")) { "missing contract file $path" }
            .bufferedReader()
            .use { it.readText() }

    private fun assertValid(
        schema: String,
        json: String,
    ) {
        val errors = schemas.getSchema(resource(schema), InputFormat.JSON).validate(json, InputFormat.JSON)
        assertTrue("$json does not match $schema: $errors", errors.isEmpty())
    }

    private fun assertSameJson(
        expected: String,
        actual: String,
    ) = assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))

    private companion object {
        val schemas: SchemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    }
}
