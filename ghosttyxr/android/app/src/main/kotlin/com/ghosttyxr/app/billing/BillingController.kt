package com.ghosttyxr.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.ghosttyxr.app.util.GxrLog
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "Billing"

/** UI-facing paywall status. */
sealed interface BillingUiState {
    data object Idle : BillingUiState
    data object Connecting : BillingUiState
    /** Product loaded; [priceText] like "$4.99/month", [hasTrialOffer] gates the trial CTA copy. */
    data class Ready(val priceText: String, val hasTrialOffer: Boolean) : BillingUiState
    data object PurchaseInFlight : BillingUiState
    /** Server confirmed Pro. */
    data object ProActive : BillingUiState
    /** Play Billing cannot run until the headset's Play Store/account setup is repaired. */
    data class Blocked(val message: String) : BillingUiState
    data class Error(val message: String) : BillingUiState
}

/** Pure offer projection used to make offer selection stable and host-JVM testable. */
internal data class BillingOffer(
    val offerToken: String,
    val basePlanId: String,
    val offerId: String?,
    val priceText: String?,
    val hasFreeTrial: Boolean,
)

/**
 * Owns the Play Billing connection and the Pro subscription purchase/restore flow.
 *
 * Purchase truth lives server-side: after Play reports a purchase, the token goes to
 * the `verifyPurchase` Cloud Function (via [VerifyPurchaseClient]), which validates it
 * with the Play Developer API, acknowledges, and writes the Firestore entitlement doc.
 * The client never grants itself Pro from a Play result alone — [EntitlementStore]
 * updates optimistically from the function's response and stays synced by its
 * Firestore listener + RTDN afterwards.
 */
