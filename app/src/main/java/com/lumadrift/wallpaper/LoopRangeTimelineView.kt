package com.lumadrift.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class LoopRangeTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        style = Paint.Style.FILL
    }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        style = Paint.Style.FILL
    }
    private var startFraction = 0f
    private var endFraction = 1f
    private val horizontalInset = 8f * resources.displayMetrics.density
    private val trackHeight = 5f * resources.displayMetrics.density
    private val handleRadius = 7f * resources.displayMetrics.density

    fun setRange(startTenths: Int, endTenths: Int, durationTenths: Int) {
        if (durationTenths <= 0) {
            startFraction = 0f
            endFraction = 0f
        } else {
            val safeStart = startTenths.coerceIn(0, durationTenths)
            val safeEnd = endTenths.coerceIn(safeStart, durationTenths)
            startFraction = safeStart.toFloat() / durationTenths.toFloat()
            endFraction = safeEnd.toFloat() / durationTenths.toFloat()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = horizontalInset
        val right = (width - horizontalInset).coerceAtLeast(left)
        val centerY = height / 2f
        val trackTop = centerY - trackHeight / 2f
        val trackBottom = centerY + trackHeight / 2f
        canvas.drawRoundRect(
            RectF(left, trackTop, right, trackBottom),
            trackHeight,
            trackHeight,
            trackPaint
        )

        val startX = left + (right - left) * startFraction
        val endX = left + (right - left) * endFraction
        canvas.drawRoundRect(
            RectF(startX, trackTop, endX.coerceAtLeast(startX), trackBottom),
            trackHeight,
            trackHeight,
            rangePaint
        )
        canvas.drawCircle(startX, centerY, handleRadius, rangePaint)
        canvas.drawCircle(endX, centerY, handleRadius, rangePaint)
    }
}
