package com.lumadrift.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import kotlin.math.min
import kotlin.math.sin

class DriftWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DriftEngine()

    private inner class DriftEngine : Engine(),
        android.content.SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var visible = false
        private var surfaceAvailable = false
        private var destroyed = false
        private var animationEnabled = preferences.getBoolean(ANIMATION_ENABLED_KEY, true)
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private val frame = object : Runnable {
            override fun run() {
                if (!canDraw()) return
                drawFrame()
                if (animationEnabled && canDraw()) {
                    handler.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            preferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            updateRendering()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            surfaceAvailable = width > 0 && height > 0
            updateRendering()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacks(frame)
            preferences.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: android.content.SharedPreferences?,
            key: String?
        ) {
            if (key == ANIMATION_ENABLED_KEY) {
                animationEnabled = preferences.getBoolean(ANIMATION_ENABLED_KEY, true)
                updateRendering()
            }
        }

        private fun canDraw(): Boolean =
            !destroyed && visible && surfaceAvailable

        private fun updateRendering() {
            handler.removeCallbacks(frame)
            if (!canDraw()) return

            drawFrame()
            if (animationEnabled) {
                handler.postDelayed(frame, FRAME_INTERVAL_MS)
            }
        }

        private fun drawFrame() {
            if (!canDraw()) return

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas, SystemClock.elapsedRealtime())
            } catch (error: RuntimeException) {
                Log.w(TAG, "Wallpaper surface is not available for drawing", error)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Could not release wallpaper canvas", error)
                    }
                }
            }
        }

        private fun render(canvas: Canvas, timeMs: Long) {
            val width = surfaceWidth.toFloat()
            val height = surfaceHeight.toFloat()
            val minDimension = min(width, height)
            val motion = if (animationEnabled) timeMs / 3_200.0 else 0.0
            val driftX = (sin(motion) * minDimension * 0.08f).toFloat()
            val driftY = (sin(motion * 0.72) * minDimension * 0.045f).toFloat()

            canvas.drawColor(Color.rgb(9, 16, 35))

            basePaint.color = Color.rgb(15, 29, 57)
            canvas.drawCircle(width * 0.78f, height * 0.24f, minDimension * 0.42f, basePaint)
            basePaint.color = Color.rgb(11, 22, 45)
            canvas.drawCircle(width * 0.18f, height * 0.78f, minDimension * 0.46f, basePaint)

            orbPaint.color = Color.argb(74, 72, 168, 232)
            canvas.drawCircle(
                width * 0.72f + driftX,
                height * 0.34f + driftY,
                minDimension * 0.21f,
                orbPaint
            )
            orbPaint.color = Color.argb(58, 91, 117, 226)
            canvas.drawCircle(
                width * 0.28f - driftX * 0.55f,
                height * 0.68f - driftY * 0.7f,
                minDimension * 0.16f,
                orbPaint
            )
            orbPaint.color = Color.argb(96, 170, 218, 247)
            canvas.drawCircle(
                width * 0.53f + driftX * 0.25f,
                height * 0.48f + driftY * 0.35f,
                minDimension * 0.018f,
                orbPaint
            )
        }
    }

    companion object {
        const val PREFERENCES_NAME = "wallpaper_settings"
        const val ANIMATION_ENABLED_KEY = "animation_enabled"

        private const val TAG = "LumaDrift"
        private const val FRAME_INTERVAL_MS = 1_000L / 24L
    }
}
