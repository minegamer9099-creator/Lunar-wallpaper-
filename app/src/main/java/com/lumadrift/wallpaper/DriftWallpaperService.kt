package com.lumadrift.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
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
        private var selectedVideoUri = readSelectedVideoUri()
        private var lastSelectedVideoUri = selectedVideoUri
        private var failedVideoUri: Uri? = null
        private var videoPlayer: MediaPlayer? = null
        private var activeVideoUri: Uri? = null
        private var resumePositionMs = 0

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
            releaseVideoPlayer(savePosition = true)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacks(frame)
            releaseVideoPlayer(savePosition = false)
            preferences.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: android.content.SharedPreferences?,
            key: String?
        ) {
            when (key) {
                ANIMATION_ENABLED_KEY -> {
                    animationEnabled = preferences.getBoolean(ANIMATION_ENABLED_KEY, true)
                    updateRendering()
                }
                VIDEO_URI_KEY -> {
                    val nextUri = readSelectedVideoUri()
                    if (nextUri != selectedVideoUri) {
                        selectedVideoUri = nextUri
                        if (nextUri != lastSelectedVideoUri) {
                            resumePositionMs = 0
                            failedVideoUri = null
                        }
                        lastSelectedVideoUri = nextUri
                        if (nextUri == null) {
                            resumePositionMs = 0
                            failedVideoUri = null
                        }
                    }
                    updateRendering()
                }
            }
        }

        private fun canDraw(): Boolean =
            !destroyed && visible && surfaceAvailable

        private fun updateRendering() {
            handler.removeCallbacks(frame)
            if (!canDraw()) {
                releaseVideoPlayer(savePosition = true)
                return
            }

            val videoUri = selectedVideoUri
            if (videoUri != null && videoUri != failedVideoUri) {
                if (activeVideoUri == videoUri && videoPlayer != null) return
                if (videoPlayer != null) releaseVideoPlayer(savePosition = false)
                startVideoPlayer(videoUri)
                return
            }

            releaseVideoPlayer(savePosition = false)
            drawFrame()
            if (animationEnabled) {
                handler.postDelayed(frame, FRAME_INTERVAL_MS)
            }
        }

        private fun startVideoPlayer(uri: Uri) {
            if (!canDraw()) return

            val player = try {
                MediaPlayer()
            } catch (error: RuntimeException) {
                handleVideoFailure(uri, null, "Could not create the video player", error)
                return
            }

            videoPlayer = player
            activeVideoUri = uri
            try {
                player.setDataSource(this@DriftWallpaperService, uri)
                player.setSurface(surfaceHolder.surface)
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                player.isLooping = true
                player.setVolume(0f, 0f)
                player.setOnPreparedListener { preparedPlayer ->
                    if (
                        videoPlayer !== preparedPlayer ||
                        !canDraw() ||
                        selectedVideoUri != uri
                    ) {
                        if (videoPlayer === preparedPlayer) {
                            releaseVideoPlayer(savePosition = false)
                        }
                        return@setOnPreparedListener
                    }

                    try {
                        if (resumePositionMs > 0) preparedPlayer.seekTo(resumePositionMs)
                        preparedPlayer.start()
                    } catch (error: IllegalStateException) {
                        handleVideoFailure(
                            uri,
                            preparedPlayer,
                            "Could not start the selected wallpaper video",
                            error
                        )
                    }
                }
                player.setOnErrorListener { failedPlayer, what, extra ->
                    handleVideoFailure(
                        uri,
                        failedPlayer,
                        "Video playback failed (what=$what, extra=$extra)",
                        null
                    )
                    true
                }
                player.prepareAsync()
            } catch (error: Exception) {
                handleVideoFailure(uri, player, "Could not prepare the selected wallpaper video", error)
            }
        }

        private fun handleVideoFailure(
            uri: Uri,
            player: MediaPlayer?,
            message: String,
            error: Throwable?
        ) {
            if (player != null && videoPlayer !== player) return
            failedVideoUri = uri
            if (error == null) {
                Log.e(TAG, "$message; using the built-in wallpaper instead")
            } else {
                Log.e(TAG, "$message; using the built-in wallpaper instead", error)
            }
            releaseVideoPlayer(savePosition = false)
            updateRendering()
        }

        private fun releaseVideoPlayer(savePosition: Boolean) {
            val player = videoPlayer ?: return
            if (savePosition && activeVideoUri == selectedVideoUri) {
                try {
                    resumePositionMs = player.currentPosition
                } catch (_: IllegalStateException) {
                    // Keep the last known position if preparation had not completed.
                }
            }

            videoPlayer = null
            activeVideoUri = null
            try {
                player.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not release the video player cleanly", error)
            }
        }

        private fun readSelectedVideoUri(): Uri? =
            preferences.getString(VIDEO_URI_KEY, null)?.let { Uri.parse(it) }

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
        const val VIDEO_URI_KEY = "selected_video_uri"
        const val VIDEO_NAME_KEY = "selected_video_name"

        private const val TAG = "LumaDrift"
        private const val FRAME_INTERVAL_MS = 1_000L / 24L
    }
}
