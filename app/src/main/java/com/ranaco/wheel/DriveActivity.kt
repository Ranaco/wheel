package com.ranaco.wheel

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings.Global
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs

/** The one screen you drive from. Everything stops, and the PC lets go, as soon as it's left. */
class DriveActivity : Activity() {
    private lateinit var settings: Settings
    private val controls = Controls()
    private lateinit var motion: Motion
    private lateinit var link: Link
    private lateinit var wifiLock: WifiManager.WifiLock
    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private lateinit var haptics: Haptics
    private lateinit var view: DriveView

    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val main = Handler(Looper.getMainLooper())

    // Fold to pause. Closing the phone mid-race would otherwise leave the car to crash on its
    // own; instead Menu is tapped as the halves come together, and held just long enough for
    // the game to see it before the controls are let go.
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val hinge by lazy { sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }
    private var folding = false
    private var tappedAt = -TAP_MS
    private val finishPause = Runnable {
        controls.releaseAll()
        link.stop()
    }
    private val hingeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val angle = event.values[0]
            hingeAngle = angle
            // The cockpit while half-folded, the wheel once open; in between, whichever it was.
            // Closing the phone passes through the cockpit's range, so it has to settle there first.
            when {
                angle >= OPEN_ANGLE -> {
                    main.removeCallbacks(enterFlex)
                    setFlex(false)
                }
                angle in FOLD_ANGLE..FLEX_MAX -> if (!flex && settings.flexMode) {
                    main.removeCallbacks(enterFlex)
                    main.postDelayed(enterFlex, FLEX_SETTLE_MS)
                }
                else -> main.removeCallbacks(enterFlex)
            }
            if (!folding && angle < FOLD_ANGLE) {
                folding = true
                if (settings.foldPause && link.connected) {
                    controls.press(Button.START)
                    tappedAt = SystemClock.uptimeMillis()
                    main.postDelayed({ controls.release(Button.START) }, TAP_MS)
                    // The game will be paused when the phone opens again.
                    view.showMenu()
                }
            } else if (folding && angle > OPEN_ANGLE) {
                folding = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = updateRotation()
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private lateinit var rumble: Rumble

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        rumble = Rumble(vibrator)
        haptics = Haptics(vibrator).also { it.rumble = rumble }
        motion = Motion(this, settings, controls, haptics)
        link = Link(this, settings, controls, rumble::update, deviceName())
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "wheel")
            .apply { setReferenceCounted(false) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = DriveView(this, controls, motion, link, settings, ::openSettings)
        setContentView(view)
        preferSixtyHertz()
        // A stray back swipe from a gripping hand shouldn't end the race; home still leaves.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {}
        pair(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!settings.flexMode) setFlex(false)
        orient()
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        rumble.enabled = settings.rumble
        rumble.strength = settings.rumbleStrength
        rumble.start()
        haptics.steering = settings.steerFeel
        haptics.pedals = settings.pedalFeel
        haptics.reset()
        displays.registerDisplayListener(displayListener, null)
        updateRotation()
        view.refresh()
        motion.start()
        link.start(settings.pcAddress)
        wifiLock.acquire()
        // Back before a fold's Menu tap finished: carry on with the same link.
        main.removeCallbacks(finishPause)
        hinge?.let { sensors.registerListener(hingeListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
        sensors.unregisterListener(hingeListener)
        main.removeCallbacks(enterFlex)
        // Folding turned the screen off mid-tap: let Menu finish reaching the game first.
        val tapLeft = tappedAt + TAP_MS - SystemClock.uptimeMillis()
        if (tapLeft > 0) main.postDelayed(finishPause, tapLeft) else finishPause.run()
        if (wifiLock.isHeld) wifiLock.release()
        displays.unregisterDisplayListener(displayListener)
        rumble.stop()
    }

    /**
     * A dashboard doesn't need 120 Hz. Asking for 60 halves the screen's work, and the
     * phone gets warm enough in two hands as it is.
     */
    private fun preferSixtyHertz() {
        val display = display ?: return
        val current = display.mode
        val sixty = display.supportedModes.firstOrNull {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                abs(it.refreshRate - 60f) < 1f
        } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = sixty.modeId }
    }

    private var flex = false
    private var hingeAngle = 180f
    private val enterFlex = Runnable { if (hingeAngle in FOLD_ANGLE..FLEX_MAX) setFlex(true) }

    private fun setFlex(on: Boolean) {
        if (on == flex) return
        flex = on
        motion.flex = on
        view.flex = on
        haptics.reset()
        orient()
    }

    /** Sideways for the wheel; upright, hinge across the middle, for the cockpit. */
    private fun orient() {
        requestedOrientation = when {
            flex -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            settings.flip -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun updateRotation() {
        display?.let { motion.rotation = it.rotation }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** What the owner calls the phone, else its model. */
    private fun deviceName() = Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Coming back to the front restarts the link, which then uses the PC just scanned.
        pair(intent)
    }

    /**
     * The PC's QR code, opened from the camera: wheel://pair?h=192.168.1.6&p=47800&n=Gaming-PC&t=….
     * That PC is tried first from now on; discovery still finds it if its address moves. The
     * token (t) tells the PC this phone scanned its screen, so it lets the phone in without asking.
     */
    private fun pair(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "wheel" || uri.host != "pair") return
        val host = uri.getQueryParameter("h")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val port = uri.getQueryParameter("p")?.toIntOrNull() ?: Link.PORT
        settings.lastPc = if (port == Link.PORT) host else "$host:$port"
        uri.getQueryParameter("t")?.takeIf { it.isNotBlank() }?.let { settings.pairToken = it }
        // An address typed into Settings would otherwise win over the code just scanned.
        if (settings.pcAddress.isNotEmpty()) settings.pcAddress = ""
        intent.data = null
        val name = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: host
        Toast.makeText(this, "Connecting to $name", Toast.LENGTH_SHORT).show()
    }

    // The volume keys are the paddles: up shifts up, down shifts down.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val button = paddle(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) controls.press(button)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val button = paddle(keyCode) ?: return super.onKeyUp(keyCode, event)
        controls.release(button)
        return true
    }

    private fun paddle(keyCode: Int) = when {
        !settings.volumeShift || settings.droneMode -> null  // a drone has no gears to shift
        keyCode == KeyEvent.KEYCODE_VOLUME_UP -> Button.SHIFT_UP
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> Button.SHIFT_DOWN
        else -> null
    }

    companion object {
        /** Closing past this many degrees (fully open is 180) taps Menu. */
        private const val FOLD_ANGLE = 60f
        /** Opening past this arms it again, and leaves the cockpit for the wheel. */
        private const val OPEN_ANGLE = 150f
        /** Folded between [FOLD_ANGLE] and this is the cockpit. */
        private const val FLEX_MAX = 130f
        /** How long the hinge stays in the cockpit's range before the screen switches. */
        private const val FLEX_SETTLE_MS = 300L
        private const val TAP_MS = 150L
    }
}
