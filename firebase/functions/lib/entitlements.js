const { getFirestore, FieldValue } = require("firebase-admin/firestore");

const COLLECTION = "entitlements";
const ONBOARDING_SOURCE = "onboarding";
const ONBOARDING_ACCESS_MS = 24 * 60 * 60 * 1000;
const PAID_EXPIRY_SLACK_MS = 24 * 60 * 60 * 1000;

/** Shape of an entitlement doc. Missing doc == free tier. */
function emptyEntitlement() {
  return {
    tier: "free",
    expiryTimeMillis: 0,
    purchaseToken: null,
    source: null,
    onboardingGrantClaimedAtMillis: null,
    onboardingCompletedAtMillis: null,
  };
}

function entitlementRef(uid) {
  return getFirestore().collection(COLLECTION).doc(uid);
}

function entitlementFromSnapshot(snap) {
  return snap.exists
    ? { ...emptyEntitlement(), ...snap.data() }
    : emptyEntitlement();
}

async function getEntitlement(uid) {
  return entitlementFromSnapshot(await entitlementRef(uid).get());
}

/**
 * Pro check with expiry: a "pro" doc whose expiry has passed (plus RTDN lag
 * slack) is treated as free. RTDN normally flips the doc first, but this guards
 * against missed notifications.
 */
function isEntitled(ent, nowMillis = Date.now()) {
  if (ent.tier !== "pro") return false;
  const slack = ent.source === ONBOARDING_SOURCE ? 0 : PAID_EXPIRY_SLACK_MS;
  return ent.expiryTimeMillis + slack > nowMillis;
}

async function isPro(uid, nowMillis = Date.now()) {
  return isEntitled(await getEntitlement(uid), nowMillis);
}

async function setPro(uid, { expiryTimeMillis, purchaseToken, productId }) {
  await entitlementRef(uid).set(
    {
      tier: "pro",
      expiryTimeMillis,
      purchaseToken,
      productId: productId || "pro_monthly",
      source: "play",
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
}

function canBeginOnboardingAccess(entitlement, nowMillis = Date.now()) {
  if (isEntitled(entitlement, nowMillis)) return false;
  return !entitlement.purchaseToken &&
    !entitlement.onboardingCompletedAtMillis &&
    !entitlement.onboardingGrantClaimedAtMillis;
}

async function beginOnboardingAccess(uid, nowMillis = Date.now()) {
  const ref = entitlementRef(uid);
  return getFirestore().runTransaction(async (transaction) => {
    const current = entitlementFromSnapshot(await transaction.get(ref));

    if (!canBeginOnboardingAccess(current, nowMillis)) {
      return current;
    }

    const granted = {
      ...current,
      tier: "pro",
      expiryTimeMillis: nowMillis + ONBOARDING_ACCESS_MS,
      purchaseToken: null,
      productId: "onboarding",
      source: ONBOARDING_SOURCE,
      onboardingGrantClaimedAtMillis: nowMillis,
    };
    transaction.set(
      ref,
      { ...granted, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    return granted;
  });
}

async function completeOnboardingAccess(uid, nowMillis = Date.now()) {
  const ref = entitlementRef(uid);
  return getFirestore().runTransaction(async (transaction) => {
    const current = entitlementFromSnapshot(await transaction.get(ref));
    if (current.onboardingCompletedAtMillis && current.source !== ONBOARDING_SOURCE) {
      return current;
    }
    const completed = {
      ...current,
      onboardingCompletedAtMillis: nowMillis,
    };
    if (current.source === ONBOARDING_SOURCE) {
      Object.assign(completed, {
        tier: "free",
        expiryTimeMillis: 0,
        purchaseToken: null,
        productId: null,
        source: null,
      });
    }
    transaction.set(
      ref,
      { ...completed, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    return completed;
  });
}

async function setFree(uid) {
  await entitlementRef(uid).set(
    {
      tier: "free",
      expiryTimeMillis: 0,
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
}

/** Find the uid that owns a purchase token (for RTDN, which has no uid). */
async function findUidByPurchaseToken(purchaseToken) {
  const q = await getFirestore()
    .collection(COLLECTION)
    .where("purchaseToken", "==", purchaseToken)
    .limit(1)
    .get();
  return q.empty ? null : q.docs[0].id;
}

module.exports = {
  getEntitlement,
  isEntitled,
  canBeginOnboardingAccess,
  isPro,
  setPro,
  setFree,
  beginOnboardingAccess,
  completeOnboardingAccess,
  findUidByPurchaseToken,
  COLLECTION,
  ONBOARDING_SOURCE,
  ONBOARDING_ACCESS_MS,
};
