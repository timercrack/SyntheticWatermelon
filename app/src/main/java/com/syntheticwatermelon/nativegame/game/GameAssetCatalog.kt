package com.syntheticwatermelon.nativegame.game

import com.syntheticwatermelon.nativegame.game.model.FruitType

object GameAssetCatalog {
    private const val FRUIT_DIR = "game/fruit"
    private const val AUDIO_DIR = "game/audio"

    fun fruitAssetPath(type: FruitType): String = "$FRUIT_DIR/${type.assetFileName}"

    object Audio {
        const val CLICK = "$AUDIO_DIR/click.mp3"
        const val TOGGLE = "$AUDIO_DIR/toggle.mp3"
        const val MERGE = "$AUDIO_DIR/merge.mp3"
        const val SUCCESS = "$AUDIO_DIR/success.mp3"
        const val GAME_OVER = "$AUDIO_DIR/gameover.mp3"
    }
}
