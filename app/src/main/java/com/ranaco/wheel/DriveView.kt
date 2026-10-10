package com.ranaco.wheel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.ranaco.wheel.Palette.BOLD
import com.ranaco.wheel.Palette.BRAKE
import com.ranaco.wheel.Palette.DIM
import com.ranaco.wheel.Palette.EDGE
import com.ranaco.wheel.Palette.GAS
import com.ranaco.wheel.Palette.LABEL
import com.ranaco.wheel.Palette.MEDIUM
import com.ranaco.wheel.Palette.PRESSED
import com.ranaco.wheel.Palette.STEER
import com.ranaco.wheel.Palette.SURFACE
import com.ranaco.wheel.Palette.TRACK
import com.ranaco.wheel.Palette.WAIT
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The driving screen, held sideways in both hands. Thumbs sit at the two ends; the
 * middle, across the fold, is for looking at.
 *
 *   SHIFT −              VIEW MENU RESET D-PAD ⚙           SHIFT +
 *   HORN | LIGHTS     BRAKE   ( dial )   GAS     CAMERA | LOOK BACK
 *   BOOST                   ● PC name                       HANDBRAKE
 *
 * The middle shows what needs doing first: finding the PC, then setting neutral, then
 * the dial. Tapping the middle makes the current pose neutral.
 *
 * For a game's menus, the ends become a D-pad and the A B X Y buttons, and the middle
 * takes swipes as arrows and a tap as A. That comes up with D-PAD, with MENU (which
 * pauses most games), on folding the phone, or by laying it flat.
 *
 * Redraws only when something on it changes, so a still screen costs nothing.
 */
