package com.ranaco.wheel

import android.content.Context
import java.security.SecureRandom
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Everything the rider can tune, in SharedPreferences. Angles are degrees. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Turning the phone this far from centre is full lock. */
    var steerLock by float("steer_lock", 60f)
    var steerDeadzone by float("steer_deadzone", 1f)
    /** 1 is linear; higher is gentler near centre. */
    var steerCurve by float("steer_curve", 1f)
    /** 0 to 0.8: how much each reading is blended with the last. */
    var smoothing by float("smoothing", 0.2f)

    /** Tilting the top away this far from neutral is full gas. */
    var gasAt by float("gas_at", 25f)
    /** Tilting the top toward you this far from neutral is full brake. */
    var brakeAt by float("brake_at", 20f)
    // Hands drift and wobble 5–15° while turning; 4° let that through as gas.
    var pedalDeadzone by float("pedal_deadzone", 7f)
    var swapPedals by bool("swap_pedals", false)

    var volumeShift by bool("volume_shift", true)
    /** Closing a flip phone taps Menu, so the game pauses. */
    var foldPause by bool("fold_pause", true)
    var rumble by bool("rumble", true)
    /** 0 to 1: how strongly the game's rumble plays. */
    var rumbleStrength by float("rumble_strength", 1f)
    var steerFeel by bool("steer_feel", true)
    var pedalFeel by bool("pedal_feel", true)
    var flip by bool("flip", false)
    /** Empty: find the PC by broadcast. */
    var pcAddress by string("pc_address", "")
    /** The PC that answered last, tried first next time. */
    var lastPc by string("last_pc", "")
    /** From the last PC code scanned: shows that PC this phone was in front of its screen, so it's let in. */
    var pairToken by string("pair_token", "")

    /** Eight random bytes, made once: how the PC tells this phone from others. */
    val phoneId: ByteArray
        get() {
            prefs.getString("phone_id", null)?.let { saved ->
                return saved.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            val made = ByteArray(8).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("phone_id", made.joinToString("") { "%02x".format(it) }).apply()
            return made
        }

    /** The pose you drive from, set by tapping the dial. */
    var neutralPitch by float("neutral_pitch", DEFAULT_PITCH)
    var neutralSteer by float("neutral_steer", 0f)
    var centered by bool("centered", false)

    /** Half-folding a flip phone switches to the cockpit: dashboard on top, buttons below. */
    var flexMode by bool("flex_mode", true)
    // The cockpit is held differently, so it keeps a neutral of its own.
    var flexNeutralPitch by float("flex_neutral_pitch", 0f)
    var flexNeutralSteer by float("flex_neutral_steer", 0f)
    var flexCentered by bool("flex_centered", false)

    fun resetTuning() {
        prefs.edit().apply {
            listOf("steer_lock", "steer_deadzone", "steer_curve", "smoothing", "gas_at", "brake_at",
                "pedal_deadzone", "swap_pedals", "neutral_pitch", "neutral_steer", "centered",
                "flex_neutral_pitch", "flex_neutral_steer", "flex_centered").forEach(::remove)
        }.apply()
    }

    private fun float(key: String, default: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getFloat(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) =
            prefs.edit().putFloat(key, value).apply()
    }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }

    private fun string(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            prefs.edit().putString(key, value).apply()
    }

    companion object {
        /** Screen tilted back 30° from upright, roughly how a phone sits in two hands. */
        const val DEFAULT_PITCH = 30f
    }
}
