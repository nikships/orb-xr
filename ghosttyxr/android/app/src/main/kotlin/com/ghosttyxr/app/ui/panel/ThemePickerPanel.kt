package com.ghosttyxr.app.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosttyxr.app.terminal.theme.GhosttyTheme
import com.ghosttyxr.app.terminal.theme.GhosttyThemeRegistry
import com.ghosttyxr.app.terminal.theme.GhosttyTokens
import com.ghosttyxr.app.ui.SETTINGS_PANEL_TEST_TAG
import com.ghosttyxr.app.ui.THEME_NAV_TEST_TAG
import com.ghosttyxr.app.ui.THEME_SEARCH_TEST_TAG
import com.ghosttyxr.app.ui.chrome.GxrIconButton
import com.ghosttyxr.app.ui.chrome.GxrPanel
import com.ghosttyxr.app.ui.chrome.GxrText
import com.ghosttyxr.app.ui.chrome.GxrTextField

/**
 * Root-panel row that opens the theme sub-menu. Shows a live swatch of the active theme
 * plus a chevron, mirroring a standard "drill-in" settings cell.
 */
@Composable
internal fun ThemeNavRow(activeTheme: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    val theme = remember(activeTheme) {
        activeTheme?.let { GhosttyThemeRegistry.getTheme(context, it) }
    }
    GxrPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(THEME_NAV_TEST_TAG),
        shape = RoundedCornerShape(6.dp),
        color = GhosttyTokens.SurfaceContainer,
        borderColor = GhosttyTokens.Outline.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (theme != null) {
                ThemeSwatch(theme)
            }
            Column(modifier = Modifier.weight(1f)) {
                GxrText("Terminal theme", fontSize = 13.sp)
                GxrText(
                    text = activeTheme ?: "Default",
                    fontSize = 11.sp,
                    color = GhosttyTokens.OnSurfaceVariant,
                )
            }
            GxrText("›", fontSize = 22.sp, color = GhosttyTokens.OnSurfaceVariant)
        }
    }
}

/**
 * The theme picker as its own full-panel sub-screen: a back header, search field, and the
 * scrollable catalog. Each row previews the theme's palette so picking is visual, not textual.
 */
@Composable
internal fun ThemePickerPanel(
    activeTheme: String?,
    availableThemes: List<String>,
    onThemeSelected: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var themeQuery by remember { mutableStateOf("") }
    val filteredThemes = remember(availableThemes, themeQuery) {
        if (themeQuery.isBlank()) availableThemes
        else availableThemes.filter { it.contains(themeQuery, ignoreCase = true) }
    }
    GxrPanel(
        modifier = modifier
            .fillMaxSize()
            .testTag(SETTINGS_PANEL_TEST_TAG),
        shape = RoundedCornerShape(GhosttyTokens.PaneCorner),
        color = GhosttyTokens.Surface,
        borderColor = GhosttyTokens.Outline,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GxrIconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to settings" },
                ) {
                    GxrText("‹", fontSize = 22.sp, color = GhosttyTokens.Accent)
                }
                GxrText("Theme", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            GxrTextField(
                value = themeQuery,
                onValueChange = { themeQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(THEME_SEARCH_TEST_TAG),
                singleLine = true,
                placeholder = "Search themes",
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filteredThemes, key = { it }) { themeName ->
                    ThemeCatalogRow(
                        themeName = themeName,
                        theme = remember(themeName) { GhosttyThemeRegistry.getTheme(context, themeName) },
                        selected = themeName == activeTheme,
                        onClick = { onThemeSelected(themeName) },
                    )
                }
            }
        }
    }
}

/** One row in the theme catalog: palette preview, name, and a selected check. */
@Composable
private fun ThemeCatalogRow(
    themeName: String,
    theme: GhosttyTheme?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GxrPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) GhosttyTokens.Accent.copy(alpha = 0.10f) else Color.Transparent,
        borderColor = if (selected) GhosttyTokens.Accent else GhosttyTokens.Outline.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (theme != null) {
                ThemeSwatch(theme)
            }
            GxrText(
                text = themeName,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) GhosttyTokens.Accent else GhosttyTokens.OnSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                GxrText("✓", fontSize = 15.sp, color = GhosttyTokens.Accent)
            }
        }
    }
}

/**
 * A compact preview tile of a theme: its background with a few palette accents stacked,
 * so the catalog reads as colour chips rather than a wall of names.
 */
@Composable
private fun ThemeSwatch(theme: GhosttyTheme) {
    val accents = remember(theme) {
        // Red, green, blue, magenta from the ANSI palette make a recognisable signature.
        listOf(1, 2, 4, 5).map { theme.palette.getOrElse(it) { theme.foreground } }
    }
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 28.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(theme.background)
            .border(1.dp, GhosttyTokens.Outline.copy(alpha = 0.6f), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            accents.forEach { dot ->
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dot),
                )
            }
        }
    }
}
