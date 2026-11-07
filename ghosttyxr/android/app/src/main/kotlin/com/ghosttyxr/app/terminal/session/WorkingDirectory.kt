package com.ghosttyxr.app.terminal.session

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Helpers for host working directories reported by OSC 7 (via libghostty) and for
 * safely injecting them into shell / tmux bootstrap commands.
 */
object WorkingDirectory {
    /**
     * Normalize a raw pwd from the terminal (plain absolute path or `file://` URL)
     * into a host absolute path. Returns null when the value is missing, relative,
     * or otherwise not safe to reuse as a cwd.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val path = when {
            trimmed.startsWith("file://") -> pathFromFileUrl(trimmed) ?: return null
            else -> trimmed
        }
        // Only absolute Unix paths — refuses relative/empty/drive-letter forms.
        if (!path.startsWith("/") || path.contains('\u0000')) return null
        return path
    }

    /** Shell-single-quote [path] for safe embedding in a remote shell command. */
    fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** `cd -- 'path'` suitable for post-connect submission (no trailing CR). */
    fun cdCommand(path: String): String = "cd -- ${shellQuote(path)}"

    /**
     * Build the one-shot post-connect line: optional `cd` then optional agent launch,
     * joined with `&&` so a failed cd does not start the agent in the wrong place.
     */
    fun postConnectCommand(workingDirectory: String?, launchCommand: String?): String? {
        val cwd = normalize(workingDirectory)
        val parts = buildList {
            if (cwd != null) add(cdCommand(cwd))
            if (!launchCommand.isNullOrBlank()) add(launchCommand.trim())
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" && ")
    }

    private fun pathFromFileUrl(url: String): String? {
        // file:///Users/me  |  file://localhost/Users/me  |  file://hostname/Users/me
        val withoutScheme = url.removePrefix("file://")
        val path = when {
            withoutScheme.startsWith("/") -> withoutScheme
            withoutScheme.startsWith("localhost/") -> withoutScheme.removePrefix("localhost")
            else -> {
                val slash = withoutScheme.indexOf('/')
                if (slash < 0) return null
                withoutScheme.substring(slash)
            }
        }
        return try {
            // The Charset overload requires API 33; the charset-name overload
            // has identical UTF-8 behavior and is available throughout minSdk.
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            path
        }
    }
}
