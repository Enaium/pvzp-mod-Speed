package cn.enaium.fabricpvz.speedmod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.stream.Collectors
import kotlin.KotlinVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import cn.enaium.fabricpvz.provider.PvzGameProvider

/**
 * End-to-end verification of the speed mod on the real Fabric Loader.
 *
 * <p>The test does exactly what a launcher does: it copies the <em>built mod jar</em> into a real
 * {@code mods/} directory and starts {@code net.fabricmc.loader.impl.launch.knot.KnotClient} in a
 * second JVM with the game, the loader (plus Mixin and ASM, pulled in by
 * the game jar carries) and the API modules on the class path. The loader
 * resolves the mod, runs its {@code ModInitializer}, registers its Mixin configuration and only then
 * calls the game entry point, which this test redirects to
 * [cn.enaium.fabricpvz.speedmod.test.SpeedModGameMain] with {@code -Dpvz.game.mainClass}.
 *
 * <p>Running inside the loader is the whole point: without Mixin nothing would scale the update rate,
 * so the numbers below are the only way to show the mod works as a mod and not as a library.
 *
 * <p>The test runs the harness twice: once with only this mod in the mods directory, and once with the
 * sibling auto-collect mod's jar next to it - the combination a player has when both are installed.
 * The second run is where a crash of the two together shows up: that mod clicks every sun and coin on
 * the board (which is the game's own click path, {@code Coin.mouseDown} -> {@code Coin.collect}).
 *
 * <p>The harness reports through a file because the loader defines its own copies of the game, the
 * API and the mod classes; the assertions below are all made on that report.
 */
class SpeedModEndToEndTest {

    companion object {

        /** Launcher main class of the real Fabric Loader (client environment), from its own manifest. */
        private const val LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient"

        /** System property carrying the class path the launcher has to run with. */
        private const val FORK_CLASSPATH_PROPERTY = "pvz.test.forkClasspath"

        /** System property naming the built mod jar. */
        private const val MOD_JAR_PROPERTY = "pvz.test.modJar"

        /**
         * The sibling mod this one is loaded next to, where this workspace installs its jar: the
         * auto-collect mod's own e2e installs this mod the same way. Both mods end up in one
         * {@code mods/} directory, which is the combination a player gets.
         */
        private val COMPANION_JARS = listOf(
                "../PvZ-Portable/run/mods/auto-collect-mod-1.0.0.jar",
                "../auto-collect-mod/build/libs/auto-collect-mod-1.0.0.jar")

        /** The id of that companion mod, as its {@code fabric.mod.json} declares it. */
        private const val COMPANION_MOD_ID = "auto-collect-mod"

        /**
         * Mod ids that must be resolved next to this mod: the loader, the game, the aggregate API mod and
         * the API module that owns the events the mod uses. The mod declares its dependency on
         * {@code api-events} rather than on the aggregate, so it keeps loading however the aggregate
         * is named or whether it is present at all.
         */
        private val DEPENDENCY_MODS = listOf(
                "fabricloader", "pvz-portable", "fabric-api",
                "api-base", "api-events", "api-plant", "api-zombie")

        /** Frames the harness drives per measurement; the assertions below use it for their arithmetic. */
        private const val FRAMES = 100
    }

    @TempDir
    lateinit var tmp: Path

