package cn.enaium.fabricpvz.speedmod.test

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Locale
import java.util.TreeSet

import cn.enaium.fabricpvz.api.PvzFabricApi
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.Coin
import cn.enaium.fabricpvz.portable.lawn.CoinMotion
import cn.enaium.fabricpvz.portable.lawn.CoinType
import cn.enaium.fabricpvz.portable.lawn.CursorType
import cn.enaium.fabricpvz.portable.lawn.GameConstants
import cn.enaium.fabricpvz.portable.lawn.GameMode
import cn.enaium.fabricpvz.portable.lawn.GameObjectType
import cn.enaium.fabricpvz.portable.lawn.GameScenes
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.LawnApp
import cn.enaium.fabricpvz.portable.lawn.Plant
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.SeedType
import cn.enaium.fabricpvz.portable.sexy.KeyCode
import cn.enaium.fabricpvz.portable.sexy.Rect
import cn.enaium.fabricpvz.speedmod.GameSpeed
import cn.enaium.fabricpvz.speedmod.SpeedControls
import cn.enaium.fabricpvz.speedmod.SpeedMod
import net.fabricmc.loader.api.FabricLoader

/**
 * The game entry point of the end-to-end run: Fabric Loader starts this class instead of the real
 * {@code Main} ({@code -Dpvz.game.mainClass}), so everything below runs inside the loader's class
 * loader with the mods of a real {@code mods/} directory already resolved, their entry points run
 * and their Mixin configurations applied.
 *
 * <p>Because the loader defines its own copies of the game, the API and the mod classes, this class
 * cannot report to the test through memory: every observation is appended to the file named by
 * {@code -Dpvz.test.report} and the JUnit side asserts on it.
 *
 * <p>Phases:
 * <ol>
 *   <li>what the loader loaded and that the mod's entry point ran;</li>
 *   <li>a real headless board ({@code preNewGame} + {@code startPlaying}) driven through the game's
 *       own frame entry point {@code LawnApp.updateFrames()};</li>
 *   <li>the speed control: the same number of frames must advance the board by 0.5x / 1x / 1.5x /
 *       3x / 0x ticks;</li>
 *   <li>the controls on screen: their scaled rectangles beside the progress meter (or below the
 *       menu button on a board without one), and clicks on them change the speed while the board
 *       itself never sees the click; space is the mod's own pause, with no dialog;</li>
 *   <li>suns and coins on the board at 1x: the auto-collect mod, when it is installed next to this
 *       one, must collect them and the game's own counters must move by exactly their value - and
 *       when it is not, nothing may be collected at all.</li>
 * </ol>
 */
class SpeedModGameMain private constructor() {

