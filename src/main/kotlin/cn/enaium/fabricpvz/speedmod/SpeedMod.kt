package cn.enaium.fabricpvz.speedmod

import cn.enaium.fabricpvz.api.lawn.LawnEvents
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/**
 * Mod entry point (Fabric Loader's [ModInitializer]).
 *
 * Letting Fabric Loader instantiate this Kotlin class is what makes the whole mod Kotlin: the loader
 * reflectively calls the no-argument constructor of the entry point named in `fabric.mod.json`, so
 * the entry point is a `class` (not an `object`, which has only a private constructor and would have
 * to be named through the `kotlin` language adapter of fabric-language-kotlin - the language module
 * this mod requires, and the one whose nested kotlin-stdlib is the runtime the whole mod links
 * against).
 *
 * The mod is one speed setting ([GameSpeed]) plus the three controls that change it
 * ([SpeedControls]). Everything else is two hooks:
 *
 * - **drawing**: [LawnEvents.POST_DRAW], the game's own "the board finished drawing itself" call
 *   point (`BoardRender.draw` dispatches it after the object passes, which includes the top-right
 *   menu button the controls sit under). No mixin is needed for the picture.
 * - **input and the update rate**: the two mixins of `speedmod.mixin`, because the modding API
 *   deliberately exposes no hooks for "this click was consumed" and for "how many game updates does
 *   this frame run".
 *
 * [controlsOf] is the read-only lookup the input mixin uses: a board that was never drawn has no
 * controls, and a click on it must therefore reach the board untouched.
 *
 * Everything this class reports goes through the workspace's one logging stack (the SLF4J API, whose
 * Log4j backend the launcher publishes): the mod's log lines, Fabric Loader's own lines and the Mixin
 * subsystem's lines are one stream in one format, which is what makes a boot log readable.
 *
 * The companion is reached through its instance - Kotlin as `SpeedMod.controlsOf(board)`, the Java
 * side (the Mixin classes and the end-to-end harness) as `SpeedMod.Companion.controlsOf(board)` - so
 * no member of this class is a Java static.
 */
class SpeedMod : ModInitializer {

    override fun onInitialize() {
        initialized = true
        kotlinGreeting = greeting()
        LawnEvents.POST_DRAW.register(
            LawnEvents.PostDraw { theBoard, theGraphics -> drawControls(theBoard, theGraphics) },
        )
        LOGGER.info("speed controls registered (0.5x - 3x, below the menu button)")
        LOGGER.info(kotlinGreeting)
    }

    companion object {

        /** Fabric mod id, as declared in `fabric.mod.json`. */
        val MOD_ID: String = "speed-mod"

        /**
         * The mod's logger, named by the mod id: on the unified stack its name is the `[speed-mod]`
         * every line of this mod carries (`log4j2.xml` in fabric-provider formats it).
         */
        private val LOGGER = LoggerFactory.getLogger(MOD_ID)

        /** The controls of the last board that drew itself, or null; the mixins and tests read it. */
        private var lastControls: SpeedControls? = null

        /** Set by [onInitialize]: proves to a test that Fabric Loader ran this entry point. */
        var initialized: Boolean = false

        /**
         * What the mod's Kotlin code reported when the entry point ran: the toolchain's proof that a
         * Kotlin class, compiled by this project, executes under the real Fabric Loader with the
         * Kotlin standard library reachable at runtime.
         */
        var kotlinGreeting: String? = null

        /** What the mod logs on startup, and what the end-to-end harness reports. */
        private fun greeting(): String =
            "[$MOD_ID] Kotlin ${KotlinVersion.CURRENT} class running " +
                "(stdlib classes resolved from the launch class path, not from the mod jar: " +
                "${stdlibLocation()})"

        /**
         * Where the Kotlin standard library this class links against actually lives: the jar the
         * launcher put on the class path, which for a mod of this workspace is the nested
         * `kotlin-stdlib` of fabric-language-kotlin - and it is what makes the mod jar itself carry no
         * `kotlin/` classes. Logged so a boot log - or the end-to-end harness - shows it.
         */
        private fun stdlibLocation(): String =
            KotlinVersion::class.java.protectionDomain?.codeSource?.location?.toString() ?: "<bootstrap>"

        /** The game's post-draw event: the board is fully drawn, so the controls go on top of it. */
        private fun drawControls(theBoard: Board, theGraphics: Graphics) {
            var aControls = controlsOf(theBoard)
            if (aControls == null) {
                // First draw of this board (or a new board after a level change): the buttons need the
                // board's app, so they are created here rather than at mod init time (the game does not
                // exist yet then). The previous board's controls go away with it.
                clearControls()
                aControls = SpeedControls(theBoard)
                lastControls = aControls
                GameSpeed.get().resetCredit()
            }

            if (aControls.isVisible()) {
                aControls.draw(theGraphics)
            }
        }

        /**
         * The controls of a board, or null when this board never drew itself (and therefore never
         * showed controls). Used by the input mixin, which must not create anything.
         */
        fun controlsOf(theBoard: Board): SpeedControls? {
            val aControls = lastControls
            return if (aControls != null && aControls.board() === theBoard) aControls else null
        }

        /** The controls built for the last drawn board, or null; used by tests. */
        fun controls(): SpeedControls? = lastControls

        /** Drops the controls of a board that is gone, cancelling a press that was in flight. */
        fun clearControls() {
            lastControls?.cancelPress()
            lastControls = null
        }
    }
}
