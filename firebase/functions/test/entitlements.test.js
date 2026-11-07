const test = require("node:test");
const assert = require("node:assert/strict");

const {
  canBeginOnboardingAccess,
  isEntitled,
  ONBOARDING_SOURCE,
  ONBOARDING_ACCESS_MS,
} = require("../lib/entitlements");

test("active onboarding access is entitled without paid slack", () => {
  const now = 1_000_000;
  assert.equal(
    isEntitled({
      tier: "pro",
      source: ONBOARDING_SOURCE,
      expiryTimeMillis: now + ONBOARDING_ACCESS_MS,
    }, now),
    true,
  );
  assert.equal(
    isEntitled({
      tier: "pro",
      source: ONBOARDING_SOURCE,
      expiryTimeMillis: now,
    }, now),
    false,
  );
});

test("paid access retains the RTDN expiry slack", () => {
  const now = 2_000_000;
  assert.equal(
    isEntitled({
      tier: "pro",
      source: "play",
      expiryTimeMillis: now - 1,
    }, now),
    true,
  );
});

test("free tier is never entitled", () => {
  assert.equal(
    isEntitled({
      tier: "free",
      source: null,
      expiryTimeMillis: Number.MAX_SAFE_INTEGER,
    }),
    false,
  );
});

test("onboarding access is one-time and never replaces a purchase", () => {
  const free = {
    tier: "free",
    expiryTimeMillis: 0,
    purchaseToken: null,
    onboardingGrantClaimedAtMillis: null,
    onboardingCompletedAtMillis: null,
  };
  assert.equal(canBeginOnboardingAccess(free), true);
  assert.equal(canBeginOnboardingAccess({ ...free, purchaseToken: "play-token" }), false);
  assert.equal(canBeginOnboardingAccess({ ...free, onboardingGrantClaimedAtMillis: 1 }), false);
  assert.equal(canBeginOnboardingAccess({ ...free, onboardingCompletedAtMillis: 1 }), false);
});
