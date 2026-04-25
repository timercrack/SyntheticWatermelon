package com.syntheticwatermelon.nativegame.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.syntheticwatermelon.nativegame.game.model.FruitBody
import com.syntheticwatermelon.nativegame.game.model.FruitType
import com.syntheticwatermelon.nativegame.game.model.GameWorld
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {
    companion object {
        private const val PREFS_NAME = "synthetic_watermelon_native"
        private const val KEY_BEST_SCORE = "best_score"
        private const val MERGE_ANIMATION_DURATION = 0.22f
        private const val FIREWORK_BURST_DURATION = 0.46f
        private const val FIREWORK_CHAIN_BASE_DELAY = 0.08f
        private const val FIREWORK_CHAIN_STEP_DELAY = 0.07f
        private const val FIREWORK_CHAIN_JITTER = 0.04f
        private const val FIREWORK_PARTICLE_GRAVITY = 720f
        private const val FIREWORK_PARTICLE_MIN_LIFETIME = 0.62f
        private const val FIREWORK_PARTICLE_MAX_LIFETIME = 1.05f
        private const val FIREWORK_PARTICLE_DRAG_PER_SECOND = 3.2f
        private const val TITLE_FRUIT_SPACING = 16f
        private const val TWO_PI = 6.2831855f
    }

    private data class MergeAnimation(
        val type: FruitType,
        val x: Float,
        val y: Float,
        var elapsed: Float = 0f,
    )

    private data class FireworkBurst(
        val x: Float,
        val y: Float,
        val color: Int,
        val maxRadius: Float,
        val brightness: Float,
        var elapsed: Float = 0f,
    )

    private data class FireworkParticle(
        var x: Float,
        var y: Float,
        var previousX: Float,
        var previousY: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        val radius: Float,
        val lifetime: Float,
        val brightness: Float,
        var elapsed: Float = 0f,
    )

    private data class ScheduledFireworkLaunch(
        val x: Float,
        val y: Float,
        val color: Int,
        val scale: Float,
        val brightness: Float,
        val particleBoost: Float,
        var delay: Float,
    )

    private data class FireworkAnchor(
        val x: Float,
        val y: Float,
    )

    private data class TitleFruitDecoration(
        val type: FruitType,
        val x: Float,
        val y: Float,
        val radius: Float,
        val rotationDegrees: Float = 0f,
        val alpha: Float = 1f,
    )

    private sealed interface PendingWorldAction {
        data class MovePreview(val x: Float) : PendingWorldAction

        data object DropPreview : PendingWorldAction

        data class SetAutoDropEnabled(val enabled: Boolean) : PendingWorldAction

        data object StartGame : PendingWorldAction

        data object PauseGame : PendingWorldAction

        data object ResumeGame : PendingWorldAction

        data object RestartGame : PendingWorldAction

        data object ReturnToTitle : PendingWorldAction
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val fruitBitmapStore = FruitBitmapStore(context)
    private val audioEngine = SimpleAudioEngine(context)
    @Suppress("DEPRECATION")
    private val appVersionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?: error("Missing versionName")
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val world = GameWorld(
        bestScoreSeed = preferences.getInt(KEY_BEST_SCORE, 0),
        onBestScoreChanged = { bestScore ->
            preferences.edit().putInt(KEY_BEST_SCORE, bestScore).apply()
        },
    )

    @Volatile
    private var running = false

    @Volatile
    private var targetGravityX = 0f

    @Volatile
    private var targetGravityY = GameWorld.BASE_GRAVITY

    @Volatile
    private var currentUiState = GameUiState.TITLE

    private var renderThread: Thread? = null
    private var worldScale = 1f
    private var viewportLeft = 0f
    private var viewportTop = 0f
    private var autoDropEnabled = false
    private var tiltSensorRegistered = false
    private var accelerometerFallbackX = 0f
    private var accelerometerFallbackY = SensorManager.GRAVITY_EARTH
    private var accelerometerFallbackZ = 0f
    private var accelerometerInitialized = false
    private var titleFruitLayoutSignature = Int.MIN_VALUE
    private var titleFruitDecorations = emptyList<TitleFruitDecoration>()
    private val mergeAnimations = mutableListOf<MergeAnimation>()
    private val scheduledFireworkLaunches = mutableListOf<ScheduledFireworkLaunch>()
    private val fireworkBursts = mutableListOf<FireworkBurst>()
    private val fireworkParticles = mutableListOf<FireworkParticle>()
    private val pendingWorldActions = ConcurrentLinkedQueue<PendingWorldAction>()
    private val gravityScale = GameWorld.BASE_GRAVITY / SensorManager.GRAVITY_EARTH
    private val effectRandom = Random.Default
    private val tiltSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> updateTiltGravity(event.values[0], event.values[1])
                Sensor.TYPE_ACCELEROMETER -> updateAccelerometerFallback(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val titleButtonRect = RectF(170f, 730f, 550f, 840f)
    private val titlePreviewRect = RectF(150f, 56f, 570f, 196f)
    private val pauseButtonRect = RectF(622f, 54f, 682f, 114f)
    private val soundButtonRect = RectF(544f, 54f, 604f, 114f)
    private val overlayPrimaryRect = RectF(190f, 630f, 530f, 730f)
    private val overlaySecondaryRect = RectF(190f, 760f, 530f, 860f)
    private val overlayTertiaryRect = RectF(190f, 890f, 530f, 990f)

    private val binFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF2D7")
        style = Paint.Style.FILL
    }
    private val binStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C684F")
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }
    private val warningLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF6B6B")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55A86D42")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7FFFDF8")
        style = Paint.Style.FILL
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val accentButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7CC06A")
        style = Paint.Style.FILL
    }
    private val secondaryButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFA86A")
        style = Paint.Style.FILL
    }
    private val tertiaryButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE6D3C0")
        style = Paint.Style.FILL
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3FFFFFF")
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF513B2E")
        textAlign = Paint.Align.CENTER
        textSize = 68f
        isFakeBoldText = true
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A6558")
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val titleBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6FAF63")
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val titleHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A6558")
        textAlign = Paint.Align.CENTER
        textSize = 26f
    }
    private val titleFooterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C7668")
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val hudValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF513B2E")
        textSize = 42f
        isFakeBoldText = true
    }
    private val hudLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C7668")
        textSize = 24f
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val darkButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF513B2E")
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val fruitShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
        style = Paint.Style.FILL
    }
    private val mergeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mergeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val fireworkBurstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fireworkParticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fireworkTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fruitBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val mutedButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF513B2E")
        textAlign = Paint.Align.CENTER
        textSize = 28f
        isFakeBoldText = true
    }
    private val fireworkPalette = intArrayOf(
        FruitType.GIANT_WATERMELON.color,
        FruitType.WATERMELON.color,
        Color.parseColor("#FFFFC145"),
        Color.parseColor("#FFFF7B7B"),
        Color.parseColor("#FF7ED6FF"),
        Color.parseColor("#FFE38CFF"),
    )
    private val fruitBitmapRect = RectF()
    private val fruitClipPath = Path()

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        resumeGameLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pauseGameLoop()
    }

    fun resumeGameLoop() {
        if (running) {
            return
        }
        if (!holder.surface.isValid) {
            return
        }
        registerTiltSensor()
        running = true
        renderThread = Thread(this, "SyntheticWatermelonRender").also { it.start() }
        audioEngine.onHostResumed()
    }

    fun pauseGameLoop() {
        unregisterTiltSensor()
        audioEngine.onHostPaused()
        running = false
        val thread = renderThread ?: return
        if (Thread.currentThread() != thread) {
            thread.join(400)
        }
        renderThread = null
    }

    fun release() {
        pauseGameLoop()
        fruitBitmapStore.release()
        audioEngine.release()
    }

    override fun run() {
        var previousFrameNanos = System.nanoTime()
        while (running) {
            if (!holder.surface.isValid) {
                continue
            }

            val currentFrameNanos = System.nanoTime()
            val deltaSeconds = ((currentFrameNanos - previousFrameNanos) / 1_000_000_000f)
                .coerceAtMost(0.05f)
            previousFrameNanos = currentFrameNanos

            processPendingWorldActions()
            processAutoDrop()
            currentUiState = world.state
            world.updateGravity(targetGravityX, targetGravityY)
            val updateResult = world.update(deltaSeconds)
            currentUiState = world.state
            updateMergeAnimations(deltaSeconds)
            updateFireworkEffects(deltaSeconds)
            if (updateResult.mergeEvents.isNotEmpty()) {
                mergeAnimations += updateResult.mergeEvents.map { event ->
                    MergeAnimation(event.type, event.x, event.y)
                }
                launchMergeCelebrations(updateResult.mergeEvents)
            }
            if (updateResult.mergedType != null) {
                audioEngine.playMerge(updateResult.mergedType)
            }
            if (updateResult.gameOverTriggered) {
                audioEngine.playGameOver()
            }

            val canvas = holder.lockCanvas() ?: continue
            try {
                drawFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val worldX = screenToWorldX(event.x)
        val worldY = screenToWorldY(event.y)
        when (currentUiState) {
            GameUiState.TITLE -> handleTitleTouch(event, worldX, worldY)
            GameUiState.PLAYING -> handlePlayingTouch(event, worldX, worldY)
            GameUiState.PAUSED -> handlePausedTouch(event, worldX, worldY)
            GameUiState.GAME_OVER -> handleGameOverTouch(event, worldX, worldY)
        }
        return true
    }

    private fun handleTitleTouch(event: MotionEvent, worldX: Float, worldY: Float) {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return
        }
        if (soundButtonRect.contains(worldX, worldY)) {
            toggleSound()
            return
        }
        if (titleButtonRect.contains(worldX, worldY)) {
            audioEngine.playClick()
            enqueueWorldAction(PendingWorldAction.StartGame)
            return
        }
        if (titlePreviewRect.contains(worldX, worldY)) {
            previewTitleCelebration()
        }
    }

    private fun handlePlayingTouch(event: MotionEvent, worldX: Float, worldY: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (soundButtonRect.contains(worldX, worldY)) {
                    return
                }
                if (pauseButtonRect.contains(worldX, worldY)) {
                    return
                }
                enqueueWorldAction(PendingWorldAction.MovePreview(worldX))
                enqueueWorldAction(PendingWorldAction.SetAutoDropEnabled(true))
            }

            MotionEvent.ACTION_MOVE -> {
                enqueueWorldAction(PendingWorldAction.MovePreview(worldX))
            }

            MotionEvent.ACTION_UP -> {
                enqueueWorldAction(PendingWorldAction.SetAutoDropEnabled(false))
                if (pauseButtonRect.contains(worldX, worldY)) {
                    audioEngine.playClick()
                    enqueueWorldAction(PendingWorldAction.PauseGame)
                    return
                }
                if (soundButtonRect.contains(worldX, worldY)) {
                    toggleSound()
                    return
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                enqueueWorldAction(PendingWorldAction.SetAutoDropEnabled(false))
            }
        }
    }

    private fun handlePausedTouch(event: MotionEvent, worldX: Float, worldY: Float) {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return
        }
        if (soundButtonRect.contains(worldX, worldY)) {
            toggleSound()
            return
        }
        when {
            overlayPrimaryRect.contains(worldX, worldY) -> {
                audioEngine.playClick()
                enqueueWorldAction(PendingWorldAction.ResumeGame)
            }

            overlaySecondaryRect.contains(worldX, worldY) -> {
                audioEngine.playClick()
                enqueueWorldAction(PendingWorldAction.RestartGame)
            }

            overlayTertiaryRect.contains(worldX, worldY) -> {
                audioEngine.playClick()
                enqueueWorldAction(PendingWorldAction.ReturnToTitle)
            }
        }
    }

    private fun handleGameOverTouch(event: MotionEvent, worldX: Float, worldY: Float) {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return
        }
        if (soundButtonRect.contains(worldX, worldY)) {
            toggleSound()
            return
        }
        when {
            overlayPrimaryRect.contains(worldX, worldY) -> {
                audioEngine.playClick()
                enqueueWorldAction(PendingWorldAction.RestartGame)
            }

            overlaySecondaryRect.contains(worldX, worldY) -> {
                audioEngine.playClick()
                enqueueWorldAction(PendingWorldAction.ReturnToTitle)
            }
        }
    }

    private fun enqueueWorldAction(action: PendingWorldAction) {
        pendingWorldActions.add(action)
    }

    private fun processPendingWorldActions() {
        while (true) {
            when (val action = pendingWorldActions.poll() ?: break) {
                is PendingWorldAction.MovePreview -> world.movePreviewTo(action.x)
                PendingWorldAction.DropPreview -> world.dropPreview()
                is PendingWorldAction.SetAutoDropEnabled -> {
                    autoDropEnabled = action.enabled
                    if (action.enabled) {
                        world.dropPreview()
                    }
                }
                PendingWorldAction.StartGame -> {
                    autoDropEnabled = false
                    clearTransientEffects()
                    world.startGame()
                }
                PendingWorldAction.PauseGame -> {
                    autoDropEnabled = false
                    world.pause()
                }
                PendingWorldAction.ResumeGame -> {
                    autoDropEnabled = false
                    world.resume()
                }
                PendingWorldAction.RestartGame -> {
                    autoDropEnabled = false
                    clearTransientEffects()
                    world.restartGame()
                }
                PendingWorldAction.ReturnToTitle -> {
                    autoDropEnabled = false
                    clearTransientEffects()
                    world.returnToTitle()
                    invalidateTitleFruitShowcase()
                }
            }
        }
    }

    private fun processAutoDrop() {
        if (!autoDropEnabled) {
            return
        }

        world.dropPreview()
    }

    private fun toggleSound() {
        audioEngine.toggleMuted()
    }

    private fun registerTiltSensor() {
        if (tiltSensorRegistered) {
            return
        }

        val sensor = gravitySensor ?: accelerometerSensor ?: return
        accelerometerInitialized = false
        sensorManager.registerListener(tiltSensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        tiltSensorRegistered = true
    }

    private fun unregisterTiltSensor() {
        if (!tiltSensorRegistered) {
            return
        }

        sensorManager.unregisterListener(tiltSensorListener)
        tiltSensorRegistered = false
    }

    private fun updateAccelerometerFallback(x: Float, y: Float, z: Float) {
        if (!accelerometerInitialized) {
            accelerometerFallbackX = x
            accelerometerFallbackY = y
            accelerometerFallbackZ = z
            accelerometerInitialized = true
        } else {
            val alpha = 0.86f
            accelerometerFallbackX = alpha * accelerometerFallbackX + (1f - alpha) * x
            accelerometerFallbackY = alpha * accelerometerFallbackY + (1f - alpha) * y
            accelerometerFallbackZ = alpha * accelerometerFallbackZ + (1f - alpha) * z
        }

        updateTiltGravity(accelerometerFallbackX, accelerometerFallbackY)
    }

    private fun updateTiltGravity(sensorX: Float, sensorY: Float) {
        var gravityX = -sensorX * gravityScale
        var gravityY = sensorY * gravityScale
        val magnitude = sqrt(gravityX * gravityX + gravityY * gravityY)
        if (magnitude < GameWorld.BASE_GRAVITY * 0.12f) {
            gravityX = 0f
            gravityY = GameWorld.BASE_GRAVITY
        } else if (magnitude > GameWorld.BASE_GRAVITY) {
            val scale = GameWorld.BASE_GRAVITY / magnitude
            gravityX *= scale
            gravityY *= scale
        }

        targetGravityX = gravityX
        targetGravityY = gravityY
    }

    private fun drawFrame(canvas: Canvas) {
        updateViewport(canvas)
        canvas.drawColor(Color.parseColor("#FFF7EC"))
        canvas.save()
        canvas.translate(viewportLeft, viewportTop)
        canvas.scale(worldScale, worldScale)

        drawBackdrop(canvas)
        when (world.state) {
            GameUiState.TITLE -> drawTitle(canvas)
            GameUiState.PLAYING -> drawActiveGame(canvas)
            GameUiState.PAUSED -> {
                drawActiveGame(canvas)
                drawPauseOverlay(canvas)
            }
            GameUiState.GAME_OVER -> {
                drawActiveGame(canvas)
                drawGameOverOverlay(canvas)
            }
        }

        canvas.restore()
    }

    private fun drawBackdrop(canvas: Canvas) {
        val topBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFE0B3")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, GameWorld.WORLD_WIDTH, 240f, topBandPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(92f, 96f, 34f, dotPaint)
        canvas.drawCircle(628f, 154f, 54f, dotPaint)
        canvas.drawCircle(582f, 92f, 22f, dotPaint)
    }

    private fun drawTitle(canvas: Canvas) {
        val centerX = GameWorld.WORLD_WIDTH / 2f
        drawBin(canvas)

        val bestScoreY = titleButtonRect.bottom + 72f
        val footerY = world.binBottom - 72f

        canvas.drawText("合成大西瓜", centerX, 114f, titlePaint)
        canvas.drawText("重力感应版", centerX, 162f, titleBadgePaint)
        canvas.drawText("左右倾斜手机改变重力方向", centerX, 212f, titleHintPaint)
        canvas.drawText("按住会连续下落，拖动可调整落点", centerX, 248f, titleHintPaint)
        canvas.drawText("相同水果相撞会合成。", centerX, 284f, titleHintPaint)

        drawButton(
            canvas = canvas,
            rect = soundButtonRect,
            paint = tertiaryButtonPaint,
            textPaint = mutedButtonTextPaint,
            label = if (audioEngine.isMuted()) "静" else "音",
        )

        drawButton(canvas, titleButtonRect, accentButtonPaint, buttonTextPaint, "开始游戏")
        canvas.drawText("最高分 ${world.bestScore}", centerX, bestScoreY, hudValuePaint)
        drawTitleFruitShowcase(canvas)
        drawMergeAnimations(canvas)
        drawFireworkEffects(canvas)
        canvas.drawText("timercrack · v$appVersionName", centerX, footerY, titleFooterPaint)
    }

    private fun drawActiveGame(canvas: Canvas) {
        drawBin(canvas)
        drawWarningLine(canvas)
        drawHudCards(canvas)
        drawHudButtons(canvas)
        drawPreview(canvas)
        drawFruitStack(canvas)
        drawMergeAnimations(canvas)
        drawFireworkEffects(canvas)
    }

    private fun drawBin(canvas: Canvas) {
        val wallHalfStroke = binStrokePaint.strokeWidth * 0.5f
        val insideRect = RectF(world.binLeft, world.binTop, world.binRight, world.binBottom)
        canvas.drawRoundRect(insideRect, 34f, 34f, binFillPaint)
        canvas.drawLine(world.binLeft - wallHalfStroke, world.binTop, world.binLeft - wallHalfStroke, world.binBottom, binStrokePaint)
        canvas.drawLine(world.binRight + wallHalfStroke, world.binTop, world.binRight + wallHalfStroke, world.binBottom, binStrokePaint)
        canvas.drawLine(world.binLeft - wallHalfStroke, world.binBottom + wallHalfStroke, world.binRight + wallHalfStroke, world.binBottom + wallHalfStroke, binStrokePaint)
    }

    private fun drawWarningLine(canvas: Canvas) {
        warningLinePaint.alpha = (70 + world.overflowProgress * 185f).toInt()
        canvas.drawLine(
            world.binLeft + 10f,
            world.warningLineY,
            world.binRight - 10f,
            world.warningLineY,
            warningLinePaint,
        )
    }

    private fun drawHudCards(canvas: Canvas) {
        val scoreCard = RectF(38f, 38f, 290f, 132f)
        val nextCard = RectF(312f, 38f, 510f, 132f)
        canvas.drawRoundRect(scoreCard, 28f, 28f, cardPaint)
        canvas.drawRoundRect(nextCard, 28f, 28f, cardPaint)

        canvas.drawText("分数", scoreCard.left + 24f, 72f, hudLabelPaint)
        canvas.drawText(world.score.toString(), scoreCard.left + 24f, 116f, hudValuePaint)
        canvas.drawText("最高 ${world.bestScore}", scoreCard.right - 120f, 116f, hudLabelPaint)

        canvas.drawText("下一个", nextCard.left + 22f, 72f, hudLabelPaint)
        drawFruit(canvas, world.nextFruitType, nextCard.right - 56f, 86f, 0.9f, radiusOverride = 34f)
    }

    private fun drawHudButtons(canvas: Canvas) {
        drawButton(
            canvas = canvas,
            rect = soundButtonRect,
            paint = tertiaryButtonPaint,
            textPaint = mutedButtonTextPaint,
            label = if (audioEngine.isMuted()) "静" else "音",
        )
        drawButton(
            canvas = canvas,
            rect = pauseButtonRect,
            paint = tertiaryButtonPaint,
            textPaint = mutedButtonTextPaint,
            label = if (world.state == GameUiState.PAUSED) "▶" else "停",
        )
    }

    private fun drawPreview(canvas: Canvas) {
        if (world.state != GameUiState.PLAYING) {
            return
        }
        canvas.drawLine(world.previewX, world.spawnY, world.previewX, world.binBottom - 16f, guideLinePaint)
        val alpha = if (world.canDropPreview()) 0.95f else 0.55f
        drawFruit(canvas, world.currentFruitType, world.previewX, world.spawnY, alpha)
    }

    private fun drawFruitStack(canvas: Canvas) {
        val sortedFruits = world.fruits.sortedBy { it.y }
        for (fruit in sortedFruits) {
            drawFruit(canvas, fruit)
        }
    }

    private fun drawTitleFruitShowcase(canvas: Canvas) {
        for (decoration in titleFruitDecorations) {
            drawFruit(
                canvas = canvas,
                type = decoration.type,
                x = decoration.x,
                y = decoration.y,
                alpha = decoration.alpha,
                rotationDegrees = decoration.rotationDegrees,
                radiusOverride = decoration.radius,
            )
        }
    }

    private fun rebuildTitleFruitShowcaseIfNeeded(force: Boolean = false) {
        val layoutSignature = titleButtonRect.bottom.toInt() * 31 + world.binBottom.toInt()
        if (!force && titleFruitDecorations.isNotEmpty() && titleFruitLayoutSignature == layoutSignature) {
            return
        }

        titleFruitLayoutSignature = layoutSignature
        val random = Random.Default
        val bestScoreY = titleButtonRect.bottom + 72f
        val footerY = world.binBottom - 72f
        val fruitArea = RectF(
            world.binLeft + 20f,
            bestScoreY + 86f,
            world.binRight - 20f,
            footerY - 34f,
        )
        titleFruitDecorations = buildRandomTitleFruitDecorations(fruitArea, random)
    }

    private fun buildRandomTitleFruitDecorations(area: RectF, random: Random): List<TitleFruitDecoration> {
        repeat(24) {
            val placedDecorations = mutableListOf<TitleFruitDecoration>()
            var placementFailed = false

            for (type in FruitType.entries.sortedByDescending(::titleFruitRadius)) {
                val radius = titleFruitRadius(type)
                var bestDecoration: TitleFruitDecoration? = null
                var bestClearance = Float.NEGATIVE_INFINITY

                repeat(720) {
                    val x = lerp(area.left + radius, area.right - radius, random.nextFloat())
                    val y = lerp(area.top + radius, area.bottom - radius, random.nextFloat())
                    val candidate = TitleFruitDecoration(
                        type = type,
                        x = x,
                        y = y,
                        radius = radius,
                        rotationDegrees = randomOffset(random, 12f),
                        alpha = 0.94f + random.nextFloat() * 0.06f,
                    )
                    val clearance = titleFruitClearance(candidate, placedDecorations, area)
                    if (clearance > bestClearance) {
                        bestClearance = clearance
                        bestDecoration = candidate
                    }
                }

                if (bestDecoration == null || bestClearance < 0f) {
                    placementFailed = true
                    break
                }

                val placedDecoration = bestDecoration
                    ?: error("Title fruit candidate unexpectedly missing")
                placedDecorations += placedDecoration
            }

            if (!placementFailed) {
                return placedDecorations.sortedBy { decoration -> decoration.y }
            }
        }

        error("Unable to place title fruits without overlap")
    }

    private fun titleFruitClearance(
        candidate: TitleFruitDecoration,
        placedDecorations: List<TitleFruitDecoration>,
        area: RectF,
    ): Float {
        var minClearance = minOf(
            candidate.x - area.left - candidate.radius,
            area.right - candidate.x - candidate.radius,
            candidate.y - area.top - candidate.radius,
            area.bottom - candidate.y - candidate.radius,
        )

        for (placedDecoration in placedDecorations) {
            val dx = candidate.x - placedDecoration.x
            val dy = candidate.y - placedDecoration.y
            val distance = sqrt(dx * dx + dy * dy)
            val clearance = distance - candidate.radius - placedDecoration.radius - TITLE_FRUIT_SPACING
            if (clearance < minClearance) {
                minClearance = clearance
            }
        }

        return minClearance
    }

    private fun titleFruitRadius(type: FruitType): Float {
        return when (type) {
            FruitType.GRAPE -> 24f
            FruitType.CHERRY -> 31f
            FruitType.ORANGE -> 40f
            FruitType.LEMON -> 48f
            FruitType.KIWI -> 54f
            FruitType.TOMATO -> 64f
            FruitType.PEACH -> 74f
            FruitType.PINEAPPLE -> 84f
            FruitType.COCONUT -> 90f
            FruitType.WATERMELON -> 98f
            FruitType.GIANT_WATERMELON -> 104f
        }
    }

    private fun invalidateTitleFruitShowcase() {
        titleFruitDecorations = emptyList()
        titleFruitLayoutSignature = Int.MIN_VALUE
    }

    private fun randomOffset(random: Random, amount: Float): Float {
        if (amount == 0f) {
            return 0f
        }

        return random.nextFloat() * amount * 2f - amount
    }

    private fun drawFruit(canvas: Canvas, fruit: FruitBody) {
        drawFruit(
            canvas = canvas,
            type = fruit.type,
            x = fruit.x,
            y = fruit.y,
            alpha = 1f,
            rotationDegrees = fruit.rotationDegrees,
        )
    }

    private fun drawFruit(
        canvas: Canvas,
        type: FruitType,
        x: Float,
        y: Float,
        alpha: Float,
        rotationDegrees: Float = 0f,
        radiusOverride: Float? = null,
    ) {
        val radius = radiusOverride ?: type.radius
        canvas.drawCircle(x, y + radius * 0.15f, radius * 0.92f, fruitShadowPaint)
        drawFruitBitmap(
            canvas = canvas,
            bitmap = fruitBitmapStore.getBitmap(type),
            x = x,
            y = y,
            radius = radius,
            alpha = alpha,
            rotationDegrees = rotationDegrees,
        )
    }

    private fun drawFruitBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        x: Float,
        y: Float,
        radius: Float,
        alpha: Float,
        rotationDegrees: Float,
    ) {
        val zoomRadius = radius * 1.08f
        fruitBitmapRect.set(
            x - zoomRadius,
            y - zoomRadius,
            x + zoomRadius,
            y + zoomRadius,
        )
        fruitBitmapPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        fruitClipPath.reset()
        fruitClipPath.addCircle(x, y, radius * 0.98f, Path.Direction.CW)
        canvas.save()
        canvas.rotate(rotationDegrees % 360f, x, y)
        canvas.clipPath(fruitClipPath)
        canvas.drawBitmap(bitmap, null, fruitBitmapRect, fruitBitmapPaint)
        canvas.restore()
    }

    private fun drawMergeAnimations(canvas: Canvas) {
        for (animation in mergeAnimations) {
            val progress = (animation.elapsed / MERGE_ANIMATION_DURATION).coerceIn(0f, 1f)
            val pulseProgress = if (progress < 0.55f) {
                progress / 0.55f
            } else {
                1f - ((progress - 0.55f) / 0.45f)
            }.coerceIn(0f, 1f)
            val scale = if (progress < 0.55f) {
                lerp(0.72f, 1.22f, progress / 0.55f)
            } else {
                lerp(1.22f, 1.0f, (progress - 0.55f) / 0.45f)
            }
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val baseRadius = animation.type.radius

            mergeGlowPaint.color = animation.type.color
            mergeGlowPaint.alpha = (alpha * 82f).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                animation.x,
                animation.y,
                baseRadius * lerp(0.94f, 1.26f, pulseProgress),
                mergeGlowPaint,
            )

            mergeRingPaint.color = animation.type.color
            mergeRingPaint.alpha = (alpha * 140f).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                animation.x,
                animation.y,
                baseRadius * lerp(0.86f, 1.44f, progress),
                mergeRingPaint,
            )

            drawFruit(
                canvas = canvas,
                type = animation.type,
                x = animation.x,
                y = animation.y,
                alpha = alpha,
                radiusOverride = baseRadius * scale,
            )
        }
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, GameWorld.WORLD_WIDTH, world.visibleHeight, overlayPaint)
        val panelRect = RectF(118f, overlayPrimaryRect.top - 220f, 602f, overlayPrimaryRect.top + 410f)
        canvas.drawRoundRect(panelRect, 38f, 38f, panelPaint)
        canvas.drawText("已暂停", panelRect.centerX(), panelRect.top + 92f, titlePaint)
        canvas.drawText("先歇一口气，再继续搓瓜。", panelRect.centerX(), panelRect.top + 142f, subtitlePaint)
        drawButton(canvas, overlayPrimaryRect, accentButtonPaint, buttonTextPaint, "继续")
        drawButton(canvas, overlaySecondaryRect, secondaryButtonPaint, buttonTextPaint, "重开")
        drawButton(canvas, overlayTertiaryRect, tertiaryButtonPaint, darkButtonTextPaint, "返回标题")
    }

    private fun drawGameOverOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, GameWorld.WORLD_WIDTH, world.visibleHeight, overlayPaint)
        val panelRect = RectF(118f, overlayPrimaryRect.top - 240f, 602f, overlayPrimaryRect.top + 370f)
        canvas.drawRoundRect(panelRect, 38f, 38f, panelPaint)
        canvas.drawText("游戏结束", panelRect.centerX(), panelRect.top + 90f, titlePaint)
        canvas.drawText("本局 ${world.score} 分 · 最高 ${world.bestScore} 分", panelRect.centerX(), panelRect.top + 146f, subtitlePaint)
        canvas.drawText("顶部警戒线被占太久，瓜盆宣布罢工。", panelRect.centerX(), panelRect.top + 194f, subtitlePaint)
        drawButton(canvas, overlayPrimaryRect, accentButtonPaint, buttonTextPaint, "再来一局")
        drawButton(canvas, overlaySecondaryRect, tertiaryButtonPaint, darkButtonTextPaint, "返回标题")
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        paint: Paint,
        textPaint: Paint,
        label: String,
    ) {
        canvas.drawRoundRect(rect, 26f, 26f, paint)
        val baseline = rect.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        canvas.drawText(label, rect.centerX(), baseline, textPaint)
    }

    private fun updateViewport(canvas: Canvas) {
        worldScale = min(
            canvas.width / GameWorld.WORLD_WIDTH,
            canvas.height / GameWorld.WORLD_HEIGHT,
        )
        world.updateViewportHeight(canvas.height / worldScale)
        viewportLeft = (canvas.width - GameWorld.WORLD_WIDTH * worldScale) * 0.5f
        viewportTop = 0f
        updateInteractiveRects()
    }

    private fun screenToWorldX(screenX: Float): Float {
        return ((screenX - viewportLeft) / worldScale).coerceIn(0f, GameWorld.WORLD_WIDTH)
    }

    private fun screenToWorldY(screenY: Float): Float {
        return ((screenY - viewportTop) / worldScale).coerceIn(0f, world.visibleHeight)
    }

    private fun updateInteractiveRects() {
        soundButtonRect.set(544f, 54f, 604f, 114f)
        pauseButtonRect.set(622f, 54f, 682f, 114f)

        val titleButtonTop = world.binTop + 150f
        titleButtonRect.set(170f, titleButtonTop, 550f, titleButtonTop + 110f)
        rebuildTitleFruitShowcaseIfNeeded()

        val panelTop = (world.visibleHeight - 630f) * 0.46f
        overlayPrimaryRect.set(190f, panelTop + 220f, 530f, panelTop + 320f)
        overlaySecondaryRect.set(190f, panelTop + 350f, 530f, panelTop + 450f)
        overlayTertiaryRect.set(190f, panelTop + 480f, 530f, panelTop + 580f)
    }

    private fun updateMergeAnimations(deltaSeconds: Float) {
        val iterator = mergeAnimations.iterator()
        while (iterator.hasNext()) {
            val animation = iterator.next()
            animation.elapsed += deltaSeconds
            if (animation.elapsed >= MERGE_ANIMATION_DURATION) {
                iterator.remove()
            }
        }
    }

    private fun updateFireworkEffects(deltaSeconds: Float) {
        val launchIterator = scheduledFireworkLaunches.iterator()
        while (launchIterator.hasNext()) {
            val launch = launchIterator.next()
            launch.delay -= deltaSeconds
            if (launch.delay > 0f) {
                continue
            }

            spawnFireworkBurst(
                x = launch.x,
                y = launch.y,
                color = launch.color,
                scale = launch.scale,
                brightness = launch.brightness,
                particleBoost = launch.particleBoost,
            )
            launchIterator.remove()
        }

        val burstIterator = fireworkBursts.iterator()
        while (burstIterator.hasNext()) {
            val burst = burstIterator.next()
            burst.elapsed += deltaSeconds
            if (burst.elapsed >= FIREWORK_BURST_DURATION) {
                burstIterator.remove()
            }
        }

        val particleIterator = fireworkParticles.iterator()
        while (particleIterator.hasNext()) {
            val particle = particleIterator.next()
            particle.elapsed += deltaSeconds
            if (particle.elapsed >= particle.lifetime) {
                particleIterator.remove()
                continue
            }

            particle.previousX = particle.x
            particle.previousY = particle.y
            particle.x += particle.vx * deltaSeconds
            particle.y += particle.vy * deltaSeconds

            val drag = (1f - FIREWORK_PARTICLE_DRAG_PER_SECOND * deltaSeconds).coerceIn(0.82f, 1f)
            particle.vx *= drag
            particle.vy = particle.vy * drag + FIREWORK_PARTICLE_GRAVITY * deltaSeconds
        }
    }

    private fun launchMergeCelebrations(mergeEvents: List<GameWorld.MergeEvent>) {
        val maxOrdinal = FruitType.entries.lastIndex.coerceAtLeast(1)
        for (event in mergeEvents) {
            if (!SimpleAudioEngine.shouldCelebrateMerge(event.type)) {
                continue
            }

            val scale = lerp(0.58f, 1.15f, event.type.ordinal / maxOrdinal.toFloat())
            launchScreenFireworkCelebration(event.type, scale)
        }
    }

    private fun launchScreenFireworkCelebration(type: FruitType, scale: Float) {
        val centerAnchor = FireworkAnchor(
            x = GameWorld.WORLD_WIDTH * 0.5f,
            y = (world.visibleHeight * 0.5f).coerceIn(world.binTop + 120f, world.binBottom - 180f),
        )
        val randomArea = RectF(
            56f,
            120f,
            GameWorld.WORLD_WIDTH - 56f,
            world.visibleHeight - 120f,
        )
        val anchors = mutableListOf(centerAnchor)
        val extraBurstCount = if (scale >= 0.95f) 8 else 6
        val minSeparation = lerp(126f, 196f, scale.coerceIn(0f, 1f))
        val centerScale = scale * 1.35f

        queueFireworkLaunch(
            x = centerAnchor.x,
            y = centerAnchor.y,
            color = type.color,
            scale = centerScale,
            brightness = 1.55f,
            particleBoost = 1.55f,
            delay = 0f,
        )

        repeat(extraBurstCount) { index ->
            val anchor = if (index < 3 || effectRandom.nextFloat() < 0.58f) {
                pickCornerBiasedFireworkAnchor(randomArea, anchors, minSeparation)
            } else {
                pickRandomFireworkAnchor(randomArea, anchors, minSeparation)
            }
            anchors += anchor
            val chainDelay = FIREWORK_CHAIN_BASE_DELAY +
                index * FIREWORK_CHAIN_STEP_DELAY +
                effectRandom.nextFloat() * FIREWORK_CHAIN_JITTER
            queueFireworkLaunch(
                x = anchor.x,
                y = anchor.y,
                color = fireworkPalette[(type.ordinal + index + 1) % fireworkPalette.size],
                scale = scale * lerp(0.74f, 1.04f, effectRandom.nextFloat()),
                brightness = lerp(0.96f, 1.22f, effectRandom.nextFloat()),
                particleBoost = lerp(0.88f, 1.18f, effectRandom.nextFloat()),
                delay = chainDelay,
            )
        }
    }

    private fun queueFireworkLaunch(
        x: Float,
        y: Float,
        color: Int,
        scale: Float,
        brightness: Float,
        particleBoost: Float,
        delay: Float,
    ) {
        if (delay <= 0f) {
            spawnFireworkBurst(x, y, color, scale, brightness, particleBoost)
            return
        }

        scheduledFireworkLaunches += ScheduledFireworkLaunch(
            x = x,
            y = y,
            color = color,
            scale = scale,
            brightness = brightness,
            particleBoost = particleBoost,
            delay = delay,
        )
    }

    private fun pickCornerBiasedFireworkAnchor(
        area: RectF,
        existingAnchors: List<FireworkAnchor>,
        minSeparation: Float,
    ): FireworkAnchor {
        val cornerWidth = (area.width() * 0.24f).coerceAtLeast(126f)
        val cornerHeight = (area.height() * 0.22f).coerceAtLeast(150f)
        val cornerAreas = listOf(
            RectF(area.left, area.top, area.left + cornerWidth, area.top + cornerHeight),
            RectF(area.right - cornerWidth, area.top, area.right, area.top + cornerHeight),
            RectF(area.left, area.bottom - cornerHeight, area.left + cornerWidth, area.bottom),
            RectF(area.right - cornerWidth, area.bottom - cornerHeight, area.right, area.bottom),
        )
        var bestAnchor = pickRandomFireworkAnchor(area, existingAnchors, minSeparation)
        var bestScore = scoreFireworkAnchor(bestAnchor, existingAnchors, area, minSeparation)

        repeat(28) {
            val cornerArea = cornerAreas[effectRandom.nextInt(cornerAreas.size)]
            val candidate = FireworkAnchor(
                x = lerp(cornerArea.left, cornerArea.right, effectRandom.nextFloat()),
                y = lerp(cornerArea.top, cornerArea.bottom, effectRandom.nextFloat()),
            )
            val score = scoreFireworkAnchor(candidate, existingAnchors, area, minSeparation) + 34f
            if (score > bestScore) {
                bestScore = score
                bestAnchor = candidate
            }
        }

        return bestAnchor
    }

    private fun pickRandomFireworkAnchor(
        area: RectF,
        existingAnchors: List<FireworkAnchor>,
        minSeparation: Float,
    ): FireworkAnchor {
        var bestAnchor = FireworkAnchor(area.centerX(), area.centerY())
        var bestScore = Float.NEGATIVE_INFINITY

        repeat(24) {
            val candidate = FireworkAnchor(
                x = lerp(area.left, area.right, effectRandom.nextFloat()),
                y = lerp(area.top, area.bottom, effectRandom.nextFloat()),
            )
            val score = scoreFireworkAnchor(candidate, existingAnchors, area, minSeparation)
            if (score > bestScore) {
                bestScore = score
                bestAnchor = candidate
            }
        }

        return bestAnchor
    }

    private fun scoreFireworkAnchor(
        candidate: FireworkAnchor,
        existingAnchors: List<FireworkAnchor>,
        area: RectF,
        minSeparation: Float,
    ): Float {
        var nearestDistance = Float.POSITIVE_INFINITY
        for (anchor in existingAnchors) {
            val dx = candidate.x - anchor.x
            val dy = candidate.y - anchor.y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < nearestDistance) {
                nearestDistance = distance
            }
        }

        val edgeClearance = minOf(
            candidate.x - area.left,
            area.right - candidate.x,
            candidate.y - area.top,
            area.bottom - candidate.y,
        )
        return minOf(nearestDistance - minSeparation, edgeClearance)
    }

    private fun spawnFireworkBurst(
        x: Float,
        y: Float,
        color: Int,
        scale: Float,
        brightness: Float = 1f,
        particleBoost: Float = 1f,
    ) {
        val normalizedScale = (scale / 1.55f).coerceIn(0f, 1f)
        fireworkBursts += FireworkBurst(
            x = x,
            y = y,
            color = color,
            maxRadius = lerp(48f, 126f, normalizedScale),
            brightness = brightness,
        )

        val particleCount = (22 + scale * 14f * particleBoost).toInt()
        repeat(particleCount) {
            val angle = effectRandom.nextFloat() * TWO_PI
            val speed = lerp(220f, 520f, effectRandom.nextFloat()) * scale
            val particleColor = fireworkPalette[effectRandom.nextInt(fireworkPalette.size)]
            fireworkParticles += FireworkParticle(
                x = x,
                y = y,
                previousX = x,
                previousY = y,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                color = particleColor,
                radius = lerp(4f, 8f, effectRandom.nextFloat()) * scale.coerceAtLeast(0.58f),
                lifetime = lerp(
                    FIREWORK_PARTICLE_MIN_LIFETIME,
                    FIREWORK_PARTICLE_MAX_LIFETIME,
                    effectRandom.nextFloat(),
                ),
                brightness = brightness,
            )
        }
    }

    private fun drawFireworkEffects(canvas: Canvas) {
        for (burst in fireworkBursts) {
            val progress = (burst.elapsed / FIREWORK_BURST_DURATION).coerceIn(0f, 1f)
            val alpha = (1f - progress).coerceIn(0f, 1f)

            fireworkBurstPaint.color = burst.color
            fireworkBurstPaint.alpha = (alpha * 190f * burst.brightness).toInt().coerceIn(0, 255)
            fireworkBurstPaint.strokeWidth = lerp(14f, 2f, progress) * burst.brightness.coerceAtMost(1.5f)
            canvas.drawCircle(
                burst.x,
                burst.y,
                lerp(20f, burst.maxRadius, progress),
                fireworkBurstPaint,
            )

            fireworkParticlePaint.color = burst.color
            fireworkParticlePaint.alpha = (alpha * 90f * burst.brightness).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                burst.x,
                burst.y,
                lerp(16f, burst.maxRadius * 0.42f, progress),
                fireworkParticlePaint,
            )
        }

        for (particle in fireworkParticles) {
            val progress = (particle.elapsed / particle.lifetime).coerceIn(0f, 1f)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val trailX = lerp(particle.x, particle.previousX, 0.78f)
            val trailY = lerp(particle.y, particle.previousY, 0.78f)

            fireworkTrailPaint.color = particle.color
            fireworkTrailPaint.alpha = (alpha * 220f * particle.brightness).toInt().coerceIn(0, 255)
            fireworkTrailPaint.strokeWidth = particle.radius * lerp(1.45f, 0.45f, progress) * particle.brightness.coerceAtMost(1.45f)
            canvas.drawLine(particle.x, particle.y, trailX, trailY, fireworkTrailPaint)

            fireworkParticlePaint.color = particle.color
            fireworkParticlePaint.alpha = (alpha * 255f * particle.brightness).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                particle.x,
                particle.y,
                particle.radius * lerp(1.18f, 0.36f, progress),
                fireworkParticlePaint,
            )
        }
    }

    private fun clearTransientEffects() {
        mergeAnimations.clear()
        scheduledFireworkLaunches.clear()
        fireworkBursts.clear()
        fireworkParticles.clear()
    }

    private fun previewTitleCelebration() {
        val bestScoreY = titleButtonRect.bottom + 72f
        val footerY = world.binBottom - 72f
        val previewX = GameWorld.WORLD_WIDTH / 2f
        val previewY = ((bestScoreY + footerY) * 0.5f - 12f)
            .coerceIn(world.binTop + 280f, world.binBottom - 220f)

        clearTransientEffects()
        mergeAnimations += MergeAnimation(FruitType.GIANT_WATERMELON, previewX, previewY)
        launchScreenFireworkCelebration(FruitType.GIANT_WATERMELON, 1.18f)
        audioEngine.playMerge(FruitType.GIANT_WATERMELON)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }
}
