package com.lumadrift.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class WallpaperPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var previewActive = false
    private var animationEnabled = true
    private var frameScheduled = false

    private val invalidateFrame = Runnable {
        frameScheduled = false
        if (previewActive && animationEnabled) invalidate()
    }

    init {
        contentDescription = context.getString(R.string.preview_description)
        isFocusable = false
    }

    fun setAnimationEnabled(enabled: Boolean) {
        animationEnabled = enabled
        removeScheduledFrame()
        invalidate()
        scheduleNextFrame()
    }

    fun setPreviewActive(active: Boolean) {
        previewActive = active
        if (active) {
            invalidate()
            scheduleNextFrame()
        } else {
            removeScheduledFrame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawWallpaper(canvas, SystemClock.elapsedRealtime())
        scheduleNextFrame()
    }

    override fun onDetachedFromWindow() {
        removeScheduledFrame()
        previewActive = false
        super.onDetachedFromWindow()
    }

    private fun drawWallpaper(canvas: Canvas, timeMs: Long) {
        val width = width.toFloat()
        val height = height.toFloat()
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

    private fun scheduleNextFrame() {
        if (!previewActive || !animationEnabled || frameScheduled) return
        frameScheduled = true
        postDelayed(invalidateFrame, FRAME_INTERVAL_MS)
    }

    private fun removeScheduledFrame() {
        removeCallbacks(invalidateFrame)
        frameScheduled = false
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 1_000L / 24L
    }
}
