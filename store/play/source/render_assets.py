#!/usr/bin/env python3
"""Render every Google Play listing asset for Orb XR (com.ghosttyxr.app).

Dependency-light asset pipeline: the only requirement is Pillow
(`pip install -r store/play/source/requirements.txt`).

Determinism contract: rendering is deterministic at the PIXEL level (fixed
seeds, no timestamps) — a rerender is pixel-identical to the committed assets
(`ImageChops.difference(a, b).getbbox() is None`). Exact PNG BYTES are not
guaranteed across Pillow/zlib versions (encoder output is version-dependent);
the committed files were encoded with Pillow 11.3.0. Verify rerenders by
diffing pixels, not bytes.

Everything else — fonts, terminal color themes, chrome design tokens — is
read from this repository so the renders stay faithful to the shipping app:

  * Typeface  : JetBrains Mono TTFs from `ghosttyxr/android/app/src/main/res/font/`
                (the app's bundled chrome + terminal face).
  * Palettes  : real Ghostty theme files from
                `ghosttyxr/android/app/src/main/assets/themes/` (Catppuccin
                Mocha is the app default — see GhosttyThemeRegistry.kt).
  * Chrome    : color/geometry tokens mirrored from
                `com.ghosttyxr.app.terminal.theme.GhosttyTokens`
                (GhosttyXRTheme.kt) and the pane status-bar layout in
                `ui/pane/PaneSurface.kt`.

Outputs (written to `store/play/assets/`):

  icon/icon_512.png                     512x512   32-bit PNG (Play store icon)
  feature/feature_graphic_1024x500.png  1024x500  24-bit PNG, no alpha
  screenshots/xr/01..06 *.png           3840x2400 24-bit PNG, 8:5 (Android XR slot)

Usage:
  python3 store/play/source/render_assets.py            # render everything
  python3 store/play/source/render_assets.py --only 03  # one screenshot

The screenshots are studio recreations of the real Orb XR UI (exact tokens,
fonts, themes, status-bar chrome, pane layout and feature states from the
source tree). Replace with on-device captures via `adb shell screencap` when a
Galaxy XR unit is available if you prefer raw captures — the Play slot specs
stay the same. See store/play/README.md.
"""

from __future__ import annotations

import argparse
import math
import random
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# --------------------------------------------------------------------------- paths

HERE = Path(__file__).resolve().parent          # store/play/source
PLAY = HERE.parent                              # store/play
REPO = PLAY.parent.parent                       # repo root
FONT_DIR = REPO / "ghosttyxr/android/app/src/main/res/font"
THEME_DIR = REPO / "ghosttyxr/android/app/src/main/assets/themes"
OUT = PLAY / "assets"

# --------------------------------------------------------------------------- tokens
# Mirrors com.ghosttyxr.app.terminal.theme.GhosttyTokens exactly.

BACKGROUND = "#0A0E13"
SURFACE = "#10151C"
SURFACE_CONTAINER = "#141B24"
OUTLINE = "#2C3A49"
ON_SURFACE = "#E7EDF4"
ON_SURFACE_VARIANT = "#A6B6C7"
ON_SURFACE_FAINT = "#7E8EA0"
ACCENT = "#3DDADF"          # focused pane + interactive highlight
IDLE_BORDER = (166, 182, 199, 51)   # 0x33A6B6C7
ONLINE = "#6BE675"
DANGER = "#FF8A80"
WARNING = "#FFC773"
SCRIM_STRONG = (10, 14, 19, 204)    # 0xCC0A0E13

VOID_TOP = "#04060A"
VOID_BOTTOM = "#0A0E13"


_HEX_DIGITS = re.compile(r"^[0-9a-fA-F]+$")


def _strip_trailing_comment(value: str) -> str:
    """Strip a whitespace-delimited trailing comment (e.g. ``#1e1e2e  # note``).

    Only whitespace followed by ``#`` starts a comment: a leading ``#`` is
    part of a valid color, so naive ``split("#")`` would destroy the value.
    """
    return re.sub(r"\s+#.*$", "", value).strip()


