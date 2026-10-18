package cn.enaium.fabricpvz.speedmod.test

import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.ImageID
import cn.enaium.fabricpvz.portable.lawn.LawnApp
import cn.enaium.fabricpvz.portable.lawn.ReanimationType
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.SeedType
import cn.enaium.fabricpvz.portable.lawn.ZenGarden
import cn.enaium.fabricpvz.portable.lawn.system.Music
import cn.enaium.fabricpvz.portable.lawn.system.ProfileMgr
import cn.enaium.fabricpvz.portable.lawn.system.ReanimatorCache
import cn.enaium.fabricpvz.portable.pvzp.PvzpResourceManager
import cn.enaium.fabricpvz.portable.pvzp.fx.EffectSystem
import cn.enaium.fabricpvz.portable.pvzp.fx.PvzpFoley
import cn.enaium.fabricpvz.portable.pvzp.fx.PvzpParticleDefinitions
import cn.enaium.fabricpvz.portable.pvzp.fx.TrailDefinitions
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimation
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimator
import cn.enaium.fabricpvz.portable.pvzp.reanim.ReanimatorTrackInstance
import cn.enaium.fabricpvz.portable.sexy.SexyAppBase
import cn.enaium.fabricpvz.portable.sexy.graphics.Font
import cn.enaium.fabricpvz.portable.sexy.graphics.ImageFont
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage
import cn.enaium.fabricpvz.portable.sexy.sound.DummyMusicInterface
import cn.enaium.fabricpvz.portable.sexy.sound.SoundInstance
import cn.enaium.fabricpvz.portable.sexy.sound.SoundManager

/**
 * Headless [LawnApp] fixture for the end-to-end run.
 *
 * <p>{@code LawnApp}'s constructor does no platform work, but everything downstream (boards, widgets,
 * entity constructors) reads the services {@code LawnApp::Init} and the loading thread create: the
 * profile manager, the effect system, the audio facades, the definition tables and the loaded image
 * and font resources. The game's own test suites fill those in with a shared fixture; a mod project
 * cannot use that fixture (it lives in the game's test sources, which are not published), so the
 * parts this harness needs are replicated here, exactly as the game's
 * {@code cn.enaium.fabricpvz.portable.test.HeadlessTestApp} does.
 */
class HeadlessLawn private constructor() {

