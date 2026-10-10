package com.ranaco.wheel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The game's rumble, on the phone's one motor.
 *
 * A pad has two: a heavy, slow one for crashes and kerbs ("large") and a light, fast one
 * for road and tyre texture ("small"). The phone has one motor with amplitude control,
 * so the heavy one becomes steady strength and the light one a quick on-off buzz. Both
 * are lifted on a curve: games like Assetto Corsa mostly send the light motor at a few
 * percent, which a phone's motor can't otherwise be felt at all.
 */
class Rumble(private val vibrator: Vibrator) {
    /** 0 to 1, from Settings. */
    @Volatile var strength = 1f
    @Volatile var enabled = true
    /** True while the heavy motor runs: clicks would only interrupt it. */
    val heavy get() = playing?.let { it.heavy > 0 } ?: false

    private data class Level(val heavy: Int, val light: Int)

    @Volatile private var playing: Level? = null
    @Volatile private var heardAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val amplitudeControl = vibrator.hasAmplitudeControl()

    // If the PC stops saying what to play, stop playing it: a looping buzz must never outlive the game.
    private val watchdog = object : Runnable {
        override fun run() {
            if (playing != null && SystemClock.elapsedRealtime() - heardAt > SILENCE_MS) silence()
            main.postDelayed(this, 250)
        }
    }

    fun start() {
        main.removeCallbacks(watchdog)
        main.post(watchdog)
    }

    /** From the link's thread, every status from the PC (on change and four times a second). */
    fun update(large: Int, small: Int) {
        heardAt = SystemClock.elapsedRealtime()
        if (!enabled) return
        val level = level(large, small)
        if (level == playing) return
        play(level)
    }

    /** The screen's gone: nothing plays, and nothing's left watching. */
    fun stop() {
        main.removeCallbacks(watchdog)
        silence()
    }

    private fun silence() {
        playing = null
        vibrator.cancel()
    }

    /** Settings' Try: road texture building up, then a hit. */
    fun demo() {
        val wasEnabled = enabled
        enabled = true
        val steps = listOf(0 to 12, 0 to 40, 0 to 90, 255 to 60, 0 to 30, 0 to 0)
        steps.forEachIndexed { i, (large, small) ->
            main.postDelayed({
                heardAt = SystemClock.elapsedRealtime()
                val level = level(large, small)
                if (level.heavy == 0 && level.light == 0) silence() else play(level)
                if (i == steps.lastIndex) enabled = wasEnabled
            }, i * 450L)
        }
    }

    /**
     * Makes room for a click from [Haptics]: Android won't let a short vibration cut into a
     * looping one, so the buzz stops for the click and comes back straight after it.
     */
    fun pauseFor(ms: Long) {
        val level = playing ?: return
        vibrator.cancel()
        main.postDelayed({ if (playing == level) play(level, force = true) }, ms)
    }

    private fun play(level: Level, force: Boolean = false) {
        if (!force && level == playing) return
        playing = level
        if (level.heavy == 0 && level.light == 0) {
            vibrator.cancel()
            playing = null
            return
        }
        val effect = if (!amplitudeControl) {
            VibrationEffect.createWaveform(longArrayOf(0, 40, 20), 1)
        } else if (level.light == 0) {
            // Heavy only: steady, at strength.
            VibrationEffect.createWaveform(longArrayOf(BUZZ_ON + BUZZ_OFF), intArrayOf(level.heavy), 0)
        } else {
            // Light texture: on-off at about 40 Hz, riding on whatever the heavy motor adds.
            VibrationEffect.createWaveform(
                longArrayOf(BUZZ_ON, BUZZ_OFF),
                intArrayOf(max(level.light, level.heavy), level.heavy),
                0,
            )
        }
        vibrator.vibrate(effect, ATTRIBUTES)
    }

    /** In steps, so a value wobbling by one doesn't restart the motor fifty times a second. */
    private fun level(large: Int, small: Int) = Level(step(lift(large), 16), step(lift(small), 24))

    /** 0 stays 0; anything else lands between just-felt and full, faint values lifted most. */
    private fun lift(value: Int): Int {
        if (value <= 0 || strength <= 0f) return 0
        val curved = FLOOR + (255 - FLOOR) * sqrt(value / 255f)
        return (curved * strength).roundToInt().coerceIn(1, 255)
    }

    private fun step(amplitude: Int, size: Int) =
        if (amplitude == 0) 0 else ((amplitude + size / 2) / size * size).coerceIn(size, 255)

    companion object {
        /** About the weakest the Razr's motor can be felt in two hands. */
        private const val FLOOR = 70f
        private const val BUZZ_ON = 14L
        private const val BUZZ_OFF = 10L
        private const val SILENCE_MS = 700L
        // Media, not touch: turning off touch vibration shouldn't silence the game.
        private val ATTRIBUTES = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
    }
}