    companion object {

        /** Frames driven per measurement: enough for the fractional 0.5x/1.5x patterns to average out. */
        private const val FRAMES = 100

        /** Frames driven with suns and coins on the board: enough for a collected coin to reach its counter. */
        private const val COLLECT_FRAMES = 300

        /** A sun-countdown far beyond this run: no sun may fall from the sky by the game's own timer. */
        private const val NO_SKY_SUNS = 1_000_000

        private val REPORT = StringBuilder()

        private var sError = ""

        @JvmStatic
        fun main(args: Array<String>) {
            val aReportPath = Path.of(requiredProperty("pvz.test.report"))
            var aApp: LawnApp? = null
            try {
                loaderPhase()
                aApp = boardPhase()
                ratePhase(aApp)
                controlsPhase(aApp)
                collectionPhase(aApp)
            } catch (aThrowable: Throwable) {
                sError = aThrowable.toString()
                aThrowable.printStackTrace()
            } finally {
                report("harnessError", if (sError.isEmpty()) "none" else sError)
                try {
                    flush(aReportPath)
                } catch (anException: Exception) {
                    anException.printStackTrace()
                }

                if (aApp != null) {
                    LawnApp.gLawnApp = null
                }
            }

            System.out.println("[speed-mod] report written to " + aReportPath)
            System.out.println(REPORT)

            if (!sError.isEmpty()) {
                throw IllegalStateException("the speed mod harness failed: " + sError)
            }
        }

        // ---------------------------------------------------------------- phase 1

        /** What the loader resolved, and that the mod's own entry point ran. */
        private fun loaderPhase() {
            val aLoader = FabricLoader.getInstance()
            report("loaderVersion", aLoader.getModContainer("fabricloader")
                    .map { container -> container.metadata.version.friendlyString }
                    .orElse("<missing>"))
            report("gameVersion", aLoader.rawGameVersion)
            report("environment", aLoader.environmentType.name.lowercase(Locale.ROOT))

            val aModIds = ArrayList<String>()
            for (aContainer in aLoader.allMods) {
                aModIds.add(aContainer.metadata.id)
            }
            report("modsLoaded", TreeSet(aModIds).joinToString(","))
            report("modCount", aModIds.size)
            report("speedModLoaded", aLoader.isModLoaded(SpeedMod.MOD_ID))

            report("apiInitialized", PvzFabricApi.isInitialized())
            // Set by the mod's own entry point, which only Fabric Loader runs.
            report("speedModEntrypointRan", SpeedMod.initialized)
            // The mod's Kotlin code: a Kotlin class of the mod jar that Fabric Loader caused to run, with
            // the Kotlin standard library resolved at runtime.
            report("kotlinEntrypoint", SpeedMod.kotlinGreeting ?: "<missing>")
        }

        // ---------------------------------------------------------------- phase 2

        /** A real level: the game's own {@code preNewGame} + {@code startPlaying}, headlessly. */
        private fun boardPhase(): LawnApp {
            val aApp = HeadlessLawn.LoadedReanimApp()
            aApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
            HeadlessLawn.initialize(aApp)
            aApp.mPlayerInfo.mLevel = 1
            // Not a first-time player: no tutorial popups from the first sun/plant.
            aApp.mPlayerInfo.mFinishedAdventure = 1

            aApp.preNewGame(GameMode.GAMEMODE_ADVENTURE, false)
            val aBoard = aApp.mBoard
            report("boardCreated", aBoard != null)
            aApp.startPlaying()
            // The two lines the level-intro cutscene performs when it hands the board over
            // (CutScene.java:1208-1211): the menu button appears again and the scene starts playing.
            // Starting the board directly is the same shortcut the game's own mode-coverage test takes;
            // the buttons of the mod follow the menu button, so this is what makes them visible.
            aBoard.mMenuButton.mBtnNoDraw = false
            // What the level intro does before the scene plays: the seed chooser hands the bank its
            // seeds. Skipping it would leave the board in a state no player can be in (every packet at
            // SEED_NONE), which collecting a sun reads (see HeadlessLawn.chooseSeeds).
            HeadlessLawn.chooseSeeds(aBoard)
            // The game's own sky-sun timer (BoardWaves.java:961-971): pushed out of the way so that every
            // sun on this board was put there by the harness, at a fixed tick.
            aBoard.mSunCountDown = NO_SKY_SUNS

            report("gameScene", if (aApp.mGameScene == GameScenes.SCENE_PLAYING) "PLAYING" else aApp.mGameScene.toString())
            report("boardLevel", if (aBoard == null) -1 else aBoard.mLevel)
            report("boardInWidgetTree", aApp.mWidgetManager.mWidgets.contains(aBoard))
            report("boardTicksAtStart", if (aBoard == null) -1 else aBoard.mBoardUpdateCounter)
            report("autoCollectModLoaded", FabricLoader.getInstance().isModLoaded("auto-collect-mod"))
            return aApp
        }

        // ---------------------------------------------------------------- phase 3

        /** The speed control: ticks per frame, measured on the board's own tick counter. */
        private fun ratePhase(theApp: LawnApp) {
            val aBoard = theApp.mBoard
            val aSpeed = GameSpeed.get()

            report("rangeStart", GameSpeed.START)
            report("rangeEnd", GameSpeed.END)
            report("step", GameSpeed.STEP)

            // 1x is the mod's untouched state: the game's own code path must run.
            aSpeed.reset()
            report("vanilla", aSpeed.isVanilla())
            report("ticksAt1x", ticks(aSpeed, theApp, aBoard))

            aSpeed.setMultiplier(0.5f)
            report("ticksAt0_5x", ticks(aSpeed, theApp, aBoard))

            aSpeed.setMultiplier(1.5f)
            report("ticksAt1_5x", ticks(aSpeed, theApp, aBoard))

            aSpeed.setMultiplier(3.0f)
            report("ticksAt3x", ticks(aSpeed, theApp, aBoard))

            aSpeed.togglePause()
            report("pausedReported", aSpeed.paused())
            report("ticksAtPause", ticks(aSpeed, theApp, aBoard))
            aSpeed.togglePause()
            report("ticksAfterResumeAt3x", ticks(aSpeed, theApp, aBoard))
            aSpeed.setMultiplier(1.0f)
            report("ticksBackAt1x", ticks(aSpeed, theApp, aBoard))

            // The value the not-yet-clicked state starts at, and the frames it took to get there.
            aSpeed.reset()
            report("defaultMultiplier", aSpeed.multiplier())
            report("defaultDisplay", aSpeed.display())
        }

        /** Runs {@value #FRAMES} frames at the current speed and returns the board's tick delta. */
        private fun ticks(theSpeed: GameSpeed, theApp: LawnApp, theBoard: Board): Int {
            val aBefore = theBoard.mBoardUpdateCounter
            for (aFrame in 0 until FRAMES) {
                theApp.updateFrames()
            }
            return theBoard.mBoardUpdateCounter - aBefore
        }

        // ---------------------------------------------------------------- phase 4

        /** The three buttons: where they are, what a click does, and that the board never sees it. */
        private fun controlsPhase(theApp: LawnApp) {
            val aBoard = theApp.mBoard
            val aSpeed = GameSpeed.get()
            aSpeed.reset()

            // The buttons are created by the mod's post-draw hook; one real draw pass creates them.
            theApp.mWidgetManager.drawScreen()
            val aControls = SpeedMod.controls()
            report("controlsCreated", aControls != null && aControls.board() === aBoard)
            report("controlsVisible", aControls != null && aControls.isVisible())
            val aControlSet = aControls!!

            // Where the game puts its own menu button, and where the controls put themselves: two rows
            // below the menu button (and below the store button when drawn), right-aligned to the menu
            // button's drawn right edge, at 0.75 scale of the game's stone art (53x34 buttons, 87x34
            // readout). Row one is `slow fast`, row two `pause readout`.
            report("menuButtonRect", rect(aBoard.mMenuButton.mX, aBoard.mMenuButton.mY,
                    aBoard.mMenuButton.mWidth, aBoard.mMenuButton.mHeight))
            report("stoneArt", sliceSize(ImageID.IMAGE_BUTTON_LEFT) + " " + sliceSize(ImageID.IMAGE_BUTTON_MIDDLE) +
                    " " + sliceSize(ImageID.IMAGE_BUTTON_RIGHT))
            report("stoneArtHeight", artHeight())
            report("scaledButtonSize", scaled(stonePanelWidth(0)).toString() + "x" + scaled(artHeight()))
            report("scaledReadoutSize", scaled(stonePanelWidth(1)).toString() + "x" + scaled(artHeight()))

            val aRects = aControlSet.rects()
            report("buttonRects", rect(aRects[0]) + " " + rect(aRects[1]) + " " + rect(aRects[2]))
            report("readoutRect", rect(aRects[3]))
            report("panelHeights", aRects[0].mHeight.toString() + " " + aRects[1].mHeight + " " + aRects[2].mHeight +
                    " " + aRects[3].mHeight)
            report("buttonWidths", aRects[0].mWidth.toString() + " " + aRects[1].mWidth + " " + aRects[2].mWidth)
            report("readoutWidth", aRects[3].mWidth)
            report("rowOneTop", aRects[0].mY)
            report("rowTwoTop", aRects[1].mY)
            report("rowOneRightEdge", aRects[2].mX + aRects[2].mWidth)
            report("readoutRightEdge", aRects[3].mX + aRects[3].mWidth)
            report("twoRowsBelowMenu", aRects[0].mY >= aBoard.mMenuButton.mY + artHeight() &&
                    aRects[1].mY > aRects[0].mY &&
                    aRects[0].mY == aRects[2].mY && aRects[1].mY == aRects[3].mY)
            report("rowOneIsSlowFast", aRects[0].mX < aRects[2].mX)
            report("rowTwoIsPauseReadout", aRects[1].mX < aRects[3].mX)
            report("rowRightEdgesAreMenuArtRightEdge", aRects[2].mX + aRects[2].mWidth ==
                    aBoard.mMenuButton.mX + gameDrawnWidth(aBoard.mMenuButton.mWidth) &&
                    aRects[3].mX + aRects[3].mWidth ==
                    aBoard.mMenuButton.mX + gameDrawnWidth(aBoard.mMenuButton.mWidth))

            var aOverlap = false
            for (i in aRects.indices) {
                for (j in i + 1 until aRects.size) {
                    aOverlap = aOverlap or aRects[i].intersects(aRects[j])
                }
            }
            report("panelsOverlap", aOverlap)
            report("insideBoardWidth", aRects[3].mX >= 0 &&
                    aRects[2].mX + aRects[2].mWidth <= GameConstants.BOARD_WIDTH)
            report("controlLabels", aControlSet.labels().joinToString(" "))

            // Slow: one click below the start of the range, and the value stops at the lower bound.
            click(aBoard, centre(aRects[0]))
            report("multiplierAfterOneSlowClick", aSpeed.multiplier())
            report("displayAfterOneSlowClick", aSpeed.display())
            click(aBoard, centre(aRects[0]))
            click(aBoard, centre(aRects[0]))
            report("multiplierAtLowerBound", aSpeed.multiplier())

            // Fast: back up, and the value stops at the upper bound however many times it is clicked.
            for (aClick in 0 until 8) {
                click(aBoard, centre(aRects[2]))
            }
            report("multiplierAtUpperBound", aSpeed.multiplier())
            report("displayAtUpperBound", aSpeed.display())

            // Pause toggles, and the pause button's label follows the state.
            click(aBoard, centre(aRects[1]))
            report("pausedAfterPauseClick", aSpeed.paused())
            report("labelsWhilePaused", aControlSet.labels().joinToString(" "))
            click(aBoard, centre(aRects[1]))
            report("pausedAfterSecondClick", aSpeed.paused())
            report("labelsAfterResume", aControlSet.labels().joinToString(" "))

            // Space is the mod's pause: it toggles the mod's own pause flag, runs zero ticks while
            // paused, resumes on the second press, and never opens the game's pause dialog (the game's
            // own space branch calls LawnApp.doPauseDialog, which puts a widget in the dialog stack).
            aSpeed.reset()
            val aDialogsBefore = theApp.dialogCount
            aBoard.keyDown(KeyCode.KEYCODE_SPACE)
            report("spacePaused", aSpeed.paused())
            report("spaceDialogsOpened", theApp.dialogCount - aDialogsBefore)
            report("spaceTicksWhilePaused", ticks(aSpeed, theApp, aBoard))
            aBoard.keyDown(KeyCode.KEYCODE_SPACE)
            report("spacePausedAfterSecondPress", aSpeed.paused())
            report("spaceDialogsOpenedAfterSecondPress", theApp.dialogCount - aDialogsBefore)
            report("spaceTicksAfterResume", ticks(aSpeed, theApp, aBoard))

            // A release outside the button it started on cancels the press (the value must not move).
            aSpeed.reset()
            aBoard.mouseDown(centre(aRects[2])[0], centre(aRects[2])[1], 1)
            val aConsumedDown = aBoard.mIgnoreMouseUp
            aBoard.mouseUp(400, 300, 1)
            report("pressOutsideReleaseConsumed", aConsumedDown)
            report("multiplierAfterCancelledPress", aSpeed.multiplier())

            // The board's own input still works: a shovel click on a plant digs it up...
            val aPlant = aBoard.newPlant(0, 0, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE)
            val aPlantCentre = intArrayOf(aPlant.mX + 40, aPlant.mY + 40)
            aBoard.pickUpTool(GameObjectType.OBJECT_TYPE_SHOVEL)
            report("shovelCursor", aBoard.mCursorObject.mCursorType == CursorType.CURSOR_TYPE_SHOVEL)
            val aShovelled = aBoard.mPlantsShoveled
            click(aBoard, aPlantCentre)
            report("plantDugUpByShovelClick", aBoard.mPlantsShoveled - aShovelled)

            // ...and a shovel click on a speed button digs nothing up, because the button took the click.
            val aSecondPlant = aBoard.newPlant(0, 0, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE)
            report("secondPlantPlaced", aSecondPlant != null)
            aBoard.pickUpTool(GameObjectType.OBJECT_TYPE_SHOVEL)
            val aShovelledBefore = aBoard.mPlantsShoveled
            val aMultiplierBefore = aSpeed.multiplier()
            click(aBoard, centre(aRects[2]))
            report("plantDugUpByButtonClick", aBoard.mPlantsShoveled - aShovelledBefore)
            report("multiplierMovedByButtonClick", aSpeed.multiplier() - aMultiplierBefore)
            report("secondPlantStillThere", aSecondPlant != null && !aSecondPlant.mDead)
        }

        // ---------------------------------------------------------------- phase 5

        /**
         * Suns and coins on the board, driven at 1x: the mod that is installed next to this one collects
         * them - the auto-collect mod does, by clicking what the player could click - and the game's own
         * counters must move by exactly their value. With no such mod installed nothing collects them, so
         * the deltas reported here are the mod's doing and nothing else's.
         */
        private fun collectionPhase(theApp: LawnApp) {
            val aBoard = theApp.mBoard
            // 1x: a frame is a tick, so the frames below are the ticks a collected coin needs to fly to
            // its counter and score (Coin.updateCollected, Coin.java:482-578).
            GameSpeed.get().setMultiplier(1.0f)

            // Three sun types (25 / 15 / 50, Coin.java:1044-1049) and the three money types
            // (1 / 5 / 100, Coin.java:1051-1055): distinct values, so the sums below are real sums.
            val aSuns = listOf(
                    aBoard.addCoin(400, 60, CoinType.COIN_SUN, CoinMotion.COIN_MOTION_FROM_SKY),
                    aBoard.addCoin(200, 60, CoinType.COIN_SMALLSUN, CoinMotion.COIN_MOTION_FROM_PLANT),
                    aBoard.addCoin(600, 60, CoinType.COIN_LARGESUN, CoinMotion.COIN_MOTION_FROM_SKY))
            val aMoney = listOf(
                    aBoard.addCoin(300, 200, CoinType.COIN_SILVER, CoinMotion.COIN_MOTION_COIN),
                    aBoard.addCoin(350, 220, CoinType.COIN_GOLD, CoinMotion.COIN_MOTION_COIN),
                    aBoard.addCoin(400, 240, CoinType.COIN_DIAMOND, CoinMotion.COIN_MOTION_COIN))
            report("sunsPlaced", aSuns.size)
            report("moneyPlaced", aMoney.size)
            report("sunValuePlaced", aSuns.stream().mapToInt { it.sunValue }.sum())
            report("moneyValuePlaced", aMoney.stream().mapToInt { aCoin -> Coin.getCoinValue(aCoin.mType) }.sum())

            val aSunBefore = aBoard.mSunMoney
            val aCoinsBefore = theApp.mPlayerInfo.mCoins
            val aCoinsCollectedBefore = aBoard.mCoinsCollected
            val aLevelCoinsBefore = aBoard.mLevelCoinsCollected
            for (aFrame in 0 until COLLECT_FRAMES) {
                theApp.updateFrames()
            }

            report("collectionFrames", COLLECT_FRAMES)
            report("sunMoneyBefore", aSunBefore)
            report("sunDelta", aBoard.mSunMoney - aSunBefore)
            report("playerCoinsBefore", aCoinsBefore)
            report("coinsDelta", theApp.mPlayerInfo.mCoins - aCoinsBefore)
            report("coinsCollectedDelta", aBoard.mCoinsCollected - aCoinsCollectedBefore)
            // The board's own level tally counts silver and gold only (Coin.scoreCoin, Coin.java:358-364).
            report("levelCoinsDelta", aBoard.mLevelCoinsCollected - aLevelCoinsBefore)
            report("coinsLeftOnBoard", aBoard.mCoins.mSize)
            report("sunsLeftOnBoard", aSuns.stream().filter { aCoin -> !aCoin.mDead }.count().toInt())
        }

        /** A press and release at a point, the way the framework's widget manager routes a click. */
        private fun click(theBoard: Board, thePoint: IntArray) {
            theBoard.mouseDown(thePoint[0], thePoint[1], 1)
            theBoard.mouseUp(thePoint[0], thePoint[1], 1)
        }

        private fun centre(theRect: Rect): IntArray {
            return intArrayOf(theRect.mX + theRect.mWidth / 2, theRect.mY + theRect.mHeight / 2)
        }

        /** Height of the stone art every panel is drawn at (the 9-slice ignores the requested height). */
        private fun artHeight(): Int {
            return Resources.getImageById(ImageID.IMAGE_BUTTON_MIDDLE).height
        }

        /** A panel dimension at the row's 0.75 scale: the art size rounded down. */
        private fun scaled(theSize: Int): Int {
            return Math.floor(theSize * 0.75).toInt()
        }

        /** The width the stone 9-slice uses for a panel with {@code theMiddleSlices} middle slices. */
        private fun stonePanelWidth(theMiddleSlices: Int): Int {
            return Resources.getImageById(ImageID.IMAGE_BUTTON_LEFT).width +
                    theMiddleSlices * Resources.getImageById(ImageID.IMAGE_BUTTON_MIDDLE).width +
                    Resources.getImageById(ImageID.IMAGE_BUTTON_RIGHT).width
        }

        /**
         * The width {@code GameButton.drawStoneButton} really draws for a panel requested to be
         * {@code theWidth} wide - the game's own arithmetic, spelled out here so the check on the mod's
         * rectangles does not go through the mod's own helper.
         */
        private fun gameDrawnWidth(theWidth: Int): Int {
            val aLeft = Resources.getImageById(ImageID.IMAGE_BUTTON_LEFT).width
            val aMiddle = Resources.getImageById(ImageID.IMAGE_BUTTON_MIDDLE).width
            val aRight = Resources.getImageById(ImageID.IMAGE_BUTTON_RIGHT).width
            return aLeft + Math.max(0, (theWidth - aLeft - aRight) / aMiddle) * aMiddle + aRight
        }

        private fun sliceSize(theImageId: Int): String {
            return Resources.getImageById(theImageId).width.toString() + "x" +
                    Resources.getImageById(theImageId).height
        }

        private fun rect(theRect: Rect): String {
            return rect(theRect.mX, theRect.mY, theRect.mWidth, theRect.mHeight)
        }

        private fun rect(theX: Int, theY: Int, theWidth: Int, theHeight: Int): String {
            return theX.toString() + "," + theY + "," + theWidth + "x" + theHeight
        }

        // ---------------------------------------------------------------- reporting

        private fun requiredProperty(theName: String): String {
            val aValue = System.getProperty(theName, "")
            if (aValue.isBlank()) {
                throw IllegalStateException("-D" + theName + " is required")
            }
            return aValue
        }

        private fun report(theKey: String, theValue: Any?) {
            REPORT.append(theKey).append('=').append(theValue).append('\n')
        }

        private fun flush(theReportPath: Path) {
            Files.writeString(theReportPath, REPORT.toString())
        }
    }
}