def hex_rgb(h: str) -> tuple[int, int, int]:
    """Parse ``#rgb``, ``#rrggbb`` or ``#rrggbbaa`` (leading ``#`` optional).

    3-digit shorthand is expanded; the alpha byte of 8-digit values is
    ignored (RGB prefix wins). Raises ``ValueError`` with a clear message on
    anything malformed instead of mis-slicing it.
    """
    cleaned = _strip_trailing_comment(h)
    if cleaned.startswith("#"):
        cleaned = cleaned[1:]
    if len(cleaned) not in (3, 6, 8) or not _HEX_DIGITS.match(cleaned):
        raise ValueError(
            f"malformed hex color {h!r}: expected #rgb, #rrggbb or #rrggbbaa"
        )
    if len(cleaned) == 3:
        cleaned = "".join(c * 2 for c in cleaned)
    return tuple(int(cleaned[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


# --------------------------------------------------------------------------- fonts

_FONT_FILES = {
    "regular": "jetbrains_mono_regular.ttf",
    "medium": "jetbrains_mono_medium.ttf",
    "semibold": "jetbrains_mono_semibold.ttf",
    "bold": "jetbrains_mono_bold.ttf",
}
_font_cache: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}


def font(weight: str, px: int) -> ImageFont.FreeTypeFont:
    key = (weight, px)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(str(FONT_DIR / _FONT_FILES[weight]), px)
    return _font_cache[key]


# --------------------------------------------------------------------------- themes
# Parse real Ghostty theme files bundled with the app.


@dataclass
class Theme:
    name: str
    background: str
    foreground: str
    palette: dict[int, str] = field(default_factory=dict)

    def c(self, key: str) -> str:
        """Resolve a semantic color key to hex."""
        named = {
            "fg": self.foreground,
            "bg": self.background,
            "dim": self.palette.get(8, self.foreground),
            "red": self.palette.get(9, self.palette.get(1, self.foreground)),
            "green": self.palette.get(10, self.palette.get(2, self.foreground)),
            "yellow": self.palette.get(11, self.palette.get(3, self.foreground)),
            "blue": self.palette.get(12, self.palette.get(4, self.foreground)),
            "magenta": self.palette.get(13, self.palette.get(5, self.foreground)),
            "cyan": self.palette.get(14, self.palette.get(6, self.foreground)),
            "white": self.palette.get(15, self.foreground),
        }
        return named.get(key, key)  # pass hex through


def load_theme(name: str) -> Theme:
    """Parse a Ghostty theme file with exact key matching.

    Only ``background``, ``foreground`` and ``palette`` are consumed, matched
    on the full key (so ``background-opacity`` etc. are ignored rather than
    clobbering ``background``). ``palette`` values must be ``INDEX=COLOR``.
    Blank lines and full-line comments are skipped; whitespace-delimited
    trailing comments are stripped from values. Malformed palette entries
    raise ``ValueError`` naming the file and line.
    """
    theme = Theme(name=name, background="#1e1e2e", foreground="#cdd6f4")
    for lineno, raw in enumerate((THEME_DIR / name).read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue  # blank, full-line comment, or not a key=value line
        key, _, value = (part.strip() for part in line.partition("="))
        if key == "palette":
            entry = _strip_trailing_comment(value)
            idx_str, sep, color = (part.strip() for part in entry.partition("="))
            if not sep or not idx_str.isdigit() or not color:
                raise ValueError(
                    f"{name}:{lineno}: malformed palette entry {raw!r} "
                    "(expected 'palette = INDEX=COLOR')"
                )
            theme.palette[int(idx_str)] = color
        elif key == "background":
            theme.background = _strip_trailing_comment(value)
        elif key == "foreground":
            theme.foreground = _strip_trailing_comment(value)
    return theme


MOCHA = "Catppuccin Mocha"  # GhosttyThemeRegistry.DEFAULT_THEME_NAME

# --------------------------------------------------------------------------- canvas


def void_canvas(w: int, h: int, stars: int = 0, seed: int = 7) -> Image.Image:
    """Deep-ink vertical gradient, optionally with the starry focus-mode sky."""
    top, bottom = hex_rgb(VOID_TOP), hex_rgb(VOID_BOTTOM)
    col = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        col.putpixel((0, y), tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    img = col.resize((w, h))
    if stars:
        rng = random.Random(seed)
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        for _ in range(stars):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.choice([1, 1, 1, 2, 2, 3])
            a = rng.randint(40, 170)
            tint = rng.choice([(231, 237, 244), (166, 214, 223), (200, 205, 230)])
            d.ellipse([x - r, y - r, x + r, y + r], fill=(*tint, a))
        layer = layer.filter(ImageFilter.GaussianBlur(0.6))
        img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")
    return img


def rounded(w: int, h: int, radius: int, fill, border=None, border_w: int = 0) -> Image.Image:
    """Anti-aliased rounded rectangle rendered at 2x and downsampled."""
    s = 2
    img = Image.new("RGBA", (w * s, h * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w * s - 1, h * s - 1], radius * s, fill=fill)
    if border and border_w:
        d.rounded_rectangle(
            [border_w * s // 2, border_w * s // 2, w * s - 1 - border_w * s // 2, h * s - 1 - border_w * s // 2],
            radius * s,
            outline=border,
            width=border_w * s,
        )
    return img.resize((w, h), Image.LANCZOS)


def glow(canvas: Image.Image, box: tuple[int, int, int, int], color: str, radius: int, alpha: int) -> None:
    """Soft rectangular halo behind a pane (phosphor glow, cf. GlowIndication)."""
    x0, y0, x1, y1 = box
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle([x0, y0, x1, y1], 48, fill=(*hex_rgb(color), alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(radius))
    canvas.alpha_composite(layer)


# --------------------------------------------------------------------------- pane

Segment = tuple[str, str]           # (color key or hex, text)
Line = list[Segment]


@dataclass
class PaneSpec:
    pane_id: int
    lines: list[Line]
    state: str = "connected"        # status label, cf. describeState()
    title: str | None = None        # SSH window title shown when connected
    theme: str = MOCHA
    focused: bool = False
    listening: bool = False         # voice: green border + mic chip
    font_px: int = 30
    bg_opacity: float = 0.97


def status_dot_color(state: str) -> str:
    if state.startswith("connected") or state == "title":
        return ONLINE
    if state.startswith(("connecting", "reconnecting", "authenticated")):
        return WARNING
    if state.startswith("failed"):
        return DANGER
    return ON_SURFACE_FAINT


def render_pane(spec: PaneSpec, w: int, h: int) -> Image.Image:
    """One terminal pane: status bar + cell grid, per PaneSurface.kt."""
    theme = load_theme(spec.theme)
    corner = 44  # PaneCorner 24dp at cockpit render scale

    bg = (*hex_rgb(theme.background), int(255 * spec.bg_opacity))
    pane = rounded(w, h, corner, bg)

    # Focus border: accent when focused, Online while voice-listening (PaneSurface).
    border_color = None
    if spec.listening:
        border_color = (*hex_rgb(ONLINE), 255)
    elif spec.focused:
        border_color = (*hex_rgb(ACCENT), 255)
    if border_color:
        ring = rounded(w, h, corner, (0, 0, 0, 0), border=border_color, border_w=5)
        pane.alpha_composite(ring)
    else:
        ring = rounded(w, h, corner, (0, 0, 0, 0), border=IDLE_BORDER, border_w=3)
        pane.alpha_composite(ring)

    d = ImageDraw.Draw(pane)

    # ---- status bar (GxrPanel: ScrimStrong, ChromeCorner, dot + labels) ----
    pad = 20
    sb_h = 64
    sb_font = font("semibold", 26)
    state_font = font("medium", 24)
    theme_font = font("regular", 22)

    label = f"pane {spec.pane_id}"
    state_text = spec.title if (spec.state == "connected" and spec.title) else spec.state
    theme_text = f"· {spec.theme}"

    sb_w = (
        24 + 16 + 14
        + int(d.textlength(label, sb_font)) + 16
        + int(d.textlength(state_text, state_font)) + 16
        + int(d.textlength(theme_text, theme_font)) + 24
    )
    if spec.listening:
        sb_w += 64
    bar = rounded(sb_w, sb_h, 18, SCRIM_STRONG)
    pane.alpha_composite(bar, (pad, pad))

    bd = ImageDraw.Draw(pane)
    cx = pad + 24
    cy = pad + sb_h // 2
    bd.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=status_dot_color(spec.state))
    tx = cx + 8 + 14
    bd.text((tx, cy), label, font=sb_font, anchor="lm",
            fill=ACCENT if spec.focused else ON_SURFACE)
    tx += int(bd.textlength(label, sb_font)) + 16
    bd.text((tx, cy), state_text, font=state_font, anchor="lm", fill=ON_SURFACE_VARIANT)
    tx += int(bd.textlength(state_text, state_font)) + 16
    bd.text((tx, cy), theme_text, font=theme_font, anchor="lm", fill=ON_SURFACE_FAINT)
    if spec.listening:
        # Mic glyph: capsule body + cradle arc + stem (VoiceMicButton, listening).
        tx += int(bd.textlength(theme_text, theme_font)) + 30
        mx = tx + 12
        bd.rounded_rectangle([mx - 6, cy - 15, mx + 6, cy + 1], 6, fill=ONLINE)
        bd.arc([mx - 12, cy - 10, mx + 12, cy + 8], 20, 160, fill=ONLINE, width=3)
        bd.line([mx, cy + 8, mx, cy + 14], fill=ONLINE, width=3)

    # ---- terminal cell grid ----
    mono = font("regular", spec.font_px)
    mono_b = font("bold", spec.font_px)
    line_h = int(spec.font_px * 1.5)
    ty = pad + sb_h + 26
    for line in spec.lines:
        tx = pad + 18
        for color_key, text in line:
            if not text or not color_key:
                continue
            bold = color_key.endswith("!")
            key = color_key.rstrip("!")
            f = mono_b if bold else mono
            fill = theme.c(key)
            d.text((tx, ty), text, font=f, fill=fill)
            tx += int(d.textlength(text, f))
        ty += line_h
        if ty > h - line_h:
            break
    return pane


def tilt(img: Image.Image, direction: str, strength: float = 0.10) -> Image.Image:
    """Perspective-tilt a pane toward the viewer (curved-arc arrangement).

    direction: 'left' pane sits left of center (its outer/left edge recedes).
    """
    w, h = img.size
    dy = int(h * strength / 2)
    dx = int(w * strength * 0.55)
    if direction == "left":
        quad = [(0 + dx, 0 + dy), (w, 0), (w, h), (0 + dx, h - dy)]
    elif direction == "right":
        quad = [(0, 0), (w - dx, dy), (w - dx, h - dy), (0, h)]
    else:
        return img
    out_w = w
    coeffs = _perspective_coeffs([(0, 0), (w, 0), (w, h), (0, h)], quad)
    return img.transform((out_w, h), Image.PERSPECTIVE, coeffs, Image.BICUBIC)


def _perspective_coeffs(src, dst):
    """Solve the 8 perspective coefficients mapping dst -> src (PIL convention)."""
    matrix = []
    for (sx, sy), (dx, dy) in zip(src, dst):
        matrix.append([dx, dy, 1, 0, 0, 0, -sx * dx, -sx * dy])
        matrix.append([0, 0, 0, dx, dy, 1, -sy * dx, -sy * dy])
    b = [c for pt in src for c in pt]
    return _solve(matrix, b)


def _solve(a, b):
    """Gaussian elimination (8x8) — avoids a numpy dependency."""
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for col in range(n):
        pivot = max(range(col, n), key=lambda r: abs(m[r][col]))
        m[col], m[pivot] = m[pivot], m[col]
        div = m[col][col]
        m[col] = [v / div for v in m[col]]
        for r in range(n):
            if r != col and m[r][col]:
                factor = m[r][col]
                m[r] = [rv - factor * cv for rv, cv in zip(m[r], m[col])]
    return [m[i][n] for i in range(n)]


# --------------------------------------------------------------------------- captions


def caption(canvas: Image.Image, headline: str, sub: str, x: int = 170, y: int = 130) -> None:
    d = ImageDraw.Draw(canvas)
    h_font = font("semibold", 108)
    s_font = font("regular", 44)
    d.text((x, y), "❯", font=h_font, fill=ACCENT)
    d.text((x + 90, y), headline, font=h_font, fill=ON_SURFACE)
    d.text((x + 96, y + 140), sub, font=s_font, fill=ON_SURFACE_VARIANT)


# --------------------------------------------------------------------------- transcripts
# Credible agent sessions. Color keys map through the active Ghostty theme.


def lines_claude_feature() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "claude")],
        [("cyan!", "● Claude Code")],
        [("dim", "──────────────────────────────────────────")],
        [("fg", "> wire the reconnect policy into the pane engine")],
        [("", "")],
        [("cyan", "● "), ("fg", "I'll update the engine loop, then run the unit tests.")],
        [("cyan", "● "), ("fg", "Read"), ("dim", "(terminal/session/ReconnectPolicy.kt)")],
        [("dim", "  └ 84 lines")],
        [("cyan", "● "), ("fg", "Update"), ("dim", "(terminal/session/TerminalSessionEngine.kt)")],
        [("dim", "  └ Updated with 12 additions and 4 removals")],
        [("red", "      - val delay = base * attempt")],
        [("green", "      + val delay = policy.nextDelay(attempt)")],
        [("green", "      +     .coerceAtMost(policy.maxDelayMs)")],
        [("cyan", "● "), ("fg", "Bash"), ("dim", "(./gradlew :app:testDebugUnitTest)")],
        [("dim", "  └ "), ("green", "212 tests completed, 212 passed")],
        [("green", "✓ "), ("fg", "Reconnects now back off with jitter.")],
        [("", "")],
        [("dim", "  esc to interrupt · ? for shortcuts")],
    ]


def lines_claude_tests() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "claude")],
        [("fg", "> add tests for wrap-around pane focus")],
        [("cyan", "● "), ("fg", "Write"), ("dim", "(util/PaneFocusTest.kt)")],
        [("dim", "  └ 61 lines")],
        [("cyan", "● "), ("fg", "Bash"), ("dim", "(./gradlew :app:testDebugUnitTest --tests '*PaneFocus*')")],
        [("dim", "  └ "), ("green", "8 tests completed, 8 passed")],
        [("green", "✓ "), ("fg", "Focus wraps 6 → 1 and 1 → 6.")],
        [("yellow", "▌ waiting for your input…")],
    ]


