const { getAuth } = require("firebase-admin/auth");
const { getAppCheck } = require("firebase-admin/app-check");
const logger = require("firebase-functions/logger");

/**
 * Verifies the Firebase ID token (Authorization: Bearer <token>) and the App
 * Check token (X-Firebase-AppCheck header) on a raw onRequest function.
 *
 * Returns the decoded uid on success. On failure, writes the HTTP error
 * response and returns null — callers must bail out when null.
 */
async function requireAuthAndAppCheck(req, res) {
  const authHeader = req.get("Authorization") || "";
  const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : "";
  if (!idToken) {
    res.status(401).json({ error: "Missing Authorization bearer token" });
    return null;
  }

  const appCheckToken = req.get("X-Firebase-AppCheck") || "";
  if (!appCheckToken) {
    res.status(401).json({ error: "Missing App Check token" });
    return null;
  }

  let uid;
  try {
    const decoded = await getAuth().verifyIdToken(idToken);
    uid = decoded.uid;
  } catch (err) {
    logger.warn("ID token verification failed", { message: err.message });
    res.status(401).json({ error: "Invalid auth token" });
    return null;
  }

  try {
    await getAppCheck().verifyToken(appCheckToken);
  } catch (err) {
    logger.warn("App Check verification failed", { message: err.message });
    res.status(401).json({ error: "Invalid App Check token" });
    return null;
  }

  return uid;
}

module.exports = { requireAuthAndAppCheck };
