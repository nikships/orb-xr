package com.ghosttyxr.app.ui

/**
 * Public Compose `testTag`s for the cockpit UI — the stable contract the
 * instrumented tests (`FlatAppE2ETest`, `SpatialPanelSemanticsTest`) match
 * against. Kept in one place so test and production code reference the same
 * constants and renames stay in sync.
 */
const val SETTINGS_PANEL_TEST_TAG = "ghosttyxr.settings.panel"
const val PAIRING_STATUS_TEST_TAG = "ghosttyxr.pairing.status"
const val THEME_SEARCH_TEST_TAG = "ghosttyxr.settings.themeSearch"
const val THEME_NAV_TEST_TAG = "ghosttyxr.settings.themeNav"
const val FORGET_MAC_TEST_TAG = "ghosttyxr.pairing.forgetMac"
const val PRO_UPGRADE_BUTTON_TEST_TAG = "ghosttyxr.settings.proUpgrade"
const val PRO_MANAGE_BUTTON_TEST_TAG = "ghosttyxr.settings.proManage"
const val SPACE_TOGGLE_TEST_TAG = "ghosttyxr.settings.spaceToggle"
const val HOST_KEYBOARD_STATUS_TEST_TAG = "ghosttyxr.settings.hostKeyboardStatus"
const val RESUME_LIST_TEST_TAG = "ghosttyxr.resume.list"
const val RESUME_SESSION_TEST_TAG_PREFIX = "ghosttyxr.resume.session."
const val START_FRESH_TEST_TAG = "ghosttyxr.resume.startFresh"
const val END_SESSION_TEST_TAG_PREFIX = "ghosttyxr.resume.endSession."
const val END_ALL_SESSIONS_TEST_TAG = "ghosttyxr.resume.endAllSessions"
const val ONBOARDING_ROOT_TEST_TAG = "ghosttyxr.onboarding.root"
const val ONBOARDING_CONTINUE_TEST_TAG = "ghosttyxr.onboarding.continue"
const val ONBOARDING_BACK_TEST_TAG = "ghosttyxr.onboarding.back"
const val ONBOARDING_SKIP_TEST_TAG = "ghosttyxr.onboarding.skip"
const val ONBOARDING_MUTE_TEST_TAG = "ghosttyxr.onboarding.mute"
const val ONBOARDING_COACH_TEST_TAG = "ghosttyxr.onboarding.coach"
const val ONBOARDING_COACH_ACTION_TEST_TAG = "ghosttyxr.onboarding.coachAction"
const val ONBOARDING_COACH_STATUS_TEST_TAG = "ghosttyxr.onboarding.coachStatus"
const val ONBOARDING_COACH_MUTE_TEST_TAG = "ghosttyxr.onboarding.coachMute"
const val WINDOW_MIRROR_VIDEO_TEST_TAG = "ghosttyxr.mirror.video"
const val WINDOW_MIRROR_FALLBACK_TEST_TAG = "ghosttyxr.mirror.fallback"

/** Dangerous, runtime-granted permission gating ARCore hand tracking (the palm-up voice pet). */
const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"

/**
 * Dangerous, runtime-granted permission gating DeviceTrackingMode.SPATIAL head-pose access on
 * immersive devices — used by rotateToLookAtUser panes and the voice pet's "face me" billboard.
 */
const val HEAD_TRACKING_PERMISSION = "android.permission.HEAD_TRACKING"