def lines_codex() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "codex")],
        [("magenta!", "› "), ("fg", "plan: session persistence across headset sleep")],
        [("dim", "• Reading terminal/session/…")],
        [("fg", "Proposed patch "), ("dim", "(3 files)")],
        [("yellow", "  M "), ("fg", "session/SessionForegroundService.kt")],
        [("yellow", "  M "), ("fg", "session/TerminalSessionRepository.kt")],
        [("green", "  A "), ("fg", "session/SessionRestoreTest.kt")],
        [("", "")],
        [("cyan", "apply changes? "), ("dim", "[y/N]")],
    ]


def lines_gemini() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "gemini")],
        [("blue!", "◆ "), ("fg", "Triaging the failing instrumented run…")],
        [("green", "  PASS "), ("dim", "app unit tests (210/210)")],
        [("red", "  FAIL "), ("fg", "app: FlatAppE2ETest.paneSpawn "), ("dim", "(211/212)")],
        [("blue!", "◆ "), ("fg", "Root cause: the AVD lacks camera")],
        [("fg", "  permission — gating the QR test.")],
    ]


def lines_aider() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "aider")],
        [("dim", "Repo-map: 214 files, 31k tokens")],
        [("fg", "Added "), ("cyan", "terminal/theme/GhosttyTheme.kt"), ("fg", " to the chat")],
        [("green", "Applied edit"), ("fg", " to GhosttyTheme.kt")],
        [("yellow", "Commit "), ("fg", "b3f21ae "), ("dim", "refactor(theme): parse selection colors")],
        [("green", "❯ "), ("fg", "█")],
    ]


