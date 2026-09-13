package com.github.ananbox.anna

import com.github.ananbox.Anbox

/**
 * Touch injection through the existing anbox input device. Coordinates are in
 * guest display pixels (the same space pushFinger* already uses).
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

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private const val FINGER = 0
    private const val STEP_MS = 16
}
