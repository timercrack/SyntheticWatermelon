package com.syntheticwatermelon.nativegame.game.model

import com.syntheticwatermelon.nativegame.game.GameUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class GameWorld(
    bestScoreSeed: Int = 0,
    private val random: Random = Random.Default,
    private val onBestScoreChanged: (Int) -> Unit = {},
) {
    data class UpdateResult(
        val mergedType: FruitType? = null,
        val mergeEvents: List<MergeEvent> = emptyList(),
        val impactEvents: List<ImpactEvent> = emptyList(),
        val gameOverTriggered: Boolean = false,
    )

    data class MergeEvent(
        val type: FruitType,
        val x: Float,
        val y: Float,
    )

    data class ImpactEvent(
        val x: Float,
        val y: Float,
        val intensity: Float,
    )

    private data class MergeResult(
        val strongestType: FruitType? = null,
        val events: List<MergeEvent> = emptyList(),
    )

    private data class MergeCandidate(
        val first: FruitBody,
        val second: FruitBody,
        val mergedType: FruitType,
    )

    private data class Contact(
        val first: FruitBody,
        val second: FruitBody? = null,
        val normalX: Float,
        val normalY: Float,
        val penetration: Float,
        val contactX: Float,
        val contactY: Float,
        val restitution: Float,
        val staticFriction: Float,
        val dynamicFriction: Float,
    )

    companion object {
        const val WORLD_WIDTH = 720f
        const val WORLD_HEIGHT = 1280f
        const val BASE_GRAVITY = 2200f

        private const val BIN_LEFT = 54f
        private const val BIN_RIGHT = WORLD_WIDTH - 54f
        private const val BIN_TOP = 168f
        private const val BIN_BOTTOM_INSET = 44f
        private const val WARNING_LINE_OFFSET = 72f

        private const val LINEAR_DAMPING = 0.08f
        private const val WALL_RESTITUTION = 0.06f
        private const val FRUIT_RESTITUTION = 0.04f
        private const val WALL_STATIC_FRICTION = 0.20f
        private const val WALL_DYNAMIC_FRICTION = 0.14f
        private const val FRUIT_STATIC_FRICTION = 0.16f
        private const val FRUIT_DYNAMIC_FRICTION = 0.10f
        private const val RESTITUTION_BOUNCE_SPEED = 180f
        private const val POSITION_CORRECTION_PERCENT = 0.72f
        private const val POSITION_CORRECTION_SLOP = 0.35f
        private const val COLLISION_SOLVER_ITERATIONS = 10
        private const val WALL_COLLISION_PADDING = 4f
        private const val CONTACT_SKIN = 1.25f
        private const val SLEEP_SPEED = 26f
        private const val WAKE_SPEED = 42f
        private const val SLEEP_DELAY = 0.22f
        private const val SUPPORT_NORMAL_THRESHOLD = 0.28f
        private const val SLEEP_WAKE_GRAVITY_DIRECTION_DELTA = 0.14f
        private const val ROLL_ROTATION_SPEED_DEADZONE = 6f
        private const val ROLL_ROTATION_VISUAL_GAIN = 2f
        private const val RAD_TO_DEGREES = 57.29578f
        private const val MERGE_RELEASE_DURATION = 0.16f
        private const val MERGE_POP_SPEED = 128f
        private const val MERGE_DISTANCE_FACTOR = 1.02f
        private const val MERGE_COOLDOWN = 0.10f
        private const val IMPACT_SOUND_COOLDOWN = 0.08f
        private const val IMPACT_SOUND_MIN_SPEED = 180f
        private const val IMPACT_SOUND_MAX_SPEED = 1100f
        private const val DROP_COOLDOWN = 0.32f
        private const val STEP_DT = 1f / 120f
        private const val MAX_FRAME_DT = 1f / 20f
        private const val OVERFLOW_SECONDS_TO_FAIL = 1.35f
        private const val OVERFLOW_SECONDS_TO_RECOVER = 0.55f
    }

    private val mutableFruits = mutableListOf<FruitBody>()
    private var nextFruitId = 1L
    private var elapsedSeconds = 0f
    private var gravityX = 0f
    private var gravityY = BASE_GRAVITY
    private var sleepReferenceGravityX = 0f
    private var sleepReferenceGravityY = 1f

    var state: GameUiState = GameUiState.TITLE
        private set

    var score: Int = 0
        private set

    var bestScore: Int = bestScoreSeed
        private set

    var previewX: Float = WORLD_WIDTH / 2f
        private set

    var currentFruitType: FruitType = FruitType.randomSpawn(random)
        private set

    var nextFruitType: FruitType = FruitType.randomSpawn(random)
        private set

    var timeSinceLastDrop: Float = DROP_COOLDOWN
        private set

    var overflowProgress: Float = 0f
        private set

    var visibleHeight: Float = WORLD_HEIGHT
        private set

    val fruits: List<FruitBody>
        get() = mutableFruits

    val binLeft: Float
        get() = BIN_LEFT

    val binRight: Float
        get() = BIN_RIGHT

    val binTop: Float
        get() = BIN_TOP

    val binBottom: Float
        get() = visibleHeight - BIN_BOTTOM_INSET

    val binHeight: Float
        get() = binBottom - binTop

    val warningLineY: Float
        get() = binTop + WARNING_LINE_OFFSET

    val spawnY: Float
        get() = BIN_TOP + currentFruitType.radius + 20f

    fun updateViewportHeight(height: Float) {
        visibleHeight = max(WORLD_HEIGHT, height)
    }

    internal fun debugSetFruits(fruits: List<FruitBody>) {
        mutableFruits.clear()
        mutableFruits += fruits.onEach { fruit ->
            fruit.previousX = fruit.x
            fruit.previousY = fruit.y
        }
        nextFruitId = (fruits.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    internal fun debugSetGravity(x: Float, y: Float) {
        gravityX = x
        gravityY = y
    }

    internal fun debugSetElapsedSeconds(seconds: Float) {
        elapsedSeconds = seconds
    }

    fun updateGravity(targetX: Float, targetY: Float) {
        gravityX += (targetX - gravityX) * 0.18f
        gravityY += (targetY - gravityY) * 0.18f
    }

    fun startGame() {
        resetGameplayState()
        state = GameUiState.PLAYING
    }

    fun restartGame() {
        resetGameplayState()
        state = GameUiState.PLAYING
    }

    fun returnToTitle() {
        resetGameplayState()
        state = GameUiState.TITLE
    }

    fun pause() {
        if (state == GameUiState.PLAYING) {
            state = GameUiState.PAUSED
        }
    }

    fun resume() {
        if (state == GameUiState.PAUSED) {
            state = GameUiState.PLAYING
        }
    }

    fun canDropPreview(): Boolean {
        return state == GameUiState.PLAYING && timeSinceLastDrop >= DROP_COOLDOWN
    }

    fun movePreviewTo(x: Float) {
        val radius = currentFruitType.radius
        previewX = x.coerceIn(BIN_LEFT + radius, BIN_RIGHT - radius)
    }

    fun dropPreview(): FruitType? {
        if (!canDropPreview()) {
            return null
        }

        val droppedType = currentFruitType
        val fruit = FruitBody(
            id = nextFruitId++,
            type = droppedType,
            x = previewX,
            y = BIN_TOP + droppedType.radius + 20f,
            vx = 0f,
            vy = 30f,
            lastMergeTime = elapsedSeconds,
        )
        mutableFruits += fruit

        currentFruitType = nextFruitType
        nextFruitType = FruitType.randomSpawn(random)
        timeSinceLastDrop = 0f
        movePreviewTo(previewX)
        return droppedType
    }

    fun update(deltaSeconds: Float): UpdateResult {
        if (state != GameUiState.PLAYING) {
            return UpdateResult()
        }

        var remaining = min(deltaSeconds, MAX_FRAME_DT)
        var strongestMerge: FruitType? = null
        val mergeEvents = mutableListOf<MergeEvent>()
        val impactEvents = mutableListOf<ImpactEvent>()
        var gameOverTriggered = false

        while (remaining > 0f) {
            val step = min(remaining, STEP_DT)
            val stepResult = stepSimulation(step)
            if (stepResult.mergedType != null) {
                strongestMerge = stepResult.mergedType
            }
            if (stepResult.mergeEvents.isNotEmpty()) {
                mergeEvents += stepResult.mergeEvents
            }
            if (stepResult.impactEvents.isNotEmpty()) {
                impactEvents += stepResult.impactEvents
            }
            if (stepResult.gameOverTriggered) {
                gameOverTriggered = true
                break
            }
            remaining -= step
        }

        return UpdateResult(strongestMerge, mergeEvents, impactEvents, gameOverTriggered)
    }

    private fun stepSimulation(dt: Float): UpdateResult {
        elapsedSeconds += dt
        timeSinceLastDrop += dt
        val impactEvents = mutableListOf<ImpactEvent>()

        wakeSleepingFruitsOnGravityShift()
        prepareBodiesForStep()
        integrateMotion(dt)
        solveContacts(impactEvents)
        updateSleepStates(buildContacts(), dt)

        val mergeResult = mergeFruits()
        if (mergeResult.events.isNotEmpty()) {
            solveContacts(impactEvents, emitImpact = false)
            updateSleepStates(buildContacts(), 0f)
        }

        updateFruitRotation(dt)

        val gameOverTriggered = updateOverflow(dt)
        updateBestScore()
        return UpdateResult(mergeResult.strongestType, mergeResult.events, impactEvents, gameOverTriggered)
    }

    private fun prepareBodiesForStep() {
        for (fruit in mutableFruits) {
            fruit.previousX = fruit.x
            fruit.previousY = fruit.y
            fruit.contactCount = 0
            fruit.supportCount = 0
            if (fruit.isSleeping) {
                fruit.vx = 0f
                fruit.vy = 0f
            }
        }
    }

    private fun integrateMotion(dt: Float) {
        for (fruit in mutableFruits) {
            if (fruit.isSleeping) {
                continue
            }

            fruit.vx += gravityX * dt
            fruit.vy += gravityY * dt
            val damping = 1f / (1f + LINEAR_DAMPING * dt)
            fruit.vx *= damping
            fruit.vy *= damping
            fruit.x += fruit.vx * dt
            fruit.y += fruit.vy * dt
        }
    }

    private fun solveContacts(
        impactEvents: MutableList<ImpactEvent>,
        emitImpact: Boolean = true,
    ) {
        repeat(COLLISION_SOLVER_ITERATIONS) { iteration ->
            val contacts = buildContacts()
            if (contacts.isEmpty()) {
                return
            }

            val shouldEmitImpact = emitImpact && iteration == 0
            for (contact in contacts) {
                solveContact(contact, impactEvents, shouldEmitImpact)
            }
        }
    }

    private fun buildContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()

        for (fruit in mutableFruits) {
            val leftLimit = binLeft + WALL_COLLISION_PADDING + fruit.radius
            val rightLimit = binRight - WALL_COLLISION_PADDING - fruit.radius
            val floorLimit = binBottom - WALL_COLLISION_PADDING - fruit.radius
            if (fruit.x < leftLimit + CONTACT_SKIN) {
                contacts += Contact(
                    first = fruit,
                    normalX = -1f,
                    normalY = 0f,
                    penetration = leftLimit - fruit.x,
                    contactX = leftLimit,
                    contactY = fruit.y,
                    restitution = WALL_RESTITUTION,
                    staticFriction = WALL_STATIC_FRICTION,
                    dynamicFriction = WALL_DYNAMIC_FRICTION,
                )
            }

            if (fruit.x > rightLimit - CONTACT_SKIN) {
                contacts += Contact(
                    first = fruit,
                    normalX = 1f,
                    normalY = 0f,
                    penetration = fruit.x - rightLimit,
                    contactX = rightLimit,
                    contactY = fruit.y,
                    restitution = WALL_RESTITUTION,
                    staticFriction = WALL_STATIC_FRICTION,
                    dynamicFriction = WALL_DYNAMIC_FRICTION,
                )
            }

            if (fruit.y > floorLimit - CONTACT_SKIN) {
                contacts += Contact(
                    first = fruit,
                    normalX = 0f,
                    normalY = 1f,
                    penetration = fruit.y - floorLimit,
                    contactX = fruit.x,
                    contactY = floorLimit,
                    restitution = WALL_RESTITUTION,
                    staticFriction = WALL_STATIC_FRICTION,
                    dynamicFriction = WALL_DYNAMIC_FRICTION,
                )
            }

            if (gravityY < -BASE_GRAVITY * 0.12f) {
                val topLimit = binTop + WALL_COLLISION_PADDING + fruit.radius
                if (fruit.y < topLimit + CONTACT_SKIN) {
                    contacts += Contact(
                        first = fruit,
                        normalX = 0f,
                        normalY = -1f,
                        penetration = topLimit - fruit.y,
                        contactX = fruit.x,
                        contactY = topLimit,
                        restitution = WALL_RESTITUTION,
                        staticFriction = WALL_STATIC_FRICTION,
                        dynamicFriction = WALL_DYNAMIC_FRICTION,
                    )
                }
            }
        }

        for (i in 0 until mutableFruits.size) {
            val first = mutableFruits[i]
            for (j in i + 1 until mutableFruits.size) {
                val second = mutableFruits[j]
                var dx = second.x - first.x
                var dy = second.y - first.y
                val minDistance = first.radius + second.radius
                var distanceSquared = dx * dx + dy * dy
                val maxContactDistance = minDistance + CONTACT_SKIN

                if (distanceSquared >= maxContactDistance * maxContactDistance) {
                    continue
                }

                if (distanceSquared < 0.0001f) {
                    val gravityDirection = currentGravityDirection()
                    dx = gravityDirection.first
                    dy = gravityDirection.second
                    distanceSquared = dx * dx + dy * dy
                    if (distanceSquared < 0.0001f) {
                        dx = 0f
                        dy = 1f
                        distanceSquared = 1f
                    }
                }

                val distance = sqrt(distanceSquared)
                val normalX = dx / distance
                val normalY = dy / distance
                contacts += Contact(
                    first = first,
                    second = second,
                    normalX = normalX,
                    normalY = normalY,
                    penetration = minDistance - distance,
                    contactX = first.x + normalX * first.radius,
                    contactY = first.y + normalY * first.radius,
                    restitution = FRUIT_RESTITUTION,
                    staticFriction = FRUIT_STATIC_FRICTION,
                    dynamicFriction = FRUIT_DYNAMIC_FRICTION,
                )
            }
        }

        return contacts
    }

    private fun solveContact(
        contact: Contact,
        impactEvents: MutableList<ImpactEvent>,
        emitImpact: Boolean,
    ) {
        val first = contact.first
        val second = contact.second
        val firstInverseMass = first.inverseMass
        val secondInverseMass = second?.inverseMass ?: 0f
        val totalInverseMass = firstInverseMass + secondInverseMass
        if (totalInverseMass <= 0f) {
            return
        }

        val correctionMagnitude = ((contact.penetration - POSITION_CORRECTION_SLOP).coerceAtLeast(0f) / totalInverseMass) *
            POSITION_CORRECTION_PERCENT
        if (correctionMagnitude > 0f) {
            val correctionX = contact.normalX * correctionMagnitude
            val correctionY = contact.normalY * correctionMagnitude
            first.x -= correctionX * firstInverseMass
            first.y -= correctionY * firstInverseMass
            if (second != null) {
                second.x += correctionX * secondInverseMass
                second.y += correctionY * secondInverseMass
            }
        }

        val relativeVelocityX = (second?.vx ?: 0f) - first.vx
        val relativeVelocityY = (second?.vy ?: 0f) - first.vy
        val normalVelocity = relativeVelocityX * contact.normalX + relativeVelocityY * contact.normalY
        if (normalVelocity > 0f) {
            return
        }

        if (emitImpact) {
            emitImpactIfNeeded(
                impactEvents = impactEvents,
                x = contact.contactX,
                y = contact.contactY,
                speed = -normalVelocity,
                fruits = second?.let { arrayOf(first, it) } ?: arrayOf(first),
            )
        }

        val restitution = if (-normalVelocity > RESTITUTION_BOUNCE_SPEED) {
            contact.restitution
        } else {
            0f
        }
        val normalImpulse = (-(1f + restitution) * normalVelocity / totalInverseMass).coerceAtLeast(0f)
        if (normalImpulse > 0f) {
            first.vx -= normalImpulse * firstInverseMass * contact.normalX
            first.vy -= normalImpulse * firstInverseMass * contact.normalY
            if (second != null) {
                second.vx += normalImpulse * secondInverseMass * contact.normalX
                second.vy += normalImpulse * secondInverseMass * contact.normalY
            }
        }

        val postNormalVelocityX = (second?.vx ?: 0f) - first.vx
        val postNormalVelocityY = (second?.vy ?: 0f) - first.vy
        val tangentVelocityX = postNormalVelocityX - contact.normalX * (
            postNormalVelocityX * contact.normalX + postNormalVelocityY * contact.normalY
        )
        val tangentVelocityY = postNormalVelocityY - contact.normalY * (
            postNormalVelocityX * contact.normalX + postNormalVelocityY * contact.normalY
        )
        val tangentSpeedSquared = tangentVelocityX * tangentVelocityX + tangentVelocityY * tangentVelocityY
        if (tangentSpeedSquared > 0.000001f) {
            val inverseTangentLength = 1f / sqrt(tangentSpeedSquared)
            val tangentX = tangentVelocityX * inverseTangentLength
            val tangentY = tangentVelocityY * inverseTangentLength
            val tangentSpeed = postNormalVelocityX * tangentX + postNormalVelocityY * tangentY
            var tangentImpulse = -tangentSpeed / totalInverseMass
            val maxStaticImpulse = normalImpulse * contact.staticFriction
            if (abs(tangentImpulse) > maxStaticImpulse) {
                val frictionDirection = if (tangentSpeed < 0f) 1f else -1f
                tangentImpulse = frictionDirection * normalImpulse * contact.dynamicFriction
            }

            first.vx -= tangentImpulse * firstInverseMass * tangentX
            first.vy -= tangentImpulse * firstInverseMass * tangentY
            if (second != null) {
                second.vx += tangentImpulse * secondInverseMass * tangentX
                second.vy += tangentImpulse * secondInverseMass * tangentY
            }
        }

        wakeBodyIfNeeded(first)
        if (second != null) {
            wakeBodyIfNeeded(second)
        }
    }

    private fun updateSleepStates(contacts: List<Contact>, dt: Float) {
        val gravityDirection = currentGravityDirection()

        for (fruit in mutableFruits) {
            fruit.contactCount = 0
            fruit.supportCount = 0
        }

        for (contact in contacts) {
            registerSupport(contact.first, contact.normalX, contact.normalY, gravityDirection.first, gravityDirection.second)
            contact.second?.let {
                registerSupport(it, -contact.normalX, -contact.normalY, gravityDirection.first, gravityDirection.second)
            }
        }

        for (fruit in mutableFruits) {
            val recentlyMerged = elapsedSeconds - fruit.lastMergeTime < MERGE_RELEASE_DURATION
            val speedSquared = fruit.vx * fruit.vx + fruit.vy * fruit.vy
            val canSleep = fruit.supportCount > 0 && speedSquared <= SLEEP_SPEED * SLEEP_SPEED && !recentlyMerged

            if (fruit.contactCount == 0 || recentlyMerged) {
                fruit.sleepTimer = 0f
                if (fruit.isSleeping && (fruit.contactCount == 0 || recentlyMerged)) {
                    wakeBody(fruit)
                }
                continue
            }

            if (canSleep) {
                fruit.sleepTimer += dt
                if (fruit.sleepTimer >= SLEEP_DELAY) {
                    if (!fruit.isSleeping) {
                        sleepReferenceGravityX = gravityDirection.first
                        sleepReferenceGravityY = gravityDirection.second
                    }
                    fruit.isSleeping = true
                    fruit.vx = 0f
                    fruit.vy = 0f
                }
            } else {
                fruit.sleepTimer = 0f
                wakeBody(fruit)
            }
        }
    }

    private fun registerSupport(
        fruit: FruitBody,
        supportNormalX: Float,
        supportNormalY: Float,
        gravityDirectionX: Float,
        gravityDirectionY: Float,
    ) {
        fruit.contactCount += 1
        if (supportNormalX * gravityDirectionX + supportNormalY * gravityDirectionY > SUPPORT_NORMAL_THRESHOLD) {
            fruit.supportCount += 1
        }
    }

    private fun mergeFruits(): MergeResult {
        if (mutableFruits.size < 2) {
            return MergeResult()
        }

        val gravityDirection = currentGravityDirection()
        val mergePopVx = -gravityDirection.first * MERGE_POP_SPEED
        val mergePopVy = -gravityDirection.second * MERGE_POP_SPEED

        val candidates = mutableListOf<MergeCandidate>()
        for (i in 0 until mutableFruits.size) {
            val first = mutableFruits[i]
            for (j in i + 1 until mutableFruits.size) {
                val second = mutableFruits[j]
                if (first.type != second.type) {
                    continue
                }

                val mergedType = first.type.next() ?: continue
                if (elapsedSeconds - first.lastMergeTime < MERGE_COOLDOWN ||
                    elapsedSeconds - second.lastMergeTime < MERGE_COOLDOWN
                ) {
                    continue
                }

                val dx = second.x - first.x
                val dy = second.y - first.y
                val distanceSquared = dx * dx + dy * dy
                val mergeDistance = (first.radius + second.radius) * MERGE_DISTANCE_FACTOR
                if (distanceSquared <= mergeDistance * mergeDistance) {
                    candidates += MergeCandidate(first, second, mergedType)
                }
            }
        }

        if (candidates.isEmpty()) {
            return MergeResult()
        }

        candidates.sortByDescending { it.mergedType.ordinal }
        val usedIds = mutableSetOf<Long>()
        val createdFruits = mutableListOf<FruitBody>()
        val mergeEvents = mutableListOf<MergeEvent>()
        var strongestMerge: FruitType? = null

        for (candidate in candidates) {
            if (candidate.first.id in usedIds || candidate.second.id in usedIds) {
                continue
            }

            usedIds += candidate.first.id
            usedIds += candidate.second.id

            val mergedFruit = FruitBody(
                id = nextFruitId++,
                type = candidate.mergedType,
                x = ((candidate.first.x + candidate.second.x) * 0.5f).coerceIn(
                    BIN_LEFT + candidate.mergedType.radius,
                    BIN_RIGHT - candidate.mergedType.radius,
                ),
                y = min(
                    (candidate.first.y + candidate.second.y) * 0.5f,
                    binBottom - candidate.mergedType.radius,
                ),
                vx = (candidate.first.vx + candidate.second.vx) * 0.5f + mergePopVx,
                vy = (candidate.first.vy + candidate.second.vy) * 0.5f + mergePopVy,
                lastMergeTime = elapsedSeconds,
                rotationDegrees = (candidate.first.rotationDegrees + candidate.second.rotationDegrees) * 0.5f,
            )
            createdFruits += mergedFruit
            mergeEvents += MergeEvent(candidate.mergedType, mergedFruit.x, mergedFruit.y)
            score += candidate.mergedType.scoreValue
            strongestMerge = when {
                strongestMerge == null -> candidate.mergedType
                candidate.mergedType.ordinal > strongestMerge.ordinal -> candidate.mergedType
                else -> strongestMerge
            }
        }

        if (usedIds.isEmpty()) {
            return MergeResult()
        }

        mutableFruits.removeAll { it.id in usedIds }
        mutableFruits += createdFruits
        createdFruits.forEach { mergedFruit ->
            wakeFruitsNear(mergedFruit.x, mergedFruit.y, mergedFruit.radius)
        }
        return MergeResult(strongestMerge, mergeEvents)
    }

    private fun updateOverflow(dt: Float): Boolean {
        val overflowing = mutableFruits.any { it.y - it.radius < warningLineY }
        overflowProgress = if (overflowing) {
            min(1f, overflowProgress + dt / OVERFLOW_SECONDS_TO_FAIL)
        } else {
            max(0f, overflowProgress - dt / OVERFLOW_SECONDS_TO_RECOVER)
        }

        if (overflowProgress >= 1f) {
            state = GameUiState.GAME_OVER
            return true
        }
        return false
    }

    private fun updateBestScore() {
        if (score > bestScore) {
            bestScore = score
            onBestScoreChanged(bestScore)
        }
    }

    private fun currentGravityDirection(): Pair<Float, Float> {
        val magnitude = sqrt(gravityX * gravityX + gravityY * gravityY)
        if (magnitude < 0.001f) {
            return 0f to 1f
        }

        return gravityX / magnitude to gravityY / magnitude
    }

    private fun updateFruitRotation(dt: Float) {
        val gravityDirection = currentGravityDirection()
        val tangentX = gravityDirection.second
        val tangentY = -gravityDirection.first

        for (fruit in mutableFruits) {
            if (fruit.isSleeping) {
                continue
            }

            val tangentialSpeed = fruit.vx * tangentX + fruit.vy * tangentY
            if (abs(tangentialSpeed) < ROLL_ROTATION_SPEED_DEADZONE) {
                continue
            }

            val normalSpeed = fruit.vx * gravityDirection.first + fruit.vy * gravityDirection.second
            if (fruit.contactCount == 0 && abs(tangentialSpeed) <= abs(normalSpeed) * 1.05f) {
                continue
            }

            fruit.rotationDegrees += tangentialSpeed * dt / fruit.radius * RAD_TO_DEGREES * ROLL_ROTATION_VISUAL_GAIN
        }
    }

    private fun wakeSleepingFruitsOnGravityShift() {
        if (mutableFruits.none { it.isSleeping }) {
            return
        }

        val gravityDirection = currentGravityDirection()
        val deltaX = gravityDirection.first - sleepReferenceGravityX
        val deltaY = gravityDirection.second - sleepReferenceGravityY
        if (deltaX * deltaX + deltaY * deltaY <
            SLEEP_WAKE_GRAVITY_DIRECTION_DELTA * SLEEP_WAKE_GRAVITY_DIRECTION_DELTA
        ) {
            return
        }

        mutableFruits.forEach(::wakeBody)
        sleepReferenceGravityX = gravityDirection.first
        sleepReferenceGravityY = gravityDirection.second
    }

    private fun wakeBody(fruit: FruitBody) {
        fruit.isSleeping = false
        fruit.sleepTimer = 0f
    }

    private fun wakeBodyIfNeeded(fruit: FruitBody) {
        if (!fruit.isSleeping) {
            return
        }

        if (fruit.vx * fruit.vx + fruit.vy * fruit.vy > WAKE_SPEED * WAKE_SPEED) {
            wakeBody(fruit)
        }
    }

    private fun wakeFruitsNear(x: Float, y: Float, radius: Float) {
        val wakePadding = 36f
        for (fruit in mutableFruits) {
            val dx = fruit.x - x
            val dy = fruit.y - y
            val maxDistance = fruit.radius + radius + wakePadding
            if (dx * dx + dy * dy <= maxDistance * maxDistance) {
                wakeBody(fruit)
            }
        }
    }

    private fun emitImpactIfNeeded(
        impactEvents: MutableList<ImpactEvent>,
        x: Float,
        y: Float,
        speed: Float,
        fruits: Array<FruitBody>,
    ) {
        if (speed < IMPACT_SOUND_MIN_SPEED) {
            return
        }

        if (fruits.any { elapsedSeconds - it.lastImpactTime < IMPACT_SOUND_COOLDOWN }) {
            return
        }

        fruits.forEach { it.lastImpactTime = elapsedSeconds }
        impactEvents += ImpactEvent(
            x = x,
            y = y,
            intensity = ((speed - IMPACT_SOUND_MIN_SPEED) / (IMPACT_SOUND_MAX_SPEED - IMPACT_SOUND_MIN_SPEED))
                .coerceIn(0f, 1f),
        )
    }

    private fun resetGameplayState() {
        mutableFruits.clear()
        nextFruitId = 1L
        elapsedSeconds = 0f
        score = 0
        previewX = WORLD_WIDTH / 2f
        currentFruitType = FruitType.randomSpawn(random)
        nextFruitType = FruitType.randomSpawn(random)
        timeSinceLastDrop = DROP_COOLDOWN
        overflowProgress = 0f
        gravityX = 0f
        gravityY = BASE_GRAVITY
        sleepReferenceGravityX = 0f
        sleepReferenceGravityY = 1f
    }
}
