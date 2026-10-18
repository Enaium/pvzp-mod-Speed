# speed-mod

A game-speed mod for the PvZ port: three buttons in the top-right corner of the lawn, directly
below the game's own **Menu** button, that set the game to **0.5x - 3x** or pause it.

It is a plain Fabric mod: `fabric.mod.json`, a `ModInitializer`, two small Mixins, and a jar that is
loaded from `<gamedir>/mods` by the real Fabric Loader through
the game provider the game jar itself carries (`cn.enaium.fabricpvz.provider`).

The mod's own code is **Kotlin** (`src/main/kotlin`, compiled by the workspace toolchain plugin); the
two Mixins stay Java (`src/main/java/.../mixin`), because a Mixin configuration is a Java class
annotated with `@Mixin` and Mixin's own processor reads it as such. The end-to-end harness is Java
too. The Kotlin side keeps the entry point a `class` with a no-argument constructor - Fabric Loader
instantiates a `ModInitializer` reflectively, which a Kotlin `object` (private constructor) could not
satisfy without a language adapter this workspace does not ship. The Kotlin standard library is a
launcher library (the game's launch libraries put one copy on the launch class path), so the mod jar carries
its own classes and resources only - never `kotlin/`.

| mod id | version | entry point |
|---|---|---|
| `speed-mod` | `1.0.0` | `cn.enaium.fabricpvz.speedmod.SpeedMod` |

## Controls

```
                                  ┌──────────────┐
                                  │    Menu      │  ← the game's own button (Board.mMenuButton)
                                  └──────────────┘
┌────┬────┬────┐
│ << │ =  │ >> │  ← slow, pause/resume (">"), fast
└────┴────┴────┘
┌──────────────┐
│    1.0x      │  ← the multiplier the game is running at (readout, not a button)
└──────────────┘
```

The button row is right-aligned to the Menu button and sits 4 pixels below its art, with 4-pixel gaps
between the buttons; the readout is centred under the row, 4 pixels below it.

| panel | effect |
|---|---|
| `<<` | one step slower: 1.0x → 0.5x, never below **0.5x** |
| `=` | pauses the game (the board runs zero ticks); the label becomes `>` while paused, and pressing it again resumes |
| `>>` | one step faster: 1.0x → 3.0x, never above **3.0x** |
| `1.0x` | the multiplier the game is running at (readout, not a button) |

The panels are drawn with the game's own stone-button rendering (`GameButton` with
`mDrawStoneButton`, the same look as the Menu button), so they read as part of the game's UI. The
row follows the Menu button in every mode (Zen Garden and the upsell move the button and add a
store button above them; the Zen Garden fade slides it off screen) because it is laid out from the
Menu button's own position and art every time it is drawn. The controls appear when the Menu button
is on screen and are not drawn while the game's own full-screen dialogs are up.

### Why the panels are the sizes they are

`GameButton.drawStoneButton` (`GameButton.java:41-79`) draws a stone panel as the retail 9-slice at
the slices' own size - left `36x46`, middle `46x46`, right `35x46` - repeating the middle slice
`(w - 36 - 35) / 46` times and using the requested height only to place the label, never for the
drawn bounds. A stone panel is therefore 71, 117, 163, 209 … wide and always 46 tall. Asking for any
other rectangle (38x20 was the first attempt) draws art that is *wider and taller* than the
rectangle: the panels then overlap each other and the readout under them, and the labels are
centred for a 20-pixel box inside 46 pixels of art, i.e. visibly off-centre.

The mod therefore lays its panels out from that arithmetic (`SpeedControls.panelWidth` /
`SpeedControls.drawnWidth`): the value panel is 163 wide (two middle slices), each button 71 wide
(no middle slice), everything 46 tall - so a panel's rectangle *is* the rectangle the game draws,
the hit test uses the same rectangle (a click lands exactly on the panel the player sees), no panel
can reach into another, and the game's own label centring is exact: the readout's label is centred
like the label of the game's own Menu button (measured on the rendered frame: 0.5 px horizontal
offset for both).

The labels are single glyphs on purpose: the stone label font is a retail bitmap font with a
limited glyph set (it has no `|`, so a `||` label measures zero wide and draws nothing).

## How it works

### Where the speed is applied

The framework calls `LawnApp.updateFrames()` once per frame, and one call runs exactly one game tick:
`LawnApp.updateFrames` (`LawnApp.java:1357`) runs an `aUpdateCount`-times loop over
`SexyAppBase.updateFrames()` (`SexyAppBase.java:716`), which is what reaches the widget tree and
therefore `Board.update()`. The loop is the game's own rate lever - its slow-mo/fast-mo debug keys
drive the same variable (`aUpdateCount = 0` for slow-mo, `20` for fast-mo).

`mixin/UpdateRateMixin` injects at the head of that method. At 1x (and outside a level, i.e. with
`mBoard == null`) it returns immediately, so a vanilla run executes the game's own code path
unchanged. Otherwise it cancels the call and re-runs the game's own method as many times as
`GameSpeed.beginFrame()` returns, with the replayed calls marked so they run the body once each:
`0` for pause, `3` for 3x. A fractional multiplier is a credit: 0.5x accumulates half a tick per
frame and spends one every other frame, 1.5x alternates 1, 2, 1, 2 - a true average of 1.5 ticks per
frame rather than a rounding of it.

