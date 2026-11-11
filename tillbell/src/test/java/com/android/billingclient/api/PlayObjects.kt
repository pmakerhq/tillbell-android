// Lives in the Play Billing package to reach the package-private ProductDetails constructor:
// Play builds ProductDetails from JSON and offers no public way to create one in tests.
package com.android.billingclient.api

import org.json.JSONArray
import org.json.JSONObject

/** A subscription offer for [productDetails]: a base plan when [offerId] is null. */
data class TestOffer(
    val basePlanId: String,
    val formattedPrices: List<String>,
    val offerId: String? = null,
    val offerToken: String = "token-$basePlanId-${offerId ?: "base"}",
)

/** Builds subscription [ProductDetails] the way Play does, from its JSON representation. */
fun productDetails(
    productId: String,
    vararg offers: TestOffer,
): ProductDetails {
    val offerDetails =
        JSONArray(
            offers.map { offer ->
                JSONObject()
                    .put("basePlanId", offer.basePlanId)
                    .putOpt("offerId", offer.offerId)
                    .put("offerIdToken", offer.offerToken)
                    .put("offerTags", JSONArray())
                    .put(
                        "pricingPhases",
                        JSONArray(
                            offer.formattedPrices.map { price ->
                                JSONObject()
                                    .put("formattedPrice", price)
                                    .put("priceAmountMicros", 0)
                                    .put("priceCurrencyCode", "EUR")
                                    .put("billingPeriod", "P1M")
                                    .put("billingCycleCount", 0)
                                    .put("recurrenceMode", 1)
                            },
                        ),
                    )
            },
        )
    val json =
        JSONObject()
            .put("productId", productId)
            .put("type", BillingClient.ProductType.SUBS)
            .put("title", productId)
            .put("name", productId)
            .put("description", "")
            .put("skuDetailsToken", "details-token")
            .put("subscriptionOfferDetails", offerDetails)
    return ProductDetails(json.toString())
}

/** Builds a [Purchase] from the JSON Play returns. [jsonState] is 0 for purchased, 4 for pending. */
fun purchase(
    productId: String,
    purchaseToken: String,
    jsonState: Int = 0,
): Purchase {
    val json =
        JSONObject()
            .put("orderId", "GPA.0000")
            .put("packageName", "app.example")
            .put("productIds", JSONArray(listOf(productId)))
            .put("purchaseTime", 0)
            .put("purchaseState", jsonState)
            .put("purchaseToken", purchaseToken)
            .put("acknowledged", false)
    return Purchase(json.toString(), "signature")
}

/** A [BillingResult] with the given response code. */
fun billingResult(
    responseCode: Int,
    debugMessage: String = "",
): BillingResult =
    BillingResult
        .newBuilder()
        .setResponseCode(responseCode)
        .setDebugMessage(debugMessage)
        .build()