@SuppressLint("ViewConstructor")
class DriveView(
    context: Context,
    private val controls: Controls,
    private val motion: Motion,
    private val link: Link,
    private val settings: Settings,
    private val onSettings: () -> Unit,
) : View(context) {

    /**
     * Held buttons get bigger type, paddles a big sign; arrows and face buttons are their
     * Xbox mark; all of them black until pressed. STICK is one of drone mode's two sticks.
     */
    private enum class Kind { HOLD, PADDLE, TAP, ARROW, FACE, GEAR, STICK }

    private class Pad(
        val label: String,
        val button: Button? = null,
        val kind: Kind = Kind.TAP,
        val glyph: String? = null,
        val action: (() -> Unit)? = null,
    ) {
        val rect = RectF()
        /** 0 idle to 1 pressed, eased so a press fades in and out rather than blinking. */
        var glow = 0f
    }

    private val shiftDown = Pad("SHIFT", Button.SHIFT_DOWN, Kind.PADDLE, glyph = "−")
    private val horn = Pad("HORN", Button.HORN)
    private val lights = Pad("LIGHTS", Button.LIGHTS)
    private val boost = Pad("BOOST", Button.BOOST, Kind.HOLD)
    private val shiftUp = Pad("SHIFT", Button.SHIFT_UP, Kind.PADDLE, glyph = "+")
    private val camera = Pad("CAMERA", Button.CAMERA)
    private val lookBack = Pad("LOOK BACK", Button.LOOK_BACK)
    private val handbrake = Pad("HANDBRAKE", Button.HANDBRAKE, Kind.HOLD)
    private val back = Pad("VIEW", Button.BACK)
    private val start = Pad("MENU", Button.START, action = { menuTapped() })
    private val reset = Pad("RESET", Button.RESET)
    private val toggle = Pad("D-PAD", action = { setMenu(if (menu == null) Entry.TAPPED else null) })
    private val gear = Pad("", kind = Kind.GEAR, action = { onSettings() })
    private val middle = Pad("", action = { recenter() })

    // The menu controls: a D-pad under the left thumb, the face buttons under the right.
    private val arrowUp = Pad("", Button.MENU_UP, Kind.ARROW)
    private val arrowDown = Pad("", Button.MENU_DOWN, Kind.ARROW)
    private val arrowLeft = Pad("", Button.MENU_LEFT, Kind.ARROW)
    private val arrowRight = Pad("", Button.MENU_RIGHT, Kind.ARROW)
    private val faceA = Pad("SELECT", Button.MENU_A, Kind.FACE)
    private val faceB = Pad("BACK", Button.MENU_B, Kind.FACE)
    private val faceX = Pad("", Button.MENU_X, Kind.FACE)
    private val faceY = Pad("", Button.MENU_Y, Kind.FACE)

    // Drone mode: two sticks, as on a drone's radio (Mode 2), and its buttons in the middle.
    private val leftStick = Pad("", kind = Kind.STICK)
    private val rightStick = Pad("", kind = Kind.STICK)
    private val arm = Pad("ARM", Button.DRONE_ARM, Kind.HOLD)
    private val flightMode = Pad("MODE", Button.DRONE_MODE)

    // A drone's RESET is beside its other buttons, so its top row has one fewer.
    private val wheelTop = listOf(back, start, reset, toggle, gear)
    private val droneTop = listOf(back, start, toggle, gear)
    private val menuPads = listOf(arrowUp, arrowDown, arrowLeft, arrowRight, faceA, faceB, faceX, faceY)
    private val driving = listOf(shiftDown, horn, lights, boost, shiftUp, camera, lookBack, handbrake) + wheelTop
    private val flying = listOf(leftStick, rightStick, arm, flightMode, reset, camera) + droneTop
    private val menuing = menuPads + wheelTop
    private val menuingDrone = menuPads + droneTop
    private val drawn get() = when {
        menu != null -> if (drone) menuingDrone else menuing
        drone -> flying
        else -> driving
    }
    private val owners = SparseArray<Pad>()
    /** Fingers on the middle while the menu controls are up, and where each came down. */
    private val swipes = SparseArray<PointF>()

    /** How the menu controls came up, which decides what puts them away again. */
    private enum class Entry { PUT_DOWN, TAPPED }
    /** The menu controls are up (and how), or null while driving. */
    private var menu: Entry? = null
    private var wasFlat = false
    private var flatChangedAt = 0L
    /** DRIVE tapped with the phone flat: the menu controls wait until it's been picked up. */
    private var dismissed = false

    /** Flying a drone instead of driving (Settings.droneMode). */
    private var drone = false
    /** The sticks. Throttle, 0 to 1, stays where it's left, as on a radio; yaw, pitch and
     *  roll, -1 to 1 (right and up positive), spring back to centre. */
    private var throttle = 0f
    private var yaw = 0f
    private var pitch = 0f
    private var roll = 0f
    /** A finger on a stick: which one, where it came down, and the throttle then. */
    private class Grip(val stick: Pad, val x: Float, val y: Float, val throttle: Float)
    private val grips = SparseArray<Grip>()
    // From Settings: the sticks' expo, and how far a thumb moves for full stick.
    private var expo = 1f
    private var reach = 1f

    // From Settings when the screen comes back (see refresh), not on every frame.
    private var gasAt = settings.gasAt
    private var brakeAt = settings.brakeAt
    private var pedalDeadzone = settings.pedalDeadzone
    private var lock = settings.steerLock
    private var centered = false
    private var lastPc = ""

    private val brakeBar = RectF()
    private val gasBar = RectF()
    private val card = RectF()
    private var dialX = 0f
    private var dialY = 0f
    private var dialR = 0f
    private var pillY = 0f
    private var insets = Insets.NONE
    private var centeredAt = -CENTRED_MS
    private var animating = false

    /** Half-folded: the dashboard goes on the top half, the buttons on the bottom one. */
    var flex = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
            requestLayout()
        }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = MEDIUM
        letterSpacing = 0.08f
        // Even-width digits, so the readouts don't shuffle about as they change.
        fontFeatureSettings = "tnum"
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * dp; color = LABEL }
    private val wrapped = HashMap<String, StaticLayout>()
    private val oval = RectF()
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f * dp }
    private val badgeInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val badgeBox = RectF()
    private val arrow = Path()

    // What was on screen last frame; the next frame is only drawn if this changes.
    private val shown = IntArray(18)
    private val now = IntArray(18)
    private val tick = object : Runnable {
        override fun run() {
            followFlat()
            snapshot()
            if (animating || !now.contentEquals(shown)) {
                now.copyInto(shown)
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isHapticFeedbackEnabled = true
        refresh()
    }

    /** Re-reads what Settings may have changed. */
    fun refresh() {
        gasAt = settings.gasAt
        brakeAt = settings.brakeAt
        pedalDeadzone = settings.pedalDeadzone
        lock = settings.steerLock
        expo = settings.droneExpo
        reach = settings.droneStick * dp
        if (drone != settings.droneMode) {
            drone = settings.droneMode
            resetSticks()
            requestLayout()
        }
        centered = settings.neutral(flex).set
        lastPc = settings.lastPc
        invalidate()
    }

    /** Back on screen, a drone starts with no throttle and every stick centred: never wherever they were left. */
    fun resetSticks() {
        grips.clear()
        throttle = 0f
        yaw = 0f
        pitch = 0f
        roll = 0f
        sendSticks()
    }

    /** The sticks onto the controller, Mode 2: throttle and yaw on the left, pitch and roll on the right. */
    private fun sendSticks() {
        if (!drone) {
            controls.leftY = 0f
            controls.rightX = 0f
            controls.rightY = 0f
            return
        }
        controls.leftY = throttle * 2 - 1
        controls.steer = Motion.shape(yaw, 0f, 1f, expo)
        controls.rightX = Motion.shape(roll, 0f, 1f, expo)
        controls.rightY = Motion.shape(pitch, 0f, 1f, expo)
    }

    /** A finger on a stick moved: from where it came down, [reach] is full stick. */
    private fun moveStick(grip: Grip, x: Float, y: Float) {
        val dx = ((x - grip.x) / reach).coerceIn(-1f, 1f)
        val dy = ((grip.y - y) / reach).coerceIn(-1f, 1f)
        if (grip.stick === leftStick) {
            // The throttle's whole travel is twice the reach, like a stick from bottom to top.
            val t = (grip.throttle + (grip.y - y) / (2 * reach)).coerceIn(0f, 1f)
            // A tick at no throttle and at full, so the ends can be found without looking.
            if ((t == 0f || t == 1f) && t != throttle) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            throttle = t
            yaw = dx
        } else {
            roll = dx
            pitch = dy
        }
        sendSticks()
    }

    /** A finger left a stick: everything on it but the throttle springs back. */
    private fun letGo(grip: Grip) {
        if (grip.stick === leftStick) {
            yaw = 0f
        } else {
            roll = 0f
            pitch = 0f
        }
        sendSticks()
    }

    /** Every finger off the sticks at once: all but the throttle back to centre. */
    private fun letGoAll() {
        grips.clear()
        yaw = 0f
        pitch = 0f
        roll = 0f
        sendSticks()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun snapshot() {
        now[0] = (controls.steer * 400).roundToInt()
        now[1] = (controls.throttle * 200).roundToInt()
        now[2] = (controls.brake * 200).roundToInt()
        now[3] = motion.steerAngle.roundToInt()
        now[4] = (motion.forwardAngle * 2).roundToInt()
        now[5] = controls.buttons
        now[6] = if (link.connected) 1 else 0
        now[7] = if (motion.parked) 1 else 0
        now[8] = if (centered) 1 else 0
        now[9] = link.pcName.hashCode() * 31 + link.player +
            (if (link.full) 7 else 0) + (if (link.waiting) 11 else 0) + (if (link.blocked) 13 else 0)
        now[10] = if (SystemClock.uptimeMillis() - centeredAt < CENTRED_MS) 1 else 0
        now[11] = if (motion.available) 1 else 0
        now[12] = link.xbox.contentHashCode()
        now[13] = (menu?.ordinal ?: -1) + 1
        now[14] = (throttle * 200).roundToInt() + grips.size() * 1000
        now[15] = (yaw * 200).roundToInt()
        now[16] = (roll * 200).roundToInt()
        now[17] = (pitch * 200).roundToInt()
    }

    /**
     * Laid flat for a moment, the phone brings up the menu controls (it can't steer flat
     * anyway); picked up again, it drives. Menus opened by a tap stay until DRIVE or MENU.
     */
    private fun followFlat() {
        val flat = motion.parked
        val time = SystemClock.uptimeMillis()
        if (flat != wasFlat) {
            wasFlat = flat
            flatChangedAt = time
        }
        if (!flat) dismissed = false
        if (time - flatChangedAt < SETTLE_MS) return
        if (flat && menu == null && !dismissed) setMenu(Entry.PUT_DOWN)
        else if (!flat && menu == Entry.PUT_DOWN) setMenu(null)
    }

    private fun setMenu(entry: Entry?) {
        if (entry == menu) return
        if (entry == null && motion.parked) dismissed = true
        val swap = (menu == null) != (entry == null)
        menu = entry
        if (swap) {
            // The buttons under the fingers are about to change: let go of whatever they held.
            for (k in 0 until owners.size()) owners.valueAt(k).button?.let(controls::release)
            owners.clear()
            swipes.clear()
            letGoAll()
            controls.menu = entry != null
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            requestLayout()
        }
        invalidate()
    }

    /** MENU pauses most games, so it brings up the menu controls; pressed again, it puts them away. */
    private fun menuTapped() = when (menu) {
        null -> setMenu(Entry.TAPPED)
        Entry.TAPPED -> setMenu(null)
        Entry.PUT_DOWN -> Unit
    }

    /** Folding the phone pauses the game, so it opens on the menu controls. */
    fun showMenu() {
        if (menu == null) setMenu(Entry.TAPPED)
    }

    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets {
        insets = windowInsets.getInsets(WindowInsets.Type.displayCutout())
        requestLayout()
        return windowInsets
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (flex) layoutFlex() else layoutWheel()
        // Gripping the ends mustn't fire the back gesture: these are the pads at the edges.
        val edges = when {
            menu != null -> listOf(arrowLeft.rect, faceB.rect)
            drone -> listOf(leftStick.rect, rightStick.rect)
            else -> listOf(boost.rect, handbrake.rect)
        }
        systemGestureExclusionRects = edges.map {
            Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
        }
    }

    /**
     * Half-folded, held like a handheld console:
     *
     *   [        PC        ]
     *   BRAKE  ( dial )  GAS      top half: looked at
     *   ---- the fold ----
     *   VIEW MENU RESET D-PAD ⚙
     *   SHIFT −      SHIFT +      bottom half: under the thumbs
     *   HORN LIGHTS  CAMERA LOOK BACK  (with the menu controls up: the D-pad and A B X Y)
     *   BOOST        HANDBRAKE
     */
    private fun layoutFlex() {
        val gap = 10 * dp
        val l = insets.left + gap
        val r = width - insets.right - gap
        val t = insets.top + gap
        val b = height - insets.bottom - gap
        // Nothing sits on the crease.
        val fold = height / 2f
        val topEnd = fold - 18 * dp
        val bottomStart = fold + 18 * dp

        pillY = t + 14 * dp
        val bar = 20 * dp
        val areaTop = t + 40 * dp
        val areaBottom = topEnd - 26 * dp
        brakeBar.set(l + gap, areaTop, l + gap + bar, areaBottom)
        gasBar.set(r - gap - bar, areaTop, r - gap, areaBottom)
        // The menu controls and a drone have no pedals, so their card takes the bars' room too.
        if (menu != null || drone) card.set(l + gap, areaTop, r - gap, areaBottom)
        else card.set(brakeBar.right + gap * 1.5f, areaTop, gasBar.left - gap * 1.5f, areaBottom)
        middle.rect.set(card)
        dialX = (l + r) / 2
        dialY = (areaTop + areaBottom) / 2
        dialR = min(card.width(), card.height()) / 2

        val row = 44 * dp
        layoutTopRow(l, r, bottomStart, row, gap)
        val y0 = bottomStart + row + gap
        val rest = b - y0
        val paddle = rest * 0.24f
        val small = rest * 0.2f
        val half = (r - l - gap) / 2
        if (menu != null) {
            cross(RectF(l, y0, l + half, b), arrowUp, arrowDown, arrowLeft, arrowRight)
            cross(RectF(r - half, y0, r, b), faceY, faceA, faceX, faceB)
            return
        }
        fun side(x0: Float, top: Pad, a: Pad, c: Pad, bottom: Pad) {
            val x1 = x0 + half
            var y = y0
            top.rect.set(x0, y, x1, y + paddle)
            y += paddle + gap
            val mid = (x0 + x1) / 2
            a.rect.set(x0, y, mid - gap / 2, y + small)
            c.rect.set(mid + gap / 2, y, x1, y + small)
            y += small + gap
            bottom.rect.set(x0, y, x1, b)
        }
        if (drone) {
            leftStick.rect.set(l, y0, l + half, b)
            rightStick.rect.set(r - half, y0, r, b)
            droneButtons(l + gap, r - gap, areaTop, areaBottom, gap)
            return
        }
        side(l, shiftDown, horn, lights, boost)
        side(r - half, shiftUp, camera, lookBack, handbrake)
    }

    /** A drone's buttons, two by two at the bottom of the middle, with the card above them. */
    private fun droneButtons(l: Float, r: Float, top: Float, bottom: Float, gap: Float) {
        val h = 54 * dp
        val w = (r - l - gap) / 2
        val y = bottom - 2 * h - gap
        arm.rect.set(l, y, l + w, y + h)
        flightMode.rect.set(l + w + gap, y, r, y + h)
        reset.rect.set(l, y + h + gap, l + w, bottom)
        camera.rect.set(l + w + gap, y + h + gap, r, bottom)
        card.set(l, top, r, y - gap)
    }

    /** VIEW, MENU, RESET (but a drone's is with its buttons) and the D-pad switch share the
     *  row; Settings is a square gear at the end. */
    private fun layoutTopRow(l: Float, r: Float, y: Float, row: Float, gap: Float) {
        val pads = (if (drone) droneTop else wheelTop) - gear
        val w = (r - l - row - gap * pads.size) / pads.size
        pads.forEachIndexed { i, pad ->
            val x = l + i * (w + gap)
            pad.rect.set(x, y, x + w, y + row)
        }
        gear.rect.set(r - row, y, r, y + row)
    }

    /**
     * Four pads in a plus, as big as [area] allows, in its middle: the D-pad's arrows, or
     * the face buttons' diamond (Y on top, A at the bottom, X and B either side).
     */
    private fun cross(area: RectF, top: Pad, bottom: Pad, start: Pad, end: Pad) {
        val gap = 6 * dp
        val arm = (min(area.width(), area.height()) - 2 * gap) / 3
        val cx = area.centerX()
        val cy = area.centerY()
        val near = arm / 2 + gap  // from the middle to an arm's inner edge
        top.rect.set(cx - arm / 2, cy - near - arm, cx + arm / 2, cy - near)
        bottom.rect.set(cx - arm / 2, cy + near, cx + arm / 2, cy + near + arm)
        start.rect.set(cx - near - arm, cy - arm / 2, cx - near, cy + arm / 2)
        end.rect.set(cx + near, cy - arm / 2, cx + near + arm, cy + arm / 2)
    }

    private fun layoutWheel() {
        val gap = 10 * dp
        // The camera's cutout sits at one end; the same margin at both ends keeps the halves even.
        val side = max(insets.left, insets.right) + gap
        val l = side
        val t = insets.top + gap
        val r = width - side
        val b = height - insets.bottom - gap
        val h = b - t
        val column = (r - l) * 0.27f

        // Each end: a paddle on top, two small buttons, and a big one to hold at the bottom.
        val paddle = h * 0.26f
        val small = h * 0.22f
        fun column(x0: Float, top: Pad, a: Pad, c: Pad, bottom: Pad) {
            val x1 = x0 + column
            top.rect.set(x0, t, x1, t + paddle)
            val y1 = t + paddle + gap
            val mid = (x0 + x1) / 2
            a.rect.set(x0, y1, mid - gap / 2, y1 + small)
            c.rect.set(mid + gap / 2, y1, x1, y1 + small)
            bottom.rect.set(x0, y1 + small + gap, x1, b)
        }
        when {
            menu != null -> {
                cross(RectF(l, t, l + column, b), arrowUp, arrowDown, arrowLeft, arrowRight)
                cross(RectF(r - column, t, r, b), faceY, faceA, faceX, faceB)
            }
            drone -> {
                leftStick.rect.set(l, t, l + column, b)
                rightStick.rect.set(r - column, t, r, b)
            }
            else -> {
                column(l, shiftDown, horn, lights, boost)
                column(r - column, shiftUp, camera, lookBack, handbrake)
            }
        }

        // The middle: small buttons on top, the dial between the pedal bars, the PC at the bottom.
        val cl = l + column + 2 * gap
        val cr = r - column - 2 * gap
        val row = 44 * dp
        layoutTopRow(cl, cr, t, row, gap)
        pillY = b - 14 * dp
        val areaTop = t + row + gap * 2
        val areaBottom = b - 40 * dp
        val bar = 20 * dp
        brakeBar.set(cl, areaTop, cl + bar, areaBottom)
        gasBar.set(cr - bar, areaTop, cr, areaBottom)
        if (menu != null || drone) card.set(cl, areaTop, cr, areaBottom)
        else card.set(cl + bar + gap * 1.5f, areaTop, cr - bar - gap * 1.5f, areaBottom)
        if (drone && menu == null) droneButtons(cl, cr, areaTop, areaBottom, gap)
        middle.rect.set(card)
        dialX = (cl + cr) / 2
        dialY = (areaTop + areaBottom) / 2
        dialR = min(card.width(), card.height()) / 2
    }

    private fun recenter() {
        motion.recenter()
        centered = true
        centeredAt = SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val pad = hit(e.getX(i), e.getY(i)) ?: return true
                if (pad === middle && menu != null) {
                    swipes.put(e.getPointerId(i), PointF(e.getX(i), e.getY(i)))
                    return true
                }
                if (pad.kind == Kind.STICK) {
                    // A stick moves from where the thumb lands, so touching it never jumps the throttle.
                    grips.put(e.getPointerId(i), Grip(pad, e.getX(i), e.getY(i), throttle))
                    return true
                }
                owners.put(e.getPointerId(i), pad)
                pad.button?.let {
                    controls.press(it)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                pad.action?.invoke()
            }
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) {
                grips[e.getPointerId(k)]?.let { moveStick(it, e.getX(k), e.getY(k)) }
            }
            // A finger that slides off its button keeps holding it until it lifts.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val pointer = e.getPointerId(i)
                grips[pointer]?.let { grip ->
                    grips.remove(pointer)
                    letGo(grip)
                }
                swipes[pointer]?.let { from ->
                    swipes.remove(pointer)
                    swipe(e.getX(i) - from.x, e.getY(i) - from.y)
                }
                lift(pointer)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (k in 0 until owners.size()) owners.valueAt(k).button?.let(controls::release)
                owners.clear()
                swipes.clear()
                letGoAll()
            }
        }
        return true
    }

    private fun lift(pointer: Int) {
        val pad = owners[pointer] ?: return
        owners.remove(pointer)
        pad.button?.let(controls::release)
    }

    /** The middle, with the menu controls up: a swipe is a press of that arrow, a tap is A. */
    private fun swipe(dx: Float, dy: Float) {
        val button = when {
            hypot(dx, dy) < SWIPE_DP * dp -> Button.MENU_A
            abs(dx) > abs(dy) -> if (dx > 0) Button.MENU_RIGHT else Button.MENU_LEFT
            else -> if (dy > 0) Button.MENU_DOWN else Button.MENU_UP
        }
        controls.press(button)
        controls.release(button)  // held the shortest time a game still sees
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun hit(x: Float, y: Float): Pad? {
        // A drone's middle is only something to look at: there's no pose to set.
        val pads = if (drone && menu == null) drawn else drawn + middle
        pads.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        // Near misses at the edge of a button still count.
        val slop = 8 * dp
        return pads.firstOrNull { x >= it.rect.left - slop && x <= it.rect.right + slop && y >= it.rect.top - slop && y <= it.rect.bottom + slop }
    }

    override fun onDraw(canvas: Canvas) {
        animating = false
        for (pad in drawn) {
            val target = if (pad.button?.let(controls::isHeld) == true) 1f else 0f
            // Quick in, a little slower out.
            pad.glow += (target - pad.glow) * if (target > pad.glow) 0.6f else 0.3f
            if (abs(target - pad.glow) < 0.02f) pad.glow = target else animating = true
            drawPad(canvas, pad)
        }

        if (menu == null && !drone) {
            val forward = motion.forwardAngle
            drawPedal(canvas, brakeBar, controls.brake, -forward, brakeAt, BRAKE, "BRAKE")
            drawPedal(canvas, gasBar, controls.throttle, forward, gasAt, GAS, "GAS")
        }

        when {
            !motion.available && !drone -> drawCard(canvas, BRAKE, "No motion sensor", listOf(
                "This phone can't steer: it has no accelerometer or gyroscope.",
            ))
            !link.connected -> drawCard(canvas, WAIT, "Looking for your PC", listOf(
                "Open Wheel on your PC, then scan the code in its window with this phone's camera.",
                if (lastPc.isEmpty()) "On the same Wi-Fi, it also finds the PC by itself."
                else "On the same Wi-Fi, it also finds the PC by itself. Last seen at $lastPc.",
            ))
            link.waiting -> drawCard(canvas, WAIT, "Waiting for ${link.pcName ?: "the PC"}", listOf(
                "Click Allow in Wheel's window on ${link.pcName ?: "the PC"}, or scan the code there with this phone's camera.",
                "After that, this phone joins straight away.",
            ))
            link.blocked -> drawCard(canvas, BRAKE, "Not allowed on ${link.pcName ?: "this PC"}", listOf(
                "Wheel on ${link.pcName ?: "the PC"} turned this phone down.",
                "It can let it in again from its window, under Phones this PC knows.",
            ))
            // Menus come before neutral matters: a game's first screens are menus.
            menu != null -> drawCard(canvas, Color.WHITE, "Menu controls", listOf(
                "Swipe here to move, tap to select.",
                when {
                    menu == Entry.PUT_DOWN -> "Pick the phone up to ${if (drone) "fly" else "drive"}."
                    drone -> "Tap FLY to fly."
                    else -> "Tap DRIVE to drive."
                },
            ))
            drone -> drawReadout(canvas)
            !centered -> drawCard(canvas, Color.WHITE, "Set your neutral", listOf(
                "Hold the phone the way you'll drive, then tap here.",
                "Gas and brake stay off until you do.",
            ))
            else -> drawDial(canvas)
        }
        drawPill(canvas)
        // Flat with the menu controls put away: nothing's being driven, and this says why.
        if (menu == null && !drone && motion.parked && link.connected && centered) drawPaused(canvas)
    }

    /**
     * The Xbox button [pad] presses on this PC. Until the PC says, only the menu controls
     * have one: their look is their mark, so they fall back to what they press by default.
     */
    private fun xboxOf(pad: Pad): Xbox? {
        val button = pad.button ?: return null
        link.xbox?.getOrNull(button.ordinal)?.let { return Xbox.of(it.toInt() and 0xFF) }
        return when (button) {
            Button.MENU_UP -> Xbox.UP
            Button.MENU_DOWN -> Xbox.DOWN
            Button.MENU_LEFT -> Xbox.LEFT
            Button.MENU_RIGHT -> Xbox.RIGHT
            Button.MENU_A -> Xbox.A
            Button.MENU_B -> Xbox.B
            Button.MENU_X -> Xbox.X
            Button.MENU_Y -> Xbox.Y
            else -> null
        }
    }

    private fun drawPad(canvas: Canvas, pad: Pad) {
        val g = pad.glow
        val radius = (if (pad.kind == Kind.TAP || pad.kind == Kind.GEAR) 16 else 20) * dp
        val cx = pad.rect.centerX()
        val cy = pad.rect.centerY()
        val ink = mix(LABEL, Color.BLACK, g)
        fill.color = mix(SURFACE, PRESSED, g)
        // The switch out of the menu controls is lit, so the way back to driving is easy to find.
        stroke.color = mix(if (pad === toggle && menu != null) LABEL else EDGE, PRESSED, g)
        text.color = ink
        text.textSize = (if (pad.kind == Kind.HOLD) 17 else 12) * dp
        text.typeface = MEDIUM
        if (pad.kind == Kind.FACE) {
            val r = min(pad.rect.width(), pad.rect.height()) / 2
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, stroke)
        } else {
            canvas.drawRoundRect(pad.rect, radius, radius, fill)
            canvas.drawRoundRect(pad.rect, radius, radius, stroke)
        }
        val xbox = xboxOf(pad)
        val glyph = pad.glyph
        val label = if (pad === toggle && menu != null) (if (drone) "FLY" else "DRIVE") else pad.label
        if (pad.kind == Kind.STICK) {
            return drawStick(canvas, pad)
        } else if (pad.kind == Kind.GEAR) {
            drawGear(canvas, cx, cy, ink)
        } else if (pad.kind == Kind.ARROW || pad.kind == Kind.FACE) {
            val size = min(pad.rect.width(), pad.rect.height())
            val lift = if (pad.label.isEmpty()) 0f else size * 0.08f
            if (xbox != null) drawMark(canvas, xbox, cx, cy - lift, size * 0.5f, mix(Color.WHITE, Color.BLACK, g))
            if (pad.label.isNotEmpty()) {
                text.typeface = MEDIUM
                text.textSize = 9 * dp
                text.color = mix(DIM, Color.BLACK, g)
                canvas.drawText(pad.label, cx, cy + size * 0.3f, text)
            }
        } else if (glyph != null) {
            val labelSize = text.textSize
            text.textSize = 40 * dp
            text.color = mix(Color.WHITE, Color.BLACK, g)
            canvas.drawText(glyph, cx, cy + 6 * dp, text)
            text.textSize = labelSize
            text.color = mix(DIM, Color.BLACK, g)
            canvas.drawText(label, cx, cy + 30 * dp, text)
        } else if (xbox != null && pad.rect.height() < 60 * dp) {
            // A short button has no corner to spare: the mark goes after the label, which
            // gets a little smaller where the two would crowd the edges.
            val room = pad.rect.width() - 20 * dp - BADGE_GAP * dp - badgeWidth(xbox)
            val natural = text.measureText(label)
            if (natural > room) text.textSize *= max(0.75f, room / natural)
            val w = text.measureText(label)
            val total = w + BADGE_GAP * dp + badgeWidth(xbox)
            canvas.drawText(label, cx - total / 2 + w / 2, cy + text.textSize * 0.35f, text)
            drawBadge(canvas, xbox, cx + total / 2 - badgeWidth(xbox) / 2, cy, g)
            return
        } else {
            canvas.drawText(label, cx, cy + text.textSize * 0.35f, text)
        }
        if (xbox != null && pad.kind != Kind.ARROW && pad.kind != Kind.FACE) {
            val inset = 10 * dp
            drawBadge(canvas, xbox, pad.rect.right - inset - badgeWidth(xbox) / 2, pad.rect.top + inset + BADGE_SIZE * dp / 2, g)
        }
    }

    private fun badgeWidth(xbox: Xbox) = (if (xbox.wide) BADGE_SIZE + 9 else BADGE_SIZE) * dp

    /**
     * The Xbox button a pad presses, the way games' prompts show it: a letter in a ring,
     * a bumper or stick in a pill, the Menu and View marks, or a D-pad arrow.
     */
    private fun drawBadge(canvas: Canvas, xbox: Xbox, cx: Float, cy: Float, g: Float) {
        val h = BADGE_SIZE * dp
        val w = badgeWidth(xbox)
        badgeRing.color = mix(DIM, Color.BLACK, g)
        badgeBox.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.drawRoundRect(badgeBox, h / 2, h / 2, badgeRing)
        drawMark(canvas, xbox, cx, cy, h, mix(LABEL, Color.BLACK, g))
    }

    /** An Xbox button's mark, without its ring, in a box [h] tall centred on cx, cy. */
    private fun drawMark(canvas: Canvas, xbox: Xbox, cx: Float, cy: Float, h: Float, ink: Int) {
        badgeInk.color = ink
        badgeInk.strokeWidth = h * 0.07f
        val u = h / 10  // a tenth of the mark, for the shapes inside it
        when (xbox) {
            Xbox.MENU -> for (k in -1..1) canvas.drawLine(cx - 2.6f * u, cy + k * 2f * u, cx + 2.6f * u, cy + k * 2f * u, badgeInk)
            Xbox.VIEW -> {
                badgeBox.set(cx - 3f * u, cy - 2.6f * u, cx + 1f * u, cy + 0.8f * u)
                canvas.drawRoundRect(badgeBox, u * 0.6f, u * 0.6f, badgeInk)
                badgeBox.set(cx - 1f * u, cy - 0.8f * u, cx + 3f * u, cy + 2.6f * u)
                canvas.drawRoundRect(badgeBox, u * 0.6f, u * 0.6f, badgeInk)
            }
            Xbox.UP, Xbox.DOWN, Xbox.LEFT, Xbox.RIGHT -> {
                val angle = when (xbox) { Xbox.UP -> 0f; Xbox.RIGHT -> 90f; Xbox.DOWN -> 180f; else -> 270f }
                arrow.reset()
                arrow.moveTo(cx, cy - 2.8f * u)
                arrow.lineTo(cx + 2.8f * u, cy + 2f * u)
                arrow.lineTo(cx - 2.8f * u, cy + 2f * u)
                arrow.close()
                canvas.save()
                canvas.rotate(angle, cx, cy)
                badgeInk.style = Paint.Style.FILL
                canvas.drawPath(arrow, badgeInk)
                badgeInk.style = Paint.Style.STROKE
                canvas.restore()
            }
            // The Xbox button itself: a ring with a dot, rather than anyone's logo.
            Xbox.GUIDE -> {
                canvas.drawCircle(cx, cy, 2.8f * u, badgeInk)
                badgeInk.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 1.1f * u, badgeInk)
                badgeInk.style = Paint.Style.STROKE
            }
            else -> {
                text.typeface = BOLD
                text.textSize = h * if (xbox.wide) 0.475f else 0.55f
                text.color = ink
                canvas.drawText(xbox.name, cx, cy + text.textSize * 0.36f, text)
                text.typeface = MEDIUM
            }
        }
    }

    /** Settings, as a gear: eight teeth round a ring. */
    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, ink: Int) {
        badgeInk.color = ink
        badgeInk.strokeWidth = 2.2f * dp
        canvas.drawCircle(cx, cy, 5.5f * dp, badgeInk)
        badgeInk.strokeCap = Paint.Cap.BUTT
        badgeInk.strokeWidth = 3.2f * dp
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cx + 6.4f * dp * c, cy + 6.4f * dp * s, cx + 9.4f * dp * c, cy + 9.4f * dp * s, badgeInk)
        }
        badgeInk.strokeCap = Paint.Cap.ROUND
    }

    /**
     * A bar on the tilt scale: the dark stretch at the bottom is the deadzone, the white
     * line is where the phone is, and the colour between them is how much pedal that is.
     */
    private fun drawPedal(canvas: Canvas, bar: RectF, value: Float, angle: Float, full: Float, color: Int, label: String) {
        val radius = bar.width() / 2
        fill.color = SURFACE
        canvas.drawRoundRect(bar, radius, radius, fill)
        fun y(degrees: Float) = bar.bottom - bar.height() * (degrees / full).coerceIn(0f, 1f)
        val dz = y(pedalDeadzone)
        val at = y(angle)
        if (value > 0f) {
            fill.color = color
            canvas.drawRoundRect(bar.left, min(at, dz - bar.width()), bar.right, dz, radius, radius, fill)
        }
        line.color = DIM
        line.strokeWidth = 1.5f * dp
        canvas.drawLine(bar.left - 4 * dp, dz, bar.right + 4 * dp, dz, line)
        if (angle > 0f && centered && !motion.parked) {
            line.color = Color.WHITE
            line.strokeWidth = 2.5f * dp
            canvas.drawLine(bar.left - 3 * dp, at, bar.right + 3 * dp, at, line)
        }
        text.typeface = MEDIUM
        text.textSize = 10 * dp
        text.color = if (value > 0f) color else DIM
        canvas.drawText(if (value > 0f) "${(value * 100).roundToInt()}%" else label, bar.centerX(), bar.bottom + 16 * dp, text)
    }

    /**
     * One of drone mode's sticks, as a radio's gimbal: a square gate, the stick's dot in it,
     * and for the throttle, the gate filled up to where it's set.
     */
    private fun drawStick(canvas: Canvas, pad: Pad) {
        val r = pad.rect
        val throttleStick = pad === leftStick
        var held = false
        for (k in 0 until grips.size()) if (grips.valueAt(k).stick === pad) held = true
        val radius = 20 * dp
        fill.color = SURFACE
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = if (held) LABEL else EDGE
        canvas.drawRoundRect(r, radius, radius, stroke)

        text.typeface = MEDIUM
        text.textSize = 11 * dp
        text.color = DIM
        canvas.drawText(if (throttleStick) "THROTTLE · YAW" else "PITCH · ROLL", r.centerX(), r.top + 24 * dp, text)

        // The gate: as big as fits between the label and the throttle's number.
        val size = min(r.width() - 32 * dp, r.height() - 84 * dp)
        val cx = r.centerX()
        val cy = r.top + 38 * dp + (r.height() - 72 * dp) / 2
        val half = size / 2
        badgeBox.set(cx - half, cy - half, cx + half, cy + half)
        fill.color = Color.BLACK
        canvas.drawRoundRect(badgeBox, 16 * dp, 16 * dp, fill)
        if (throttleStick && throttle > 0f) {
            // The throttle, filled up from the bottom, since it stays where it's left.
            canvas.save()
            canvas.clipRect(cx - half, cy + half - size * throttle, cx + half, cy + half)
            fill.color = Palette.SHADE
            canvas.drawRoundRect(badgeBox, 16 * dp, 16 * dp, fill)
            canvas.restore()
        }
        stroke.color = EDGE
        canvas.drawRoundRect(badgeBox, 16 * dp, 16 * dp, stroke)
        line.color = TRACK
        line.strokeWidth = 1.5f * dp
        canvas.drawLine(cx - half, cy, cx + half, cy, line)
        canvas.drawLine(cx, cy - half, cx, cy + half, line)

        val x = if (throttleStick) yaw else roll
        val y = if (throttleStick) throttle * 2 - 1 else pitch
        val dotX = cx + half * x
        val dotY = cy - half * y
        fill.color = Color.BLACK
        canvas.drawCircle(dotX, dotY, 14 * dp, fill)
        fill.color = if (throttleStick && throttle > 0f) GAS else Color.WHITE
        canvas.drawCircle(dotX, dotY, 11 * dp, fill)

        if (throttleStick) {
            text.textSize = 13 * dp
            text.color = if (throttle > 0f) Color.WHITE else LABEL
            canvas.drawText("${(throttle * 100).roundToInt()}%", cx, r.bottom - 16 * dp, text)
        }
    }

    /** Flying, connected: what the sticks are sending, in the middle above the buttons. */
    private fun drawReadout(canvas: Canvas) {
        fun signed(v: Float) = (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
        val cx = card.centerX()
        val cy = card.centerY()
        text.typeface = MEDIUM
        text.textSize = 10 * dp
        text.color = DIM
        canvas.drawText("THROTTLE", cx, cy - 26 * dp, text)
        text.textSize = 30 * dp
        text.color = if (throttle > 0f) Color.WHITE else LABEL
        canvas.drawText("${(throttle * 100).roundToInt()}%", cx, cy + 8 * dp, text)
        text.textSize = 11 * dp
        text.color = DIM
        canvas.drawText("yaw ${signed(controls.steer)}   pitch ${signed(controls.rightY)}   roll ${signed(controls.rightX)}",
            cx, cy + 32 * dp, text)
    }

    private fun drawDial(canvas: Canvas) {
        val r = dialR - 16 * dp
        oval.set(dialX - r, dialY - r, dialX + r, dialY + r)
        // A 240° track; the fill runs from top centre to full lock at either end.
        arc.strokeWidth = 12 * dp
        arc.color = TRACK
        canvas.drawArc(oval, 150f, 240f, false, arc)
        line.strokeWidth = 2 * dp
        for (k in -2..2) {
            val a = Math.toRadians((270 + k * 60).toDouble())
            line.color = if (k == 0) LABEL else EDGE
            val inner = r + 11 * dp
            val outer = r + (if (k == 0) 18 else 15) * dp
            canvas.drawLine(dialX + inner * cos(a).toFloat(), dialY + inner * sin(a).toFloat(),
                dialX + outer * cos(a).toFloat(), dialY + outer * sin(a).toFloat(), line)
        }
        // Lying flat, the angles mean nothing; the paused overlay says what's going on.
        if (motion.parked) return
        val steer = controls.steer
        if (steer != 0f) {
            arc.color = STEER
            canvas.drawArc(oval, 270f, steer * 120f, false, arc)
        }
        // Where the phone actually is, on the same scale, a little past full lock.
        val turned = (motion.steerAngle / lock * 120f).coerceIn(-132f, 132f)
        val a = Math.toRadians((270 + turned).toDouble())
        val kx = dialX + r * cos(a).toFloat()
        val ky = dialY + r * sin(a).toFloat()
        fill.color = Color.BLACK
        canvas.drawCircle(kx, ky, 10 * dp, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(kx, ky, 7 * dp, fill)

        val deg = motion.steerAngle.roundToInt()
        text.typeface = MEDIUM
        text.color = Color.WHITE
        text.textSize = 38 * dp
        canvas.drawText(if (deg == 0) "0°" else "${if (deg < 0) "L" else "R"} ${abs(deg)}°", dialX, dialY + 8 * dp, text)
        val tilt = motion.forwardAngle.roundToInt()
        text.color = DIM
        text.textSize = 11 * dp
        canvas.drawText("tilt ${if (tilt > 0) "+" else ""}$tilt°", dialX, dialY + 32 * dp, text)
        val justSet = SystemClock.uptimeMillis() - centeredAt < CENTRED_MS
        text.color = if (justSet) GAS else DIM
        canvas.drawText(if (justSet) "✓ neutral set" else "tap to re-centre", dialX, dialY + r * 0.66f, text)
    }

    /** The middle, when something needs doing before the dial is any use: a title and a few lines. */
    private fun drawCard(canvas: Canvas, accent: Int, title: String, lines: List<String>) {
        val radius = 22 * dp
        fill.color = SURFACE
        canvas.drawRoundRect(card, radius, radius, fill)
        stroke.color = EDGE
        canvas.drawRoundRect(card, radius, radius, stroke)

        val pad = 22 * dp
        val width = (card.width() - 2 * pad).roundToInt()
        // Laid out once per text and width, not on every frame.
        val layouts = lines.map { line ->
            wrapped.getOrPut("$width $line") {
                StaticLayout.Builder.obtain(line, 0, line.length, body, width).setLineSpacing(2 * dp, 1f).build()
            }
        }
        val titleSize = 17 * dp
        val space = 10 * dp
        val total = titleSize + space + layouts.sumOf { (it.height + space).toDouble() }.toFloat()
        var y = card.centerY() - total / 2
        val x = card.left + pad

        fill.color = accent
        canvas.drawCircle(x + 4 * dp, y + titleSize * 0.62f, 4 * dp, fill)
        text.textAlign = Paint.Align.LEFT
        text.typeface = MEDIUM
        text.letterSpacing = 0.02f
        text.textSize = titleSize
        text.color = Color.WHITE
        canvas.drawText(title, x + 16 * dp, y + titleSize * 0.95f, text)
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.08f
        y += titleSize + space

        for (layout in layouts) {
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + space
        }
    }

    private fun drawPill(canvas: Canvas) {
        val (dot, label) = when {
            link.full -> WAIT to "${link.pcName ?: "PC"} is full"
            link.waiting -> WAIT to "${link.pcName ?: "PC"}  ·  waiting"
            link.blocked -> BRAKE to "${link.pcName ?: "PC"}  ·  not allowed"
            link.connected -> GAS to ((link.pcName ?: "PC") + if (link.player > 0) "  ·  P${link.player}" else "")
            else -> WAIT to "searching"
        }
        text.typeface = MEDIUM
        text.textSize = 11 * dp
        val w = text.measureText(label) + 34 * dp
        val h = 26 * dp
        val rect = RectF(dialX - w / 2, pillY - h / 2, dialX + w / 2, pillY + h / 2)
        fill.color = SURFACE
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        stroke.color = EDGE
        canvas.drawRoundRect(rect, h / 2, h / 2, stroke)
        fill.color = dot
        canvas.drawCircle(rect.left + 14 * dp, pillY, 3.5f * dp, fill)
        text.color = LABEL
        canvas.drawText(label, dialX + 7 * dp, pillY + 4 * dp, text)
    }

    private fun drawPaused(canvas: Canvas) {
        fill.color = Color.argb(170, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        text.typeface = MEDIUM
        text.color = Color.WHITE
        text.textSize = 22 * dp
        canvas.drawText("Paused", width / 2f, height / 2f, text)
        text.color = LABEL
        text.textSize = 12 * dp
        canvas.drawText(when {
            flex -> "hold it by the bottom half to ${if (drone) "fly" else "drive"}"
            drone -> "lift the phone to fly"
            else -> "lift the phone to drive"
        }, width / 2f, height / 2f + 24 * dp, text)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).roundToInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** How long "neutral set" shows after a tap. */
        private const val CENTRED_MS = 1200L
        /** The Xbox mark's height, and its gap from the label on a short button, in dp. */
        private const val BADGE_SIZE = 20f
        private const val BADGE_GAP = 7f
        /** How long the phone has to stay flat (or picked up) before the menu controls follow. */
        private const val SETTLE_MS = 400L
        /** Shorter than this on the middle is a tap, longer a swipe, in dp. */
        private const val SWIPE_DP = 28f
    }
}
