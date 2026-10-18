package cn.enaium.fabricpvz.speedmod.test

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.GameMode
import cn.enaium.fabricpvz.portable.lawn.GameScenes
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.LawnApp
import cn.enaium.fabricpvz.portable.lawn.Main
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.widget.GameButton
import cn.enaium.fabricpvz.portable.sexy.Color
import cn.enaium.fabricpvz.portable.sexy.Rect
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage
import cn.enaium.fabricpvz.speedmod.GameSpeed
import cn.enaium.fabricpvz.speedmod.SpeedControls
import cn.enaium.fabricpvz.speedmod.SpeedMod

/**
 * Visual check of the mod: boots the game through the real Fabric Loader with the real video driver,
 * starts a real level, drives the framework's own frame loop and writes what the window shows into
 * {@code run/build/window-dump/} (run through {@code ./gradlew dumpWindow}).
 *
 * <p>Two things matter for a dependable dump, both learned from the game's own {@code dumpWindow}
 * tool: the frame is read back <em>before</em> {@code present} (after a present the back buffer is
 * undefined), and the read-back happens on the frame-loop thread (SDL's renderer is not thread safe).
 *
 * <p>The three dumps are the controls at 1x, after a click on the slow button, and after a click on
 * the pause button - i.e. the `1.0x < = >` row (beside the progress meter, or below the menu button
 * on a board without one) and the two states the buttons can be in.
 */
class SpeedModWindowShot private constructor() {

