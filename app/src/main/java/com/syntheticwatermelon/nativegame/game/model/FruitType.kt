package com.syntheticwatermelon.nativegame.game.model

import kotlin.random.Random

enum class FruitType(
    val displayName: String,
    val assetFileName: String,
    val radius: Float,
    val scoreValue: Int,
    val color: Int,
) {
    GRAPE("葡萄", "grape.png", 26f, 1, 0xFF8D63D2.toInt()),
    CHERRY("樱桃", "cherry.png", 34f, 3, 0xFFE45B73.toInt()),
    ORANGE("橘子", "orange.png", 44f, 6, 0xFFFFB04C.toInt()),
    LEMON("柠檬", "lemon.png", 52f, 10, 0xFFF6D94A.toInt()),
    KIWI("猕猴桃", "kiwi.png", 62f, 15, 0xFF7FB95A.toInt()),
    TOMATO("西红柿", "tomato.png", 74f, 21, 0xFFE2645A.toInt()),
    PEACH("桃", "peach.png", 88f, 28, 0xFFFFA08A.toInt()),
    PINEAPPLE("菠萝", "pineapple.png", 102f, 36, 0xFFF9C74F.toInt()),
    COCONUT("椰子", "coconut.png", 118f, 45, 0xFFC7A17A.toInt()),
    WATERMELON("西瓜", "watermelon.png", 138f, 55, 0xFF64B565.toInt()),
    GIANT_WATERMELON("大西瓜", "giant_watermelon.png", 160f, 68, 0xFF4EAD57.toInt());

    fun next(): FruitType? = entries.getOrNull(ordinal + 1)

    companion object {
        private val spawnPool = listOf(GRAPE, CHERRY, ORANGE, LEMON, KIWI)

        fun randomSpawn(random: Random): FruitType {
            return spawnPool[random.nextInt(spawnPool.size)]
        }
    }
}
