package com.ranaco.wheel

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.ranaco.wheel.Palette.DIM
import com.ranaco.wheel.Palette.EDGE
import com.ranaco.wheel.Palette.HINT
import com.ranaco.wheel.Palette.LABEL
import com.ranaco.wheel.Palette.MEDIUM
import com.ranaco.wheel.Palette.OUTLINE
import com.ranaco.wheel.Palette.PRESSED
import com.ranaco.wheel.Palette.RULE
import com.ranaco.wheel.Palette.SHADE
import com.ranaco.wheel.Palette.SURFACE
import com.ranaco.wheel.Palette.THUMB_OFF
import com.ranaco.wheel.Palette.TRACK_ON
import kotlin.math.roundToInt

/**
 * Tuning, in the drive screen's black. A live view of the phone sits at the top, so
 * every slider can be tried by moving the phone before going back to drive.
 */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var motion: Motion
    private lateinit var haptics: Haptics
    private lateinit var rumble: Rumble
    private lateinit var preview: TiltPreview
    private lateinit var neutralLine: TextView
    private lateinit var content: LinearLayout
    private val controls = Controls()
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        rumble = Rumble(vibrator)
        haptics = Haptics(vibrator).also { it.rumble = rumble }
        motion = Motion(this, settings, controls, haptics)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); isFillViewport = true }
        val frame = FrameLayout(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.addView(frame)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // A readable column, even with the phone held sideways.
        frame.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val side = ((r - l - px(640)) / 2).coerceAtLeast(px(20))
            if (content.paddingLeft != side) content.setPadding(side, px(12), side, px(40))
        }
        setContentView(scroll)
        build()
    }

    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            display?.let { motion.rotation = it.rotation }
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onResume() {
        super.onResume()
        flip()
        displays.registerDisplayListener(displayListener, null)
        display?.let { motion.rotation = it.rotation }
        haptics.steering = settings.steerFeel
        haptics.pedals = settings.pedalFeel
        rumble.strength = settings.rumbleStrength
        rumble.start()
        motion.start()
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
        rumble.stop()
        displays.unregisterDisplayListener(displayListener)
    }

    private fun flip() {
        requestedOrientation = if (settings.flip) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /** Settings changed: the live view and the clicks follow at once. */
    private fun changed() {
        motion.reload()
        preview.refresh()
        haptics.steering = settings.steerFeel
        haptics.pedals = settings.pedalFeel
    }

    private fun build() {
        content.removeAllViews()
        content.addView(header())

        val live = section("Live")
        preview = TiltPreview(this, motion, controls, settings)
        live.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(14); bottomMargin = px(6)
        })
        divider(live)
        val neutral = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, px(14)) }
        neutralLine = label("", 13f, LABEL)
        neutral.addView(neutralLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        neutral.addView(pill("Set neutral here") {
            motion.recenter()
            showNeutral()
            preview.refresh()
        })
        live.addView(neutral)
        showNeutral()

        val mode = section("Controller")
        toggle(mode, "Fly a drone", "For drone games like Liftoff: tilt to pitch and roll, and a thumb pad for throttle and yaw.",
            settings.droneMode, last = true) {
            settings.droneMode = it
            // The rest of the page is about one or the other.
            content.post { build() }
        }

        if (settings.droneMode) {
            val drone = section("Drone")
            slider(drone, "Sensitivity", "How far you tilt the phone, each way, for full stick. Lower is more sensitive.",
                10, 60, 1, settings.droneTilt, { "full stick at $it°" }) { settings.droneTilt = it }
            slider(drone, "Centre deadzone", "Small wobbles near level are ignored.",
                0, 10, 1, settings.droneDeadzone, { "$it°" }) { settings.droneDeadzone = it }
            slider(drone, "Expo", "1.0 is linear. Higher makes small tilts gentler, for fine control.",
                10, 30, 1, settings.droneExpo * 10, { "%.1f".format(it / 10f) }) { settings.droneExpo = it / 10f }
            slider(drone, "Smoothing", "Steadier, at the cost of a little lag.",
                0, 80, 5, settings.smoothing * 100, { "$it%" }, last = true) { settings.smoothing = it / 100f }
        } else {
            driving()
        }
        feelAndButtons()
        connection()
    }

    private fun driving() {
        val steering = section("Steering")
        slider(steering, "Sensitivity", "How far you turn the phone, each way, for full lock. Lower is more sensitive.",
            20, 120, 5, settings.steerLock, { "full lock at $it°" }) { settings.steerLock = it }
        slider(steering, "Centre deadzone", "Small wobbles near straight ahead are ignored.",
            0, 10, 1, settings.steerDeadzone, { "$it°" }) { settings.steerDeadzone = it }
        slider(steering, "Response curve", "1.0 is linear. Higher makes small turns gentler.",
            10, 30, 1, settings.steerCurve * 10, { "%.1f".format(it / 10f) }) { settings.steerCurve = it / 10f }
        slider(steering, "Smoothing", "Steadier, at the cost of a little lag.",
            0, 80, 5, settings.smoothing * 100, { "$it%" }, last = true) { settings.smoothing = it / 100f }

        val pedals = section("Pedals")
        slider(pedals, "Gas sensitivity", "How far you tilt the top away for full gas. Lower is more sensitive.",
            8, 45, 1, settings.gasAt, { "full at $it°" }) { settings.gasAt = it }
        slider(pedals, "Brake sensitivity", "How far you tilt the top toward you for full brake.",
            8, 45, 1, settings.brakeAt, { "full at $it°" }) { settings.brakeAt = it }
        slider(pedals, "Deadzone", "Tilt this far from neutral before either pedal starts. Raise it if gas comes on while you steer.",
            0, 15, 1, settings.pedalDeadzone, { "$it°" }) { settings.pedalDeadzone = it }
        toggle(pedals, "Swap gas and brake", "Tilt the top toward you for gas instead.", settings.swapPedals, last = true) { settings.swapPedals = it }
    }

    private fun feelAndButtons() {
        val drone = settings.droneMode
        val feel = section("Feel")
        toggle(feel, "Game rumble", "The phone vibrates when the game rumbles the controller.", settings.rumble, rumble::demo) { settings.rumble = it }
        slider(feel, "Rumble strength", "Faint rumble, like road texture, is lifted so it can still be felt.",
            0, 100, 5, settings.rumbleStrength * 100, { "$it%" }, last = drone) {
            settings.rumbleStrength = it / 100f
            rumble.strength = it / 100f
        }
        if (!drone) {
            toggle(feel, "Steering clicks", "A tick as the wheel passes centre, and a bump at full lock.", settings.steerFeel, {
                haptics.tryCentre()
                preview.postDelayed({ haptics.tryLock() }, 400)
            }) { settings.steerFeel = it }
            toggle(feel, "Pedal clicks", "A tick as gas or brake starts, and a click at full.", settings.pedalFeel, haptics::tryPedal, last = true) { settings.pedalFeel = it }
        }

        val buttons = section("Buttons")
        if (!drone) {
            toggle(buttons, "Volume keys shift gears", "Volume up shifts up, volume down shifts down.", settings.volumeShift) { settings.volumeShift = it }
        }
        if (getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null) {
            toggle(buttons, "Close the phone to pause", "Folding the phone taps Menu, so the game pauses before the controls let go.", settings.foldPause) { settings.foldPause = it }
            toggle(buttons, "Half-fold for the cockpit", "Fold it like a small laptop: the dashboard on the top half, buttons under your thumbs on the bottom.", settings.flexMode) { settings.flexMode = it }
        }
        toggle(buttons, "Flip the screen", "Turns the drive screen the other way round.", settings.flip, last = true) {
            settings.flip = it
            flip()
        }
    }

    private fun connection() {
        val connection = section("Connection")
        connection.addView(label(if (settings.lastPc.isEmpty()) "No PC found yet." else "Last connected to ${settings.lastPc}.", 13f, LABEL).apply {
            setPadding(0, px(14), 0, px(4))
        })
        field(connection, "PC address", "Leave empty to find the PC on its own.", settings.pcAddress) { settings.pcAddress = it }

        content.addView(label("Reset all tuning", 15f, LABEL).apply {
            setPadding(px(4), px(28), px(4), px(8))
            setOnClickListener {
                settings.resetTuning()
                motion.reload()
                build()
            }
        })
        content.addView(label("Wheel for PC turns this phone into an Xbox controller. Its window shows the code to scan and who's playing.", 12f, DIM).apply {
            setPadding(px(4), px(6), px(4), 0)
        })
    }

    private fun showNeutral() {
        val neutral = settings.neutral(flex = false, drone = settings.droneMode)
        neutralLine.text = if (neutral.set) "${if (settings.droneMode) "Level" else "Neutral"} is ${neutral.pitch.roundToInt()}° leaned back."
        else "Not set. Hold the phone how you'll ${if (settings.droneMode) "fly" else "drive"}."
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(4), px(12), 0, px(4))
        addView(label("Settings", 28f, Color.WHITE, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(pill("Done") { finish() })
    }

    /** A titled card; rows go inside it. */
    private fun section(title: String): LinearLayout {
        content.addView(label(title.uppercase(), 12f, DIM, medium = true).apply {
            letterSpacing = 0.12f
            setPadding(px(4), px(26), 0, px(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(2), px(18), px(2))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = 20 * dp
                setStroke(px(1), EDGE)
            }
        }
        content.addView(card)
        return card
    }

    private fun divider(card: LinearLayout) {
        card.addView(View(this).apply { setBackgroundColor(EDGE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))
    }

    private fun slider(card: LinearLayout, name: String, help: String, min: Int, max: Int, step: Int, value: Float,
                       format: (Int) -> String, last: Boolean = false, set: (Float) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(14), 0, px(10)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val shown = label("", 14f, Color.WHITE).apply { fontFeatureSettings = "tnum" }
        top.addView(label(name, 16f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(shown)
        row.addView(top)
        row.addView(label(help, 13f, DIM).apply { setPadding(0, px(2), 0, 0) })
        val start = value.roundToInt().coerceIn(min, max)
        shown.text = format(start)
        row.addView(SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (start - min) / step
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(RULE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(px(4), px(10), px(4), px(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = min + progress * step
                    shown.text = format(v)
                    if (fromUser) {
                        set(v.toFloat())
                        changed()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
        card.addView(row)
        if (!last) divider(card)
    }

    private fun toggle(card: LinearLayout, name: String, help: String, value: Boolean, test: (() -> Unit)? = null,
                       last: Boolean = false, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(14), 0, px(14))
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(label(name, 16f, Color.WHITE))
        words.addView(label(help, 13f, DIM).apply { setPadding(0, px(2), px(8), 0) })
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        test?.let {
            row.addView(pill("Try", it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(10) })
        }
        val switch = Switch(this).apply {
            isChecked = value
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, THUMB_OFF))
            trackTintList = ColorStateList(states, intArrayOf(TRACK_ON, SHADE))
            setOnCheckedChangeListener { _, checked ->
                set(checked)
                changed()
            }
        }
        row.addView(switch)
        words.setOnClickListener { switch.toggle() }
        card.addView(row)
        if (!last) divider(card)
    }

    private fun field(card: LinearLayout, name: String, help: String, value: String, set: (String) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(10), 0, px(14)) }
        row.addView(label(name, 16f, Color.WHITE))
        row.addView(label(help, 13f, DIM).apply { setPadding(0, px(2), 0, px(8)) })
        row.addView(EditText(this).apply {
            setText(value)
            hint = "e.g. 192.168.1.6"
            setHintTextColor(HINT)
            setTextColor(Color.WHITE)
            textSize = 15f
            backgroundTintList = ColorStateList.valueOf(RULE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) = set(s.toString().trim())
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
            })
        })
        card.addView(row)
    }

    /** An outlined pill that turns white while pressed, like the drive screen's buttons. */
    private fun pill(text: String, onClick: () -> Unit) = label(text, 14f, Color.WHITE).apply {
        val pressed = GradientDrawable().apply { setColor(PRESSED); cornerRadius = 99 * dp }
        val idle = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 99 * dp; setStroke(px(1), OUTLINE) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), idle)
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
        setPadding(px(16), px(8), px(16), px(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (medium) typeface = MEDIUM
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun px(value: Int) = (value * dp).roundToInt()
}
