package com.ranaco.wheel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns how the phone is held into steering and pedals.
 *
 * Everything comes from which way is up, seen from the screen. Hold it like a wheel:
 * turning it in the plane of the screen steers, and tilting the top away from you or
 * toward you is gas or brake. The two are independent, since turning in the screen's
 * plane doesn't change how far the screen leans back.
 */
class Motion(
    context: Context,
    private val settings: Settings,
    private val controls: Controls,
    private val haptics: Haptics,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    // Gyro-fused when the phone has a gyroscope; the accelerometer alone is the fallback.
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** The display's rotation, so "up" is measured from the screen as you see it. */
    @Volatile var rotation = Surface.ROTATION_90

    /**
     * Half-folded and held by the bottom half, like a handheld console. On the Razr the
     * sensor is in that half, which stays roughly level, so steering and pedals are read
     * from it directly rather than from the screen.
     */
    @Volatile var flex = false
        set(value) {
            if (field == value) return
            field = value
            loadNeutral()
            primed = false
            roll = 0f
        }

    /** Angles as last read, relative to neutral, for the dial. */
    @Volatile var steerAngle = 0f
        private set
    /** How far the phone is tilted from neutral toward gas: positive is gas, negative brake, whichever way they're set. */
    @Volatile var forwardAngle = 0f
        private set
    /** Lying flat or upside down: put down, not driving. */
    @Volatile var parked = false
        private set

    private val matrix = FloatArray(9)
    private var rawSteer = 0f
    private var rawPitch = 0f
    private var smoothX = 0f
    private var smoothY = 0f
    private var smoothZ = 0f
    private var primed = false
    /** A drone's roll, kept continuous: past 180° it carries on (200°) rather than wrapping to -160°. */
    private var roll = 0f

    // A snapshot of the settings, so the sensor thread doesn't read preferences 400 times a
    // second; taken again by reload(). Until the first centre, nobody has said what "no
    // pedal" looks like, so the pedals stay off.
    private var lock = 0f
    private var steerDeadzone = 0f
    private var curve = 0f
    private var smoothing = 0f
    private var gasAt = 0f
    private var brakeAt = 0f
    private var pedalDeadzone = 0f
    private var swap = false
    private var drone = false
    private var droneTilt = 0f
    private var droneDeadzone = 0f
    private var droneExpo = 0f
    private var neutralPitch = 0f
    private var neutralSteer = 0f
    private var centered = false

    val available get() = sensor != null

    fun start() {
        reload()
        primed = false
        roll = 0f
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    init {
        reload()
    }

    /** Picks up changed settings without restarting the sensor. */
    fun reload() {
        lock = settings.steerLock
        steerDeadzone = settings.steerDeadzone
        curve = settings.steerCurve
        smoothing = settings.smoothing.coerceIn(0f, 0.8f)
        gasAt = settings.gasAt
        brakeAt = settings.brakeAt
        pedalDeadzone = settings.pedalDeadzone
        swap = settings.swapPedals
        drone = settings.droneMode
        droneTilt = settings.droneTilt
        droneDeadzone = settings.droneDeadzone
        droneExpo = settings.droneExpo
        loadNeutral()
    }

    private fun loadNeutral() {
        neutralPitch = if (flex) settings.flexNeutralPitch else settings.neutralPitch
        neutralSteer = if (flex) settings.flexNeutralSteer else settings.neutralSteer
        centered = if (flex) settings.flexCentered else settings.centered
    }

    fun stop() {
        sensors.unregisterListener(this)
        controls.neutral()
    }

    /** Wherever the phone is now becomes straight ahead with no pedal. */
    fun recenter() {
        neutralPitch = rawPitch
        neutralSteer = rawSteer
        roll = 0f
        if (flex) {
            settings.flexNeutralPitch = rawPitch
            settings.flexNeutralSteer = rawSteer
            settings.flexCentered = true
        } else {
            settings.neutralPitch = rawPitch
            settings.neutralSteer = rawSteer
            settings.centered = true
        }
        centered = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Up, in the phone's own axes.
        var x: Float
        var y: Float
        var z: Float
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            x = matrix[6]
            y = matrix[7]
            z = matrix[8]
        } else {
            val v = event.values
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 0.1f } ?: return
            x = v[0] / n
            y = v[1] / n
            z = v[2] / n
        }
        if (!primed || smoothing == 0f) {
            smoothX = x; smoothY = y; smoothZ = z
            primed = true
        } else {
            val keep = smoothing
            smoothX = smoothX * keep + x * (1 - keep)
            smoothY = smoothY * keep + y * (1 - keep)
            smoothZ = smoothZ * keep + z * (1 - keep)
        }
        x = smoothX; y = smoothY; z = smoothZ

        // Into the screen's axes: x right, y up, z out of the glass.
        val sx: Float
        val sy: Float
        when (rotation) {
            Surface.ROTATION_90 -> { sx = -y; sy = x }
            Surface.ROTATION_180 -> { sx = -x; sy = -y }
            Surface.ROTATION_270 -> { sx = y; sy = -x }
            else -> { sx = x; sy = y }
        }

        val flat: Boolean
        if (flex) {
            // The bottom half's axes: x right, y away from you toward the hinge, z up out of it.
            // Right hand down is steering right; the hinge edge dipping away is gas.
            rawSteer = Math.toDegrees(atan2(-x, z).toDouble()).toFloat()
            rawPitch = Math.toDegrees(atan2(-y, z).toDouble()).toFloat()
            // Stood on its edge or turned over: not being held like a console.
            flat = z < FLEX_LEVEL
        } else {
            // Turning clockwise tips "up" toward the screen's left, which is steering right.
            rawSteer = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
            // How far the screen leans back from upright: 0 standing, 90 lying face up.
            rawPitch = Math.toDegrees(atan2(z, hypot(sx, sy)).toDouble()).toFloat()
            flat = abs(rawPitch) > FLAT
        }

        val steer = if (drone) unwrapRoll(wrap(rawSteer - neutralSteer)) else wrap(rawSteer - neutralSteer)
        val tilt = rawPitch - neutralPitch
        val forward = if (swap && !drone) -tilt else tilt
        steerAngle = steer
        forwardAngle = forward

        // Upside down is a phone that's been put down, unless it's flying: a drone can roll right over.
        parked = flat || (!drone && abs(steer) > UPSIDE_DOWN)
        if (parked || controls.menu) {
            controls.neutral()
            haptics.reset()
            return
        }

        if (drone) {
            // Flying: roll and pitch on the right stick, as on a drone radio (Mode 2); the
            // thumb pad does throttle and yaw. Tipping the top away pushes the stick forward.
            controls.rightX = shape(steer, droneDeadzone, droneTilt, droneExpo)
            controls.rightY = if (centered) shape(forward, droneDeadzone, droneTilt, droneExpo) else 0f
            haptics.reset()
            return
        }
        controls.steer = shape(steer, steerDeadzone, lock, curve)
        if (!centered) {
            controls.throttle = 0f
            controls.brake = 0f
        } else {
            controls.throttle = pedal(forward, pedalDeadzone, gasAt)
            controls.brake = pedal(-forward, pedalDeadzone, brakeAt)
        }
        haptics.update(controls.steer, controls.throttle, controls.brake)
    }

    /** The roll nearest the last one, so a roll past 180° keeps the stick where it was going. */
    private fun unwrapRoll(wrapped: Float): Float {
        var next = wrapped
        while (next - roll > 180f) next -= 360f
        while (next - roll < -180f) next += 360f
        roll = next
        return next
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        /** Past this the screen faces the ceiling or the floor: the phone has been put down. */
        const val FLAT = 80f
        const val UPSIDE_DOWN = 135f
        /** In the cockpit, the bottom half tipped more than about 75° from level. */
        const val FLEX_LEVEL = 0.25f

        fun wrap(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** -1 to 1, with a deadzone at centre and an optional curve. */
        fun shape(angle: Float, deadzone: Float, full: Float, curve: Float): Float {
            val past = abs(angle) - deadzone
            if (past <= 0f) return 0f
            val span = (full - deadzone).coerceAtLeast(1f)
            val v = (past / span).coerceAtMost(1f).pow(curve)
            return if (angle < 0) -v else v
        }

        /** 0 to 1 for tilting one way past the deadzone, reaching 1 at [full] degrees. */
        fun pedal(angle: Float, deadzone: Float, full: Float): Float {
            val past = angle - deadzone
            if (past <= 0f) return 0f
            return (past / (full - deadzone).coerceAtLeast(1f)).coerceAtMost(1f)
        }
    }
}
