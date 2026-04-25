package com.syntheticwatermelon.nativegame

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.syntheticwatermelon.nativegame.game.GameSurfaceView

class MainActivity : Activity() {
    private lateinit var gameSurfaceView: GameSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameSurfaceView = GameSurfaceView(this)
        setContentView(gameSurfaceView)
    }

    override fun onResume() {
        super.onResume()
        gameSurfaceView.resumeGameLoop()
    }

    override fun onPause() {
        gameSurfaceView.pauseGameLoop()
        super.onPause()
    }

    override fun onDestroy() {
        gameSurfaceView.release()
        super.onDestroy()
    }
}