    @Test
    fun scalesTheGameRateAndServesItsButtonsFromTheInstalledModJar() {
        val modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY))
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: " + modJar)
        assertModJarContents(modJar)

        val mods = Files.createDirectories(tmp.resolve("mods"))
        val installed = Files.copy(modJar, mods.resolve(modJar.fileName))
        assertTrue(Files.size(installed) > 0, "the installed mod jar must not be empty")

        val runDir = Files.createDirectories(tmp.resolve("run"))
        val report = tmp.resolve("speed-report.txt")
        val output = tmp.resolve("loader-output.txt")

        val exitCode = launch(mods, runDir, report, output)
        assertEquals(0, exitCode, "Fabric Loader must boot the game with the mod: " + failureMessage(output, report))

        val values = readReport(report)
        assertEquals("none", values["harnessError"], "the harness must run to the end")

        // 1) The loader resolved the game, the API modules and this mod jar, and ran its entry point.
        // Against the provider's own statement, not a literal: what this asserts is that the loader is
        // handed the version the game provider publishes, whatever the game version is.
        assertEquals(PvzGameProvider.DEFAULT_GAME_VERSION, values["gameVersion"],
            "the provider must report the game's version")
        assertEquals("client", values["environment"])
        assertEquals("true", values["speedModLoaded"])
        for (id in DEPENDENCY_MODS) {
            assertTrue(values["modsLoaded"]!!.split(",").contains(id),
                    "mod " + id + " must be loaded, got " + values["modsLoaded"])
        }
        // The mod is Kotlin, so the real language module has to be resolved next to it: this mod
        // declares the dependency in its fabric.mod.json and the toolchain puts the artifact on the
        // launcher class path (a development run discovers the mods that are on the class path).
        assertTrue(values["modsLoaded"]!!.split(",").contains("fabric-language-kotlin"),
                "fabric-language-kotlin must be resolved next to this mod, got " + values["modsLoaded"])
        assertEquals("true", values["apiInitialized"])
        assertEquals("true", values["speedModEntrypointRan"])

        // 1b) Kotlin: the mod's entry point is a Kotlin class (Fabric Loader instantiates it through
        //     its no-argument constructor, which is why it is a `class` and not an `object`), it ran
        //     inside the loader, and its Kotlin code reported the Kotlin standard library it links
        //     against at runtime. The version in the log line is the stdlib the toolchain wires, which
        //     is the one this test itself compiles against (kotlin-stdlib is on the test class path,
        //     and the mod jar bundles no stdlib of its own).
        val kotlin = values["kotlinEntrypoint"]
        assertNotNull(kotlin, "the mod's Kotlin class must have reported from inside the loader")
        assertTrue(kotlin!!.startsWith("[speed-mod] Kotlin " + KotlinVersion.CURRENT + " class running"),
                "a Kotlin class must run under Fabric Loader against the toolchain's stdlib, got: " + kotlin)
        assertFalse(kotlin.contains("org.jetbrains.kotlin/kotlin-stdlib"),
                "the mod's runtime stdlib must be the one fabric-language-kotlin carries (its nested jar, " +
                        "which the loader adds to the class path), not a plain library that happens to be " +
                        "on this test's class path, got: " + kotlin)

        // 2) A real level is running: the board is in the widget tree and the scene is playing.
        assertEquals("true", values["boardCreated"])
        assertEquals("PLAYING", values["gameScene"])
        assertEquals("true", values["boardInWidgetTree"])

        // 3) The speed control, measured on the board's own tick counter over the same 100 frames.
        assertEquals("true", values["vanilla"])
        assertEquals(FRAMES.toString(), values["ticksAt1x"], "1x must tick once per frame")
        assertEquals((FRAMES / 2).toString(), values["ticksAt0_5x"], "0.5x must tick every other frame")
        assertNear(FRAMES * 1.5, values["ticksAt1_5x"], 1, "1.5x must average one and a half ticks")
        assertEquals((FRAMES * 3).toString(), values["ticksAt3x"], "3x must tick three times per frame")
        assertEquals("true", values["pausedReported"])
        assertEquals("0", values["ticksAtPause"], "pause must stop the board completely")
        assertEquals((FRAMES * 3).toString(), values["ticksAfterResumeAt3x"],
                "resuming must go back to the selected multiplier, not to 1x")
        assertEquals(FRAMES.toString(), values["ticksBackAt1x"], "1x must tick once per frame again")
        assertEquals("1.0", values["defaultMultiplier"])
        assertEquals("1.0x", values["defaultDisplay"])

        // 4) The controls: two rows below the menu button, right-aligned to its drawn right edge,
        //    drawn at 0.75 scale (53x34 buttons, 87x34 readout), reading `slow fast` on the first
        //    row and `pause readout` on the second (`<` `>` / `=` `1.0x`).
        assertEquals("true", values["controlsCreated"])
        assertEquals("true", values["controlsVisible"])
        assertEquals("53x34", values["scaledButtonSize"], "a button is the 71x46 stone art at 0.75")
        assertEquals("87x34", values["scaledReadoutSize"], "the readout is the 117x46 stone art at 0.75")
        assertEquals("34 34 34 34", values["panelHeights"], "all four panels are 34 pixels tall")
        assertEquals("53 53 53", values["buttonWidths"], "the three buttons are 53 pixels wide")
        assertEquals("87", values["readoutWidth"], "the readout is 87 pixels wide")
        assertEquals("40", values["rowOneTop"], "row one sits one gap below the menu button's 46-pixel art")
        assertEquals("78", values["rowTwoTop"], "row two sits one gap below row one")
        assertEquals("798", values["rowOneRightEdge"],
                "the row is right-aligned to the menu button's drawn right edge (681 + 117)")
        assertEquals("798", values["readoutRightEdge"], "the readout ends row two at the same right edge")
        assertEquals("true", values["twoRowsBelowMenu"])
        assertEquals("true", values["rowOneIsSlowFast"], "row one reads slow, fast")
        assertEquals("true", values["rowTwoIsPauseReadout"], "row two reads pause, readout")
        assertEquals("true", values["rowRightEdgesAreMenuArtRightEdge"])
        assertEquals("< = >", values["controlLabels"])
        assertEquals("< > >", values["labelsWhilePaused"], "pause becomes the resume arrow")
        assertEquals("< = >", values["labelsAfterResume"])
        assertEquals("false", values["panelsOverlap"], "no two panels may overlap")
        assertEquals("true", values["insideBoardWidth"])
        assertEquals("681,-10,117x46", values["menuButtonRect"], "the vanilla adventure menu button")
        assertEquals("0.5", values["multiplierAfterOneSlowClick"], "slow must step down by 0.5")
        assertEquals("0.5x", values["displayAfterOneSlowClick"])
        assertEquals("0.5", values["multiplierAtLowerBound"], "the range must not go below 0.5x")
        assertEquals("3.0", values["multiplierAtUpperBound"], "the range must not go above 3x")
        assertEquals("3.0x", values["displayAtUpperBound"])
        assertEquals("true", values["pausedAfterPauseClick"])
        assertEquals("false", values["pausedAfterSecondClick"])

        // 4b) Space is the mod's pause: it toggles the mod's own pause (zero board ticks) and must
        //     never reach the game's own space branch, which opens the pause dialog.
        assertEquals("true", values["spacePaused"])
        assertEquals("0", values["spaceDialogsOpened"],
                "space must not open the game's pause dialog")
        assertEquals("0", values["spaceTicksWhilePaused"])
        assertEquals("false", values["spacePausedAfterSecondPress"])
        assertEquals("0", values["spaceDialogsOpenedAfterSecondPress"])
        assertEquals(FRAMES.toString(), values["spaceTicksAfterResume"],
                "the second space press resumes the game")

        // 5) Clicks: a press released outside its button is cancelled, the board's own input still
        //    works, and a click on a button never reaches the board.
        assertEquals("true", values["pressOutsideReleaseConsumed"])
        assertEquals("1.0", values["multiplierAfterCancelledPress"])
        assertEquals("true", values["shovelCursor"])
        assertEquals("1", values["plantDugUpByShovelClick"], "the board's own shovel click must still work")
        assertEquals("true", values["secondPlantPlaced"])
        assertEquals("0", values["plantDugUpByButtonClick"], "a click on a button must not reach the shovel")
        assertEquals("0.5", values["multiplierMovedByButtonClick"], "the button must take the click instead")
        assertEquals("true", values["secondPlantStillThere"])

        // 6) Suns and coins on the board: with no auto-collect mod installed nothing collects them, so
        //    the counters must not move - which is what makes the deltas of the combined run below
        //    that mod's doing and nothing else's.
        assertEquals("3", values["sunsPlaced"])
        assertEquals("3", values["moneyPlaced"])
        assertEquals("90", values["sunValuePlaced"])
        assertEquals("106", values["moneyValuePlaced"])
        assertEquals("0", values["sunDelta"], "nothing clicks a sun when the auto-collect mod is absent")
        assertEquals("0", values["coinsDelta"], "nothing clicks a coin when the auto-collect mod is absent")
        assertEquals("0", values["levelCoinsDelta"])
        assertEquals("6", values["coinsLeftOnBoard"], "the suns and coins must still be on the board")
        assertEquals("3", values["sunsLeftOnBoard"], "the suns must still be on the board")
        assertEquals("false", values["autoCollectModLoaded"])
    }

    /**
     * The same harness with the auto-collect mod installed next to this one: the two mods together,
     * which is what a player's installation looks like. The auto-collect mod clicks every sun and coin
     * the player could click, and that click runs the game's own sun-collection path
     * ({@code Coin.mouseDown} -> {@code Coin.collect}), which reads every seed packet's cost
     * ({@code Coin.java:1013-1026}) - so on a board whose seed bank was never chosen it throws
     * {@code ArrayIndexOutOfBoundsException} from {@code Plant.getPlantDefinition}.
     *
     * <p>Both halves are asserted: the run must finish without that exception, the game's counters
     * must move by exactly the value of what was on the board (the 3+3 case: +90 sun, +106 coins, of
     * the values {@code Coin.java:1044-1055} gives those six types), and this mod's rate scaling must
     * be the numbers it is without the company.
     */
    @Test
    fun runsWithTheAutoCollectModInstalledAndStillScalesTheRate() {
        val modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY))
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: " + modJar)

        val mods = Files.createDirectories(tmp.resolve("mods"))
        Files.copy(modJar, mods.resolve(modJar.fileName))
        installCompanionJar(mods)

        val runDir = Files.createDirectories(tmp.resolve("run"))
        val report = tmp.resolve("speed-report.txt")
        val output = tmp.resolve("loader-output.txt")

        val exitCode = launch(mods, runDir, report, output)
        assertEquals(0, exitCode, "Fabric Loader must boot the game with both mods installed: " +
                failureMessage(output, report))

        val values = readReport(report)
        assertEquals("none", values["harnessError"],
                "collecting a sun must not throw with both mods installed: " + failureMessage(output, report))

        // 1) Both mods are resolved from the mods directory, with the language module they need.
        val installed = values["modsLoaded"]!!.split(",")
        assertTrue(installed.contains(COMPANION_MOD_ID),
                "the auto-collect mod must be installed next to this one, got " + values["modsLoaded"])
        assertTrue(installed.contains("speed-mod"), "got " + values["modsLoaded"])
        assertTrue(installed.contains("fabric-language-kotlin"), "got " + values["modsLoaded"])
        for (id in DEPENDENCY_MODS) {
            assertTrue(installed.contains(id), "mod " + id + " must be loaded, got " + values["modsLoaded"])
        }
        assertEquals("true", values["speedModLoaded"])
        assertEquals("true", values["autoCollectModLoaded"])
        assertEquals("true", values["speedModEntrypointRan"])

        // 2) A real level is running.
        assertEquals("true", values["boardCreated"])
        assertEquals("PLAYING", values["gameScene"])
        assertEquals("true", values["boardInWidgetTree"])

        // 3) The rate control is the same with the other mod installed - the numbers of the run above.
        assertEquals("true", values["vanilla"])
        assertEquals(FRAMES.toString(), values["ticksAt1x"], "1x must tick once per frame")
        assertEquals((FRAMES / 2).toString(), values["ticksAt0_5x"], "0.5x must tick every other frame")
        assertNear(FRAMES * 1.5, values["ticksAt1_5x"], 1, "1.5x must average one and a half ticks")
        assertEquals((FRAMES * 3).toString(), values["ticksAt3x"], "3x must tick three times per frame")
        assertEquals("0", values["ticksAtPause"], "pause must stop the board completely")
        assertEquals((FRAMES * 3).toString(), values["ticksAfterResumeAt3x"])
        assertEquals(FRAMES.toString(), values["ticksBackAt1x"])

        // 4) The auto-collect mod collected the suns and the coins: the counters the player sees
        //    (the seed bank's sun, the coin bank) moved by exactly what was put on the board, nothing
        //    was left behind - and it is the only thing that clicks a coin here, since the run without
        //    it collects nothing.
        assertEquals("3", values["sunsPlaced"])
        assertEquals("3", values["moneyPlaced"])
        assertEquals("90", values["sunValuePlaced"])
        assertEquals("106", values["moneyValuePlaced"])
        assertEquals("90", values["sunDelta"], "the three suns must be collected: 25 + 15 + 50")
        assertEquals("106", values["coinsDelta"], "the three coins must be collected: 1 + 5 + 100")
        assertEquals("106", values["coinsCollectedDelta"], "the board's own coin tally must count them")
        assertEquals("2", values["levelCoinsDelta"],
                "silver and gold are the only types the level tally counts, the diamond is not one of them")
        assertEquals("0", values["coinsLeftOnBoard"], "every sun and coin must be gone from the board")
        assertEquals("0", values["sunsLeftOnBoard"], "no sun may be left on the board")

        // 5) The controls of this mod still answer their clicks with the other mod installed: the
        //    other mod's toggle is hand-drawn into the same corner, and its mixin swallows the clicks
        //    that land on it, so a shared corner would show up exactly here.
        assertEquals("true", values["controlsCreated"])
        assertEquals("true", values["controlsVisible"])
        assertEquals("false", values["panelsOverlap"])
        assertEquals("0.5", values["multiplierAfterOneSlowClick"])
        assertEquals("0.5", values["multiplierAtLowerBound"])
        assertEquals("3.0", values["multiplierAtUpperBound"])
        assertEquals("1", values["plantDugUpByShovelClick"], "the board's own shovel click must still work")
        assertEquals("0", values["plantDugUpByButtonClick"], "a click on a button must not reach the shovel")
        assertEquals("0.5", values["multiplierMovedByButtonClick"], "the button must take the click instead")
        assertEquals("true", values["secondPlantStillThere"])
    }

    /**
     * Copies the sibling auto-collect mod's jar into the mods directory, so the run is the real
     * "both mods installed" case. The copy is verified (a build in flight could be caught mid-write)
     * and retried once; the candidate paths are where this workspace keeps that jar.
     */
    private fun installCompanionJar(theMods: Path): Path {
        var aSource: Path? = null
        for (aCandidate in COMPANION_JARS) {
            val aPath = Path.of(aCandidate).toAbsolutePath().normalize()
            if (Files.isRegularFile(aPath)) {
                aSource = aPath
                break
            }
        }

        if (aSource == null) {
            fail<Nothing>("the auto-collect mod's jar is not installed; looked for " + COMPANION_JARS +
                    " - this run is the combination of the two mods")
        }

        val aTarget = theMods.resolve(aSource.fileName)
        for (anAttempt in 0 until 2) {
            Files.copy(aSource, aTarget, StandardCopyOption.REPLACE_EXISTING)
            if (isReadableModJar(aTarget)) {
                System.out.println("[speed-mod-e2e] companion mod installed: " + aSource + " (" +
                        Files.size(aTarget) + " bytes)")
                return aTarget
            }

            Thread.sleep(1000L)
        }

        fail<Nothing>("the auto-collect mod's jar could not be copied readably (a build of that project may be " +
                "running): " + aSource)
    }

    /** Whether a file really is a mod jar: a readable zip carrying {@code fabric.mod.json}. */
    private fun isReadableModJar(theJar: Path): Boolean {
        try {
            JarFile(theJar.toFile()).use { jar ->
                return jar.getEntry("fabric.mod.json") != null
            }
        } catch (anException: IOException) {
            return false
        }
    }

    /** Asserts a reported integer is within {@code tolerance} of an expected value. */
    private fun assertNear(expected: Double, reported: String?, tolerance: Int, message: String) {
        assertNotNull(reported, message)
        assertEquals(expected, reported!!.toDouble(), tolerance.toDouble(), message)
    }

    /** The jar the loader will read: the mod's own classes and metadata, no bundled dependencies. */
    private fun assertModJarContents(modJar: Path) {
        val entries: List<String> = JarFile(modJar.toFile()).use { jar ->
            jar.stream().map { it.name }.collect(Collectors.toList())
        }

        assertTrue(entries.contains("fabric.mod.json"), "the jar must carry fabric.mod.json, got " + entries)
        assertTrue(entries.contains("speed-mod.mixins.json"), "the jar must carry the mixin config")
        assertTrue(entries.contains("cn/enaium/fabricpvz/speedmod/SpeedMod.class"), "the entry point class")
        assertTrue(entries.contains("cn/enaium/fabricpvz/speedmod/GameSpeed.class"))
        assertTrue(entries.contains("cn/enaium/fabricpvz/speedmod/SpeedControls.class"))
        assertTrue(entries.contains("cn/enaium/fabricpvz/speedmod/mixin/UpdateRateMixin.class"))
        assertTrue(entries.contains("cn/enaium/fabricpvz/speedmod/mixin/BoardMouseMixin.class"))
        assertTrue(entries.contains("cn/enaium/fabricpvz/speedmod/mixin/BoardKeyMixin.class"))
        // The mod's classes are Kotlin (compiled by this project's Kotlin compilation, whose module
        // metadata the jar carries), the Kotlin standard library is not: it is a launcher library
        // here (the game's launch libraries publish it), shared by every Kotlin mod of an installation.
        assertTrue(entries.stream().anyMatch { name -> name.startsWith("META-INF/") && name.endsWith(".kotlin_module") },
                "the jar must carry the Kotlin compilation metadata of the mod's own sources, got " + entries)
        assertTrue(entries.stream().noneMatch { name -> name.startsWith("kotlin/") },
                "a mod jar must bundle no stdlib classes, got " + entries)
    }

    /**
     * Starts the real Fabric Loader in a second JVM, exactly like the launcher would.
     *
     * @return the launcher's exit code
     */
    private fun launch(mods: Path, runDir: Path, report: Path, output: Path): Int {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = ArrayList<String>()
        command.add(java)
        command.add("--enable-native-access=ALL-UNNAMED")

        if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) {
            // SDL (LWJGL's bindings) needs the first thread.
            command.add("-XstartOnFirstThread")
        }

        command.add("-cp")
        command.add(forkClasspath())
        // The harness entry point lives in this project's test classes, which are not part of the
        // launcher's class path proper: the provider adds these entries to the loader's own class
        // loader, so the harness and the mod/game classes it inspects are all defined by the same
        // class loader (otherwise it would read a second copy of every static).
        command.add("-Dpvz.extraClasspath=" + harnessDirectory())
        // Development mode: the loader also discovers the mods that are on the class path (the API
        // modules resolved from mavenLocal). The mod under test is deliberately NOT there: it is only
        // in the mods directory, exactly like a jar a player copied into the installation.
        command.add("-Dfabric.development=true")
        // This port is the only provider on the class path.
        command.add("-Dfabric.skipMcProvider=true")
        // A crash must exit instead of opening the loader's Swing error window.
        command.add("-Dfabric.noGui=true")
        command.add("-Dpvz.gameDir=" + runDir)
        command.add("-Dfabric.modsFolder=" + mods)
        command.add("-Dpvz.game.mainClass=cn.enaium.fabricpvz.speedmod.test.SpeedModGameMain")
        command.add("-Dpvz.test.report=" + report)
        command.add(LAUNCHER_CLASS)
        command.add("-savedir")
        command.add(runDir.resolve("userdata").toString())

        val builder = ProcessBuilder(command)
                .directory(tmp.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true)
        System.out.println("[speed-mod-e2e] boot command:\n  " + command.joinToString(" \\\n  "))
        val process = builder.start()

        if (!process.waitFor(15L, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw AssertionError("Fabric Loader did not finish; launcher output:\n" + read(output))
        }

        System.out.println("[speed-mod-e2e] " + LAUNCHER_CLASS + " exited with " + process.exitValue())
        System.out.println("[speed-mod-e2e] launcher output:\n" + read(output))
        return process.exitValue()
    }

    /**
     * The class path the launcher runs with: the test classes, the loader, the API modules, the game
     * and the game provider - but not this project's own main output, so the mod can only come from
     * the mods directory.
     */
    private fun forkClasspath(): String {
        val classpath = System.getProperty(FORK_CLASSPATH_PROPERTY, "")
        if (classpath.isBlank()) {
            fail<Nothing>("-D" + FORK_CLASSPATH_PROPERTY + " is not set (run through Gradle)")
        }
        return classpath
    }

    /** The directory holding the harness classes (this test's own output directory). */
    private fun harnessDirectory(): String {
        try {
            return Path.of(SpeedModEndToEndTest::class.java.protectionDomain.codeSource.location.toURI())
                    .toString()
        } catch (e: Exception) {
            throw IllegalStateException("cannot locate the harness classes", e)
        }
    }

    private fun readReport(report: Path): Map<String, String> {
        assertTrue(Files.isRegularFile(report), "the harness must have written its report")
        return parse(Files.readString(report))
    }

    private fun parse(content: String): Map<String, String> {
        val values = HashMap<String, String>()
        for (line in content.split("\n")) {
            val equals = line.indexOf('=')
            if (equals > 0) {
                values[line.substring(0, equals)] = line.substring(equals + 1).trim()
            }
        }
        return values
    }

    private fun read(path: Path): String {
        return if (Files.exists(path)) Files.readString(path) else "<no output>"
    }

    private fun failureMessage(output: Path, report: Path): String {
        var tail = read(output)
        val keep = 4000
        if (tail.length > keep) {
            tail = "..." + tail.substring(tail.length - keep)
        }
        return "\n--- launcher output (tail) ---\n" + tail +
                "\n--- report ---\n" + (if (Files.exists(report)) Files.readString(report) else "<none>")
    }

    private fun requiredProperty(theName: String): String {
        val value = System.getProperty(theName, "")
        assertNotNull(value, "-D" + theName + " is required")
        assertTrue(!value.isBlank(), "-D" + theName + " is required")
        return value
    }
}
