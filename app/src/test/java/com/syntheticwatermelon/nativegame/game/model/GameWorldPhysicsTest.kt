package com.syntheticwatermelon.nativegame.game.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GameWorldPhysicsTest {
    @Test
    fun fruitOnFruit_rollsAlongTiltedSupportInsteadOfSticking() {
        val world = GameWorld()
        world.startGame()
        world.debugSetGravity(920f, 2000f)

        val base = FruitBody(
            id = 1L,
            type = FruitType.COCONUT,
            x = 360f,
            y = world.binBottom - FruitType.COCONUT.radius,
        )
        val top = FruitBody(
            id = 2L,
            type = FruitType.LEMON,
            x = base.x - 70f,
            y = base.y - sqrt((base.radius + FruitType.LEMON.radius - 0.8f) * (base.radius + FruitType.LEMON.radius - 0.8f) - 70f * 70f),
        )
        world.debugSetFruits(listOf(base, top))
        val startX = top.x

        repeat(90) {
            world.update(1f / 120f)
        }

        val movedTop = world.fruits.first { it.id == 2L }
        assertTrue(movedTop.x > startX + 18f)
        assertFalse(movedTop.isSleeping)
        assertTrue(abs(movedTop.rotationDegrees) > 6f)
    }

    @Test
    fun glancingCollision_keepsFruitFallingInsteadOfEnteringSlowMotion() {
        val world = GameWorld()
        world.startGame()
        world.debugSetGravity(0f, GameWorld.BASE_GRAVITY)

        val base = FruitBody(
            id = 1L,
            type = FruitType.PEACH,
            x = 390f,
            y = world.binBottom - FruitType.PEACH.radius,
        )
        val falling = FruitBody(
            id = 2L,
            type = FruitType.LEMON,
            x = 322f,
            y = 280f,
            vx = 0f,
            vy = 860f,
        )
        world.debugSetFruits(listOf(base, falling))

        var collisionY = 0f
        var collisionDetected = false
        var framesAfterCollision = 0

        repeat(180) {
            world.update(1f / 120f)
            val moved = world.fruits.first { it.id == 2L }
            if (!collisionDetected && abs(moved.vx) > 45f) {
                collisionDetected = true
                collisionY = moved.y
                return@repeat
            }

            if (collisionDetected) {
                framesAfterCollision += 1
                if (framesAfterCollision == 12) {
                    assertTrue(moved.y > collisionY + 18f)
                    assertTrue(moved.vy > 150f)
                    return
                }
            }
        }

        fail("Glancing collision never produced a continuing downward fall")
    }

    @Test
    fun settledFruit_wakesUpWhenGravityDirectionChanges() {
        val world = GameWorld()
        world.startGame()
        world.debugSetGravity(0f, GameWorld.BASE_GRAVITY)

        val fruit = FruitBody(
            id = 1L,
            type = FruitType.TOMATO,
            x = 360f,
            y = world.binBottom - FruitType.TOMATO.radius,
        )
        world.debugSetFruits(listOf(fruit))

        repeat(80) {
            world.update(1f / 120f)
        }

        val restingFruit = world.fruits.first()
        assertTrue(restingFruit.isSleeping)

        world.debugSetGravity(1550f, 1560f)
        world.update(1f / 120f)

        assertFalse(restingFruit.isSleeping)
    }

    @Test
    fun mergedFruit_staysAwakeDuringReleaseWindow() {
        val world = GameWorld()
        world.startGame()
        world.debugSetGravity(0f, GameWorld.BASE_GRAVITY)

        val first = FruitBody(
            id = 1L,
            type = FruitType.GRAPE,
            x = 330f,
            y = world.binBottom - FruitType.GRAPE.radius,
        )
        val second = FruitBody(
            id = 2L,
            type = FruitType.GRAPE,
            x = 380f,
            y = world.binBottom - FruitType.GRAPE.radius,
        )
        world.debugSetFruits(listOf(first, second))

        world.update(1f / 120f)
        val mergedFruit = world.fruits.single()
        assertEquals(FruitType.CHERRY, mergedFruit.type)

        repeat(10) {
            world.update(1f / 120f)
        }

        assertFalse(mergedFruit.isSleeping)
    }

    @Test
    fun freeFallWithoutSupport_doesNotAccumulateVisibleRotation() {
        val world = GameWorld()
        world.startGame()
        world.debugSetGravity(0f, GameWorld.BASE_GRAVITY)

        val fruit = FruitBody(
            id = 1L,
            type = FruitType.LEMON,
            x = 360f,
            y = 320f,
            vx = 0f,
            vy = 420f,
        )
        world.debugSetFruits(listOf(fruit))

        repeat(40) {
            world.update(1f / 120f)
        }

        val movedFruit = world.fruits.first()
        assertTrue(abs(movedFruit.rotationDegrees) < 2f)
    }
}
