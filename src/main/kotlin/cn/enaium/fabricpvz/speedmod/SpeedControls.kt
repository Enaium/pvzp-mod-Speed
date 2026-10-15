package cn.enaium.fabricpvz.speedmod

import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.widget.GameButton
import cn.enaium.fabricpvz.portable.sexy.Rect
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import cn.enaium.fabricpvz.portable.sexy.graphics.Image
import kotlin.math.max

/**
 * The three in-game speed buttons and the readout below them, drawn with the game's own [GameButton]
 * stone rendering so they look like the menu button they sit under.
 *
 * Geometry is derived from the board's own menu button on every use ([layout]), so the controls
 * follow the menu button in every game mode (the zen garden and the upsell move it and add a store
 * button above them) and during the zen fade that slides it off screen. The row is aligned to the
 * menu button's right edge and sits directly below the lowest of the menu/store buttons.
 *
 * ## Input
 *
 * The buttons are hand-drawn inside the board (like `Board.mMenuButton`), not widgets of the
 * framework's widget tree, so a click is routed by the board itself: the mod's mixin into
 * `Board.mouseDown`/`Board.mouseUp` asks this class first and cancels the board's own handler when
 * the click landed on a button, which is what keeps clicks from reaching the lawn.
 * [GameButton.isMouseOver] already answers "no" while a dialog is open, so the controls behave like
 * the game's own in-board buttons with respect to dialogs.
 *
 * The class's companion is reached through its instance - Kotlin as `SpeedControls.panelWidth(2)`, the
 * Java side as `SpeedControls.Companion.panelWidth(2)` - so no member of this class is a Java static.
 */
class SpeedControls internal constructor(private val mBoard: Board) {

    private val mSlowButton = GameButton(0)

    private val mPauseButton = GameButton(1)

    private val mFastButton = GameButton(2)

    /** The three buttons in click order (slow, pause, fast); iterated on every hit test. */
    private val mButtons = arrayOf(mSlowButton, mPauseButton, mFastButton)

    private var mReadoutX: Int = 0

    private var mReadoutY: Int = 0

    private var mReadoutWidth: Int = 0

    private var mReadoutHeight: Int = 0

    /** The button the current press started on, or null when no press of ours is in flight. */
    private var mPressed: GameButton? = null

    init {
        for (aButton in mButtons) {
            // The game's own stone look (Board.mMenuButton does the same) and no framework widget:
            // the board draws and routes this button by hand.
            aButton.mApp = mBoard.mApp
            aButton.mParentWidget = mBoard
            aButton.mDrawStoneButton = true
            aButton.mLabel = when (aButton.mId) {
                0 -> SLOW_LABEL
                1 -> PAUSE_LABEL
                else -> FAST_LABEL
            }
        }

        layout()
    }

    /** The board these controls belong to. */
    fun board(): Board = mBoard

    /** True when the controls may be drawn: while the game's own top-right button is on screen. */
    fun isVisible(): Boolean = !mBoard.mMenuButton.mBtnNoDraw

    /**
     * Places the three buttons in one row under the board's current menu button (and under the store
     * button of the modes that draw one), with the multiplier readout centred underneath them.
     * Everything is derived from the game's own stone art ([panelWidth], [panelHeight]) and from the
     * menu button's drawn right edge, so no panel can overlap another and no panel's art can reach
     * into the button above it.
     *
     * The row is right-aligned to the menu button and reads left to right as `slow, pause, fast`; the
     * readout sits one gap below it, centred on the row.
     */
    private fun layout() {
        val aMenu = mBoard.mMenuButton
        // The 9-slice can be narrower than the rectangle the game asked for (a 117-wide button draws
        // 117, but a 120-wide one also draws 117): align to what is drawn, not to the request.
        val aRight = aMenu.mX + drawnWidth(aMenu.mWidth)
        val aHeight = panelHeight()
        var aTop = max(aMenu.mY + aMenu.mHeight, aMenu.mY + aHeight) + ROW_GAP
        val aStore = mBoard.mStoreButton
        if (aStore != null && !aStore.mBtnNoDraw) {
            aTop = max(aTop, aStore.mY + max(aStore.mHeight, aHeight) + ROW_GAP)
        }

        val aButtonWidth = panelWidth(BUTTON_MIDDLE_SLICES)
        val aReadoutWidth = panelWidth(READOUT_MIDDLE_SLICES)
        var aX = aRight - aButtonWidth
        mFastButton.resize(aX, aTop, aButtonWidth, aHeight)
        aX -= GAP + aButtonWidth
        mPauseButton.resize(aX, aTop, aButtonWidth, aHeight)
        aX -= GAP + aButtonWidth
        mSlowButton.resize(aX, aTop, aButtonWidth, aHeight)
        // The readout sits under the button row, centred on it: the buttons stay right-aligned to the
        // menu button (so the row is clear of the stone art above) and the value reads directly below
        // them, one ROW_GAP below their drawn bottom edge.
        val aRowLeft = aX
        val aRowWidth = aRight - aRowLeft
        mReadoutX = aRowLeft + (aRowWidth - aReadoutWidth) / 2
        mReadoutY = aTop + aHeight + ROW_GAP
        mReadoutWidth = aReadoutWidth
        mReadoutHeight = aHeight
    }

    /** The four on-screen rectangles in board coordinates: the three buttons and the readout. */
    fun rects(): Array<Rect> {
        layout()
        return arrayOf(
            Rect(mSlowButton.mX, mSlowButton.mY, mSlowButton.mWidth, mSlowButton.mHeight),
            Rect(mPauseButton.mX, mPauseButton.mY, mPauseButton.mWidth, mPauseButton.mHeight),
            Rect(mFastButton.mX, mFastButton.mY, mFastButton.mWidth, mFastButton.mHeight),
            Rect(mReadoutX, mReadoutY, mReadoutWidth, mReadoutHeight),
        )
    }

