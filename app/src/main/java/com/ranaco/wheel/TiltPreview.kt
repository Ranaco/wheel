package com.ranaco.wheel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.ranaco.wheel.Palette.BRAKE
import com.ranaco.wheel.Palette.DIM
import com.ranaco.wheel.Palette.GAS
import com.ranaco.wheel.Palette.LABEL
import com.ranaco.wheel.Palette.MEDIUM
import com.ranaco.wheel.Palette.SHADE
import com.ranaco.wheel.Palette.TRACK
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings' live view of the phone: steering and tilt on the scales the sliders set,
 * so a change can be felt and seen before going back to drive.
 *
 *   Steering                          L 12°
 *   |-------------[ ]=====o----------------|     ±full lock, deadzone at centre
 *   Pedals                            Gas 40%
 *   |brake======[  dz  ]=====o-------- gas|     full brake ... full gas
 *
 * Flying a drone, the two rows are Roll and Pitch instead, each ± full stick.
 */
@SuppressLint("ViewConstructor")
class TiltPreview(
    context: Context,
    private val motion: Motion,
    private val controls: Controls,
    private val settings: Settings,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = MEDIUM; fontFeatureSettings = "tnum" }
    private val track = RectF()

    // From Settings, by refresh().
    private var lock = 0f
    private var steerDeadzone = 0f
    private var pedalDeadzone = 0f
    private var gasAt = 0f
    private var brakeAt = 0f
    private var drone = false
    private var droneTilt = 0f
    private var droneDeadzone = 0f

    private val shown = IntArray(6)
    private val now = IntArray(6)
    private val tick = object : Runnable {
        override fun run() {
            now[0] = (motion.steerAngle * 2).roundToInt()
            now[1] = (motion.forwardAngle * 2).roundToInt()
            now[2] = (controls.throttle * 100).roundToInt()
            now[3] = (controls.brake * 100).roundToInt()
            now[4] = if (motion.parked) 1 else 0
            now[5] = (controls.steer * 200 + controls.rightX * 2000 + controls.rightY * 20000).roundToInt()
            if (!now.contentEquals(shown)) {
                now.copyInto(shown)
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    init {
        refresh()
    }

    fun refresh() {
        lock = settings.steerLock
        steerDeadzone = settings.steerDeadzone
        pedalDeadzone = settings.pedalDeadzone
        gasAt = settings.gasAt
        brakeAt = settings.brakeAt
        drone = settings.droneMode
        droneTilt = settings.droneTilt
        droneDeadzone = settings.droneDeadzone
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (118 * dp).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (drone) return drawDrone(canvas)
        val parked = motion.parked
        val steer = motion.steerAngle
        val deg = steer.roundToInt()
        row(canvas, 0f, "Steering", when {
            parked -> "phone is flat"
            deg == 0 -> "0°"
            else -> "${if (deg < 0) "L" else "R"} ${abs(deg)}°"
        })
        // -lock .. +lock, centre in the middle.
        scale(canvas, 34 * dp)
        val mid = track.centerX()
        val half = track.width() / 2
        fun sx(degrees: Float) = mid + half * (degrees / lock).coerceIn(-1.08f, 1.08f)
        deadzone(canvas, sx(-steerDeadzone), sx(steerDeadzone))
        if (!parked && controls.steer != 0f) {
            fill.color = Color.WHITE
            val to = mid + half * controls.steer
            canvas.drawRoundRect(minOf(mid, to), track.top, maxOf(mid, to), track.bottom, track.height() / 2, track.height() / 2, fill)
        }
        tickAt(canvas, mid)
        if (!parked) marker(canvas, sx(steer))

        val forward = motion.forwardAngle
        val gas = controls.throttle
        val brake = controls.brake
        row(canvas, 58 * dp, "Pedals", when {
            parked -> "phone is flat"
            gas > 0f -> "Gas ${(gas * 100).roundToInt()}%"
            brake > 0f -> "Brake ${(brake * 100).roundToInt()}%"
            else -> "none, ${if (forward >= 0) "+" else ""}${forward.roundToInt()}°"
        })
        // Full brake on the left, full gas on the right, neutral where they meet.
        scale(canvas, 92 * dp)
        val span = brakeAt + gasAt
        fun px(degrees: Float) = track.left + track.width() * ((degrees + brakeAt) / span).coerceIn(-0.04f, 1.04f)
        val zero = px(0f)
        deadzone(canvas, px(-pedalDeadzone), px(pedalDeadzone))
        if (!parked && gas > 0f) {
            fill.color = GAS
            canvas.drawRoundRect(px(pedalDeadzone), track.top, px(forward), track.bottom, track.height() / 2, track.height() / 2, fill)
        }
        if (!parked && brake > 0f) {
            fill.color = BRAKE
            canvas.drawRoundRect(px(forward), track.top, px(-pedalDeadzone), track.bottom, track.height() / 2, track.height() / 2, fill)
        }
        tickAt(canvas, zero)
        if (!parked) marker(canvas, px(forward))
        text.textSize = 10 * dp
        text.color = DIM
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("FULL BRAKE", track.left, track.bottom + 16 * dp, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("FULL GAS", track.right, track.bottom + 16 * dp, text)
    }

    /** Roll and pitch, each on a scale of full stick either way, filled with what the game gets. */
    private fun drawDrone(canvas: Canvas) {
        val parked = motion.parked
        fun angle(degrees: Float, minus: String, plus: String): String {
            val d = degrees.roundToInt()
            return when {
                parked -> "phone is flat"
                d == 0 -> "0°"
                else -> "${if (d < 0) minus else plus} ${abs(d)}°"
            }
        }
        stickRow(canvas, 0f, "Roll", angle(motion.steerAngle, "L", "R"), motion.steerAngle, controls.rightX)
        stickRow(canvas, 58 * dp, "Pitch", angle(motion.forwardAngle, "back", "forward"), motion.forwardAngle, controls.rightY)
    }

    private fun stickRow(canvas: Canvas, top: Float, label: String, value: String, degrees: Float, stick: Float) {
        row(canvas, top, label, value)
        scale(canvas, top + 34 * dp)
        val mid = track.centerX()
        val half = track.width() / 2
        fun sx(d: Float) = mid + half * (d / droneTilt).coerceIn(-1.08f, 1.08f)
        deadzone(canvas, sx(-droneDeadzone), sx(droneDeadzone))
        if (!motion.parked && stick != 0f) {
            fill.color = Color.WHITE
            val to = mid + half * stick
            canvas.drawRoundRect(minOf(mid, to), track.top, maxOf(mid, to), track.bottom, track.height() / 2, track.height() / 2, fill)
        }
        tickAt(canvas, mid)
        if (!motion.parked) marker(canvas, sx(degrees))
    }

    private fun row(canvas: Canvas, top: Float, label: String, value: String) {
        text.textSize = 13 * dp
        text.letterSpacing = 0.02f
        text.color = LABEL
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, 0f, top + 16 * dp, text)
        text.color = Color.WHITE
        text.textSize = 14 * dp
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(value, width.toFloat(), top + 16 * dp, text)
    }

    /** The track, 8dp tall and full width, centred on [y]. */
    private fun scale(canvas: Canvas, y: Float) {
        val h = 8 * dp
        track.set(6 * dp, y - h / 2, width - 6 * dp, y + h / 2)
        fill.color = TRACK
        canvas.drawRoundRect(track, h / 2, h / 2, fill)
    }

    /** The deadzone: a lighter stretch of track where nothing happens. */
    private fun deadzone(canvas: Canvas, from: Float, to: Float) {
        fill.color = SHADE
        canvas.drawRect(from, track.top, to, track.bottom, fill)
    }

    private fun tickAt(canvas: Canvas, x: Float) {
        line.color = LABEL
        line.strokeWidth = 1.5f * dp
        canvas.drawLine(x, track.top - 5 * dp, x, track.bottom + 5 * dp, line)
    }

    private fun marker(canvas: Canvas, x: Float) {
        fill.color = Color.BLACK
        canvas.drawCircle(x, track.centerY(), 8 * dp, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, track.centerY(), 6 * dp, fill)
    }
}