def lines_build() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "./gradlew assembleDebug")],
        [("dim", "> Task :app:mergeDebugResources")],
        [("fg", "> Task :app:compileDebugKotlin")],
        [("", "")],
        [("green", "██████████████████████"), ("dim", "██████ "), ("yellow", "87%")],
        [("dim", "EXECUTING [2m 14s]")],
    ]


def lines_shell() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "git status -sb")],
        [("cyan", "## "), ("fg", "feat/voice-hotkey"), ("dim", "…origin/feat/voice-hotkey")],
        [("yellow", " M "), ("fg", "terminal/voice/VoiceHotkey.kt")],
        [("red", "?? "), ("fg", "docs/voice.md")],
        [("green", "❯ "), ("fg", "rg \"MAX_PANES\" -n")],
        [("magenta", "input/PaneNavShortcuts.kt"), ("dim", ":19:"), ("fg", "const val MAX_PANES = 6")],
        [("green", "❯ "), ("fg", "█")],
    ]


def lines_voice() -> list[Line]:
    return [
        [("green", "❯ "), ("fg", "claude")],
        [("fg", "> run the flaky test suite again and summarize failures")],
        [("cyan", "● "), ("fg", "Bash"), ("dim", "(./gradlew :app:testDebugUnitTest)")],
        [("dim", "  └ running…")],
        [("", "")],
        [("green", "● listening — speak to pane 2")],
        [("dim", "  \"approve the plan and keep the")],
        [("dim", "   backoff cap at thirty seconds\"")],
    ]