    /** Draws the three buttons and the multiplier readout; called from the board's draw pass. */
    fun draw(theGraphics: Graphics) {
        layout()
        mPauseButton.mLabel = if (GameSpeed.get().paused()) RESUME_LABEL else PAUSE_LABEL
        mSlowButton.draw(theGraphics)
        mPauseButton.draw(theGraphics)
        mFastButton.draw(theGraphics)
        // The game's own stone label rendering, so the readout matches the buttons around it; the
        // panel is exactly the size the 9-slice draws, which is what centres the label inside it.
        GameButton.drawStoneButton(
            theGraphics, mReadoutX, mReadoutY, mReadoutWidth, mReadoutHeight,
            false, false, GameSpeed.get().display(),
        )
    }

    /** The button whose rectangle contains the point, or null. */
    private fun buttonAt(theX: Int, theY: Int): GameButton? {
        layout()
        for (aButton in mButtons) {
            if (Rect(aButton.mX, aButton.mY, aButton.mWidth, aButton.mHeight).contains(theX, theY)) {
                return aButton
            }
        }
        return null
    }

    /**
     * Handles a press in board coordinates.
     *
     * @return whether the click belongs to the controls and the board must not see it
     */
    fun mouseDown(theX: Int, theY: Int, theClickCount: Int): Boolean {
        if (!isVisible() || mBoard.mIgnoreMouseUp) {
            // mIgnoreMouseUp means the board is discarding the click that is already in flight.
            return false
        }

        val aButton = buttonAt(theX, theY) ?: return false
        mPressed = aButton
        aButton.mIsDown = true
        aButton.mIsOver = true
        return true
    }

    /**
     * Handles the release of a press this class accepted. The action fires when the release lands on
     * the button the press started on, like the game's own buttons; a release anywhere else cancels.
     *
     * @return whether the release belongs to the controls
     */
    fun mouseUp(theX: Int, theY: Int, theClickCount: Int): Boolean {
        val aButton = mPressed ?: return false

        mPressed = null
        aButton.mIsDown = false
        aButton.mIsOver = false
        if (aButton === buttonAt(theX, theY) && theClickCount > 0) {
            press(aButton)
        }
        return true
    }

    /** A press of one of the controls was completed: apply it to the game speed. */
    private fun press(theButton: GameButton) {
        when (theButton.mId) {
            0 -> GameSpeed.get().slow()
            1 -> GameSpeed.get().togglePause()
            else -> GameSpeed.get().fast()
        }
    }

    /** Drops an in-flight press (the board was replaced while the mouse was down). */
    fun cancelPress() {
        val aPressed = mPressed ?: return
        aPressed.mIsDown = false
        aPressed.mIsOver = false
        mPressed = null
    }

    companion object {

        /**
         * Middle slices of the stone 9-slice per panel. The retail art is left 36x46, middle 46x46,
         * right 35x46 (`GameButton.drawStoneButton`, GameButton.java:41-79), and the game draws
         * `left + n*middle + right` pixels wide - so a panel is either 71 wide (no middle slice) or
         * 117, 163, 209 ... No other width exists, and the drawn height is always the art's 46. The
         * panel's rectangle and the drawn art are therefore the same rectangle, which is what keeps
         * the panels from overlapping each other and the labels centred inside what is drawn.
         */
        private const val BUTTON_MIDDLE_SLICES = 0

        private const val READOUT_MIDDLE_SLICES = 2

        /** Space between two panels. */
        private const val GAP = 4

        /** Space between the menu/store button and the row of controls. */
        private const val ROW_GAP = 4

        private const val SLOW_LABEL = "<<"

        // The stone label font is a retail bitmap font with a limited glyph set: "|" is not in it (the
        // label then measures zero wide and nothing is drawn), capital "I" is.
        private const val PAUSE_LABEL = "="

        private const val RESUME_LABEL = ">"

        private const val FAST_LABEL = ">>"

        /** The stone art the game draws its own buttons with; all three slices are 46 pixels tall. */
        private fun slice(theImageId: Int): Image = Resources.getImageById(theImageId)

        private fun leftSliceWidth(): Int = slice(ImageID.IMAGE_BUTTON_LEFT).width

        private fun middleSliceWidth(): Int = slice(ImageID.IMAGE_BUTTON_MIDDLE).width

        private fun rightSliceWidth(): Int = slice(ImageID.IMAGE_BUTTON_RIGHT).width

        /**
         * The height every stone panel is drawn at: the 9-slice draws its images at their own size and
         * ignores the requested height (`GameButton.drawStoneButton` only uses it to place the label),
         * so a panel is as tall as the art.
         */
        fun panelHeight(): Int = slice(ImageID.IMAGE_BUTTON_MIDDLE).height

        /**
         * The width the game's 9-slice really draws for a panel requested to be `theWidth` wide:
         * `left + ((theWidth - left - right) / middle) * middle + right`. The requested width is
         * rounded down to whole middle slices, so a requested width is only a drawn width when it is
         * exactly `left + n*middle + right` - [panelWidth] is.
         */
        fun drawnWidth(theWidth: Int): Int {
            val aRepeat = (theWidth - leftSliceWidth() - rightSliceWidth()) / middleSliceWidth()
            return leftSliceWidth() + max(0, aRepeat) * middleSliceWidth() + rightSliceWidth()
        }

        /** A width the 9-slice draws in full: `left + middleSlices*middle + right`. */
        fun panelWidth(theMiddleSlices: Int): Int =
            leftSliceWidth() + max(0, theMiddleSlices) * middleSliceWidth() + rightSliceWidth()
    }
}
