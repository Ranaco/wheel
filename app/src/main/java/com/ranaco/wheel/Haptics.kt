package com.ranaco.wheel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs

/**
 * Feel for the controls, made on the phone so it works in any game: a tick through
 * centre and a bump at full lock, like a real wheel, and a tick as each pedal starts
 * and at full travel. Called from the sensor thread with every new output.
 */
class Haptics(private val vibrator: Vibrator) {
    @Volatile var steering = true
    @Volatile var pedals = true
    /** The game's rumble: clicks give way to its heavy motor, and hand the light buzz back after. */
    @Volatile var rumble: Rumble? = null

    // Lightest to strongest. Each falls back to a plain pulse of that length and strength.
    private val centre = effect(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, 14, 115)
    private val engage = effect(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.8f, 16, 140)
    private val travel = effect(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 24, 215)
    private val lock = effect(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 32, 255)

    // Armed once the wheel is clearly off centre, so wobbling around straight ahead stays quiet.
    private var offCentre = false
    private var atLock = false
    private var gasOn = false
    private var gasFull = false
    private var brakeOn = false
    private var brakeFull = false
    private var lastAt = 0L

    fun update(steer: Float, throttle: Float, brake: Float) {
        val a = abs(steer)
        if (a > 0.08f) offCentre = true
        if (offCentre && a < 0.02f) {
            offCentre = false
            if (steering) play(centre)
        }
        if (!atLock && a >= 0.995f) {
            atLock = true
            if (steering) play(lock)
        } else if (a < 0.9f) atLock = false

        gasOn = started(throttle, gasOn)
        gasFull = atFull(throttle, gasFull)
        brakeOn = started(brake, brakeOn)
        brakeFull = atFull(brake, brakeFull)
    }

    // For Settings, to feel each one. They play even with that group switched off.
    fun tryCentre() = vibrator.vibrate(centre, ATTRIBUTES)
    fun tryLock() = vibrator.vibrate(lock, ATTRIBUTES)
    fun tryPedal() {
        vibrator.vibrate(engage, ATTRIBUTES)
        Handler(Looper.getMainLooper()).postDelayed({ vibrator.vibrate(travel, ATTRIBUTES) }, 350)
    }
    fun reset() {
        offCentre = false
        atLock = false
        gasOn = false
        gasFull = false
        brakeOn = false
        brakeFull = false
    }

    /** A pedal leaving its deadzone. */
    private fun started(value: Float, was: Boolean): Boolean {
        // A little past zero to start, all the way back to stop: no chatter at the deadzone's edge.
        val now = if (was) value > 0f else value > 0.03f
        if (now && !was && pedals) play(engage)
        return now
    }

    /** A pedal reaching full travel; it re-arms once eased back below 90%. */
    private fun atFull(value: Float, was: Boolean): Boolean {
        if (!was && value >= 0.999f) {
            if (pedals) play(travel)
            return true
        }
        return was && value > 0.9f
    }

    private fun play(effect: VibrationEffect) {
        // Under a crash or a kerb a click wouldn't be felt, and would cut the thump short.
        if (rumble?.heavy == true) return
        val now = SystemClock.elapsedRealtime()
        // Two in a row would blur into one buzz.
        if (now - lastAt < 60) return
        lastAt = now
        rumble?.pauseFor(CLICK_MS)
        vibrator.vibrate(effect, ATTRIBUTES)
    }

    /**
     * The phone's own tuned primitive where it has one. Otherwise a pulse: phones without
     * primitives (the Razr 50 Ultra has none) play predefined effects at full strength,
     * so light and firm would only differ in length.
     */
    private fun effect(primitive: Int, scale: Float, ms: Long, amplitude: Int): VibrationEffect = when {
        vibrator.areAllPrimitivesSupported(primitive) ->
            VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
        vibrator.hasAmplitudeControl() -> VibrationEffect.createOneShot(ms, amplitude)
        else -> VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    companion object {
        // Media, like the game's rumble: turning off touch vibration shouldn't silence the wheel.
        private val ATTRIBUTES = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
        /** Long enough for the longest click to finish before the rumble comes back. */
        private const val CLICK_MS = 45L
    }
}
