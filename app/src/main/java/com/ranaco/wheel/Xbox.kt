package com.ranaco.wheel

/**
 * The Xbox controller's buttons, in the order the PC numbers them in its buttons message
 * (XBOX_NAMES in the companion, counting from 1). The drive screen marks each of its
 * buttons with the one it presses, so a game's "press A" can be found at a glance.
 * The letters and bumpers are drawn as their names; the rest as their marks.
 */
enum class Xbox {
    A, B, X, Y, LB, RB, LS, RS, MENU, VIEW, GUIDE, UP, DOWN, LEFT, RIGHT;

    /** Bumpers and sticks are two letters, so their mark is a pill rather than a ring. */
    val wide get() = this == LB || this == RB || this == LS || this == RS

    companion object {
        /** null for 0 (the button presses nothing) or a code from a newer PC. */
        fun of(code: Int): Xbox? = entries.getOrNull(code - 1)
    }
}
