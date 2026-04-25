package com.syntheticwatermelon.nativegame.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.syntheticwatermelon.nativegame.game.model.FruitType
import java.util.concurrent.ConcurrentHashMap

class SimpleAudioEngine(context: Context) {
    private enum class Effect(val assetPath: String) {
        CLICK(GameAssetCatalog.Audio.CLICK),
        TOGGLE(GameAssetCatalog.Audio.TOGGLE),
        MERGE(GameAssetCatalog.Audio.MERGE),
        SUCCESS(GameAssetCatalog.Audio.SUCCESS),
        GAME_OVER(GameAssetCatalog.Audio.GAME_OVER),
    }

    private val assetManager = context.applicationContext.assets
    private val readySoundIds = ConcurrentHashMap.newKeySet<Int>()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundIds: Map<Effect, Int>

    private var muted = false
    private var hostPaused = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                readySoundIds += sampleId
            }
        }
        soundIds = Effect.entries.associateWith(::loadSound)
    }

    internal companion object {
        fun shouldCelebrateMerge(type: FruitType): Boolean {
            return type == FruitType.GIANT_WATERMELON
        }
    }

    fun isMuted(): Boolean = muted

    fun toggleMuted(): Boolean {
        if (muted) {
            muted = false
            playEffect(Effect.TOGGLE)
            return false
        }

        playEffect(Effect.TOGGLE)
        muted = true
        return true
    }

    fun onHostPaused() {
        hostPaused = true
    }

    fun onHostResumed() {
        hostPaused = false
    }

    fun playClick() {
        playEffect(Effect.CLICK)
    }

    fun playMerge(type: FruitType) {
        val effect = if (shouldCelebrateMerge(type)) {
            Effect.SUCCESS
        } else {
            Effect.MERGE
        }
        playEffect(effect, rate = (1f + type.ordinal * 0.03f).coerceAtMost(1.45f))
    }

    fun playGameOver() {
        playEffect(Effect.GAME_OVER)
    }

    fun release() {
        soundPool.release()
    }

    private fun loadSound(effect: Effect): Int {
        return assetManager.openFd(effect.assetPath).use { assetFileDescriptor ->
            soundPool.load(assetFileDescriptor, 1)
        }
    }

    private fun playEffect(effect: Effect, volume: Float = 1f, rate: Float = 1f) {
        if (muted || hostPaused) {
            return
        }

        val soundId = soundIds.getValue(effect)
        if (soundId in readySoundIds) {
            soundPool.play(soundId, volume, volume, 1, 0, rate)
        }
    }
}
