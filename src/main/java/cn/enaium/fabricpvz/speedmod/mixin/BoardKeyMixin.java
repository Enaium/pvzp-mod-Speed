package cn.enaium.fabricpvz.speedmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.portable.lawn.GameMode;
import cn.enaium.fabricpvz.portable.lawn.GameScenes;
import cn.enaium.fabricpvz.portable.lawn.Resources;
import cn.enaium.fabricpvz.portable.sexy.KeyCode;
import cn.enaium.fabricpvz.speedmod.GameSpeed;

/**
 * Turns the space key into the mod's own pause toggle instead of the game's pause dialog.
 *
 * <p>{@code BoardInput.keyDown} handles space itself and opens the game's pause dialog
 * ({@code LawnApp.doPauseDialog()}). The mod's pause is a different state - the board runs zero
 * ticks but no dialog is put up - so space has to mean the mod's pause everywhere the game's own
 * space would have paused. This injector runs at the head of {@code keyDown} and cancels the call
 * when it toggles, which is what keeps the dialog from ever appearing.
 *
 * <p>The condition mirrors the game's own space branch: the scene must be playing, Dave must not be
 * in the middle of a scary potter conversation, the zen garden and the tree of wisdom advance Dave's
 * own dialog on space and are left alone, and the game must consider itself pausable
 * ({@code LawnApp.canPauseNow()}: no other dialog, no fade, no seed chooser, no Dave).
 *
 * <p>Injector-required, like the mod's other mixins: the mod asserts the game still has this method,
 * so a game change fails loudly at load instead of silently bringing the pause dialog back.
 */
@Mixin(targets = "cn.enaium.fabricpvz.portable.lawn.BoardInput")
public abstract class BoardKeyMixin {

    /** {@code BoardInput::keyDown} (Board.cpp:7628, Board::KeyDown). */
    @Inject(method = "keyDown", at = @At("HEAD"), cancellable = true)
    private static void speedmod$spaceToggles(Board aBoard, int theKey, CallbackInfo ci) {
        if (theKey != KeyCode.KEYCODE_SPACE) {
            return;
        }

        if (aBoard.mApp.mGameScene != GameScenes.SCENE_PLAYING) {
            return;
        }

        if (aBoard.isScaryPotterDaveTalking() && aBoard.mApp.mCrazyDaveMessageIndex != -1) {
            return;
        }

        if (aBoard.mApp.mGameMode == GameMode.GAMEMODE_CHALLENGE_ZEN_GARDEN
                || aBoard.mApp.mGameMode == GameMode.GAMEMODE_TREE_OF_WISDOM) {
            return;
        }

        if (!aBoard.mApp.canPauseNow()) {
            return;
        }

        GameSpeed.Companion.get().togglePause();
        if (GameSpeed.Companion.get().paused()) {
            aBoard.mApp.playSample(Resources.SOUND_PAUSE_ID);
        }

        ci.cancel();
    }
}
