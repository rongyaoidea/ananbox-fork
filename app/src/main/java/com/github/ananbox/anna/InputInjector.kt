package com.github.ananbox.anna

import com.github.ananbox.Anbox

/**
 * Touch and keyboard injection through the existing anbox input devices.
 *
 * Touch coordinates are in guest display pixels. Text is translated to Linux
 * evdev key codes (US layout) because the guest InputReader applies its own
 * key layout on top of the raw events.
 */
object InputInjector {

    fun tap(x: Int, y: Int) {
        Anbox.pushFingerDown(x, y, FINGER)
        sleep(60)
        Anbox.pushFingerUp(FINGER)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val steps = (durationMs / STEP_MS).coerceAtLeast(4)
        Anbox.pushFingerDown(x1, y1, FINGER)
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val x = x1 + ((x2 - x1) * t).toInt()
            val y = y1 + ((y2 - y1) * t).toInt()
            Anbox.pushFingerMotion(x, y, FINGER)
            sleep(STEP_MS.toLong())
        }
        Anbox.pushFingerUp(FINGER)
    }

    fun tapKey(keyCode: Int) {
        Anbox.pushKey(keyCode, true)
        sleep(15)
        Anbox.pushKey(keyCode, false)
    }

    /** Types [text] using a US keyboard layout; unknown characters are skipped. */
    fun typeText(text: String) {
        for (ch in text) {
            val mapped = mapChar(ch) ?: continue
            if (mapped.second) Anbox.pushKey(KEY_LEFTSHIFT, true)
            Anbox.pushKey(mapped.first, true)
            sleep(8)
            Anbox.pushKey(mapped.first, false)
            if (mapped.second) Anbox.pushKey(KEY_LEFTSHIFT, false)
            sleep(8)
        }
    }

    fun keyCodeByName(name: String): Int? = namedKeys[name.trim().lowercase()]

    fun hasKeyboard(): Boolean = Anbox.hasKeyboard()

    private fun mapChar(ch: Char): Pair<Int, Boolean>? = when {
        ch in 'a'..'z' -> (KEY_A + (ch - 'a')) to false
        ch in 'A'..'Z' -> (KEY_A + (ch - 'A')) to true
        ch in '1'..'9' -> (KEY_1 + (ch - '1')) to false
        else -> punctuation[ch]
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private const val FINGER = 0
    private const val STEP_MS = 16

    // Linux evdev key codes (linux/input-event-codes.h).
    private const val KEY_ESC = 1
    private const val KEY_1 = 2
    private const val KEY_2 = 3
    private const val KEY_3 = 4
    private const val KEY_4 = 5
    private const val KEY_5 = 6
    private const val KEY_6 = 7
    private const val KEY_7 = 8
    private const val KEY_8 = 9
    private const val KEY_9 = 10
    private const val KEY_0 = 11
    private const val KEY_MINUS = 12
    private const val KEY_EQUAL = 13
    private const val KEY_BACKSPACE = 14
    private const val KEY_TAB = 15
    private const val KEY_LEFTBRACE = 26
    private const val KEY_RIGHTBRACE = 27
    private const val KEY_ENTER = 28
    private const val KEY_LEFTCTRL = 29
    private const val KEY_A = 30
    private const val KEY_SEMICOLON = 39
    private const val KEY_APOSTROPHE = 40
    private const val KEY_GRAVE = 41
    private const val KEY_LEFTSHIFT = 42
    private const val KEY_BACKSLASH = 43
    private const val KEY_COMMA = 51
    private const val KEY_DOT = 52
    private const val KEY_SLASH = 53
    private const val KEY_LEFTALT = 56
    private const val KEY_SPACE = 57
    private const val KEY_HOME = 102
    private const val KEY_UP = 103
    private const val KEY_PAGEUP = 104
    private const val KEY_LEFT = 105
    private const val KEY_RIGHT = 106
    private const val KEY_END = 107
    private const val KEY_DOWN = 108
    private const val KEY_PAGEDOWN = 109
    private const val KEY_INSERT = 110
    private const val KEY_DELETE = 111
    private const val KEY_VOLUMEDOWN = 114
    private const val KEY_VOLUMEUP = 115
    private const val KEY_POWER = 116
    private const val KEY_MENU = 139
    private const val KEY_BACK = 158
    private const val KEY_HOMEPAGE = 172
    private const val KEY_SEARCH = 217
    private const val KEY_APPSELECT = 580

    private val punctuation = mapOf(
        ' ' to (KEY_SPACE to false),
        '-' to (KEY_MINUS to false), '_' to (KEY_MINUS to true),
        '=' to (KEY_EQUAL to false), '+' to (KEY_EQUAL to true),
        '[' to (KEY_LEFTBRACE to false), '{' to (KEY_LEFTBRACE to true),
        ']' to (KEY_RIGHTBRACE to false), '}' to (KEY_RIGHTBRACE to true),
        ';' to (KEY_SEMICOLON to false), ':' to (KEY_SEMICOLON to true),
        '\'' to (KEY_APOSTROPHE to false), '"' to (KEY_APOSTROPHE to true),
        '`' to (KEY_GRAVE to false), '~' to (KEY_GRAVE to true),
        '\\' to (KEY_BACKSLASH to false), '|' to (KEY_BACKSLASH to true),
        ',' to (KEY_COMMA to false), '<' to (KEY_COMMA to true),
        '.' to (KEY_DOT to false), '>' to (KEY_DOT to true),
        '/' to (KEY_SLASH to false), '?' to (KEY_SLASH to true),
        '!' to (KEY_1 to true), '@' to (KEY_2 to true), '#' to (KEY_3 to true),
        '$' to (KEY_4 to true), '%' to (KEY_5 to true), '^' to (KEY_6 to true),
        '&' to (KEY_7 to true), '*' to (KEY_8 to true), '(' to (KEY_9 to true),
        ')' to (KEY_0 to true),
        '\n' to (KEY_ENTER to false), '\t' to (KEY_TAB to false),
        '\b' to (KEY_BACKSPACE to false),
    )

    private val namedKeys = mapOf(
        "back" to KEY_BACK,
        "home" to KEY_HOMEPAGE,
        "menu" to KEY_MENU,
        "app_switch" to KEY_APPSELECT,
        "recents" to KEY_APPSELECT,
        "enter" to KEY_ENTER,
        "backspace" to KEY_BACKSPACE,
        "del" to KEY_DELETE,
        "delete" to KEY_DELETE,
        "tab" to KEY_TAB,
        "escape" to KEY_ESC,
        "esc" to KEY_ESC,
        "up" to KEY_UP,
        "down" to KEY_DOWN,
        "left" to KEY_LEFT,
        "right" to KEY_RIGHT,
        "volume_up" to KEY_VOLUMEUP,
        "volume_down" to KEY_VOLUMEDOWN,
        "power" to KEY_POWER,
        "search" to KEY_SEARCH,
    )
}