    companion object {

        /** Gives a headless app everything the real init sequence would have created. */
        fun initialize(theApp: LawnApp) {
            // A synthetic resource set must not run the game's group extractor: it would try to bind
            // resources a test pak does not contain.
            PvzpResourceManager.gExtractResourcesByName = null

            LawnApp.gLawnApp = theApp

            if (theApp.mZenGarden == null) {
                theApp.mZenGarden = ZenGarden()
            }
            if (theApp.mProfileMgr == null) {
                theApp.mProfileMgr = ProfileMgr()
            }
            if (theApp.mPlayerInfo == null) {
                // LoadingThreadProc picks a profile in the real game; headless boards need one too.
                theApp.mPlayerInfo = theApp.mProfileMgr.addProfile("HeadlessTest")
            }
            if (theApp.mEffectSystem == null) {
                theApp.mEffectSystem = EffectSystem()
                theApp.mEffectSystem.effectSystemInitialize()
            }
            if (theApp.mReanimatorCache == null) {
                // A seed bank holding real packets draws each packet's plant preview through the runtime
                // cel cache (Plant.drawSeedType, Plant.java:3589-3600); the real init sequence creates it
                // the same way (LawnApp.java:1496-1497), and the harness's board now has chosen seeds
                // (chooseSeeds), so it has to exist before anything draws the bank.
                theApp.mReanimatorCache = ReanimatorCache()
                theApp.mReanimatorCache.reanimatorCacheInitialize()
            }
            if (theApp.mMusicInterface == null) {
                // Headless runs must not open an audio device.
                theApp.mMusicInterface = DummyMusicInterface()
            }
            if (theApp.mSoundManager == null) {
                theApp.mSoundManager = StubSoundManager()
            }
            if (theApp.mMusic == null) {
                theApp.mMusic = Music()
            }
            if (theApp.mSoundSystem == null) {
                theApp.mSoundSystem = PvzpFoley()
            }

            PvzpFoley.pvzpFoleyInitialize(PvzpFoley.gLawnFoleyParamArray, PvzpFoley.gLawnFoleyParamArray.size)
            Reanimator.reanimatorLoadDefinitions(Reanimator.gLawnReanimationArray, ReanimationType.NUM_REANIMS)
            PvzpParticleDefinitions.pvzpParticleLoadDefinitions(
                    PvzpParticleDefinitions.gLawnParticleArray,
                    PvzpParticleDefinitions.gLawnParticleArray.size)
            TrailDefinitions.trailLoadDefinitions(
                    TrailDefinitions.gLawnTrailArray, TrailDefinitions.gLawnTrailArray.size)

            bindStubImages(theApp)
            bindStubFonts(theApp)
            theApp.mDefaultFont = Resources.getFontById(Resources.FONT_PICO129_ID)
        }

        /**
         * Fills the seed bank the way the seed chooser does before the board's scene starts playing.
         *
         * <p>{@code Board.initLevel} leaves every packet at {@code SEED_NONE} ({@code Board.java:1076-1082})
         * for any level that chooses its seeds, and fills them only for the level that does not choose
         * them ({@code Board.java:1104-1109}); it is the chooser's own {@code closeSeedChooser} that gives
         * each slot of a choosing level its type ({@code SeedChooserScreen.java:1174-1191}), and the chooser
         * is part of the level intro - so in the game no bank slot is empty by the time the scene is
         * {@code SCENE_PLAYING}. A harness that calls {@code startPlaying} directly skips the intro and
         * leaves a board no player can be in; collecting a sun then reads every packet's cost
         * ({@code Coin.java:1013-1026}), and a packet still holding {@code SEED_NONE} sends
         * {@code Plant.getPlantDefinition(-1)} to {@code gPlantDefs[-1]} ({@code Plant.java:4282}) - the
         * same exception for the game's own click path as for a mod that clicks the sun.
         *
         * <p>So the harness chooses seeds here, one ordinary seed per bank slot, exactly as many as the
         * level's bank holds ({@code Board.getNumSeedsInBank}, {@code SEEDBANK_MAX} at most).
         */
        fun chooseSeeds(theBoard: Board) {
            val aSeeds = intArrayOf(
                SeedType.SEED_PEASHOOTER, SeedType.SEED_SUNFLOWER, SeedType.SEED_CHERRYBOMB,
                SeedType.SEED_WALLNUT, SeedType.SEED_REPEATER, SeedType.SEED_SNOWPEA,
                SeedType.SEED_CHOMPER, SeedType.SEED_POTATOMINE, SeedType.SEED_TANGLEKELP,
                SeedType.SEED_TORCHWOOD
            )
            if (theBoard.mSeedBank.mNumPackets > aSeeds.size) {
                throw IllegalStateException("a bank of " + theBoard.mSeedBank.mNumPackets +
                        " slots needs that many chosen seeds, the harness has " + aSeeds.size)
            }

            for (i in 0 until theBoard.mSeedBank.mNumPackets) {
                theBoard.mSeedBank.mSeedPackets[i].setPacketType(aSeeds[i], SeedType.SEED_NONE)
            }
        }

        /**
         * Binds a stub image for every image resource id, so drawing and measuring never NPEs.
         *
         * <p>The stone-button slices and the progress meter are the exception: the mod lays its controls
         * out from those images' own dimensions (the retail 9-slice is 36x46 + 46x46 + 35x46), so a 1x1
         * stub would scale every panel down to nothing in the headless run and the geometry assertions
         * would measure the stub instead of the layout. Their stubs therefore carry the retail art sizes,
         * the same values the real resources have in the window shot.
         */
        fun bindStubImages(theApp: SexyAppBase) {
            for (anEntry in Resources.ENTRIES) {
                if (anEntry.type() == Resources.ResourceType.IMAGE) {
                    val aStub = MemoryImage(theApp)
                    val aSize = stubImageSize(anEntry.id())
                    aStub.create(aSize[0], aSize[1])
                    Resources.setImageById(anEntry.id(), aStub)
                }
            }
        }

        /** The retail size of the images the controls' geometry depends on; 1x1 for everything else. */
        private fun stubImageSize(theImageId: Int): IntArray {
            if (theImageId == ImageID.IMAGE_BUTTON_LEFT || theImageId == ImageID.IMAGE_BUTTON_DOWN_LEFT) {
                return intArrayOf(36, 46)
            }
            if (theImageId == ImageID.IMAGE_BUTTON_MIDDLE || theImageId == ImageID.IMAGE_BUTTON_DOWN_MIDDLE) {
                return intArrayOf(46, 46)
            }
            if (theImageId == ImageID.IMAGE_BUTTON_RIGHT || theImageId == ImageID.IMAGE_BUTTON_DOWN_RIGHT) {
                return intArrayOf(35, 46)
            }
            if (theImageId == ImageID.IMAGE_FLAGMETER) {
                return intArrayOf(158, 33)
            }
            return intArrayOf(1, 1)
        }

        /** Binds a stub font for every font resource id. */
        fun bindStubFonts(theApp: SexyAppBase) {
            for (anEntry in Resources.ENTRIES) {
                if (anEntry.type() == Resources.ResourceType.FONT) {
                    Resources.setFontById(anEntry.id(), newStubFont(theApp))
                }
            }
        }

        /** A fresh headless bitmap font: measures as zero width, never dereferences a null glyph sheet. */
        fun newStubFont(theApp: SexyAppBase): Font {
            val aGlyphSheet = MemoryImage(theApp)
            aGlyphSheet.create(1, 8)
            return ImageFont(aGlyphSheet)
        }

        /** Track count of a stubbed reanimation; covers the largest retail zombie/plant reanimation. */
        const val STUB_REANIM_TRACK_COUNT = 64

        /**
         * Makes a reanimation that could not load retail {@code .reanim} data look loaded: entity code
         * indexes its track instances unconditionally, so the array has to exist and be fully
         * initialised.
         */
        fun stubLoadedTracks(theReanim: Reanimation?) {
            if (theReanim == null || theReanim.mTrackInstances != null) {
                return
            }

            theReanim.mTrackInstances = arrayOfNulls<ReanimatorTrackInstance>(STUB_REANIM_TRACK_COUNT)
            for (aTrackIndex in 0 until STUB_REANIM_TRACK_COUNT) {
                theReanim.mTrackInstances[aTrackIndex] = ReanimatorTrackInstance()
            }
        }
    }

