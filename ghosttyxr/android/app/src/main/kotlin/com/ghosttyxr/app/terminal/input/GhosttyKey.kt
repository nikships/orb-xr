package com.ghosttyxr.app.terminal.input

/**
 * Ghostty `input.Key` enum values (c_int backed, sequential from 0).
 * Layout-independent W3C UI Events `code` identifiers — see
 * https://www.w3.org/TR/uievents-code.
 *
 * MUST match the declaration order in libghostty's `src/input/key.zig`.
 * The Zig side `intToEnum`s the value passed to `nativeEncodeKey` against
 * this exact ordinal — a single missing constant shifts every key code
 * after it.
 *
 * Ported verbatim from chuchu's `GhosttyKey.kt`.
 */
object GhosttyKey {
    const val unidentified = 0
    const val backquote = 1
    const val backslash = 2
    const val bracketLeft = 3
    const val bracketRight = 4
    const val comma = 5
    const val digit0 = 6
    const val digit1 = 7
    const val digit2 = 8
    const val digit3 = 9
    const val digit4 = 10
    const val digit5 = 11
    const val digit6 = 12
    const val digit7 = 13
    const val digit8 = 14
    const val digit9 = 15
    const val equal = 16
    const val intlBackslash = 17
    const val intlRo = 18
    const val intlYen = 19
    const val keyA = 20
    const val keyB = 21
    const val keyC = 22
    const val keyD = 23
    const val keyE = 24
    const val keyF = 25
    const val keyG = 26
    const val keyH = 27
    const val keyI = 28
    const val keyJ = 29
    const val keyK = 30
    const val keyL = 31
    const val keyM = 32
    const val keyN = 33
    const val keyO = 34
    const val keyP = 35
    const val keyQ = 36
    const val keyR = 37
    const val keyS = 38
    const val keyT = 39
    const val keyU = 40
    const val keyV = 41
    const val keyW = 42
    const val keyX = 43
    const val keyY = 44
    const val keyZ = 45
    const val minus = 46
    const val period = 47
    const val quote = 48
    const val semicolon = 49
    const val slash = 50
    const val altLeft = 51
    const val altRight = 52
    const val backspace = 53
    const val capsLock = 54
    const val contextMenu = 55
    const val controlLeft = 56
    const val controlRight = 57
    const val enter = 58
    const val metaLeft = 59
    const val metaRight = 60
    const val shiftLeft = 61
    const val shiftRight = 62
    const val space = 63
    const val tab = 64
    const val convert = 65
    const val kanaMode = 66
    const val nonConvert = 67
    const val delete = 68
    const val end = 69
    const val help = 70
    const val home = 71
    const val insert = 72
    const val pageDown = 73
    const val pageUp = 74
    const val arrowDown = 75
    const val arrowLeft = 76
    const val arrowRight = 77
    const val arrowUp = 78
    const val numLock = 79
    const val numpad0 = 80
    const val numpad1 = 81
    const val numpad2 = 82
    const val numpad3 = 83
    const val numpad4 = 84
    const val numpad5 = 85
    const val numpad6 = 86
    const val numpad7 = 87
    const val numpad8 = 88
    const val numpad9 = 89
    const val numpadAdd = 90
    const val numpadBackspace = 91
    const val numpadClear = 92
    const val numpadClearEntry = 93
    const val numpadComma = 94
    const val numpadDecimal = 95
    const val numpadDivide = 96
    const val numpadEnter = 97
    const val numpadEqual = 98
    const val numpadMemoryAdd = 99
    const val numpadMemoryClear = 100
    const val numpadMemoryRecall = 101
    const val numpadMemoryStore = 102
    const val numpadMemorySubtract = 103
    const val numpadMultiply = 104
    const val numpadParenLeft = 105
    const val numpadParenRight = 106
    const val numpadSubtract = 107
    const val numpadSeparator = 108
    const val numpadUp = 109
    const val numpadDown = 110
    const val numpadRight = 111
    const val numpadLeft = 112
    const val numpadBegin = 113
    const val numpadHome = 114
    const val numpadEnd = 115
    const val numpadInsert = 116
    const val numpadDelete = 117
    const val numpadPageUp = 118
    const val numpadPageDown = 119
    const val escape = 120
    const val f1 = 121
    const val f2 = 122
    const val f3 = 123
    const val f4 = 124
    const val f5 = 125
    const val f6 = 126
    const val f7 = 127
    const val f8 = 128
    const val f9 = 129
    const val f10 = 130
    const val f11 = 131
    const val f12 = 132
    const val f13 = 133
    const val f14 = 134
    const val f15 = 135
    const val f16 = 136
    const val f17 = 137
    const val f18 = 138
    const val f19 = 139
    const val f20 = 140
    const val f21 = 141
    const val f22 = 142
    const val f23 = 143
    const val f24 = 144
    const val f25 = 145
    const val fn = 146
    const val fnLock = 147
    const val printScreen = 148
    const val scrollLock = 149
    const val pause = 150
}

/**
 * Best-effort character → physical key code for keys whose char identity is
 * stable across QWERTY-family layouts. Returns null for chars without a
 * direct physical mapping (uncommon punctuation, dead keys, etc.) —
 * callers should fall back to passing the codepoint into `nativeEncodeKey`.
 */
fun Char.toGhosttyKey(): Int? = when (this) {
    in 'a'..'z' -> GhosttyKey.keyA + (this - 'a')
    in 'A'..'Z' -> GhosttyKey.keyA + (this - 'A')
    in '0'..'9' -> GhosttyKey.digit0 + (this - '0')
    ' ' -> GhosttyKey.space
    '`' -> GhosttyKey.backquote
    '-' -> GhosttyKey.minus
    '=' -> GhosttyKey.equal
    '[' -> GhosttyKey.bracketLeft
    ']' -> GhosttyKey.bracketRight
    '\\' -> GhosttyKey.backslash
    ';' -> GhosttyKey.semicolon
    '\'' -> GhosttyKey.quote
    ',' -> GhosttyKey.comma
    '.' -> GhosttyKey.period
    '/' -> GhosttyKey.slash
    else -> null
}
