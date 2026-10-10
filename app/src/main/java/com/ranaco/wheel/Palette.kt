package com.ranaco.wheel

import android.graphics.Color
import android.graphics.Typeface

/** The app's colours and type: black glass, quiet greys, one colour per control. */
object Palette {
    val SURFACE = Color.rgb(14, 14, 16)
    val TRACK = Color.rgb(24, 24, 27)
    val EDGE = Color.rgb(36, 36, 40)
    val PRESSED = Color.rgb(237, 237, 237)
    val LABEL = Color.rgb(180, 180, 186)
    val DIM = Color.rgb(108, 108, 115)
    val GAS = Color.rgb(52, 211, 153)
    val BRAKE = Color.rgb(248, 113, 113)
    val STEER = Color.rgb(96, 165, 250)
    val WAIT = Color.rgb(250, 204, 21)

    // Settings' controls.
    /** The lighter stretch of a track: a deadzone, or a switch that's off. */
    val SHADE = Color.rgb(44, 44, 49)
    /** A slider's empty track, a field's underline. */
    val RULE = Color.rgb(70, 70, 76)
    val OUTLINE = Color.rgb(60, 60, 66)
    val HINT = Color.rgb(80, 80, 86)
    val THUMB_OFF = Color.rgb(120, 120, 127)
    val TRACK_ON = Color.rgb(150, 150, 156)

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
}
