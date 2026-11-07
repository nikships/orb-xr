const { GoogleAuth } = require("google-auth-library");

const PACKAGE_NAME = "com.ghosttyxr.app";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";

/** States that grant entitlement per Play docs (active + in grace period). */
const ENTITLED_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
]);

async function playClient(saKeyJson) {
  const credentials = JSON.parse(saKeyJson);
  return new GoogleAuth({ credentials, scopes: [SCOPE] }).getClient();
}

function publisherUrl(path) {
  return `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}/${path}`;
}

/**
 * Fetch a SubscriptionPurchaseV2 resource for a purchase token.
 * https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get
 */
async function getSubscriptionV2(saKeyJson, purchaseToken) {
  const client = await playClient(saKeyJson);
  const url = publisherUrl(
    `purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`,
  );
  const res = await client.request({ url });
  return res.data;
}

/** Acknowledge via the v1 endpoint (v2 has no acknowledge; v1 needs subscriptionId). */
async function acknowledgeSubscription(saKeyJson, subscriptionId, purchaseToken) {
  const client = await playClient(saKeyJson);
  const url = publisherUrl(
    `purchases/subscriptions/${encodeURIComponent(subscriptionId)}` +
      `/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,
  );
  await client.request({ url, method: "POST", data: {} });
}

/**
 * Distill a SubscriptionPurchaseV2 into { entitled, expiryTimeMillis, productId,
 * acknowledged }. Uses the max lineItem expiry (single-product sub here anyway).
 */
function summarizeSubscription(sub) {
  const entitled = ENTITLED_STATES.has(sub.subscriptionState);
  let expiryTimeMillis = 0;
  let productId = null;
  for (const item of sub.lineItems || []) {
    const t = item.expiryTime ? Date.parse(item.expiryTime) : 0;
    if (t > expiryTimeMillis) {
      expiryTimeMillis = t;
      productId = item.productId || productId;
    }
    if (!productId) productId = item.productId || null;
  }
  const acknowledged =
    sub.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED";
  return { entitled, expiryTimeMillis, productId, acknowledged };
}

module.exports = {
  PACKAGE_NAME,
  getSubscriptionV2,
  acknowledgeSubscription,
  summarizeSubscription,
  ENTITLED_STATES,
};
