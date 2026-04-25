package com.syntheticwatermelon.nativegame.game.model

data class FruitBody(
    val id: Long,
    var type: FruitType,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var lastMergeTime: Float = -100f,
    var lastImpactTime: Float = -100f,
    var isSleeping: Boolean = false,
    var sleepTimer: Float = 0f,
    var contactCount: Int = 0,
    var supportCount: Int = 0,
    var rotationDegrees: Float = 0f,
    var previousX: Float = x,
    var previousY: Float = y,
) {
    val radius: Float
        get() = type.radius

    val mass: Float
        get() = radius * radius

    val inverseMass: Float
        get() = 1f / mass
}
