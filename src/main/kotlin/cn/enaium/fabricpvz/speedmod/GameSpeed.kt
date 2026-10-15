package cn.enaium.fabricpvz.speedmod

import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The game-speed state of the mod: the multiplier the player picks, whether the game is paused by
 * the mod, and the fractional-update credit that turns a fractional multiplier into whole game
 * updates.
 *
 * One process-wide instance ([get]) - a speed setting is a player preference, not a level property,
 * so it survives a level change. The class has no dependency on the game: the mixin and the controls
 * only read and write the numbers here, which is what makes the behaviour testable without a window.
 *
 * ## How a fractional multiplier reaches the game
 *
 * The framework calls `LawnApp.updateFrames()` once per displayed frame, and one call runs exactly
 * one game tick with the vanilla speed. A multiplier is therefore a *number of ticks per frame*:
 * `0.5` has to run a tick every other frame, `3` has to run three ticks per frame, and `1.5` has to
 * alternate between one and two. [beginFrame] keeps that bookkeeping (a credit that accumulates the
 * multiplier and is spent in whole ticks), so the API is one call per frame that returns the ticks
 * that frame must run.
 *
 * [START] and [END] are the inclusive bounds of the range, [STEP] the step the slow/fast buttons move
 * by. Every value stays inside the range: the multiplication of the default `0.5` step cannot drift
 * outside it.
 *
 * The class has one companion instance ([get]) and every one of its members is reached through it -
 * Kotlin as `GameSpeed.get()`, the Java side (the two Mixin classes and the end-to-end harness) as
 * `GameSpeed.Companion.get()`. Nothing is exposed with `@JvmStatic`/`@JvmField`, so there is exactly
 * one way into the class from Java, the same one Kotlin uses.
 */
class GameSpeed private constructor() {

    private var multiplierValue: Float = DEFAULT

    private var pauseFlag: Boolean = false

    /** The accumulated multiplier, spent in whole ticks by [beginFrame]. */
    private var credit: Double = 0.0

    private var replayFlag: Boolean = false

    /** The multiplier the player selected; always within `[START, END]`. */
    fun multiplier(): Float = multiplierValue

    /** Whether the mod's pause button is active (no game ticks at all). */
    fun paused(): Boolean = pauseFlag

    /**
     * Whether the game is running untouched: no pause and the vanilla multiplier. The update-rate
     * mixin leaves `LawnApp.updateFrames()` completely alone in that state.
     */
    fun isVanilla(): Boolean = !pauseFlag && multiplierValue == DEFAULT

    /** The multiplier shown on the readout: `1.5x`, `0.5x`, `3.0x`. */
    fun display(): String = String.format(Locale.ROOT, "%.1fx", multiplierValue)

    /** One step slower, never below [START]. */
    fun slow() {
        setMultiplier(multiplierValue - STEP)
    }

    /** One step faster, never above [END]. */
    fun fast() {
        setMultiplier(multiplierValue + STEP)
    }

    /** Pauses the game if it is running and resumes it if the mod paused it. */
    fun togglePause() {
        pauseFlag = !pauseFlag
        credit = 0.0
    }

    /** Sets the multiplier, clamped to `[START, END]`. */
    fun setMultiplier(theMultiplier: Float) {
        multiplierValue = min(END, max(START, theMultiplier))
        credit = 0.0
    }

    /**
     * Ticks the game must run for this frame: the accumulated multiplier, spent in whole ticks. A
     * fractional part is carried into the next frame, so `1.5` alternates 1, 2, 1, 2 - an average of
     * 1.5 ticks per frame - instead of rounding every frame to the same value.
     *
     * @return the number of game ticks to run, `0` while paused
     */
    fun beginFrame(): Int {
        if (pauseFlag) {
            credit = 0.0
            return 0
        }

        credit += multiplierValue.toDouble()
        val aTicks = floor(credit).toInt()
        credit -= aTicks
        return aTicks
    }

    /**
     * Whether the update-rate mixin is currently re-running `LawnApp.updateFrames()` itself. Replayed
     * calls must run the game's own body without being counted again, which would recurse.
     */
    fun isReplaying(): Boolean = replayFlag

    /** Marks (or clears) the replay window of the update-rate mixin. */
    fun setReplaying(theReplaying: Boolean) {
        replayFlag = theReplaying
    }

    /** Drops the carried credit; a level change starts from a whole tick. */
    fun resetCredit() {
        credit = 0.0
    }

    /** Back to the vanilla state (no pause, 1x); used by tests and by a reload of the mods. */
    fun reset() {
        multiplierValue = DEFAULT
        pauseFlag = false
        credit = 0.0
        replayFlag = false
    }

    companion object {

        /** Lowest multiplier the controls can select. */
        val START: Float = 0.5f

        /** Highest multiplier the controls can select. */
        val END: Float = 3.0f

        /** What one click of the slow/fast button moves the multiplier by. */
        val STEP: Float = 0.5f

        /** The multiplier the game runs at before anything was clicked. */
        val DEFAULT: Float = 1.0f

        private val INSTANCE = GameSpeed()

        /**
         * The one process-wide instance; a speed setting is a player preference.
         *
         * <p>Java reaches every member of this companion through the companion instance
         * ({@code GameSpeed.Companion.get()}, and {@code GameSpeed.Companion.getSTART()} for the
         * constants): the mod's Java side - the two Mixin classes and the end-to-end harness - names
         * the companion explicitly instead of the mod exposing Kotlin members as statics with
         * {@code @JvmStatic}/{@code @JvmField}.
         */
        fun get(): GameSpeed = INSTANCE
    }
}
