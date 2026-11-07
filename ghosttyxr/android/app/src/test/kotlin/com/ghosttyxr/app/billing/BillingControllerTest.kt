package com.ghosttyxr.app.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BillingControllerTest {

    @Test
    fun `insufficient funds gets actionable payment message`() {
        val result = billingResult(
            responseCode = BillingClient.BillingResponseCode.ERROR,
            subResponseCode = BillingClient.OnPurchasesUpdatedSubResponseCode
                .PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS,
        )

        assertEquals(
            "Payment declined due to insufficient funds. Update your Google Play payment method.",
            BillingController.purchaseErrorMessage(result),
        )
    }

    @Test
    fun `ineligible offer gets account-specific message`() {
        val result = billingResult(
            responseCode = BillingClient.BillingResponseCode.ERROR,
            subResponseCode = BillingClient.OnPurchasesUpdatedSubResponseCode.USER_INELIGIBLE,
        )

        assertEquals(
            "This subscription offer isn't available for your account.",
            BillingController.purchaseErrorMessage(result),
        )
    }

    @Test
    fun `transient service errors recommend retrying`() {
        val result = billingResult(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)

        assertEquals(
            "Google Play is temporarily unavailable. Try again.",
            BillingController.purchaseErrorMessage(result),
        )
    }

    @Test
    fun `billing unavailable gets actionable headset message`() {
        val result = billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)

        assertEquals(
            "Purchases are blocked on this headset. Open Google Play, sign in, and update " +
                "the Play Store, then reopen Orb XR.",
            BillingController.purchaseErrorMessage(result),
        )
        assertEquals(
            BillingUiState.Blocked(BillingController.purchaseErrorMessage(result)),
            BillingController.errorState(result),
        )
    }

    @Test
    fun `trial offer wins deterministically over base plan`() {
        val base = offer(token = "base-z", basePlan = "monthly", offerId = null)
        val trial = offer(
            token = "trial-z",
            basePlan = "monthly",
            offerId = "free-trial",
            hasTrial = true,
        )

        assertEquals(trial, BillingController.selectOffer(listOf(base, trial)))
        assertEquals(trial, BillingController.selectOffer(listOf(trial, base)))
    }

    @Test
    fun `stable identifiers break ties independent of Play list order`() {
        val later = offer(token = "z-token", basePlan = "yearly", offerId = "offer-b")
        val earlier = offer(token = "a-token", basePlan = "monthly", offerId = "offer-a")

        assertEquals(earlier, BillingController.selectOffer(listOf(later, earlier)))
        assertEquals(earlier, BillingController.selectOffer(listOf(earlier, later)))
    }

    private fun billingResult(
        responseCode: Int,
        subResponseCode: Int = BillingClient.OnPurchasesUpdatedSubResponseCode
            .NO_APPLICABLE_SUB_RESPONSE_CODE,
    ): BillingResult = BillingResult.newBuilder()
        .setResponseCode(responseCode)
        .setOnPurchasesUpdatedSubResponseCode(subResponseCode)
        .build()

    private fun offer(
        token: String,
        basePlan: String,
        offerId: String?,
        hasTrial: Boolean = false,
    ) = BillingOffer(
        offerToken = token,
        basePlanId = basePlan,
        offerId = offerId,
        priceText = "$4.99/month",
        hasFreeTrial = hasTrial,
    )
}
