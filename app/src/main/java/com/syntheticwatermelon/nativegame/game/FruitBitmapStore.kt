package com.syntheticwatermelon.nativegame.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.syntheticwatermelon.nativegame.game.model.FruitType

class FruitBitmapStore(context: Context) {
    private val assetManager = context.applicationContext.assets
    private val bitmaps = FruitType.entries.associateWith(::loadBitmap).toMutableMap()

    fun getBitmap(type: FruitType): Bitmap = bitmaps.getValue(type)

    fun release() {
        bitmaps.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        bitmaps.clear()
    }

    private fun loadBitmap(type: FruitType): Bitmap {
        val assetPath = GameAssetCatalog.fruitAssetPath(type)
        return assetManager.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input)
                ?: error("Failed to decode fruit bitmap: $assetPath")
        }
    }
}
