package com.lumadrift.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
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
        private val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var visible = false
        private var surfaceAvailable = false
        private var destroyed = false
        private var animationEnabled = preferences.getBoolean(ANIMATION_ENABLED_KEY, true)
        private var lockSplitEnabled = preferences.getBoolean(LOCK_SPLIT_ENABLED_KEY, false)
        private var lockSegmentPercent = preferences.getInt(
            LOCK_SEGMENT_PERCENT_KEY,
            DEFAULT_LOCK_SEGMENT_PERCENT
        ).coerceIn(MIN_LOCK_SEGMENT_PERCENT, MAX_LOCK_SEGMENT_PERCENT)
        private var videoDurationMs = preferences.getLong(VIDEO_DURATION_MS_KEY, 0L)
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var selectedVideoUri = readSelectedVideoUri()
        private var lastSelectedVideoUri = selectedVideoUri
        private var failedVideoUri: Uri? = null
        private var videoPlayer: MediaPlayer? = null
        private var activeVideoUri: Uri? = null
        private var resumePositionMs = 0
        private var lastKnownLocked: Boolean? = null
        private var playRemainderAfterUnlock = false
        private var lockSegmentComplete = false
        private var receiverRegistered = false
        private var activeLockedSegmentEndMs = 0L

        private val frame = object : Runnable {
            override fun run() {
                if (!canDraw()) return
                drawFrame()
                if (animationEnabled && canDraw()) {
                    handler.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }

        private val lockSegmentBoundaryCheck = object : Runnable {
            override fun run() {
                val player = videoPlayer ?: return
                if (!canDraw() || !lockSplitEnabled || !keyguardManager.isKeyguardLocked) return

                val segmentEndMs = activeLockedSegmentEndMs
                if (segmentEndMs <= 0L) return

                try {
                    if (!player.isPlaying) return
                    if (player.currentPosition.toLong() >= segmentEndMs) {
                        player.pause()
                        seekTo(player, segmentEndMs)
                        resumePositionMs = segmentEndMs
                        lockSegmentComplete = true
                        activeLockedSegmentEndMs = 0L
                        return
                    }
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Could not check the lock-screen video position", error)
                    return
                }

                handler.postDelayed(this, LOCK_BOUNDARY_CHECK_INTERVAL_MS)
            }
        }

        private val lockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> updateRendering()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            preferences.registerOnSharedPreferenceChangeListener(this)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    lockStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(lockStateReceiver, filter)
            }
            receiverRegistered = true
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
            synchronizeLockState()
            surfaceAvailable = false
            handler.removeCallbacks(frame)
            handler.removeCallbacks(lockSegmentBoundaryCheck)
            releaseVideoPlayer(savePosition = true)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacks(frame)
            handler.removeCallbacks(lockSegmentBoundaryCheck)
            releaseVideoPlayer(savePosition = false)
            if (receiverRegistered) {
                unregisterReceiver(lockStateReceiver)
                receiverRegistered = false
            }
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
                        videoDurationMs = preferences.getLong(VIDEO_DURATION_MS_KEY, 0L)
                        lastKnownLocked = null
                        playRemainderAfterUnlock = false
                        lockSegmentComplete = false
                        releaseVideoPlayer(savePosition = false)
                    }
                    updateRendering()
                }
                VIDEO_DURATION_MS_KEY -> {
                    videoDurationMs = preferences.getLong(VIDEO_DURATION_MS_KEY, 0L)
                    updateRendering()
                }
                LOCK_SPLIT_ENABLED_KEY -> {
                    lockSplitEnabled = preferences.getBoolean(LOCK_SPLIT_ENABLED_KEY, false)
                    resetSplitPlaybackForPreferenceChange()
                    updateRendering()
                }
                LOCK_SEGMENT_PERCENT_KEY -> {
                    lockSegmentPercent = preferences.getInt(
                        LOCK_SEGMENT_PERCENT_KEY,
                        DEFAULT_LOCK_SEGMENT_PERCENT
                    ).coerceIn(MIN_LOCK_SEGMENT_PERCENT, MAX_LOCK_SEGMENT_PERCENT)
                    resetSplitPlaybackForPreferenceChange()
                    updateRendering()
                }
            }
        }

        private fun canDraw(): Boolean =
            !destroyed && visible && surfaceAvailable

        private fun updateRendering() {
            handler.removeCallbacks(frame)
            handler.removeCallbacks(lockSegmentBoundaryCheck)
            synchronizeLockState()
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
                player.isLooping = false
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
                        val actualDurationMs = preparedPlayer.duration.toLong()
                        if (actualDurationMs > 0L) videoDurationMs = actualDurationMs

                        val isLocked = keyguardManager.isKeyguardLocked
                        val lockSegmentActive =
                            lockSplitEnabled && isLocked && lockBoundaryMs() > 0L
                        val remainderActive =
                            lockSplitEnabled && !isLocked && playRemainderAfterUnlock &&
                                lockBoundaryMs() > 0L
                        val segmentEndMs = lockBoundaryMs()

                        if (lockSegmentActive && lockSegmentComplete) {
                            resumePositionMs = segmentEndMs
                            seekTo(preparedPlayer, segmentEndMs)
                            return@setOnPreparedListener
                        }

                        val startPositionMs = when {
                            lockSegmentActive ->
                                resumePositionMs.coerceIn(0L, segmentEndMs)
                            remainderActive ->
                                resumePositionMs.coerceAtLeast(segmentEndMs)
                            else -> resumePositionMs
                        }
                        if (startPositionMs > 0L) seekTo(preparedPlayer, startPositionMs)
                        activeLockedSegmentEndMs = if (lockSegmentActive) segmentEndMs else 0L
                        preparedPlayer.start()
                        if (lockSegmentActive) {
                            handler.removeCallbacks(lockSegmentBoundaryCheck)
                            handler.postDelayed(
                                lockSegmentBoundaryCheck,
                                LOCK_BOUNDARY_CHECK_INTERVAL_MS
                            )
                        }
                    } catch (error: IllegalStateException) {
                        handleVideoFailure(
                            uri,
                            preparedPlayer,
                            "Could not start the selected wallpaper video",
                            error
                        )
                    }
                }
                player.setOnCompletionListener { completedPlayer ->
                    if (videoPlayer !== completedPlayer) return@setOnCompletionListener
                    try {
                        val isLocked = keyguardManager.isKeyguardLocked
                        if (lockSplitEnabled && isLocked) {
                            lockSegmentComplete = true
                            resumePositionMs = lockBoundaryMs()
                            activeLockedSegmentEndMs = 0L
                            return@setOnCompletionListener
                        }

                        val loopStartMs = if (
                            lockSplitEnabled && playRemainderAfterUnlock && !isLocked
                        ) {
                            lockBoundaryMs()
                        } else {
                            0L
                        }
                        seekTo(completedPlayer, loopStartMs)
                        resumePositionMs = loopStartMs
                        completedPlayer.start()
                    } catch (error: IllegalStateException) {
                        handleVideoFailure(
                            uri,
                            completedPlayer,
                            "Could not loop the selected wallpaper video",
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

        private fun synchronizeLockState() {
            val isLocked = keyguardManager.isKeyguardLocked
            if (!lockSplitEnabled) {
                lastKnownLocked = isLocked
                playRemainderAfterUnlock = false
                lockSegmentComplete = false
                return
            }

            when {
                isLocked && lastKnownLocked != true -> {
                    playRemainderAfterUnlock = false
                    lockSegmentComplete = false
                    resumePositionMs = 0
                    releaseVideoPlayer(savePosition = false)
                }
                !isLocked && lastKnownLocked == true -> {
                    playRemainderAfterUnlock = true
                    lockSegmentComplete = true
                    resumePositionMs = lockBoundaryMs()
                    releaseVideoPlayer(savePosition = false)
                }
            }
            lastKnownLocked = isLocked
        }

        private fun resetSplitPlaybackForPreferenceChange() {
            lastKnownLocked = null
            playRemainderAfterUnlock = false
            lockSegmentComplete = false
            resumePositionMs = 0
            releaseVideoPlayer(savePosition = false)
        }

        private fun lockBoundaryMs(): Long {
            if (videoDurationMs <= 0L) return 0L
            return (videoDurationMs * lockSegmentPercent / 100L)
                .coerceIn(1L, videoDurationMs)
        }

        private fun seekTo(player: MediaPlayer, positionMs: Long) {
            val safePosition = positionMs.coerceAtLeast(0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(safePosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
            handler.removeCallbacks(lockSegmentBoundaryCheck)
            if (savePosition && activeVideoUri == selectedVideoUri) {
                try {
                    val currentPositionMs = player.currentPosition.toLong()
                    val lockSegmentEnd = activeLockedSegmentEndMs
                    if (lockSegmentEnd > 0L && currentPositionMs >= lockSegmentEnd) {
                        resumePositionMs = lockSegmentEnd
                        lockSegmentComplete = true
                    } else {
                        resumePositionMs = currentPositionMs
                    }
                } catch (_: IllegalStateException) {
                    // Keep the last known position if preparation had not completed.
                }
            }

            videoPlayer = null
            activeVideoUri = null
            activeLockedSegmentEndMs = 0L
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
        const val VIDEO_DURATION_MS_KEY = "selected_video_duration_ms"
        const val LOCK_SPLIT_ENABLED_KEY = "lock_split_enabled"
        const val LOCK_SEGMENT_PERCENT_KEY = "lock_segment_percent"

        private const val TAG = "LumaDrift"
        private const val FRAME_INTERVAL_MS = 1_000L / 24L
        private const val LOCK_BOUNDARY_CHECK_INTERVAL_MS = 100L
        private const val DEFAULT_LOCK_SEGMENT_PERCENT = 25
        private const val MIN_LOCK_SEGMENT_PERCENT = 1
        private const val MAX_LOCK_SEGMENT_PERCENT = 99
    }
}
