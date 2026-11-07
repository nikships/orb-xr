package com.ghosttyxr.app.terminal.settings

import android.content.Context
import android.content.SharedPreferences
import com.ghosttyxr.app.DEFAULT_ENVIRONMENT_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for user-facing display/terminal settings, persisted with
 * [SharedPreferences] and exposed as [StateFlow]s so Compose reacts to changes.
 *
 * Mirrors the object + flow + prefs pattern already used by
 * [com.ghosttyxr.app.terminal.theme.GhosttyThemeRegistry] (which keeps owning the active
 * theme). New settings should hang off this store: add a flow, a key, a default, a load line
 * in [init], and a setter.
 */
object GhosttySettingsStore {

    const val DEFAULT_TRANSPARENCY_PERCENT = 100f
    const val DEFAULT_FONT_SIZE_SP = 14f
    const val MIN_FONT_SIZE_SP = 10f
    const val MAX_FONT_SIZE_SP = 20f

    /** Default expanded settings-panel size (dp) — wide enough for two columns of cards. */
    const val DEFAULT_SETTINGS_PANEL_WIDTH_DP = 860f
    const val DEFAULT_SETTINGS_PANEL_HEIGHT_DP = 640f
    const val MIN_SETTINGS_PANEL_WIDTH_DP = 360f
    const val MIN_SETTINGS_PANEL_HEIGHT_DP = 420f
    const val MAX_SETTINGS_PANEL_WIDTH_DP = 1600f
    const val MAX_SETTINGS_PANEL_HEIGHT_DP = 1200f

    private val _backgroundTransparencyPercent = MutableStateFlow(DEFAULT_TRANSPARENCY_PERCENT)
    private val _fontSizeSp = MutableStateFlow(DEFAULT_FONT_SIZE_SP)
    private val _immersiveMode = MutableStateFlow(false)
    private val _selectedEnvironmentId = MutableStateFlow(DEFAULT_ENVIRONMENT_ID)
    private val _focusedPaneId = MutableStateFlow(0)
    private val _voiceEnabled = MutableStateFlow(false)
    private val _voiceAutoSubmit = MutableStateFlow(false)
    private val _voiceSilenceAutoStop = MutableStateFlow(false)
    private val _voiceCommandsEnabled = MutableStateFlow(false)
    private val _handMenuEnabled = MutableStateFlow(true)
    private val _petSpeechEnabled = MutableStateFlow(true)
    private val _introPlayed = MutableStateFlow(false)
    private val _settingsPanelWidthDp = MutableStateFlow(DEFAULT_SETTINGS_PANEL_WIDTH_DP)
    private val _settingsPanelHeightDp = MutableStateFlow(DEFAULT_SETTINGS_PANEL_HEIGHT_DP)
    private val _snapPanesToSurfaces = MutableStateFlow(false)

    val backgroundTransparencyPercent: StateFlow<Float> = _backgroundTransparencyPercent.asStateFlow()
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()
    val immersiveMode: StateFlow<Boolean> = _immersiveMode.asStateFlow()
    val selectedEnvironmentId: StateFlow<String> = _selectedEnvironmentId.asStateFlow()
    val focusedPaneId: StateFlow<Int> = _focusedPaneId.asStateFlow()

    /** Master toggle for the voice (speech-to-text) input feature. */
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    /** When on, a finished transcript is submitted immediately (appends "\n"). Footgun — default off. */
    val voiceAutoSubmit: StateFlow<Boolean> = _voiceAutoSubmit.asStateFlow()

    /** When on, recording stops automatically after a silence threshold. Default off to avoid clipping. */
    val voiceSilenceAutoStop: StateFlow<Boolean> = _voiceSilenceAutoStop.asStateFlow()

    /**
     * Toggle for Gemini Live voice **command** mode (cockpit control). Separate from dictation.
     * Deliberately **session-scoped, not persisted**: it holds the mic open and runs a costly,
     * always-listening Live session, so it must default off each launch rather than auto-opening
     * one on app start.
     */
    val voiceCommandsEnabled: StateFlow<Boolean> = _voiceCommandsEnabled.asStateFlow()

    /**
     * Master toggle for the palm-up voice pet (gesture reveal of the always-listening companion).
     * Persisted, default **on**; a kill-switch for users who hit gesture false-positives. When off,
     * [HandMenuController] still won't summon, but the keyboard hotkey and status pill remain.
     * The prefs key predates the pet (it gated the old hand menu) and is kept so upgrades don't
     * reset the user's choice.
     */
    val handMenuEnabled: StateFlow<Boolean> = _handMenuEnabled.asStateFlow()

