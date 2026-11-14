package com.pmaker.tillbell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException

/**
 * The `tillbell` Hotwire Native bridge component: runs the Google Play subscription purchase
 * flow for the Tillbell paywall of a Rails app.
 *
 * Register it with Hotwire next to your other components:
 *
 * ```
 * Hotwire.registerBridgeComponents(
 *     BridgeComponentFactory(TillbellComponent.NAME, ::TillbellComponent),
 * )
 * ```
 *
 * It answers the page's `products`, `purchase` and `restore` messages, always, including on
 * errors, and opens the Play subscriptions page on `manage`. It reports what Google Play says
 * and never acknowledges a purchase: the server verifies and acknowledges it.
 *
 * @param name The component name Hotwire registers it under; use [NAME].
 * @param delegate The bridge delegate of the destination showing the paywall.
 */
public class TillbellComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>,
) : BridgeComponent<HotwireDestination>(name, delegate) {
    /** Handles a message from the paywall page. */
    override fun onReceive(message: Message) {
        val fragment = delegate.destination.fragment
        val billing = TillbellBilling.shared(fragment.requireContext())
        if (message.event == "manage") {
            if (!isTrusted(message.jsonData, allowedHosts)) {
                Log.w(NAME, "Ignoring manage from a page outside the allowed hosts")
                return
            }
            val productId = decodeOrNull<ManageRequest>(message.jsonData)?.productId
            openPlayStore(billing.manageUrl(productId))
            return
        }
        // Replies to this exact message, not to the latest message with the same event. When the
        // Activity is recreated meanwhile, Hotwire Native reloads the page in a new WebView: the
        // page that asked is gone, the reply is dropped by the bridge, and the purchase is not
        // lost, since Google notifies the server and `restore` returns it.
        scope.launch {
            val reply = billing.answer(message.event, message.jsonData) { delegate.destination.fragment.activity }
            reply?.let { replyWith(message.replacing(jsonData = it)) }
        }
    }

    private fun openPlayStore(url: String) {
        try {
            delegate.destination.fragment.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            // manage gets no reply, so there is nobody to tell but the developer.
            Log.w(NAME, "No app can open $url", e)
        }
    }

    /** Constants of the component. */
    public companion object {
        /** The bridge component name the Tillbell paywall page talks to. */
        public const val NAME: String = "tillbell"

        /**
         * Hosts of the pages allowed to start a purchase, e.g. `setOf("example.com")`, compared
         * with the host of the page that sent the message. Messages from any other page (a link
         * that left your site inside the app) get an `error` reply, and `manage` is ignored.
         * Null, the default, accepts every page the app shows. Set it in `Application.onCreate`.
         */
        @Volatile
        @JvmStatic
        public var allowedHosts: Set<String>? = null

        // Process-wide, not tied to a screen: a purchase in flight must still complete, and its
        // result reach Play's listener, when the Activity that started it is recreated.
        private val scope = MainScope()
    }
}

// The `metadata` object the bridge adds to every message from the page: the URL of the page.
@Serializable
private data class MessageMetadata(
    val metadata: Metadata,
) {
    @Serializable
    data class Metadata(
        val url: String,
    )
}

/**
 * True when the page that sent [jsonData] may talk to the store: its `metadata.url`, added by
 * the bridge, is on one of [allowedHosts] (any page when it is null). A message without a
 * readable URL is refused once hosts are set.
 */
internal fun isTrusted(
    jsonData: String,
    allowedHosts: Set<String>?,
): Boolean {
    if (allowedHosts == null) return true
    val url = decodeOrNull<MessageMetadata>(jsonData)?.metadata?.url ?: return false
    val host =
        try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        } ?: return false
    return allowedHosts.any { it.equals(host, ignoreCase = true) }
}

// Requests ignore the `metadata` object.
// Encoding uses this instance too, rather than the host app's Hotwire JSON converter, whose
// settings could add or drop fields the contract's schemas forbid or require.
internal val contractJson = Json { ignoreUnknownKeys = true }

/**
 * The JSON reply to a `products`, `purchase` or `restore` message, or null for messages that
 * get no reply. Malformed requests and unexpected failures get an `error` reply, so the page
 * never waits forever. [activity] is the Activity to show the purchase sheet on, if any.
 */
internal suspend fun TillbellBilling.answer(
    event: String,
    jsonData: String,
    allowedHosts: Set<String>? = TillbellComponent.allowedHosts,
    activity: () -> Activity?,
): String? {
    if (!isTrusted(jsonData, allowedHosts)) return errorReply(event, "The page is not on an allowed host")
    return try {
        when (event) {
            "products" -> {
                val reply =
                    decodeOrNull<ProductsRequest>(jsonData)?.let { products(it.products) }
                        ?: ProductsReply(QueryResult.ERROR, message = "Malformed products request")
                contractJson.encodeToString(reply)
            }
            "purchase" -> {
                val reply =
                    decodeOrNull<PurchaseRequest>(jsonData)?.let { purchase(activity, it) }
                        ?: PurchaseReply(PurchaseResult.ERROR, message = "Malformed purchase request")
                contractJson.encodeToString(reply)
            }
            "restore" -> contractJson.encodeToString(restore())
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // For example a Play Billing builder rejecting its input: still answer the page.
        errorReply(event, e.message ?: e.javaClass.name)
    }
}

private fun errorReply(
    event: String,
    message: String,
): String? =
    when (event) {
        "products" -> contractJson.encodeToString(ProductsReply(QueryResult.ERROR, message = message))
        "purchase" -> contractJson.encodeToString(PurchaseReply(PurchaseResult.ERROR, message = message))
        "restore" -> contractJson.encodeToString(RestoreReply(QueryResult.ERROR, message = message))
        else -> null
    }

private inline fun <reified T> decodeOrNull(jsonData: String): T? =
    try {
        contractJson.decodeFromString<T>(jsonData)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
