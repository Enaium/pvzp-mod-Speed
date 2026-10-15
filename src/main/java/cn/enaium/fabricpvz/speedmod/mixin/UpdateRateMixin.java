package cn.enaium.fabricpvz.speedmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.enaium.fabricpvz.portable.lawn.LawnApp;
import cn.enaium.fabricpvz.speedmod.GameSpeed;

/**
 * Runs the game's frame update the number of times the selected speed asks for.
 *
 * <h2>Why this call point</h2>
 * {@code LawnApp::UpdateFrames} (LawnApp.java:1357) is the game's own rate lever: the framework calls
 * it once per frame, and its body runs {@code aUpdateCount} game updates - 1 by default, 0/4 for the
 * game's slow-mo debug key, 20 for fast-mo, all through {@code super.updateFrames()}
 * ({@code SexyAppBase::UpdateFrames}, SexyAppBase.java:716), which is what reaches the widget tree and
 * the board's {@code update()}. Multiplying at this level therefore changes <em>how many ticks a
 * frame runs</em> and nothing else: every tick is the unchanged, whole game update, so the board's
 * per-tick behaviour, the RNG streams and the save format are untouched. Pacing it below (for example
 * by lengthening {@code mFrameTime} or raising {@code mUpdateMultiplier}) would instead stretch wall
 * time between ticks, which is not what a speed setting should do to a game whose simulation is
 * counted in ticks.
 *
 * <h2>How</h2>
 * With no speed selected the handler returns immediately and the game's method runs exactly as
 * written - a vanilla run is byte-for-byte the vanilla code path. Otherwise the handler cancels the
 * call and replays it as many times as {@link GameSpeed#beginFrame()} returns, with
 * {@link GameSpeed#isReplaying()} marking the replayed calls so they run the game's body without
 * being counted again (0 replays the frame's game update not at all: the pause button; 3 replays it
 * three times: 3x). Replaying the game's own method - rather than reimplementing its body here -
 * keeps the frame bookkeeping (delete queues, music update, game-end check) in the game's hands, and
 * those are idempotent: {@code updatePlayTimeStats} only counts whole elapsed seconds and
 * {@code checkForGameEnd} only fires while the board reports a finished level.
 *
 * <p>Outside a level ({@code mBoard == null}) the handler does nothing at all, so the menus, the
 * loading screen and the dialogs keep their own pace whatever the speed is set to.
 */
@Mixin(LawnApp.class)
public abstract class UpdateRateMixin {

    @Inject(method = "updateFrames()V", at = @At("HEAD"), cancellable = true)
    private void speedmod$scaleUpdateRate(CallbackInfo ci) {
        GameSpeed aSpeed = GameSpeed.Companion.get();
        if (aSpeed.isReplaying()) {
            return; // one of the replayed calls: run the game's own body
        }

        LawnApp self = (LawnApp) (Object) this;
        if (self.mBoard == null || aSpeed.isVanilla()) {
            return; // menus, loading, and the plain 1x case: the game's code path, untouched
        }

        int aTicks = aSpeed.beginFrame();
        if (aTicks == 1) {
            return; // one tick is the normal frame: no replay needed
        }

        ci.cancel();
        aSpeed.setReplaying(true);
        try {
            for (int i = 0; i < aTicks; i++) {
                self.updateFrames();
            }
        } finally {
            aSpeed.setReplaying(false);
        }
    }
}