    companion object {

        private const val FRAMES_PER_SHOT = 12

        /** Pixels of a panel skipped around its border when looking for its label (rounded rock corners). */
        private const val LABEL_INSET = 8

        @JvmStatic
        fun main(args: Array<String>) {
            val runDir = Path.of(System.getProperty("pvz.gameDir", ".")).toAbsolutePath()
            val dumpDir = runDir.resolve("build").resolve("window-dump")
            val saveDir = dumpDir.resolve("userdata")
            Files.createDirectories(saveDir)

            Main.installHooks()
            val app = LawnApp()
            LawnApp.gLawnApp = app
            try {
                app.setArgs(arrayOf("pvz-portable", "-savedir", saveDir.toString()))
                app.init()
                app.startLoadingThread()
                waitForLoading(app)

                if (app.mPlayerInfo == null) {
                    app.mPlayerInfo = app.mProfileMgr.addProfile("SpeedModShot")
                }
                app.mPlayerInfo.mFinishedAdventure = 1
                app.mGameMode = GameMode.GAMEMODE_ADVENTURE
                // The game's own "skip the menu" path: it removes the title screen (which would
                // otherwise sit above the board and swallow every click) and starts a new level.
                app.fastLoad(GameMode.GAMEMODE_ADVENTURE)
                val board = app.mBoard
                if (board == null) {
                    throw IllegalStateException("preNewGame created no board")
                }
                app.startPlaying()
                // What the level-intro cutscene does when it hands the board over (CutScene.java:1208) and
                // where its pan leaves the board (its own `mBoard.move(220, 0)` at CutScene.java:746 is
                // undone by `updateIntro`'s pan curve, which settles the board on the origin). Starting the
                // board directly skips that cutscene, so both are done by hand here.
                board.mMenuButton.mBtnNoDraw = false
                board.move(0, 0)
                // And what the intro does before that, in the seed chooser: it hands the bank its seeds.
                // Skipping it leaves a board no player can be in - every packet at SEED_NONE - which
                // collecting a sun reads (HeadlessLawn.chooseSeeds).
                HeadlessLawn.chooseSeeds(board)

                // The mod creates its controls on the board's first draw pass.
                var controls = SpeedMod.controls()
                for (frame in 0 until FRAMES_PER_SHOT) {
                    pump(app)
                }
                controls = SpeedMod.controls()
                if (controls == null) {
                    throw IllegalStateException("the mod drew no controls: the post-draw hook did not run")
                }
                val aControls = controls

                reportStoneArt(app)

                val rects = aControls.rects()
                System.out.println("[shot] scene=" + sceneName(app.mGameScene) +
                        " menuButton=" + rect(board.mMenuButton.mX, board.mMenuButton.mY,
                                board.mMenuButton.mWidth, board.mMenuButton.mHeight) +
                        " buttons=" + rect(rects[0]) + " " + rect(rects[1]) + " " + rect(rects[2]) +
                        " readout=" + rect(rects[3]) +
                        " speed=" + GameSpeed.get().display() +
                        " controlsForBoard=" + (aControls.board() === board) +
                        " board=" + board.mX + "," + board.mY + " " + board.mWidth + "x" + board.mHeight +
                        " app=" + app.mWidth + "x" + app.mHeight +
                        " drawable=" + app.mRendererBackend.renderOutputWidth() + "x" +
                        app.mRendererBackend.renderOutputHeight())

                // Hover the middle of the fast button, so the dump shows the hover state too.
                app.mWidgetManager.mouseMove(centre(rects[2])[0], centre(rects[2])[1])
                for (frame in 0 until FRAMES_PER_SHOT) {
                    pump(app)
                }
                measureDrawnGeometry(app, dumpDir)

                shot(app, dumpDir.resolve("speed-controls-1x.png"), "at1x")

                // One click on the slow button: the readout must read 0.5x and the buttons must look
                // pressed-then-released. The click goes through the widget manager, the way a hardware
                // click reaches the board.
                click(app, centre(rects[0]))
                app.mWidgetManager.mouseMove(centre(rects[2])[0], centre(rects[2])[1])
                for (frame in 0 until FRAMES_PER_SHOT) {
                    pump(app)
                }
                shot(app, dumpDir.resolve("speed-controls-05x.png"), "at0_5x")
                System.out.println("[shot] after the slow click speed=" + GameSpeed.get().display())

                // The pause button: paused, and the pause button's label switches to the resume arrow.
                click(app, centre(rects[1]))
                for (frame in 0 until FRAMES_PER_SHOT) {
                    pump(app)
                }
                shot(app, dumpDir.resolve("speed-controls-paused.png"), "paused")
                System.out.println("[shot] after the pause click speed=" + GameSpeed.get().display() +
                        " paused=" + GameSpeed.get().paused())
            } finally {
                app.shutdown()
                LawnApp.gLawnApp = null
            }

            System.out.println("[shot] dumps written to " + dumpDir)
        }

        /** The stone-button art the game draws with, and the geometry a 9-slice of it produces. */
        private fun reportStoneArt(theApp: LawnApp) {
            for (anId in intArrayOf(
                ImageID.IMAGE_BUTTON_LEFT,
                ImageID.IMAGE_BUTTON_MIDDLE,
                ImageID.IMAGE_BUTTON_RIGHT,
                // The pressed slices, which drawStoneButton swaps in (with a 1 px nudge): they have to
                // keep the panels apart while a button is held down too.
                ImageID.IMAGE_BUTTON_DOWN_LEFT,
                ImageID.IMAGE_BUTTON_DOWN_MIDDLE,
                ImageID.IMAGE_BUTTON_DOWN_RIGHT
            )) {
                val anImage = Resources.getImageById(anId)
                System.out.println("[shot] stone art " + Resources.getName(anId) +
                        " size=" + (if (anImage == null) "missing" else anImage.width.toString() + "x" + anImage.height))
            }
        }

        /** One framework step: events, update and a real draw (through the real renderer). */
        private fun pump(theApp: LawnApp) {
            theApp.processDeferredMessages(false)
            theApp.doUpdateFrames()
            theApp.mWidgetManager.drawScreen()
            try {
                Thread.sleep(4L)
            } catch (anException: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun waitForLoading(theApp: LawnApp) {
            val deadline = System.nanoTime() + 180_000_000_000L
            // Draw as well as update: the title screen's state machine only advances once it has drawn.
            while (!theApp.mLoadingThreadCompleted.get() && System.nanoTime() < deadline) {
                pump(theApp)
                theApp.mRendererBackend.present()
            }

            if (!theApp.mLoadingThreadCompleted.get()) {
                throw IllegalStateException("resource loading did not complete")
            }
        }

        /** A press and release through the framework's widget manager, like a hardware click. */
        private fun click(theApp: LawnApp, thePoint: IntArray) {
            theApp.mWidgetManager.mouseMove(thePoint[0], thePoint[1])
            theApp.mWidgetManager.mouseDown(thePoint[0], thePoint[1], 1)
            pump(theApp)
            theApp.mWidgetManager.mouseUp(thePoint[0], thePoint[1], 1)
        }

        private fun centre(theRect: Rect): IntArray {
            return intArrayOf(theRect.mX + theRect.mWidth / 2, theRect.mY + theRect.mHeight / 2)
        }

        /**
         * Writes one frame: the whole widget tree is marked dirty and drawn first, so the read-back is the
         * frame the mod's controls are in right now. Without that, the game only redraws the regions its
         * widgets marked dirty, and a read-back right after a present lands on the other buffer, whose
         * content is a frame or two old (the game never clears the window: the board paints itself whole).
         */
        private fun shot(theApp: LawnApp, thePath: Path, theStage: String) {
            theApp.mWidgetManager.markAllDirty()
            theApp.mWidgetManager.drawScreen()
            // Read back <em>before</em> the present: after a present the back buffer is undefined.
            val width = theApp.mRendererBackend.renderOutputWidth()
            val height = theApp.mRendererBackend.renderOutputHeight()
            val pixels = IntArray(width * height)
            theApp.mRendererBackend.readTexturePixels(0L, width, height, pixels)
            reportLabelBounds(theApp, pixels, width, height, theStage)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, width, height, pixels, 0, width)
            Files.createDirectories(thePath.getParent())
            ImageIO.write(image, "png", thePath.toFile())
            theApp.mRendererBackend.present()
            System.out.println("[shot] wrote " + thePath)
        }

        /**
         * Draws the controls a second time into an off-screen image (1:1 board coordinates, a black
         * background, no lawn behind them) and measures what came out: for every panel the pixels that
         * fall <em>outside</em> its rectangle (which would mean the art overflows into its neighbour or
         * into the button above) and the bounding box of the label's green pixels (which is how the
         * centring is checked without trusting any rectangle arithmetic).
         */
        private fun measureDrawnGeometry(theApp: LawnApp, theDumpDir: Path) {
            val aControls = SpeedMod.controls()
            if (aControls == null) {
                return
            }

            val aTarget = MemoryImage(theApp)
            aTarget.create(theApp.mWidth, theApp.mHeight)
            val aGraphics = Graphics(aTarget)
            aGraphics.setColor(Color(0, 0, 0))
            aGraphics.fillRect(0, 0, theApp.mWidth, theApp.mHeight)
            aControls.draw(aGraphics)
            // The game's own button above them goes into the same image: it is the reference for "how the
            // game centres a label in this art", drawn by the game's own code with the same slices.
            val aMenuButton = aControls.board().mMenuButton
            aMenuButton.draw(aGraphics)
            val aBits = aTarget.bits

            val aControlRects = aControls.rects()
            val aRects = arrayOf(
                aControlRects[0], aControlRects[1], aControlRects[2], aControlRects[3],
                Rect(aMenuButton.mX, aMenuButton.mY, aMenuButton.mWidth, aMenuButton.mHeight)
            )
            val aNames = arrayOf("slow", "pause", "fast", "readout", "menuButton(game's own)")
            for (anIndex in aRects.indices) {
                val aRect = aRects[anIndex]
                System.out.println("[shot] offscreen " + aNames[anIndex] + " " +
                        describePanel(aBits, aRect, aRects))
            }

            val anImage = BufferedImage(theApp.mWidth, theApp.mHeight,
                    BufferedImage.TYPE_INT_ARGB)
            anImage.setRGB(0, 0, theApp.mWidth, theApp.mHeight, aBits, 0, theApp.mWidth)
            val aPath = theDumpDir.resolve("speed-controls-offscreen.png")
            Files.createDirectories(aPath.getParent())
            ImageIO.write(anImage, "png", aPath.toFile())
            System.out.println("[shot] wrote " + aPath)
        }

        /**
         * The drawn panel and its label, measured on the off-screen image (board coordinates, 1:1).
         * {@code outsidePixels} counts only pixels that belong to neither this panel nor a sibling panel
         * (the rounded corners of the neighbours sit inside the search margin): any other drawn pixel there
         * would be art that overflowed its own rectangle.
         */
        private fun describePanel(theBits: IntArray, theRect: Rect, thePanels: Array<Rect>): String {
            val aInflate = 8
            var aOutside = 0
            var aPanel: IntArray? = null
            var aLabel: IntArray? = null
            for (y in Math.max(0, theRect.mY - aInflate) until
                    Math.min(600, theRect.mY + theRect.mHeight + aInflate)) {
                for (x in Math.max(0, theRect.mX - aInflate) until
                        Math.min(800, theRect.mX + theRect.mWidth + aInflate)) {
                    val aPixel = theBits[y * 800 + x]
                    val aDrawn = ((aPixel shr 24) and 0xFF) != 0 && (aPixel and 0xFFFFFF) != 0
                    if (!aDrawn) {
                        continue
                    }

                    val aInside = x >= theRect.mX && x < theRect.mX + theRect.mWidth &&
                            y >= theRect.mY && y < theRect.mY + theRect.mHeight
                    if (!aInside) {
                        if (!insideAny(x, y, thePanels)) {
                            aOutside++
                        }
                        continue
                    }

                    aPanel = grow(aPanel, x, y)
                    val aRed = (aPixel shr 16) and 0xFF
                    val aGreen = (aPixel shr 8) and 0xFF
                    val aBlue = aPixel and 0xFF
                    if (aGreen > 120 && aGreen - aRed > 70 && aGreen - aBlue > 70) {
                        aLabel = grow(aLabel, x, y)
                    }
                }
            }

            val aPanelText = if (aPanel == null) "<none>"
                    else (aPanel[0].toString() + "," + aPanel[1] + "-" + (aPanel[2] + 1) + "," + (aPanel[3] + 1))
            val aLabelText: String
            if (aLabel == null) {
                aLabelText = "<no label pixels>"
            } else {
                val aLabelCentreX = (aLabel[0] + aLabel[2] + 1) / 2.0
                val aLabelCentreY = (aLabel[1] + aLabel[3] + 1) / 2.0
                aLabelText = String.format(Locale.ROOT, "%s centreOffset=(%.1f,%.1f)",
                        aLabel[0].toString() + "," + aLabel[1] + "-" + (aLabel[2] + 1) + "," + (aLabel[3] + 1),
                        aLabelCentreX - (theRect.mX + theRect.mWidth / 2.0),
                        aLabelCentreY - (theRect.mY + theRect.mHeight / 2.0))
            }

            return "rect=" + rect(theRect) + " panelPixels=" + aPanelText + " outsidePixels=" + aOutside +
                    " label=" + aLabelText
        }

        private fun insideAny(theX: Int, theY: Int, theRects: Array<Rect>): Boolean {
            for (aRect in theRects) {
                if (theX >= aRect.mX && theX < aRect.mX + aRect.mWidth &&
                        theY >= aRect.mY && theY < aRect.mY + aRect.mHeight) {
                    return true
                }
            }
            return false
        }

        private fun grow(theBounds: IntArray?, theX: Int, theY: Int): IntArray {
            if (theBounds == null) {
                return intArrayOf(theX, theY, theX, theY)
            }

            theBounds[0] = Math.min(theBounds[0], theX)
            theBounds[1] = Math.min(theBounds[1], theY)
            theBounds[2] = Math.max(theBounds[2], theX)
            theBounds[3] = Math.max(theBounds[3], theY)
            return theBounds
        }

        /**
         * Where the drawn label pixels actually are, measured on the frame: for every panel the bounding
         * box of the label's green pixels (the stone label font is the only green inside a rock panel) maps
         * back to board coordinates, and its centre is compared with the panel's centre. This is the
         * "the text is centred" proof with pixels, independent of the rectangle arithmetic.
         */
        private fun reportLabelBounds(theApp: LawnApp, thePixels: IntArray, theWidth: Int, theHeight: Int,
                theStage: String) {
            val scale = theWidth.toDouble() / theApp.mWidth
            val aControls = SpeedMod.controls()
            if (aControls == null) {
                return
            }

            val aControlRects = aControls.rects()
            val aBoard = aControls.board()
            // The game's own menu button is measured the same way: it is the reference for "centred",
            // because the mod draws its panels with the game's own stone rendering and label font.
            val aRects = arrayOf(
                Rect(aBoard.mMenuButton.mX, aBoard.mMenuButton.mY,
                        aBoard.mMenuButton.mWidth, aBoard.mMenuButton.mHeight),
                aControlRects[0], aControlRects[1], aControlRects[2], aControlRects[3]
            )
            val aNames = arrayOf("menuButton(game's own)", "slow", "pause", "fast", "readout")
            for (anIndex in aRects.indices) {
                val aRect = aRects[anIndex]
                val aBounds = labelBounds(thePixels, theWidth, theHeight, scale, aRect)
                if (aBounds == null) {
                    System.out.println("[shot] " + theStage + " " + aNames[anIndex] + " rect=" + rect(aRect) +
                            " label=<no label pixels>")
                    continue
                }

                val aCentreX = (aBounds[0] + aBounds[2] + 1) / 2.0 / scale
                val aCentreY = (aBounds[1] + aBounds[3] + 1) / 2.0 / scale
                val aPanelCentreX = aRect.mX + aRect.mWidth / 2.0
                val aPanelCentreY = aRect.mY + aRect.mHeight / 2.0
                System.out.printf(Locale.ROOT,
                        "[shot] %s %s rect=%s labelBoardBounds=%.1f,%.1f-%.1f,%.1f " +
                                "labelCentreOffset=(%.1f,%.1f)%n",
                        theStage, aNames[anIndex], rect(aRect),
                        aBounds[0] / scale, aBounds[1] / scale, (aBounds[2] + 1) / scale, (aBounds[3] + 1) / scale,
                        aCentreX - aPanelCentreX, aCentreY - aPanelCentreY)
            }
        }

        /**
         * The bounding box of the label's green pixels inside {@code theRect}, in frame pixels.
         *
         * <p>The search area is the panel inset by {@value #LABEL_INSET} pixels: the rock panel is drawn
         * with rounded corners, so the corners of its rectangle show the (green) lawn through, and the
         * frame is a scaled copy of the board. The label sits well inside that inset.
         */
        private fun labelBounds(thePixels: IntArray, theWidth: Int, theHeight: Int, theScale: Double,
                theRect: Rect): IntArray? {
            val aMinX = Math.max(0, Math.ceil(theRect.mX * theScale).toInt() + LABEL_INSET)
            val aMinY = Math.max(0, Math.ceil(theRect.mY * theScale).toInt() + LABEL_INSET)
            val aMaxX = Math.min(theWidth - 1,
                    Math.floor((theRect.mX + theRect.mWidth) * theScale).toInt() - LABEL_INSET)
            val aMaxY = Math.min(theHeight - 1,
                    Math.floor((theRect.mY + theRect.mHeight) * theScale).toInt() - LABEL_INSET)
            var aBounds: IntArray? = null
            for (y in aMinY..aMaxY) {
                for (x in aMinX..aMaxX) {
                    val aPixel = thePixels[y * theWidth + x]
                    val aRed = (aPixel shr 16) and 0xFF
                    val aGreen = (aPixel shr 8) and 0xFF
                    val aBlue = aPixel and 0xFF
                    if (aGreen > 120 && aGreen - aRed > 70 && aGreen - aBlue > 70) {
                        if (aBounds == null) {
                            aBounds = intArrayOf(x, y, x, y)
                        } else {
                            aBounds[0] = Math.min(aBounds[0], x)
                            aBounds[1] = Math.min(aBounds[1], y)
                            aBounds[2] = Math.max(aBounds[2], x)
                            aBounds[3] = Math.max(aBounds[3], y)
                        }
                    }
                }
            }
            return aBounds
        }

        private fun rect(theRect: Rect?): String {
            return if (theRect == null) "<none>" else rect(theRect.mX, theRect.mY, theRect.mWidth, theRect.mHeight)
        }

        private fun rect(theX: Int, theY: Int, theWidth: Int, theHeight: Int): String {
            return theX.toString() + "," + theY + "," + theWidth + "x" + theHeight
        }

        private fun sceneName(theScene: Int): String {
            return if (theScene == GameScenes.SCENE_PLAYING) "PLAYING" else theScene.toString()
        }
    }
}