    /**
     * "Speak replies": when on, the pet's Result/Error toasts and notable notices are also
     * spoken via Gemini Flash TTS (issue #55). Persisted, default **on** — voice is additive
     * to the visual toast and each utterance costs a TTS call, so this is the off-switch.
     */
    val petSpeechEnabled: StateFlow<Boolean> = _petSpeechEnabled.asStateFlow()

    /** True once first-flight onboarding completes or is skipped. Legacy key kept for upgrades. */
    val introPlayed: StateFlow<Boolean> = _introPlayed.asStateFlow()

    /**
     * Expanded settings-panel size (dp). User-resizable in XR; the panel body reflows its
     * card columns from the measured width, so persisting the size keeps the chosen layout.
     */
    val settingsPanelWidthDp: StateFlow<Float> = _settingsPanelWidthDp.asStateFlow()
    val settingsPanelHeightDp: StateFlow<Float> = _settingsPanelHeightDp.asStateFlow()

    /**
     * Opt-in "snap panes to real-world surfaces": detached panes may anchor onto detected
     * desk/wall planes via `MovePolicy.anchor(...)`. Persisted, default **off** — plane
     * detection costs a perception session and free-floating panes are the cockpit default.
     */
    val snapPanesToSurfaces: StateFlow<Boolean> = _snapPanesToSurfaces.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _backgroundTransparencyPercent.value =
            p.getFloat(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY_PERCENT)
        _fontSizeSp.value =
            p.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        _immersiveMode.value = p.getBoolean(KEY_IMMERSIVE, false)
        _selectedEnvironmentId.value =
            p.getString(KEY_ENVIRONMENT, DEFAULT_ENVIRONMENT_ID) ?: DEFAULT_ENVIRONMENT_ID
        _focusedPaneId.value = p.getInt(KEY_FOCUSED_PANE, 0).coerceAtLeast(0)
        _voiceEnabled.value = p.getBoolean(KEY_VOICE_ENABLED, false)
        _voiceAutoSubmit.value = p.getBoolean(KEY_VOICE_AUTO_SUBMIT, false)
        _voiceSilenceAutoStop.value = p.getBoolean(KEY_VOICE_SILENCE_AUTO_STOP, false)
        _handMenuEnabled.value = p.getBoolean(KEY_HAND_MENU_ENABLED, true)
        _petSpeechEnabled.value = p.getBoolean(KEY_PET_SPEECH_ENABLED, true)
        _introPlayed.value = p.getBoolean(KEY_INTRO_PLAYED, false)
        _settingsPanelWidthDp.value = p.getFloat(KEY_SETTINGS_PANEL_WIDTH, DEFAULT_SETTINGS_PANEL_WIDTH_DP)
            .coerceIn(MIN_SETTINGS_PANEL_WIDTH_DP, MAX_SETTINGS_PANEL_WIDTH_DP)
        _settingsPanelHeightDp.value = p.getFloat(KEY_SETTINGS_PANEL_HEIGHT, DEFAULT_SETTINGS_PANEL_HEIGHT_DP)
            .coerceIn(MIN_SETTINGS_PANEL_HEIGHT_DP, MAX_SETTINGS_PANEL_HEIGHT_DP)
        _snapPanesToSurfaces.value = p.getBoolean(KEY_SNAP_PANES_TO_SURFACES, false)
        // voiceCommandsEnabled is intentionally NOT loaded — it's session-scoped (see its flow).
        // Assign last: setters gate persistence on a non-null `prefs`, so populating the flows
        // before publishing the handle keeps a concurrent setter from racing the loaded values.
        prefs = p
    }

    // Slider-backed settings update in-memory on every drag tick for live preview, then persist
    // once via `persist*` from the slider's `onValueChangeFinished`. Writing on every tick would
    // flood `QueuedWork` with disk writes that Android joins on the main thread at onPause/onStop.
    fun setBackgroundTransparencyPercent(value: Float) {
        _backgroundTransparencyPercent.value = value.coerceIn(0f, 100f)
    }

    fun persistBackgroundTransparencyPercent() =
        putFloat(KEY_TRANSPARENCY, _backgroundTransparencyPercent.value)

    fun setFontSizeSp(value: Float) {
        _fontSizeSp.value = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    fun persistFontSizeSp() = putFloat(KEY_FONT_SIZE, _fontSizeSp.value)

    fun setImmersiveMode(enabled: Boolean) = setBoolean(_immersiveMode, KEY_IMMERSIVE, enabled)

    fun setSelectedEnvironmentId(id: String) {
        _selectedEnvironmentId.value = id
        putString(KEY_ENVIRONMENT, id)
    }

    fun setFocusedPaneId(id: Int) {
        val clamped = id.coerceAtLeast(0)
        _focusedPaneId.value = clamped
        putInt(KEY_FOCUSED_PANE, clamped)
    }

    fun setVoiceEnabled(enabled: Boolean) = setBoolean(_voiceEnabled, KEY_VOICE_ENABLED, enabled)

    fun setVoiceAutoSubmit(enabled: Boolean) =
        setBoolean(_voiceAutoSubmit, KEY_VOICE_AUTO_SUBMIT, enabled)

    fun setVoiceSilenceAutoStop(enabled: Boolean) =
        setBoolean(_voiceSilenceAutoStop, KEY_VOICE_SILENCE_AUTO_STOP, enabled)

    /** In-memory only (see [voiceCommandsEnabled]); not written to prefs. */
    fun setVoiceCommandsEnabled(enabled: Boolean) {
        _voiceCommandsEnabled.value = enabled
    }

    fun setHandMenuEnabled(enabled: Boolean) =
        setBoolean(_handMenuEnabled, KEY_HAND_MENU_ENABLED, enabled)

    fun setSnapPanesToSurfaces(enabled: Boolean) =
        setBoolean(_snapPanesToSurfaces, KEY_SNAP_PANES_TO_SURFACES, enabled)

    fun setPetSpeechEnabled(enabled: Boolean) =
        setBoolean(_petSpeechEnabled, KEY_PET_SPEECH_ENABLED, enabled)

    /** Mark first-flight onboarding complete so it never replays. Persisted. */
    fun markIntroPlayed() = setBoolean(_introPlayed, KEY_INTRO_PLAYED, true)

    /**
     * Persisted immediately (unlike the slider-backed settings): resize events arrive per
     * gesture end, not per frame, so there's no disk-write flood to buffer.
     */
    fun setSettingsPanelSize(widthDp: Float, heightDp: Float) {
        val w = widthDp.takeIf { it.isFinite() }
            ?.coerceIn(MIN_SETTINGS_PANEL_WIDTH_DP, MAX_SETTINGS_PANEL_WIDTH_DP)
            ?: DEFAULT_SETTINGS_PANEL_WIDTH_DP
        val h = heightDp.takeIf { it.isFinite() }
            ?.coerceIn(MIN_SETTINGS_PANEL_HEIGHT_DP, MAX_SETTINGS_PANEL_HEIGHT_DP)
            ?: DEFAULT_SETTINGS_PANEL_HEIGHT_DP
        _settingsPanelWidthDp.value = w
        _settingsPanelHeightDp.value = h
        putFloat(KEY_SETTINGS_PANEL_WIDTH, w)
        putFloat(KEY_SETTINGS_PANEL_HEIGHT, h)
    }

    val onboardingAccessCompletionPending: Boolean
        get() = prefs?.getBoolean(KEY_ONBOARDING_ACCESS_COMPLETION_PENDING, false) ?: false

    fun markOnboardingAccessCompletionPending() =
        putBoolean(KEY_ONBOARDING_ACCESS_COMPLETION_PENDING, true)

    fun clearOnboardingAccessCompletionPending() =
        putBoolean(KEY_ONBOARDING_ACCESS_COMPLETION_PENDING, false)

    private fun setBoolean(flow: MutableStateFlow<Boolean>, key: String, value: Boolean) {
        flow.value = value
        putBoolean(key, value)
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun putFloat(key: String, value: Float) {
        prefs?.edit()?.putFloat(key, value)?.apply()
    }

    private fun putString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    private fun putInt(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
    }

    private const val PREFS_NAME = "ghosttyxr_settings"
    private const val KEY_TRANSPARENCY = "background_transparency_percent"
    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_IMMERSIVE = "immersive_mode"
    private const val KEY_ENVIRONMENT = "selected_environment_id"
    private const val KEY_FOCUSED_PANE = "focused_pane_id"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_VOICE_AUTO_SUBMIT = "voice_auto_submit"
    private const val KEY_VOICE_SILENCE_AUTO_STOP = "voice_silence_auto_stop"
    private const val KEY_HAND_MENU_ENABLED = "hand_menu_enabled"
    private const val KEY_PET_SPEECH_ENABLED = "pet_speech_enabled"
    private const val KEY_INTRO_PLAYED = "intro_played"
    private const val KEY_SETTINGS_PANEL_WIDTH = "settings_panel_width_dp"
    private const val KEY_SETTINGS_PANEL_HEIGHT = "settings_panel_height_dp"
    private const val KEY_SNAP_PANES_TO_SURFACES = "snap_panes_to_surfaces"
    private const val KEY_ONBOARDING_ACCESS_COMPLETION_PENDING =
        "onboarding_access_completion_pending"
}