class BillingController(
    context: Context,
    private val scope: CoroutineScope,
    private val verifyClient: VerifyPurchaseClient,
    private val onEntitlement: (Entitlement) -> Unit = EntitlementStore::update,
) : PurchasesUpdatedListener {

    private val _uiState = MutableStateFlow<BillingUiState>(BillingUiState.Idle)
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    @Volatile
    private var productDetails: ProductDetails? = null

    @Volatile
    private var connectionRequested = false

    private var foregroundActivity = WeakReference<Activity>(null)

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enableAutoServiceReconnection()
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .build()

    /** Release the Play Billing connection (Activity onDestroy). */
    fun release() {
        runCatching { billingClient.endConnection() }
    }

    /** Connect + load the Pro product + restore any existing purchase. Idempotent-ish. */
    fun start() {
        if (billingClient.isReady) {
            queryProduct()
            restorePurchases()
            return
        }
        if (connectionRequested) return
        connectionRequested = true
        _uiState.value = BillingUiState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connectionRequested = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    restorePurchases()
                    foregroundActivity.get()?.let(::showTransactionalMessages)
                } else {
                    GxrLog.w(TAG, "billing setup failed: ${result.debugMessage}")
                    _uiState.value = errorState(result)
                }
            }

            override fun onBillingServiceDisconnected() {
                connectionRequested = false
                GxrLog.w(TAG, "billing service disconnected")
            }
        })
    }

    /**
     * Show Google Play's transactional payment/price-plan notices on every foreground.
     * The weak reference only bridges an in-progress service connection.
     */
    fun onForeground(activity: Activity) {
        foregroundActivity = WeakReference(activity)
        if (billingClient.isReady) {
            showTransactionalMessages(activity)
        } else {
            start()
        }
    }

    fun onBackground(activity: Activity) {
        if (foregroundActivity.get() === activity) foregroundActivity.clear()
    }

    /** Launch the Play purchase sheet for the Pro subscription. */
    fun launchPurchase(activity: Activity) {
        val details = productDetails
        if (details == null) {
            _uiState.value = BillingUiState.Error("Subscription not loaded yet — try again.")
            start()
            return
        }
        val offerToken = pickOfferToken(details)
        if (offerToken == null) {
            _uiState.value = BillingUiState.Error("No subscription offer available.")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build(),
                ),
            )
            .build()
        _uiState.value = BillingUiState.PurchaseInFlight
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            GxrLog.w(
                TAG,
                "purchase launch failed: ${result.debugMessage} " +
                    "subCode=${result.onPurchasesUpdatedSubResponseCode}",
            )
            _uiState.value = errorState(result)
        }
    }

    /** Play reports purchase updates here (new purchase or change from outside the app). */
    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> verifyPurchased(purchases)
            BillingClient.BillingResponseCode.USER_CANCELED ->
                _uiState.value = readyOrIdle()
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restorePurchases()
            else -> {
                GxrLog.w(
                    TAG,
                    "purchase update failed: ${result.debugMessage} " +
                        "subCode=${result.onPurchasesUpdatedSubResponseCode}",
                )
                _uiState.value = errorState(result)
            }
        }
    }

    /** Re-check owned subscriptions (app start / panel open) so Pro restores after reinstall. */
    fun restorePurchases() {
        if (!billingClient.isReady) {
            start()
            return
        }
        scope.launch { restorePurchasesConnected() }
    }

    private suspend fun restorePurchasesConnected() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            GxrLog.w(TAG, "restore query failed: ${result.billingResult.debugMessage}")
            if (_uiState.value !is BillingUiState.ProActive) {
                _uiState.value = errorState(result.billingResult)
            }
            return
        }
        verifyPurchased(result.purchasesList)
    }

    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        scope.launch {
            val queryResult = billingClient.queryProductDetails(params)
            val result = queryResult.billingResult
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                GxrLog.w(TAG, "product query failed: ${result.debugMessage}")
                _uiState.value = errorState(result)
                return@launch
            }
            val details = queryResult.productDetailsList.orEmpty()
                .firstOrNull { it.productId == PRODUCT_ID }
            if (details == null) {
                _uiState.value = BillingUiState.Error("Subscription not found on Play.")
                return@launch
            }
            productDetails = details
            if (_uiState.value !is BillingUiState.ProActive) {
                _uiState.value = readyState(details)
            }
        }
    }

    private fun showTransactionalMessages(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val params = InAppMessageParams.newBuilder()
            .addInAppMessageCategoryToShow(
                InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL,
            )
            .build()
        val result = billingClient.showInAppMessages(activity, params) { messageResult ->
            GxrLog.d(TAG) { "in-app billing message response=${messageResult.responseCode}" }
            if (
                messageResult.responseCode ==
                InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED
            ) {
                // Never trust the dialog alone: re-query Play and send every current token
                // through verifyPurchase so the server remains entitlement authority.
                restorePurchases()
            }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            GxrLog.w(TAG, "in-app billing message launch failed: ${result.debugMessage}")
        }
    }

    private fun verify(purchaseToken: String) {
        scope.launch {
            try {
                val ent = verifyClient.verify(purchaseToken)
                onEntitlement(ent)
                _uiState.value = if (ent.isPro()) BillingUiState.ProActive else readyOrIdle()
                GxrLog.d(TAG) { "verify ok tier=${ent.tier}" }
            } catch (err: Exception) {
                GxrLog.w(TAG, "verify failed: ${err.message}")
                _uiState.value = BillingUiState.Error(
                    "Purchase made, but activation failed. It will retry automatically.",
                )
            }
        }
    }

    private fun verifyPurchased(purchases: List<Purchase>?) {
        purchases.orEmpty()
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .forEach { verify(it.purchaseToken) }
    }

    private fun readyOrIdle(): BillingUiState =
        productDetails?.let(Companion::readyState) ?: BillingUiState.Idle

    companion object {
        /** Play Console subscription product (base plan `monthly`, offer `free-trial-7d`). */
        const val PRODUCT_ID = "pro_monthly"

        /** Deep link to manage/cancel the subscription in the Play app. */
        const val MANAGE_URL =
            "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=com.ghosttyxr.app"

        /**
         * Prefer an eligible trial returned by Play, then sort identifiers so the
         * selected offer never depends on Play's list order. Pure for unit tests.
         */
        fun pickOfferToken(details: ProductDetails): String? {
            return selectOffer(details.subscriptionOfferDetails.orEmpty().map(::toBillingOffer))
                ?.offerToken
        }

        internal fun readyState(details: ProductDetails): BillingUiState.Ready {
            val selected = selectOffer(
                details.subscriptionOfferDetails.orEmpty().map(::toBillingOffer),
            )
            return BillingUiState.Ready(
                priceText = selected?.priceText ?: "subscription",
                hasTrialOffer = selected?.hasFreeTrial == true,
            )
        }

        internal fun selectOffer(offers: List<BillingOffer>): BillingOffer? =
            offers.sortedWith(
                compareByDescending<BillingOffer> { it.hasFreeTrial }
                    .thenBy { it.basePlanId }
                    .thenBy { it.offerId.orEmpty() }
                    .thenBy { it.offerToken },
            ).firstOrNull()

        internal fun errorState(result: BillingResult): BillingUiState =
            if (result.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
                BillingUiState.Blocked(BILLING_BLOCKED_MESSAGE)
            } else {
                BillingUiState.Error(purchaseErrorMessage(result))
            }

        internal fun purchaseErrorMessage(result: BillingResult): String =
            when (result.onPurchasesUpdatedSubResponseCode) {
                BillingClient.OnPurchasesUpdatedSubResponseCode
                    .PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS ->
                    "Payment declined due to insufficient funds. Update your Google Play payment method."
                BillingClient.OnPurchasesUpdatedSubResponseCode.USER_INELIGIBLE ->
                    "This subscription offer isn't available for your account."
                else -> when (result.responseCode) {
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                        BILLING_BLOCKED_MESSAGE
                    BillingClient.BillingResponseCode.NETWORK_ERROR,
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                    -> "Google Play is temporarily unavailable. Try again."
                    else -> "Purchase didn't complete."
                }
            }

        private const val BILLING_BLOCKED_MESSAGE =
            "Purchases are blocked on this headset. Open Google Play, sign in, and update " +
                "the Play Store, then reopen Orb XR."

        private fun toBillingOffer(
            offer: ProductDetails.SubscriptionOfferDetails,
        ): BillingOffer {
            val paidPhase = offer.pricingPhases.pricingPhaseList
                .lastOrNull { it.priceAmountMicros > 0 }
            return BillingOffer(
                offerToken = offer.offerToken,
                basePlanId = offer.basePlanId,
                offerId = offer.offerId,
                priceText = paidPhase?.let { phase ->
                    when (phase.billingPeriod) {
                        "P1M" -> "${phase.formattedPrice}/month"
                        "P1Y" -> "${phase.formattedPrice}/year"
                        else -> phase.formattedPrice
                    }
                },
                hasFreeTrial = hasFreeTrialPhase(offer),
            )
        }

        private fun hasFreeTrialPhase(offer: ProductDetails.SubscriptionOfferDetails): Boolean =
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
    }
}
