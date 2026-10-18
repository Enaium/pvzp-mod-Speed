# speed-mod

A game-speed mod for the PvZ port: a row of controls - the `1.0x` readout and three buttons - to
the left of the level progress meter (or, on a board without one, below the game's own **Menu**
button), that set the game to **0.5x - 3x**, pause it, and pause it again with the space key.

It is a plain Fabric mod: `fabric.mod.json`, a `ModInitializer`, three small Mixins, and a jar that is
loaded from `<gamedir>/mods` by the real Fabric Loader through
the game provider the game jar itself carries (`cn.enaium.fabricpvz.provider`).

The mod's own code is **Kotlin** (`src/main/kotlin`, compiled by the workspace toolchain plugin); the
three Mixins stay Java (`src/main/java/.../mixin`), because a Mixin configuration is a Java class
annotated with `@Mixin` and Mixin's own processor reads it as such. The end-to-end harness is Java
too. The Kotlin side keeps the entry point a `class` with a no-argument constructor - Fabric Loader
instantiates a `ModInitializer` reflectively, which a Kotlin `object` (private constructor) could not
satisfy without a language adapter this workspace does not ship. The Kotlin standard library is a
launcher library (the game's launch libraries put one copy on the launch class path), so the mod jar carries
its own classes and resources only - never `kotlin/`.

| mod id | version | entry point |
|---|---|---|
| `speed-mod` | `1.1.0` | `cn.enaium.fabricpvz.speedmod.SpeedMod` |

## Controls

```
                        ┌──────────────┐
                        │    Menu      │  ← the game's own button (Board.mMenuButton)
                        └──────────────┘
                           ┌───┬───┐
                           │ < │ > │       ← row one: slower, faster
                           └───┴───┘
                           ┌───┬───────┐
                           │ = │ 1.0x  │    ← row two: pause, readout
                           └───┴───────┘
```

The controls sit in two rows below the Menu button (and below the store button when one is drawn),
right-aligned to the Menu button's drawn right edge and drawn at 0.75 scale (53x34 buttons, 87x34
readout, 6-pixel gaps): row one is the slower and faster buttons (`<` `>`), row two is the pause
button and the multiplier readout (`=` `1.0x`).

| panel | effect |
|---|---|
| `<` | one step slower: 1.0x → 0.5x, never below **0.5x** |
| `=` | pauses the game (the board runs zero ticks); the label becomes `>` while paused, and pressing it again resumes |
| `>` | one step faster: 1.0x → 3.0x, never above **3.0x** |
| `1.0x` | the multiplier the game is running at (readout, not a button) |

The **space** key toggles the same pause (zero ticks, no dialog); the game's own pause dialog is
never opened by space while the mod is installed.

The panels are drawn with the game's own stone-button rendering (`GameButton` with
`mDrawStoneButton`, the same look as the Menu button), so they read as part of the game's UI. The
row is laid out from the game's own art every time it is drawn: from the meter's position when the
board draws one, from the Menu button's own position otherwise (Zen Garden and the upsell move the
button and add a store button above them; the Zen Garden fade slides it off screen). The controls
appear when the Menu button is on screen and are not drawn while the game's own full-screen dialogs
are up.

### Why the panels are the sizes they are

`GameButton.drawStoneButton` (`GameButton.java:41-79`) draws a stone panel as the retail 9-slice at
the slices' own size - left `36x46`, middle `46x46`, right `35x46` - repeating the middle slice
`(w - 36 - 35) / 46` times and using the requested height only to place the label, never for the
drawn bounds. A stone panel is therefore 71, 117, 163, 209 … wide and always 46 tall. Asking for any
other rectangle (38x20 was the first attempt) draws art that is *wider and taller* than the
rectangle: the panels then overlap each other and their labels are centred for a box that is not
what is drawn.

The mod therefore lays its panels out from that arithmetic (the private `panelWidth` /
`drawnWidth` helpers), then draws the whole row at **0.75 scale** with a scale origin at each
panel's top-left corner: a 71x46 button becomes 53x34, the 117x46 readout (one middle slice) 87x34.
The hit rectangles are the scaled rectangles, the panels are spaced 6 pixels apart, and the labels
are drawn by the mod itself (a graphics scale does not scale strings) centred inside the scaled
rectangle with the stone label font.

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
own handler, so a press on `<` can never also plant, shovel or pick up anything. It brackets that
with the board's own `mIgnoreMouseUp` convention (the flag `BoardInput` uses for "discard the
release of a press I swallowed"), so a press that starts on a button and ends on the lawn cannot
half-reach the board either. Clicks outside the controls go through unchanged; clicks over a dialog
never arrive here at all (the dialog widget is above the board, and `GameButton.isMouseOver` also
answers "no" while a dialog is open).

### How space pauses

`speedmod.mixin.BoardKeyMixin` injects at the head of `BoardInput.keyDown` (`Board.cpp:7628`,
`Board::KeyDown`). Where the game's own space branch would call `LawnApp.doPauseDialog()`, the mixin
toggles `GameSpeed`'s pause flag instead and cancels the call, so the pause dialog never opens: the
board runs zero ticks and space again resumes it. The mixin keeps the game's own guards (playing
scene, no scary-potter Dave conversation, not the Zen Garden or Tree of Wisdom, `canPauseNow()`),
so every case the game does not pause on is left to the game.

## Prerequisites

This mod runs on [PvZ-Portable](https://github.com/FabricPvZ/pvz-portable) and needs two more mods next to it:

- **PvZ-Portable** - the engine (the game itself). The repository: <https://github.com/FabricPvZ/pvz-portable>.
- **fabric-language-kotlin** - this mod is written in Kotlin and carries no Kotlin runtime of its own.
  Download: [Modrinth](https://modrinth.com/mod/fabric-language-kotlin) ·
  [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-language-kotlin) ·
  [Fabric's maven](https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/).
- **fabric-api** (this workspace's API) - the events the controls and the pause are built on.
  Download: the [GitHub releases](https://github.com/FabricPvZ/fabric-api/releases).

## Installing into the game

Drop the jar into the game directory's `mods/`, e.g.:

```bash
cp build/libs/speed-mod-1.1.0.jar /path/to/PvZ-Portable/run/mods/
```

The API it depends on travels as **one** jar - `fabric-api-1.2.1.jar`, whose nested jars under
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
