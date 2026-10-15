package cn.enaium.fabricpvz.speedmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.speedmod.SpeedControls;
import cn.enaium.fabricpvz.speedmod.SpeedMod;

/**
 * Routes presses and releases over the speed controls away from the board.
 *
 * <p>The board is a widget that covers the screen, and it routes its own hand-drawn buttons
 * ({@code mMenuButton}, {@code mStoreButton}) itself, inside {@code BoardInput.mouseDown} /
 * {@code BoardInput.mouseUp}. The speed buttons are hand-drawn the same way, so they are serviced
 * the same way: this mixin asks the controls first and cancels the board's handler when the click
 * landed on a button, which is the only thing standing between "the player pressed slow" and "the
 * player shovelled a plant in the corner of the lawn".
 *
 * <p>The cancellation is paired with the board's own {@code mIgnoreMouseUp} convention (the flag
 * {@code BoardInput} uses for "discard the release of a press I swallowed"), so a press that starts
 * on a button and ends on the lawn cannot half-reach the board either. The framework's widget
 * manager is not touched at all: clicks outside the controls keep flowing through the board exactly
 * as they always did, and clicks over a dialog are never delivered here in the first place (the
 * dialog widget is above the board, and {@code GameButton.isMouseOver} also answers "no" while a
 * dialog is open).
 *
 * <p>Both callbacks are injector-required (the mixin config keeps the default {@code require}: the
 * mod asserts the game still has these two methods, so a game change fails loudly at load instead of
 * silently dropping input handling).
 */
@Mixin(Board.class)
public abstract class BoardMouseMixin {

    /** {@code Board::MouseDown} (Board.java:4092) - the click's first stop. */
    @Inject(method = "mouseDown(III)V", at = @At("HEAD"), cancellable = true)
    private void speedmod$mouseDown(int theX, int theY, int theClickCount, CallbackInfo ci) {
        Board self = (Board) (Object) this;
        SpeedControls aControls = SpeedMod.Companion.controlsOf(self);
        if (aControls == null || !aControls.mouseDown(theX, theY, theClickCount)) {
            return;
        }

        self.mIgnoreMouseUp = true;
        ci.cancel();
    }

    /** {@code Board::MouseUp} (Board.java:4108) - the release of a click the controls accepted. */
    @Inject(method = "mouseUp(III)V", at = @At("HEAD"), cancellable = true)
    private void speedmod$mouseUp(int theX, int theY, int theClickCount, CallbackInfo ci) {
        Board self = (Board) (Object) this;
        SpeedControls aControls = SpeedMod.Companion.controlsOf(self);
        if (aControls == null || !aControls.mouseUp(theX, theY, theClickCount)) {
            return;
        }

        self.mIgnoreMouseUp = false;
        ci.cancel();
    }
}