# --------------------------------------------------------------------------- screenshots

W, H = 3840, 2400


def caption_bottom_note(canvas: Image.Image, text: str) -> None:
    d = ImageDraw.Draw(canvas)
    f = font("regular", 34)
    tw = d.textlength(text, f)
    d.text((W - tw - 120, H - 96), text, font=f, fill=ON_SURFACE_FAINT)


def shot_01_fleet() -> Image.Image:
    canvas = void_canvas(W, H, stars=260, seed=11).convert("RGBA")
    caption(canvas, "Six agents. One field of view.",
            "Claude Code, Codex, Gemini, Aider, builds and shells — each in its own floating terminal.")

    pw, ph = 1150, 800
    gap = 60
    row_y = [430, 430 + ph + gap]
    specs_rows = [
        [
            PaneSpec(1, lines_claude_feature(), title="claude — feature", font_px=26),
            PaneSpec(2, lines_claude_tests(), title="claude — tests", focused=True, font_px=26),
            PaneSpec(3, lines_codex(), title="codex", font_px=26),
        ],
        [
            PaneSpec(4, lines_gemini(), title="gemini", font_px=26),
            PaneSpec(5, lines_build(), title="build", font_px=26),
            PaneSpec(6, lines_shell(), title="zsh", font_px=26),
        ],
    ]
    xs = [(W - 3 * pw - 2 * gap) // 2, (W - pw) // 2, (W + pw) // 2 + gap]
    for row, y in zip(specs_rows, row_y):
        for i, spec in enumerate(row):
            pane = render_pane(spec, pw, ph)
            if i == 0:
                pane = tilt(pane, "left")
            elif i == 2:
                pane = tilt(pane, "right")
            if spec.focused:
                glow(canvas, (xs[i], y, xs[i] + pw, y + ph), ACCENT, 60, 60)
            canvas.alpha_composite(pane, (xs[i], y))
    caption_bottom_note(canvas, "Agents run on your Mac over SSH — the headset is the window.")
    return canvas


def shot_02_native() -> Image.Image:
    canvas = void_canvas(W, H, stars=200, seed=23).convert("RGBA")
    caption(canvas, "A real terminal, rendered natively.",
            "libghostty draws every cell on the headset — true color, Kitty keyboard protocol, no screen mirroring.")
    pw, ph = 2680, 1780
    x, y = (W - pw) // 2, 440
    glow(canvas, (x, y, x + pw, y + ph), ACCENT, 70, 55)
    spec = PaneSpec(1, lines_claude_feature(), title="claude — feature", focused=True, font_px=44)
    canvas.alpha_composite(render_pane(spec, pw, ph), (x, y))
    return canvas


def shot_03_pairing() -> Image.Image:
    canvas = void_canvas(W, H, stars=200, seed=31).convert("RGBA")
    caption(canvas, "Pair with your Mac in seconds.",
            "The Mac companion shows a QR code — scan it and every pane connects over SSH on your network.")

    # Center pairing panel (GxrPanel look: Surface + Outline, PaneCorner).
    pw, ph = 1500, 1560
    x, y = (W - pw) // 2, 480
    panel = rounded(pw, ph, 44, (*hex_rgb(SURFACE), 250), border=(*hex_rgb(OUTLINE), 255), border_w=3)
    canvas.alpha_composite(panel, (x, y))
    d = ImageDraw.Draw(canvas)
    d.text((x + pw // 2, y + 90), "Connect to your Mac", font=font("semibold", 56),
           anchor="mm", fill=ON_SURFACE)
    d.text((x + pw // 2, y + 170), "Orb XR · menu bar", font=font("regular", 36),
           anchor="mm", fill=ON_SURFACE_VARIANT)

    # Decorative QR (deterministic pattern, encodes nothing).
    qs, cell = 900, 30
    qx, qy = x + (pw - qs) // 2, y + 250
    q = Image.new("RGB", (qs, qs), "#FFFFFF")
    qd = ImageDraw.Draw(q)
    rng = random.Random(42)
    n = qs // cell
    for r in range(n):
        for c in range(n):
            if rng.random() < 0.45:
                qd.rectangle([c * cell, r * cell, c * cell + cell - 2, r * cell + cell - 2], fill="#0A0E13")
    for fx, fy in [(0, 0), (n - 7, 0), (0, n - 7)]:
        qd.rectangle([fx * cell, fy * cell, (fx + 7) * cell, (fy + 7) * cell], fill="#FFFFFF")
        qd.rectangle([fx * cell, fy * cell, (fx + 7) * cell - 2, (fy + 7) * cell - 2], outline="#0A0E13", width=cell)
        qd.rectangle([(fx + 2) * cell, (fy + 2) * cell, (fx + 5) * cell - 2, (fy + 5) * cell - 2], fill="#0A0E13")
    qr_panel = rounded(qs + 60, qs + 60, 28, (255, 255, 255, 255))
    canvas.alpha_composite(qr_panel, (qx - 30, qy - 30))
    canvas.paste(q, (qx, qy))

    chip_font = font("medium", 38)
    chip_text = "● nik@macbook-pro.local — 6 panes connected"
    cw = int(d.textlength(chip_text, chip_font)) + 100
    chip = rounded(cw, 96, 18, SCRIM_STRONG, border=(*hex_rgb(ONLINE), 255), border_w=3)
    cxp = x + (pw - cw) // 2
    cyp = y + ph - 170
    canvas.alpha_composite(chip, (cxp, cyp))
    d.text((cxp + 50, cyp + 48), "●", font=chip_font, anchor="lm", fill=ONLINE)
    d.text((cxp + 100, cyp + 48), chip_text[2:], font=chip_font, anchor="lm", fill=ON_SURFACE)

    caption_bottom_note(canvas, "Host keys are pinned on first use; your Mac stays the source of truth.")
    return canvas


def shot_04_focus() -> Image.Image:
    canvas = void_canvas(W, H, stars=650, seed=5).convert("RGBA")
    caption(canvas, "Focus mode: make the work the world.",
            "Swap your room for a starry void — or stay in passthrough. Environments are one toggle away.")
    pw, ph = 1500, 1050
    gap = 90
    y = 620
    specs = [
        PaneSpec(1, lines_claude_feature(), title="claude — feature", font_px=32),
        PaneSpec(2, lines_build(), title="build", focused=True, font_px=32),
    ]
    xs = [(W - 2 * pw - gap) // 2, (W + gap) // 2]
    for i, spec in enumerate(specs):
        pane = render_pane(spec, pw, ph)
        pane = tilt(pane, "left" if i == 0 else "right", 0.07)
        if spec.focused:
            glow(canvas, (xs[i], y, xs[i] + pw, y + ph), ACCENT, 60, 50)
        canvas.alpha_composite(pane, (xs[i], y))

    # Environment picker chips (EnvironmentPicker: black void default + 4 skyboxes).
    d = ImageDraw.Draw(canvas)
    chip_font = font("medium", 36)
    labels = ["Black void", "Starry night", "Clear night", "Dense stars", "Moonless"]
    active = "Starry night"
    total = sum(int(d.textlength(t, chip_font)) + 90 for t in labels) + 40 * (len(labels) - 1)
    cxp = (W - total) // 2
    cyp = y + ph + 120
    for t in labels:
        cw = int(d.textlength(t, chip_font)) + 90
        is_active = t == active
        chip = rounded(cw, 92, 18,
                       (*hex_rgb(SURFACE_CONTAINER), 235),
                       border=(*hex_rgb(ACCENT), 255) if is_active else (*hex_rgb(OUTLINE), 255),
                       border_w=3)
        canvas.alpha_composite(chip, (cxp, cyp))
        d.text((cxp + cw // 2, cyp + 46), t, font=chip_font, anchor="mm",
               fill=ACCENT if is_active else ON_SURFACE_VARIANT)
        cxp += cw + 40
    return canvas


def shot_05_themes() -> Image.Image:
    canvas = void_canvas(W, H, stars=220, seed=17).convert("RGBA")
    caption(canvas, "463 Ghostty themes, built in.",
            "Catppuccin, Dracula, Gruvbox and 460 more — with adjustable text size and pane opacity.")

    # Theme picker panel (ThemePickerPanel: rows of name + ANSI swatches).
    pp_w, pp_h = 1130, 1520
    px, py = 260, 520
    panel = rounded(pp_w, pp_h, 44, (*hex_rgb(SURFACE), 250), border=(*hex_rgb(OUTLINE), 255), border_w=3)
    canvas.alpha_composite(panel, (px, py))
    d = ImageDraw.Draw(canvas)
    d.text((px + 60, py + 70), "Theme", font=font("semibold", 48), fill=ON_SURFACE)
    d.text((px + 60, py + 140), "463 themes · Ghostty catalog", font=font("regular", 34), fill=ON_SURFACE_FAINT)

    rows = ["Catppuccin Mocha", "Catppuccin Latte", "Dracula", "Gruvbox Dark",
            "Nord", "TokyoNight", "Rose Pine", "Solarized Dark Higher Contrast"]
    ry = py + 240
    for name in rows:
        active = name == MOCHA
        row = rounded(pp_w - 120, 130, 18,
                      (*hex_rgb(SURFACE_CONTAINER), 255) if active else (0, 0, 0, 0),
                      border=(*hex_rgb(ACCENT), 255) if active else None, border_w=3 if active else 0)
        canvas.alpha_composite(row, (px + 60, ry))
        d.text((px + 100, ry + 42), name, font=font("medium", 38),
               fill=ACCENT if active else ON_SURFACE)
        try:
            t = load_theme(name)
            sw = 46
            sx = px + 100
            for idx in (1, 2, 3, 4, 5, 6):
                d.rounded_rectangle([sx, ry + 92, sx + sw, ry + 118], 6,
                                    fill=t.palette.get(idx, t.foreground))
                sx += sw + 14
        except FileNotFoundError:
            pass
        ry += 150

    spec = PaneSpec(1, lines_claude_feature(), title="claude — feature", focused=True, font_px=36)
    pw, ph = 2050, 1520
    canvas.alpha_composite(render_pane(spec, pw, ph), (px + pp_w + 100, py))
    return canvas


def shot_06_voice() -> Image.Image:
    canvas = void_canvas(W, H, stars=220, seed=29).convert("RGBA")
    caption(canvas, "Steer by voice, keys, or controller.",
            "Push-to-talk dictation lands in the pane you're looking at; a palm-up gesture summons the hand menu.")

    # Focused pane in Listening state (green border, per PaneSurface).
    pw, ph = 2000, 1500
    x, y = 340, 540
    glow(canvas, (x, y, x + pw, y + ph), ONLINE, 70, 45)
    spec = PaneSpec(2, lines_voice(), title="claude — tests", focused=True, listening=True, font_px=40)
    canvas.alpha_composite(render_pane(spec, pw, ph), (x, y))

    # Control panel extract (ControlPanel.kt sections).
    pp_w, pp_h = 1080, 1500
    px = x + pw + 100
    panel = rounded(pp_w, pp_h, 44, (*hex_rgb(SURFACE), 250), border=(*hex_rgb(OUTLINE), 255), border_w=3)
    canvas.alpha_composite(panel, (px, y))
    d = ImageDraw.Draw(canvas)
    d.text((px + 60, y + 64), "❯_", font=font("semibold", 52), fill=ACCENT)

    def section(sy: int, title: str, rows: list[tuple[str, bool | None]]) -> int:
        d.text((px + 60, sy), title, font=font("semibold", 40), fill=ON_SURFACE_VARIANT)
        sy += 74
        for label, toggled in rows:
            d.text((px + 84, sy), label, font=font("regular", 38), fill=ON_SURFACE)
            if toggled is not None:
                tw, th_ = 96, 52
                tx_ = px + pp_w - 60 - tw
                fill = (*hex_rgb(ACCENT), 255) if toggled else (*hex_rgb(OUTLINE), 255)
                d.rounded_rectangle([tx_, sy - 6, tx_ + tw, sy - 6 + th_], th_ // 2, fill=fill)
                kx = tx_ + tw - th_ + 6 if toggled else tx_ + 6
                d.ellipse([kx, sy, kx + th_ - 12, sy + th_ - 12], fill=hex_rgb(BACKGROUND))
            sy += 86
        return sy + 30
    sy = y + 190
    sy = section(sy, "Terminals", [("pane 1 · claude — feature", None),
                                   ("pane 2 · claude — tests", None),
                                   ("+  New terminal", None)])
    sy = section(sy, "Voice", [("Voice input", True),
                               ("Auto-submit on silence", True),
                               ("Voice commands", True)])
    sy = section(sy, "Layout", [("Lock panels", False)])
    sy = section(sy, "Focus", [("Immersive mode", True)])
    caption_bottom_note(canvas, "Physical keyboards pair over Bluetooth; Alt+1–6 jumps between panes.")
    return canvas


# --------------------------------------------------------------------------- icon & feature graphic


def render_icon(size: int = 512) -> Image.Image:
    s = size * 4  # supersample
    img = void_canvas(s, s, stars=90, seed=3).convert("RGBA")

    # Cyan nebula glow behind the pane cluster.
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.ellipse([s * 0.18, s * 0.30, s * 0.82, s * 0.86], fill=(*hex_rgb(ACCENT), 46))
    layer = layer.filter(ImageFilter.GaussianBlur(s * 0.09))
    img.alpha_composite(layer)

    # Three-pane arc: the spatial glyph.
    pw, ph = int(s * 0.34), int(s * 0.44)
    side_w, side_h = int(s * 0.26), int(s * 0.38)
    cy = int(s * 0.50)

    def mini_pane(w: int, h: int, focused: bool) -> Image.Image:
        corner = int(w * 0.12)
        p = rounded(w, h, corner, (*hex_rgb("#10151C"), 245),
                    border=(*hex_rgb(ACCENT), 255) if focused else (*hex_rgb(OUTLINE), 255),
                    border_w=max(4, w // 44))
        pd = ImageDraw.Draw(p)
        bar_h = int(h * 0.14)
        pd.rounded_rectangle([int(w * 0.08), int(h * 0.07), int(w * 0.92), int(h * 0.07) + bar_h],
                             bar_h // 3, fill=(*hex_rgb("#0A0E13"), 255))
        dot = int(bar_h * 0.42)
        pd.ellipse([int(w * 0.12), int(h * 0.07) + bar_h // 2 - dot // 2,
                    int(w * 0.12) + dot, int(h * 0.07) + bar_h // 2 + dot // 2],
                   fill=ONLINE if focused else ON_SURFACE_FAINT)
        # Prompt glyph + cursor block on one text line.
        gf = font("bold", int(h * 0.22))
        pd.text((int(w * 0.14), int(h * 0.34)), "❯", font=gf, fill=ACCENT)
        cw_ = int(h * 0.13)
        cy0 = int(h * 0.365)
        pd.rectangle([int(w * 0.34), cy0, int(w * 0.34) + cw_, cy0 + int(h * 0.20)],
                     fill=(*hex_rgb(ACCENT), 255) if focused else (*hex_rgb(ON_SURFACE_FAINT), 200))
        # Faint output lines.
        for i, lw in enumerate((0.55, 0.42, 0.62)):
            yy = int(h * (0.70 + i * 0.09))
            pd.rounded_rectangle([int(w * 0.14), yy, int(w * (0.14 + lw)), yy + int(h * 0.035)],
                                 8, fill=(*hex_rgb(ON_SURFACE_FAINT), 120))
        return p

    left = tilt(mini_pane(side_w, side_h, False), "left", 0.22)
    right = tilt(mini_pane(side_w, side_h, False), "right", 0.22)
    center = mini_pane(pw, ph, True)
    img.alpha_composite(left, (int(s * 0.06), cy - side_h // 2))
    img.alpha_composite(right, (int(s * 0.68), cy - side_h // 2))
    img.alpha_composite(center, ((s - pw) // 2, cy - ph // 2))

    return img.resize((size, size), Image.LANCZOS)


def render_feature_graphic(w: int = 1024, h: int = 500) -> Image.Image:
    s = 3
    W_, H_ = w * s, h * s
    img = void_canvas(W_, H_, stars=210, seed=13).convert("RGBA")

    # Right-side pane cluster.
    pw, ph = int(W_ * 0.215), int(H_ * 0.56)
    cy = H_ // 2

    def cluster_pane(focused: bool, theme_name: str, lines: list[Line]) -> Image.Image:
        return render_pane(
            PaneSpec(1 if not focused else 2, lines, title="claude" if focused else "zsh",
                     theme=theme_name, focused=focused, font_px=int(H_ * 0.026)),
            pw, ph,
        )

    short_claude = [
        [("green", "❯ "), ("fg", "claude")],
        [("cyan", "● "), ("fg", "Running tests…")],
        [("dim", "  └ "), ("green", "212 passed")],
        [("green", "✓ "), ("fg", "Done.")],
    ]
    short_shell = [
        [("green", "❯ "), ("fg", "git push")],
        [("dim", "→ origin feat/xr")],
        [("green", "❯ "), ("fg", "█")],
    ]
    left_p = tilt(cluster_pane(False, MOCHA, short_shell), "left", 0.16)
    right_p = cluster_pane(True, MOCHA, short_claude)
    glow(img, (int(W_ * 0.755), cy - ph // 2, int(W_ * 0.755) + pw, cy + ph // 2), ACCENT, 40, 50)
    img.alpha_composite(left_p, (int(W_ * 0.665), cy - ph // 2 + int(H_ * 0.05)))
    img.alpha_composite(right_p, (int(W_ * 0.755), cy - ph // 2))

    d = ImageDraw.Draw(img)
    x = int(W_ * 0.05)
    title_px = int(H_ * 0.118)
    d.text((x, int(H_ * 0.27)), "❯", font=font("bold", title_px), fill=ACCENT)
    d.text((x + int(title_px * 0.75), int(H_ * 0.27)), "Orb XR",
           font=font("bold", title_px), fill=ON_SURFACE)
    d.text((x + int(title_px * 0.80), int(H_ * 0.46)), "Orchestrate your coding agents in XR",
           font=font("medium", int(H_ * 0.050)), fill=ON_SURFACE_VARIANT)
    d.text((x + int(title_px * 0.80), int(H_ * 0.565)),
           "Floating SSH terminals to your Mac",
           font=font("regular", int(H_ * 0.045)), fill=ON_SURFACE_FAINT)

    return img.resize((w, h), Image.LANCZOS).convert("RGB")


# --------------------------------------------------------------------------- main

SCREENSHOTS = {
    "01": ("01_fleet_cockpit.png", shot_01_fleet),
    "02": ("02_native_terminal.png", shot_02_native),
    "03": ("03_pair_with_mac.png", shot_03_pairing),
    "04": ("04_focus_mode.png", shot_04_focus),
    "05": ("05_themes.png", shot_05_themes),
    "06": ("06_voice_and_controls.png", shot_06_voice),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", help="render a single asset: icon, feature, or screenshot id (01..06)")
    args = parser.parse_args()

    (OUT / "icon").mkdir(parents=True, exist_ok=True)
    (OUT / "feature").mkdir(parents=True, exist_ok=True)
    (OUT / "screenshots/xr").mkdir(parents=True, exist_ok=True)

    def want(key: str) -> bool:
        return args.only is None or args.only == key

    if want("icon"):
        path = OUT / "icon/icon_512.png"
        render_icon().save(path, "PNG")
        print(f"✓ {path.relative_to(REPO)} (512x512 32-bit PNG)")

    if want("feature"):
        path = OUT / "feature/feature_graphic_1024x500.png"
        render_feature_graphic().save(path, "PNG")
        print(f"✓ {path.relative_to(REPO)} (1024x500 24-bit PNG, no alpha)")

    for key, (name, fn) in SCREENSHOTS.items():
        if not want(key):
            continue
        img = fn().convert("RGB")
        assert img.size == (W, H), f"{name}: {img.size} != {(W, H)}"
        path = OUT / "screenshots/xr" / name
        img.save(path, "PNG")
        print(f"✓ {path.relative_to(REPO)} ({W}x{H} 24-bit PNG, 8:5)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
