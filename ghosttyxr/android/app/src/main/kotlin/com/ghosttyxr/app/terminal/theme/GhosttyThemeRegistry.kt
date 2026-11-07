package com.ghosttyxr.app.terminal.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Loads Ghostty `.txt` theme files bundled under `assets/themes/`. Each theme is parsed lazily
 * into a [GhosttyTheme] on first access and memoized.
 *
 * The registry also tracks the currently-selected theme name as a [StateFlow] so UI can react.
 * Wiring the active theme to libghostty's `nativeSetDefaultColors` is deferred until the JNI
 * bridge lands in Phase 2.
 */
object GhosttyThemeRegistry {

    /** Theme used by `buildDemoSnapshot` before a user pick exists. */
    const val DEFAULT_THEME_NAME = "Catppuccin Mocha"

    private val _themes = MutableStateFlow<List<String>>(emptyList())
    private val _active = MutableStateFlow<String?>(null)
    private val cache = mutableMapOf<String, GhosttyTheme>()
    private var prefs: SharedPreferences? = null

    val themes: StateFlow<List<String>> = _themes.asStateFlow()
    val active: StateFlow<String?> = _active.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (_themes.value.isNotEmpty()) return
        val names = context.assets.list("themes")?.sorted().orEmpty()
        _themes.value = names
        if (_active.value == null) {
            val saved = prefs?.getString(KEY_ACTIVE_THEME, null)
            _active.value = when {
                saved in names -> saved
                DEFAULT_THEME_NAME in names -> DEFAULT_THEME_NAME
                else -> names.firstOrNull()
            }
        }
    }

    fun setActive(name: String) {
        if (name !in _themes.value) return
        _active.value = name
        prefs?.edit()?.putString(KEY_ACTIVE_THEME, name)?.apply()
    }

    fun getTheme(context: Context, name: String): GhosttyTheme? {
        if (name !in _themes.value) return null
        cache[name]?.let { return it }
        val content = runCatching {
            context.assets.open("themes/$name").bufferedReader().readText()
        }.getOrNull() ?: return null
        val theme = GhosttyTheme.parse(name, content)
        cache[name] = theme
        return theme
    }

    /** Returns the currently active theme, or `null` if none selected / parse failed. */
    fun activeTheme(context: Context): GhosttyTheme? =
        _active.value?.let { getTheme(context, it) }

    private const val PREFS_NAME = "ghosttyxr_theme"
    private const val KEY_ACTIVE_THEME = "active_theme"
}