Multiplying here changes *how many ticks a frame runs* and nothing else: every tick is the
unchanged, whole game update, so per-tick gameplay, the RNG streams and the save format are
untouched. (Pacing the frame loop instead - `mFrameTime`, `mUpdateMultiplier` - stretches wall time
between ticks, which is not what a speed setting should do to a game whose simulation is counted in
ticks.) Replaying the game's own method rather than reimplementing its body keeps the per-frame
bookkeeping in the game's hands, and those parts are idempotent: `updatePlayTimeStats` only counts
whole elapsed seconds and `checkForGameEnd` only fires while the board reports a finished level.

### Where the buttons are drawn

`speedmod.SpeedMod` registers `LawnEvents.POST_DRAW` and draws the controls there. That is the
game's own "the board finished drawing itself" call point: `BoardRender.draw` dispatches it after
the object passes (`BoardRender.java:1429`), which includes the top-right UI the Menu button is
drawn by. No mixin is needed for the picture.

### How a click reaches them

`speedmod.mixin.BoardMouseMixin` injects at the head of `Board.mouseDown` (`Board.java:4092`) and
`Board.mouseUp` (`Board.java:4108`) - the two methods the framework's widget manager calls for a
click on the lawn. If the click landed on a control, the mixin services it and cancels the board's
own handler, so a press on `<<` can never also plant, shovel or pick up anything. It brackets that
with the board's own `mIgnoreMouseUp` convention (the flag `BoardInput` uses for "discard the
release of a press I swallowed"), so a press that starts on a button and ends on the lawn cannot
half-reach the board either. Clicks outside the controls go through unchanged; clicks over a dialog
never arrive here at all (the dialog widget is above the board, and `GameButton.isMouseOver` also
answers "no" while a dialog is open).

## Building

```bash
./gradlew build                # jar + the end-to-end test
./gradlew build publishToMavenLocal   # also publishes the mod jar (cn.enaium.fabricpvz:speed-mod:1.0.0)
```

The build file is the workspace's mod toolchain plugin plus the coordinates
(`plugins { id("cn.enaium.fabricpvz.loom") version "1.0.5" }`): the plugin resolves from the Gradle
Plugin Portal, which serves that version (`(cd ../loom && ./gradlew publishPlugins)`); the settings
file keeps `mavenLocal()` after the portal only as a fallback for a version the portal does not
serve yet. It supplies the real Fabric
Loader, Mixin, ASM, the API and the game on the compile class path, applies the Kotlin JVM plugin
(compiling `src/main/kotlin` at the game's class file version, with `kotlin-stdlib` `compileOnly`
because `fabric-language-kotlin` provides it at runtime), so this project declares no dependency by
hand. The mod depends on the API module `api-events` (the one that owns the events it uses), not on
the aggregate API mod, and on `fabric-language-kotlin` (the language module whose nested
`kotlin-stdlib` is the runtime of this mod's Kotlin code); it logs through SLF4J.

## Verifying

```bash
./gradlew test                 # end-to-end: the real Fabric Loader boots the jar from a mods/ dir
./gradlew dumpWindow           # real video driver: screenshots of the controls, see below
```

`./gradlew test` does what a launcher does: it copies the built jar into a real `mods/` directory and
starts `net.fabricmc.loader.impl.launch.knot.KnotClient` in a second JVM. The harness
(`src/test/java/.../test/SpeedModGameMain.java`) then boots a headless board, drives the game's own
frame entry point and reports tick counts, button geometry and click routing. The assertions cover
the tick rate at 0.5x/1x/1.5x/3x/paused, the button rectangles (three buttons in a row below the
Menu button), the range clamps, and that a click on a button changes the speed while the board's own
shovel click still digs up a plant.

The test runs that harness twice. The first run has this mod alone, and nothing collects the suns and
coins the harness puts on the board. The second run copies the sibling auto-collect mod's jar next to
this one - an installation with both mods - and there the same harness run must end without an
exception, with the game's own counters moved by exactly the value of those suns and coins (+90 sun
and +106 coins for the three suns and three coins the harness places), and with the tick numbers of
the first run. Starting a level headlessly skips the level intro, so the harness also does what the
intro does: it gives the seed bank its seeds (`HeadlessLawn.chooseSeeds`, the way the seed chooser
does before the scene plays). Without them the board is in a state no player can be in, and the first
click on a sun - by a player or by a mod - reads a seed packet that holds no seed.

`./gradlew dumpWindow` runs the same harness shape with the real video driver and writes
`run/build/window-dump/speed-controls-*.png`: the controls at 1x, after a click on the slow button
and after a click on the pause button. It also draws the controls a second time into an off-screen
image (`speed-controls-offscreen.png`) and prints, per panel, the pixels the game actually drew
versus the panel's rectangle and the bounding box of the label - the drawn panel has to be exactly
the rectangle the controls hit-test with, and the label's centre has to sit on the panel's.

## Installing into the game

Drop the jar into the game directory's `mods/`, e.g.:

```bash
cp build/libs/speed-mod-1.0.0.jar /path/to/PvZ-Portable/run/mods/
```

The API it depends on travels as **one** jar - `fabric-api-1.0.0.jar`, whose nested jars under
`META-INF/jars/` are the five modules (`api-events` among them; see `loom/README.md`,
"Jar-in-jar") - so that is the second file of an installation, and the game's own zip ships none
(a player creates `mods/` and fills it).

The game is launched by the real Fabric Loader through
`net.fabricmc.loader.impl.launch.knot.KnotClient` together with
the game provider, which registers the game as a built-in mod; the loader
discovers the jar at startup and runs `SpeedMod::onInitialize`. With the mod absent the game runs
exactly as it does today.

## License

MIT (see `LICENSE`). The game port itself (`PvZ-Portable`) is LGPL-3.0-or-later with the PopCap
framework licence.
