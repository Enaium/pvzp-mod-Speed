package cn.enaium.fabricpvz.speedmod

import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.widget.GameButton
import cn.enaium.fabricpvz.portable.sexy.Color
import cn.enaium.fabricpvz.portable.sexy.Rect
import cn.enaium.fabricpvz.portable.sexy.graphics.Font
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import kotlin.math.floor
import kotlin.math.max

/**
 * The speed readout and the three speed buttons, drawn with the game's own [GameButton] stone
 * rendering so they look like the menu button.
 *
 * The controls sit in two rows below the board's menu button (and below the store button when one
 * is drawn), right-aligned to the menu button's drawn right edge and drawn at 0.75 scale: row one
 * is `slow fast` (`<` `>`), row two is `pause readout` (`=` `1.0x`).
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
 * ## Drawing at 0.75 scale
 *
 * The game's stone art is drawn by [GameButton.drawStoneButton] at its own size (71x46, 117x46, ...),
 * ignoring the requested height. The panels are therefore drawn with the art's unscaled size under a
 * 0.75 graphics scale whose origin is the panel's own top-left corner, which turns the 71x46 button
 * into 53x34 and the 117x46 readout into 87x34. A scaled [Graphics] only scales image drawing, not
 * strings, so the labels are drawn by this class inside the scaled rectangles with the same
 * green-inset font the stone button uses.
 *
 * The class has no Java statics: Java reaches it as `SpeedControls.Companion` only where the
 * companion exposes something, and the board, the mixins and the tests otherwise go through the
 * instance returned by `SpeedMod.controls()`.
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
            aButton.mLabel = labelOf(aButton)
        }

        layout()
    }

    /** The board these controls belong to. */
    fun board(): Board = mBoard

    /** True when the controls may be drawn: while the game's own top-right button is on screen. */
    fun isVisible(): Boolean = !mBoard.mMenuButton.mBtnNoDraw

    /**
     * Places the controls in two rows below the board's menu button (and below the store button
     * when one is drawn), right-aligned to the menu button's drawn right edge:
     *
     * - row one: the slow and fast buttons (`<` `>`);
     * - row two: the pause button and the multiplier readout (`=` `1.0x`).
     *
     * All four panels have the scaled art size: 53x34 for a button, 87x34 for the readout.
     */
    private fun layout() {
        val aHeight = scaledSize(panelHeight())
        val aButtonWidth = scaledSize(panelWidth(BUTTON_MIDDLE_SLICES))
        val aReadoutWidth = scaledSize(panelWidth(READOUT_MIDDLE_SLICES))

        val aMenu = mBoard.mMenuButton
        // The 9-slice can be narrower than the rectangle the game asked for (a 117-wide button
        // draws 117, but a 120-wide one also draws 117): align to what is drawn, not to the request.
        val aRight = aMenu.mX + drawnWidth(aMenu.mWidth)
        var aTop = max(aMenu.mY + aMenu.mHeight, aMenu.mY + aHeight) + ROW_GAP
        val aStore = mBoard.mStoreButton
        if (aStore != null && !aStore.mBtnNoDraw) {
            aTop = max(aTop, aStore.mY + max(aStore.mHeight, aHeight) + ROW_GAP)
        }

        // Row one, left to right: slow, fast.
        var anX = aRight - aButtonWidth
        mFastButton.resize(anX, aTop, aButtonWidth, aHeight)
        anX -= GAP + aButtonWidth
        mSlowButton.resize(anX, aTop, aButtonWidth, aHeight)

        // Row two, left to right: pause, readout.
        val aSecondRowTop = aTop + aHeight + ROW_GAP
        mReadoutX = aRight - aReadoutWidth
        mReadoutY = aSecondRowTop
        mReadoutWidth = aReadoutWidth
        mReadoutHeight = aHeight
        mPauseButton.resize(mReadoutX - GAP - aButtonWidth, aSecondRowTop, aButtonWidth, aHeight)
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

    /** The labels the three buttons currently show, in click order (slow, pause, fast). */
    fun labels(): Array<String> = arrayOf(SLOW_LABEL, pauseLabel(), FAST_LABEL)

    /** Draws the readout and the three buttons; called from the board's draw pass. */
    fun draw(theGraphics: Graphics) {
        layout()
        mSlowButton.mLabel = SLOW_LABEL
        mPauseButton.mLabel = pauseLabel()
        mFastButton.mLabel = FAST_LABEL

        drawPanel(
            theGraphics, mSlowButton.mX, mSlowButton.mY, mSlowButton.mWidth, mSlowButton.mHeight,
            panelWidth(BUTTON_MIDDLE_SLICES), panelHeight(),
            mSlowButton.isButtonDown, mSlowButton.isMouseOver, mSlowButton.mLabel,
        )
        drawPanel(
            theGraphics, mPauseButton.mX, mPauseButton.mY, mPauseButton.mWidth, mPauseButton.mHeight,
            panelWidth(BUTTON_MIDDLE_SLICES), panelHeight(),
            mPauseButton.isButtonDown, mPauseButton.isMouseOver, mPauseButton.mLabel,
        )
        drawPanel(
            theGraphics, mFastButton.mX, mFastButton.mY, mFastButton.mWidth, mFastButton.mHeight,
            panelWidth(BUTTON_MIDDLE_SLICES), panelHeight(),
            mFastButton.isButtonDown, mFastButton.isMouseOver, mFastButton.mLabel,
        )
        drawPanel(
            theGraphics, mReadoutX, mReadoutY, mReadoutWidth, mReadoutHeight,
            panelWidth(READOUT_MIDDLE_SLICES), panelHeight(),
            false, false, GameSpeed.get().display(),
        )
    }

    /**
     * Draws one stone panel at [SCALE] and its label at full size.
     *
     * The art is drawn at its own unscaled size under a scale whose origin is the panel's top-left
     * corner, so the panel's drawn position is its rectangle; the string is not affected by the
     * graphics scale and is centred in the scaled rectangle here.
     */
    private fun drawPanel(
        theGraphics: Graphics,
        theX: Int,
        theY: Int,
        theWidth: Int,
        theHeight: Int,
        theArtWidth: Int,
        theArtHeight: Int,
        theDown: Boolean,
        theHighlighted: Boolean,
        theLabel: String,
    ) {
        theGraphics.setScale(SCALE, SCALE, theX.toFloat(), theY.toFloat())
        GameButton.drawStoneButton(
            theGraphics, theX, theY, theArtWidth, theArtHeight, theDown, theHighlighted, "",
        )
        theGraphics.setScale(1f, 1f, 0f, 0f)

        val aFont = labelFont(theHighlighted)
        theGraphics.setFont(aFont)
        theGraphics.setColor(Color.White)
        val aLabelX = theX + (theWidth - aFont.stringWidth(theLabel)) / 2
        val aLabelY = theY + (theHeight - aFont.ascent) / 2 + aFont.ascent
        theGraphics.drawString(theLabel, aLabelX, aLabelY)
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

    /** The label a button shows right now: the pause button flips to the resume arrow while paused. */
    private fun labelOf(theButton: GameButton): String = when (theButton.mId) {
        0 -> SLOW_LABEL
        1 -> pauseLabel()
        else -> FAST_LABEL
    }

    private fun pauseLabel(): String = if (GameSpeed.get().paused()) RESUME_LABEL else PAUSE_LABEL

    private fun labelFont(theHighlighted: Boolean): Font = Resources.getFontById(
        if (theHighlighted) {
            Resources.FONT_DWARVENTODCRAFT18BRIGHTGREENINSET_ID
        } else {
            Resources.FONT_DWARVENTODCRAFT18GREENINSET_ID
        },
    )

    companion object {

        /**
         * Middle slices of the stone 9-slice per panel. The retail art is left 36x46, middle 46x46,
         * right 35x46 (`GameButton.drawStoneButton`, GameButton.java:41-79), so a panel is 71 wide
         * with no middle slice and 117 wide with one. The drawn height is always the art's 46.
         */
        private const val BUTTON_MIDDLE_SLICES = 0

        private const val READOUT_MIDDLE_SLICES = 1

        /** The scale the whole row is drawn at (0.75 of the stone art). */
        private const val SCALE = 0.75f

        /** Space between two panels, in screen pixels (the scaled geometry). */
        private const val GAP = 6

        /** Space between the menu/store button and the row in the fallback placement. */
        private const val ROW_GAP = 4

        private const val SLOW_LABEL = "<"

        private const val PAUSE_LABEL = "="

        private const val RESUME_LABEL = ">"

        private const val FAST_LABEL = ">"

        /** The height every stone panel is drawn at before scaling: the 9-slice's own art height. */
        private fun panelHeight(): Int = Resources.getImageById(ImageID.IMAGE_BUTTON_MIDDLE).height

        /**
         * The width the game's 9-slice really draws for a panel requested to be `theWidth` wide:
         * `left + ((theWidth - left - right) / middle) * middle + right`. The requested width is
         * rounded down to whole middle slices, so a requested width is only a drawn width when it is
         * exactly `left + n*middle + right` - [panelWidth] is.
         */
        private fun drawnWidth(theWidth: Int): Int {
            val aRepeat = (theWidth - leftSliceWidth() - rightSliceWidth()) / middleSliceWidth()
            return leftSliceWidth() + max(0, aRepeat) * middleSliceWidth() + rightSliceWidth()
        }

        /** A width the 9-slice draws in full: `left + middleSlices*middle + right`. */
        private fun panelWidth(theMiddleSlices: Int): Int =
            leftSliceWidth() + max(0, theMiddleSlices) * middleSliceWidth() + rightSliceWidth()

        private fun leftSliceWidth(): Int = Resources.getImageById(ImageID.IMAGE_BUTTON_LEFT).width

        private fun middleSliceWidth(): Int = Resources.getImageById(ImageID.IMAGE_BUTTON_MIDDLE).width

        private fun rightSliceWidth(): Int = Resources.getImageById(ImageID.IMAGE_BUTTON_RIGHT).width

        /** A panel dimension at the row's scale: the drawn art size rounded down. */
        private fun scaledSize(theSize: Int): Int = floor(theSize * SCALE).toInt()
    }
}
