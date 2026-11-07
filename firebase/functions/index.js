const { onRequest } = require("firebase-functions/v2/https");
const { onMessagePublished } = require("firebase-functions/v2/pubsub");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");

const { requireAuthAndAppCheck } = require("./lib/auth");
const entitlements = require("./lib/entitlements");
const play = require("./lib/play");

initializeApp();

const playSaKey = defineSecret("PLAY_SA_KEY");

function requirePost(req, res) {
  if (req.method !== "POST") {
    res.status(405).json({ error: "POST only" });
    return false;
  }
  return true;
}

/** Auth + App Check. Returns uid, or null after writing the response. */
async function requireUid(req, res) {
  if (!requirePost(req, res)) return null;
  return requireAuthAndAppCheck(req, res);
}

/** Auth + App Check + active Pro entitlement. */
async function requireProUid(req, res) {
  const uid = await requireUid(req, res);
  if (!uid) return null;
  if (!(await entitlements.isPro(uid))) {
    res.status(403).json({ error: "Pro subscription required" });
    return null;
  }
  return uid;
}

/** Persist entitlement from a Play subscription summary. Returns whether entitled. */
async function applySubscriptionEntitlement(uid, purchaseToken, summary) {
  if (summary.entitled) {
    await entitlements.setPro(uid, {
      expiryTimeMillis: summary.expiryTimeMillis,
      purchaseToken,
      productId: summary.productId,
    });
    return true;
  }
  await entitlements.setFree(uid);
  return false;
}

function onboardingResponse(entitlement) {
  const { tier, expiryTimeMillis, source } = entitlement;
  return { tier, expiryTimeMillis, source };
}

/**
 * Grants the caller one bounded first-flight Pro window. The grant is server-owned,
 * idempotent, and cannot replace an active Play entitlement.
 */
exports.beginOnboardingAccess = onRequest(
  {
    region: "us-central1",
    maxInstances: 4,
    timeoutSeconds: 15,
  },
  async (req, res) => {
    const uid = await requireUid(req, res);
    if (!uid) return;
    const entitlement = await entitlements.beginOnboardingAccess(uid);
    res.json(onboardingResponse(entitlement));
  },
);

/**
 * Ends first-flight access. Paid Play entitlements are never revoked by this endpoint.
 */
exports.completeOnboardingAccess = onRequest(
  {
    region: "us-central1",
    maxInstances: 4,
    timeoutSeconds: 15,
  },
  async (req, res) => {
    const uid = await requireUid(req, res);
    if (!uid) return;
    const entitlement = await entitlements.completeOnboardingAccess(uid);
    res.json(onboardingResponse(entitlement));
  },
);

/**
 * Verifies a Play Billing subscription purchase token for the calling user,
 * acknowledges it, and flips the caller's entitlement doc to "pro".
 * POST `{ "purchaseToken": "...", "productId": "pro_monthly" }`.
 */
exports.verifyPurchase = onRequest(
  {
    region: "us-central1",
    secrets: [playSaKey],
    maxInstances: 2,
    timeoutSeconds: 30,
  },
  async (req, res) => {
    const uid = await requireUid(req, res);
    if (!uid) return;
    const purchaseToken =
      typeof req.body?.purchaseToken === "string" ? req.body.purchaseToken.trim() : "";
    if (!purchaseToken) {
      res.status(400).json({ error: "Missing 'purchaseToken'" });
      return;
    }

    let sub;
    try {
      sub = await play.getSubscriptionV2(playSaKey.value(), purchaseToken);
    } catch (err) {
      logger.error("subscriptionsv2.get failed", { message: err.message });
      res.status(400).json({ error: "Purchase token not valid" });
      return;
    }
    const summary = play.summarizeSubscription(sub);
    if (!summary.entitled) {
      logger.info("Purchase not entitled", { uid, state: sub.subscriptionState });
      await entitlements.setFree(uid);
      res.json({ tier: "free" });
      return;
    }
    if (!summary.acknowledged) {
      try {
        await play.acknowledgeSubscription(
          playSaKey.value(),
          summary.productId || "pro_monthly",
          purchaseToken,
        );
      } catch (err) {
        // Non-fatal: retried on next verify; Play refunds only if never acked in 3 days.
        logger.error("acknowledge failed", { message: err.message });
      }
    }
    await applySubscriptionEntitlement(uid, purchaseToken, summary);
    logger.info("Entitlement granted", { uid, expiry: summary.expiryTimeMillis });
    res.json({ tier: "pro", expiryTimeMillis: summary.expiryTimeMillis });
  },
);

/**
 * RTDN Pub/Sub consumer: re-verifies the affected purchase token against the
 * Play API and updates the owning user's entitlement doc (renewals extend the
 * expiry; cancellations/expiry/revocation flip back to free once the paid
 * period lapses).
 */
exports.playRtdn = onMessagePublished(
  {
    topic: "play-rtdn",
    region: "us-central1",
    secrets: [playSaKey],
    maxInstances: 2,
  },
  async (event) => {
    let payload;
    try {
      payload = event.data.message.json;
    } catch {
      logger.warn("RTDN message was not JSON");
      return;
    }
    const note = payload?.subscriptionNotification;
    if (!note?.purchaseToken) {
      // testNotification / voidedPurchase / one-time — nothing to do here.
      logger.info("RTDN without subscriptionNotification", { keys: Object.keys(payload || {}) });
      return;
    }
    const purchaseToken = note.purchaseToken;
    const uid = await entitlements.findUidByPurchaseToken(purchaseToken);
    if (!uid) {
      logger.warn("RTDN for unknown purchase token (no entitlement doc yet)");
      return;
    }

    let sub;
    try {
      sub = await play.getSubscriptionV2(playSaKey.value(), purchaseToken);
    } catch (err) {
      logger.error("RTDN re-verify failed", { message: err.message });
      return;
    }
    const summary = play.summarizeSubscription(sub);
    const entitled = await applySubscriptionEntitlement(uid, purchaseToken, summary);
    if (entitled) {
      logger.info("RTDN: entitlement refreshed", { uid, expiry: summary.expiryTimeMillis });
    } else {
      logger.info("RTDN: entitlement revoked", { uid, state: sub.subscriptionState });
    }
  },
);
