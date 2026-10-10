package com.ranaco.wheel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's buttons, by bit in the state packet. The order must match BUTTONS in the
 * companion, and new ones only ever go on the end, so an older PC just ignores them.
 *
 * START and BACK are named for the Xbox buttons they press by default, Start and Back,
 * which today's pads label Menu and View; the screen calls them MENU and VIEW too. The
 * MENU_ buttons are the menu controls' D-pad and face buttons, not the MENU button.
 */
enum class Button {
    HANDBRAKE, BOOST, SHIFT_UP, SHIFT_DOWN, HORN, LIGHTS, CAMERA, LOOK_BACK, RESET, START, BACK,
    // The menu controls' D-pad and face buttons.
    MENU_UP, MENU_DOWN, MENU_LEFT, MENU_RIGHT, MENU_A, MENU_B, MENU_X, MENU_Y,
    // Drone mode's own: arming the motors, and the flight mode.
    DRONE_ARM, DRONE_MODE;

    val bit get() = 1 shl ordinal
}

/**
 * What goes to the PC: the latest steering and pedals from [Motion], and the buttons
 * held on screen or by the volume keys. Written on the main and sensor threads, read
 * by the sender thread.
 */
class Controls {
    /** The left stick's X, -1 (full left) to 1 (full right): steering, or a drone's yaw. */
    @Volatile var steer = 0f
    /** 0 to 1. */
    @Volatile var throttle = 0f
    @Volatile var brake = 0f
    /** The rest of the sticks, -1 to 1, up and right positive. Only drone mode moves them:
     *  the left stick's Y is its throttle, the right stick its pitch (Y) and roll (X). */
    @Volatile var leftY = 0f
    @Volatile var rightX = 0f
    @Volatile var rightY = 0f
    /** The menu controls are up: the wheel and pedals stay centred, so menus don't drift. */
    @Volatile var menu = false

    private val held = AtomicInteger(0)
    private val presses = IntArray(Button.entries.size)
    private val pressedAt = LongArray(Button.entries.size)
    private val main = Handler(Looper.getMainLooper())

    val buttons get() = held.get()

    fun isHeld(button: Button) = held.get() and button.bit != 0

    /** Main thread. Counted, so a finger and a volume key can hold the same button. */
    fun press(button: Button) {
        val i = button.ordinal
        presses[i]++
        if (presses[i] == 1) {
            pressedAt[i] = SystemClock.uptimeMillis()
            held.updateAndGet { it or button.bit }
        }
    }

    /** Main thread. A quick tap still lasts [MIN_HOLD_MS], long enough for the game to see it. */
    fun release(button: Button) {
        val i = button.ordinal
        if (presses[i] == 0) return
        val wait = pressedAt[i] + MIN_HOLD_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            main.postAtTime({ release(button) }, button, SystemClock.uptimeMillis() + wait)
            return
        }
        presses[i]--
        if (presses[i] == 0) held.updateAndGet { it and button.bit.inv() }
    }

    /** Main thread. Lets go of everything, now. */
    fun releaseAll() {
        Button.entries.forEach { main.removeCallbacksAndMessages(it) }
        presses.fill(0)
        held.set(0)
    }

    /** Everything the phone's motion moves goes back to centre. The throttle is the thumb's, not the motion's. */
    fun neutral() {
        steer = 0f
        throttle = 0f
        brake = 0f
        rightX = 0f
        rightY = 0f
    }

    companion object {
        const val MIN_HOLD_MS = 80L
    }
}
