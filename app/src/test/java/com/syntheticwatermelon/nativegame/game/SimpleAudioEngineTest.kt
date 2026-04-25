package com.syntheticwatermelon.nativegame.game

import com.syntheticwatermelon.nativegame.game.model.FruitType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleAudioEngineTest {
    @Test
    fun onlyGiantWatermelonTriggersCelebrationMerge() {
        assertFalse(SimpleAudioEngine.shouldCelebrateMerge(FruitType.GRAPE))
        assertFalse(SimpleAudioEngine.shouldCelebrateMerge(FruitType.WATERMELON))
        assertTrue(SimpleAudioEngine.shouldCelebrateMerge(FruitType.GIANT_WATERMELON))
    }
}