    /** A [LawnApp] whose reanimations look loaded, the way the retail assets would. */
    class LoadedReanimApp : LawnApp() {

        override fun addReanimation(theX: Float, theY: Float, theRenderOrder: Int, theReanimationType: Int): Reanimation {
            val aReanim = super.addReanimation(theX, theY, theRenderOrder, theReanimationType)
            HeadlessLawn.stubLoadedTracks(aReanim)
            return aReanim
        }
    }

    /** Silent [SoundManager] double: headless runs must not open an audio device. */
    class StubSoundManager : SoundManager {

        private var mMasterVolume = 0.5525

        override fun initialized(): Boolean {
            return true
        }

        override fun loadSound(theSfxID: Long, theFilename: String): Boolean {
            return true
        }

        override fun loadSound(theFilename: String): Long {
            return 0L
        }

        override fun releaseSound(theSfxID: Long) {
        }

        override fun setVolume(theVolume: Double) {
        }

        override fun setBaseVolume(theSfxID: Long, theBaseVolume: Double): Boolean {
            return true
        }

        override fun setBasePan(theSfxID: Long, theBasePan: Int): Boolean {
            return true
        }

        override fun getSoundInstance(theSfxID: Long): SoundInstance {
            return StubSoundInstance()
        }

        override fun releaseSounds() {
        }

        override fun releaseChannels() {
        }

        override fun getMasterVolume(): Double {
            return mMasterVolume
        }

        override fun setMasterVolume(theVolume: Double) {
            mMasterVolume = theVolume
        }

        override fun flush() {
        }

        override fun stopAllSounds() {
        }

        override fun getFreeSoundId(): Long {
            return 0L
        }

        override fun getNumSounds(): Int {
            return 0
        }
    }

    /** Silent [SoundInstance] double. */
    class StubSoundInstance : SoundInstance {

        private var mVolume = 1.0
        private var mPlaying = false

        override fun release() {
        }

        override fun setBaseVolume(theBaseVolume: Double) {
        }

        override fun setBasePan(theBasePan: Int) {
        }

        override fun adjustPitch(theNumSteps: Double) {
        }

        override fun setVolume(theVolume: Double) {
            mVolume = theVolume
        }

        override fun setPan(thePosition: Int) {
        }

        override fun play(looping: Boolean, autoRelease: Boolean): Boolean {
            mPlaying = true
            return true
        }

        override fun stop() {
            mPlaying = false
        }

        override fun isPlaying(): Boolean {
            return mPlaying
        }

        override fun isReleased(): Boolean {
            return false
        }

        override fun getVolume(): Double {
            return mVolume
        }
    }
}
