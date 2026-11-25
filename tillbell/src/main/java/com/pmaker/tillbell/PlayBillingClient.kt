package com.pmaker.tillbell

import android.app.Activity
import android.content.Context
import androidx.core.net.toUri
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingProgram
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.EnableBillingProgramParams
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The few Play Billing calls Tillbell makes. It exists so [TillbellBilling] can be tested
 * without Google Play; [GooglePlayBillingClient] is the only production implementation.
 */
internal interface PlayBillingClient {
    /** Whether the connection to Google Play is established. */
    val isReady: Boolean

    /** Connects to Google Play and returns the setup result. */
    suspend fun connect(): BillingResult

    /** Queries the subscription products with the given ids. */
    suspend fun queryProductDetails(productIds: List<String>): Pair<BillingResult, List<ProductDetails>>

    /** Queries the subscription purchases of the device's Google account. */
    suspend fun queryPurchases(): Pair<BillingResult, List<Purchase>>

    /** Opens the Google Play purchase sheet. Its outcome arrives through the purchases listener. */
    fun launchBillingFlow(
        activity: Activity,
        flow: BillingFlow,
    ): BillingResult

    /** Whether the external offers program is available to this device and customer. */
    suspend fun isExternalOfferAvailable(): BillingResult

    /** Creates a fresh external transaction token for an external offer; null when Play gives none. */
    suspend fun createExternalOfferToken(): Pair<BillingResult, String?>

    /** Shows Google's information screen, then lets Play open [url] outside the app. */
    suspend fun launchExternalOffer(
        activity: Activity,
        url: String,
    ): BillingResult
}

/** What [PlayBillingClient.launchBillingFlow] needs to open the purchase sheet. */
internal data class BillingFlow(
    val productDetails: ProductDetails,
    val offerToken: String,
    val obfuscatedAccountId: String,
    val planChange: PlanChange?,
)

/** The subscription being replaced and Google Play's replacement mode for it. */
internal data class PlanChange(
    val oldPurchaseToken: String,
    val replacementMode: Int,
)

/** [PlayBillingClient] backed by the Play Billing Library. */
internal class GooglePlayBillingClient(
    context: Context,
    listener: PurchasesUpdatedListener,
) : PlayBillingClient {
    // Holds the application context only, so it never leaks an Activity. Automatic service
    // reconnection is opt-in and not enabled: TillbellBilling reconnects once itself, then reports.
    private val client =
        BillingClient
            .newBuilder(context.applicationContext)
            .setListener(listener)
            // Play requires pending purchases to be enabled. Prepaid plans are the
            // subscriptions that can be paid later (for example in cash).
            .enablePendingPurchases(
                PendingPurchasesParams
                    .newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build(),
            )
            // Required by the external offer calls. In Billing 9.1.0 it only records the program
            // on the builder (it relaxes the purchases listener check, which is set anyway), so
            // apps not enrolled in the program behave as before: the calls report unavailable.
            .enableBillingProgram(
                EnableBillingProgramParams
                    .newBuilder()
                    .setBillingProgram(BillingProgram.EXTERNAL_OFFER)
                    .build(),
            ).build()

    override val isReady: Boolean get() = client.isReady

    override suspend fun connect(): BillingResult =
        suspendCancellableCoroutine { continuation ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (continuation.isActive) {
                            continuation.resume(
                                BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build(),
                            )
                        }
                    }
                },
            )
        }

    override suspend fun queryProductDetails(productIds: List<String>): Pair<BillingResult, List<ProductDetails>> {
        val products =
            productIds.map {
                QueryProductDetailsParams.Product
                    .newBuilder()
                    .setProductId(it)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        return suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, details ->
                continuation.resume(result to details.productDetailsList)
            }
        }
    }

    override suspend fun queryPurchases(): Pair<BillingResult, List<Purchase>> {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(params) { result, purchases -> continuation.resume(result to purchases) }
        }
    }

    override fun launchBillingFlow(
        activity: Activity,
        flow: BillingFlow,
    ): BillingResult {
        val product =
            ProductDetailsParams
                .newBuilder()
                .setProductDetails(flow.productDetails)
                .setOfferToken(flow.offerToken)
                .build()
        // No obfuscated profile id: the server sends none and nothing is derived on the device.
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(listOf(product))
                .setObfuscatedAccountId(flow.obfuscatedAccountId)
        flow.planChange?.let {
            // setSubscriptionReplacementMode is deprecated since Billing 8 in favor of
            // SubscriptionProductReplacementParams, which also needs the old product id. The
            // v0.1 contract sends only the old purchase token, so this is the call it specifies.
            @Suppress("DEPRECATION")
            params.setSubscriptionUpdateParams(
                SubscriptionUpdateParams
                    .newBuilder()
                    .setOldPurchaseToken(it.oldPurchaseToken)
                    .setSubscriptionReplacementMode(it.replacementMode)
                    .build(),
            )
        }
        return client.launchBillingFlow(activity, params.build())
    }

    override suspend fun isExternalOfferAvailable(): BillingResult =
        suspendCancellableCoroutine { continuation ->
            client.isBillingProgramAvailableAsync(BillingProgram.EXTERNAL_OFFER) { result, _ -> continuation.resume(result) }
        }

    override suspend fun createExternalOfferToken(): Pair<BillingResult, String?> {
        val params = BillingProgramReportingDetailsParams.newBuilder().setBillingProgram(BillingProgram.EXTERNAL_OFFER).build()
        return suspendCancellableCoroutine { continuation ->
            client.createBillingProgramReportingDetailsAsync(params) { result, details ->
                // Play passes no details when the call fails.
                continuation.resume(result to details?.externalTransactionToken)
            }
        }
    }

    override suspend fun launchExternalOffer(
        activity: Activity,
        url: String,
    ): BillingResult {
        // Play shows its information screen and opens the link itself, as the program requires.
        val params =
            LaunchExternalLinkParams
                .newBuilder()
                .setBillingProgram(BillingProgram.EXTERNAL_OFFER)
                .setLinkType(LaunchExternalLinkParams.LinkType.LINK_TO_DIGITAL_CONTENT_OFFER)
                .setLaunchMode(LaunchExternalLinkParams.LaunchMode.LAUNCH_IN_EXTERNAL_BROWSER_OR_APP)
                .setLinkUri(url.toUri())
                .build()
        return suspendCancellableCoroutine { continuation ->
            client.launchExternalLink(activity, params) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }
}
