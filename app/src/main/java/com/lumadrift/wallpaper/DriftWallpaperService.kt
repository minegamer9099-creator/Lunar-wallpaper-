package com.lumadrift.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class DriftWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DriftEngine()

    private inner class DriftEngine : Engine(),
        android.content.SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        private val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var wallpaperZoom = 0f
        private var api31BlurRenderer: Api31BlurRenderer? = null
        private var visible = false
        private var surfaceAvailable = false
        private var destroyed = false
        private var animationEnabled = preferences.getBoolean(ANIMATION_ENABLED_KEY, true)
        private var videoLoopEnabled = preferences.getBoolean(VIDEO_LOOP_ENABLED_KEY, false)
        private var loopStartTenths = preferences.getInt(VIDEO_LOOP_START_TENTHS_KEY, 0)
            .coerceAtLeast(0)
        private var loopEndTenths = preferences.getInt(VIDEO_LOOP_END_TENTHS_KEY, 0)
            .coerceAtLeast(0)
        private var videoDurationMs = preferences.getLong(VIDEO_DURATION_MS_KEY, 0L)
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var selectedVideoUri = readSelectedVideoUri()
        private var lastSelectedVideoUri = selectedVideoUri
        private var failedVideoUri: Uri? = null
        private var videoPlayer: MediaPlayer? = null
        private var activeVideoUri: Uri? = null
        private var videoSurfaceAttached = false
        private var resumePositionMs = preferences
            .getLong(VIDEO_RESUME_POSITION_MS_KEY, 0L)
            .coerceAtLeast(0L)
        private var receiverRegistered = false
        private var screenIsOn = powerManager.isInteractive
        private var restartAtNextScreenOn = preferences.getBoolean(
            RESTART_VIDEO_ON_SCREEN_ON_KEY,
            false
        ) || !screenIsOn
        private var restartSeekReady = false
        private var playerPrepared = false
        private var seekInFlight = false
        private var desiredSeekPositionMs: Long? = null
        private var issuedSeekPositionMs = 0L
        private var startAfterSeek = false
        private var loopSeekPending = false
        private var loopTimerPlayer: MediaPlayer? = null
        private var loopTimerGeneration = 0L
        private var scheduledLoopTimerGeneration = 0L
        private var animationElapsedBeforePauseMs = preferences
            .getLong(BUILT_IN_ANIMATION_TIME_MS_KEY, 0L)
            .coerceAtLeast(0L)
        private var animationStartedAtElapsedMs: Long? = null

        private val deferredVideoRelease = Runnable {
            if (!canDraw() && screenIsOn) releaseVideoPlayer(savePosition = true)
        }

        private val frame = object : Runnable {
            override fun run() {
                if (!shouldAnimateBuiltIn()) return
                drawFrame()
                if (shouldAnimateBuiltIn()) {
                    handler.postDelayed(this, FRAME_INTERVAL_MS)
                }
            }
        }

        private class Api31BlurRenderer {
            private val renderNode = RenderNode("LumaDriftWallpaper")
            private var appliedBlurRadiusPx = -1

            fun beginRecording(width: Int, height: Int): Canvas {
                renderNode.setPosition(0, 0, width, height)
                return renderNode.beginRecording(width, height)
            }

            fun endRecording() {
                renderNode.endRecording()
            }

            fun draw(canvas: Canvas, blurRadiusPx: Int) {
                if (appliedBlurRadiusPx != blurRadiusPx) {
                    renderNode.setRenderEffect(
                        if (blurRadiusPx > 0) {
                            RenderEffect.createBlurEffect(
                                blurRadiusPx.toFloat(),
                                blurRadiusPx.toFloat(),
                                Shader.TileMode.CLAMP
                            )
                        } else {
                            null
                        }
                    )
                    appliedBlurRadiusPx = blurRadiusPx
                }
                canvas.drawRenderNode(renderNode)
            }
        }

        private val loopEndBoundaryCheck = object : Runnable {
            override fun run() {
                val player = videoPlayer ?: return
                if (
                    player !== loopTimerPlayer ||
                    scheduledLoopTimerGeneration != loopTimerGeneration ||
                    !videoLoopEnabled ||
                    !isLoopRangeValid() ||
                    !canDraw() ||
                    !screenIsOn
                ) return
                if (!isPlayerPlaying(player)) {
                    cancelLoopBoundaryTimer()
                    return
                }

                val currentPositionMs = try {
                    player.currentPosition.toLong()
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Could not read video position at the loop boundary", error)
                    cancelLoopBoundaryTimer()
                    return
                }

                val remainingMs = loopEndMs() - currentPositionMs
                if (remainingMs > 0L) {
                    scheduleLoopBoundary(player, remainingMs)
                } else {
                    jumpToLoopStart(player)
                }
            }
        }

        private val screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON -> handleScreenOn()
                }
            }
        }

        private fun handleScreenOff() {
            screenIsOn = false
            restartAtNextScreenOn = true
            restartSeekReady = false
            preferences.edit()
                .putBoolean(RESTART_VIDEO_ON_SCREEN_ON_KEY, true)
                .apply()
            updateRendering()
            prepareRestartWhileScreenOff()
        }

        private fun handleScreenOn() {
            screenIsOn = true
            updateRendering()
        }

        private fun prepareRestartWhileScreenOff() {
            val player = videoPlayer ?: return
            if (!playerPrepared || activeVideoUri != selectedVideoUri) return

            // Queue the required restart seek while the display is off, not on the lock screen.
            requestPlaybackPosition(
                player,
                0L,
                shouldStart = false,
                forceSeek = true
            )
        }

        private fun refreshScreenStateFromPowerManager() {
            screenIsOn = powerManager.isInteractive
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            preferences.registerOnSharedPreferenceChangeListener(this)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    screenStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenStateReceiver, filter)
            }
            receiverRegistered = true
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            updateRendering(refreshScreenState = true)
        }

        override fun onZoomChanged(zoom: Float) {
            val nextZoom = zoom.coerceIn(0f, 1f)
            if (nextZoom == wallpaperZoom) return

            // Use only the zoom progress supplied by Android; launchers may not send it.
            wallpaperZoom = nextZoom
            if (
                canDraw() &&
                shouldDrawBuiltInWallpaper() &&
                !shouldAnimateBuiltIn()
            ) {
                drawFrame()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAvailable = false
            if (videoSurfaceAttached) {
                pauseVideoPlayerForTransition(detachSurface = true)
            }
            videoSurfaceAttached = false
            updateRendering(refreshScreenState = true)
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
            updateRendering(refreshScreenState = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false
            updateRendering(refreshScreenState = true)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacks(frame)
            handler.removeCallbacks(deferredVideoRelease)
            updateAnimationClock()
            cancelLoopBoundaryTimer()
            releaseVideoPlayer(savePosition = true)
            if (receiverRegistered) {
                unregisterReceiver(screenStateReceiver)
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
                            resumePositionMs = 0L
                            persistResumePosition(0L)
                            failedVideoUri = null
                        }
                        lastSelectedVideoUri = nextUri
                        if (nextUri == null) {
                            resumePositionMs = 0L
                            persistResumePosition(0L)
                            failedVideoUri = null
                        }
                        videoDurationMs = preferences.getLong(VIDEO_DURATION_MS_KEY, 0L)
                        videoLoopEnabled = preferences.getBoolean(VIDEO_LOOP_ENABLED_KEY, false)
                        loopStartTenths = preferences.getInt(
                            VIDEO_LOOP_START_TENTHS_KEY,
                            0
                        ).coerceAtLeast(0)
                        loopEndTenths = preferences.getInt(
                            VIDEO_LOOP_END_TENTHS_KEY,
                            0
                        ).coerceAtLeast(0)
                        loopSeekPending = false
                        cancelLoopBoundaryTimer()
                        releaseVideoPlayer(savePosition = false)
                    }
                    updateRendering()
                }
                VIDEO_DURATION_MS_KEY -> {
                    videoDurationMs = preferences.getLong(VIDEO_DURATION_MS_KEY, 0L)
                    normalizeLoopRange()
                    updateRendering()
                }
                VIDEO_LOOP_ENABLED_KEY -> {
                    videoLoopEnabled = preferences.getBoolean(VIDEO_LOOP_ENABLED_KEY, false)
                    applyLoopRangeToActivePlayer()
                    updateRendering()
                }
                VIDEO_LOOP_START_TENTHS_KEY,
                VIDEO_LOOP_END_TENTHS_KEY -> {
                    normalizeLoopRange()
                    applyLoopRangeToActivePlayer()
                    updateRendering()
                }
            }
        }

        private fun canDraw(): Boolean =
            !destroyed && visible && surfaceAvailable

        private fun updateRendering(refreshScreenState: Boolean = false) {
            handler.removeCallbacks(frame)
            handler.removeCallbacks(deferredVideoRelease)
            if (refreshScreenState) refreshScreenStateFromPowerManager()
            updateAnimationClock()
            if (!canDraw() || !screenIsOn) {
                pauseVideoPlayerForTransition(
                    detachSurface = !surfaceAvailable || !screenIsOn
                )
                scheduleDeferredVideoReleaseIfNeeded()
                return
            }

            val videoUri = selectedVideoUri
            val restartPlayback = restartAtNextScreenOn
            val restartPositionReady = restartPlayback && restartSeekReady
            if (restartPlayback) {
                videoPlayer?.let { pauseVideoPlayerForTransition(detachSurface = true) }
                restartAtNextScreenOn = false
                restartSeekReady = false
                resumePositionMs = 0L
                persistResumePosition(0L)
                preferences.edit()
                    .putBoolean(RESTART_VIDEO_ON_SCREEN_ON_KEY, false)
                    .apply()
                loopSeekPending = false
                cancelLoopBoundaryTimer()
                if (videoUri != null && videoUri != failedVideoUri) {
                    // Replace the Surface's retained pre-lock buffer before video output is reattached.
                    drawFrame()
                }
            }

            if (videoUri != null && videoUri != failedVideoUri) {
                if (activeVideoUri == videoUri && videoPlayer != null) {
                    val player = videoPlayer ?: return
                    if (playerPrepared && (restartPlayback || !isPlayerPlaying(player))) {
                        if (restartPositionReady) {
                            startPreparedPlayer(player)
                        } else {
                            requestPlaybackPosition(
                                player,
                                if (restartPlayback) 0L else resumePositionMs,
                                shouldStart = true,
                                forceSeek = restartPlayback
                            )
                        }
                    } else if (!restartPlayback && !attachVideoSurface(player)) {
                        return
                    }
                    return
                }
                if (videoPlayer != null) releaseVideoPlayer(savePosition = false)
                startVideoPlayer(videoUri)
                return
            }

            releaseVideoPlayer(savePosition = false)
            drawFrame()
            if (shouldAnimateBuiltIn()) {
                handler.postDelayed(frame, FRAME_INTERVAL_MS)
            }
        }

        private fun shouldAnimateBuiltIn(): Boolean =
            animationEnabled &&
                shouldDrawBuiltInWallpaper() &&
                videoPlayer == null &&
                canDraw() &&
                screenIsOn

        private fun shouldDrawBuiltInWallpaper(): Boolean =
            selectedVideoUri == null || selectedVideoUri == failedVideoUri

        private fun blurRadiusPx(): Int =
            (min(surfaceWidth, surfaceHeight) * MAX_BLUR_RADIUS_FRACTION * wallpaperZoom)
                .roundToInt()
                .coerceIn(0, MAX_BLUR_RADIUS_PX)

        private fun updateAnimationClock() {
            val shouldRun = shouldAnimateBuiltIn()
            val nowMs = SystemClock.elapsedRealtime()
            val startedAtMs = animationStartedAtElapsedMs

            if (shouldRun) {
                if (startedAtMs == null) animationStartedAtElapsedMs = nowMs
            } else if (startedAtMs != null) {
                animationElapsedBeforePauseMs += (nowMs - startedAtMs).coerceAtLeast(0L)
                animationStartedAtElapsedMs = null
                preferences.edit()
                    .putLong(BUILT_IN_ANIMATION_TIME_MS_KEY, animationElapsedBeforePauseMs)
                    .apply()
            }
        }

        private fun animationTimeMs(): Long {
            val runningDurationMs = animationStartedAtElapsedMs?.let {
                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
            } ?: 0L
            return animationElapsedBeforePauseMs + runningDurationMs
        }

        private fun pauseVideoPlayerForTransition(detachSurface: Boolean) {
            cancelLoopBoundaryTimer()
            val player = videoPlayer ?: return
            loopSeekPending = false

            if (playerPrepared) {
                try {
                    if (activeVideoUri == selectedVideoUri) {
                        val currentPositionMs = player.currentPosition.toLong()
                        resumePositionMs = currentPositionMs.coerceAtLeast(0L)
                        persistResumePosition(resumePositionMs)
                    }
                    if (player.isPlaying) player.pause()
                } catch (_: IllegalStateException) {
                    // Keep the last known position while Android changes wallpaper surfaces.
                }
            }

            if (detachSurface && videoSurfaceAttached) {
                try {
                    player.setSurface(null)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Could not detach the wallpaper surface during a transition", error)
                } finally {
                    videoSurfaceAttached = false
                }
            }
        }

        private fun attachVideoSurface(player: MediaPlayer): Boolean {
            if (videoSurfaceAttached) return true
            val surface = surfaceHolder.surface
            if (!surface.isValid) return false

            return try {
                player.setSurface(surface)
                videoSurfaceAttached = true
                true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not attach the wallpaper surface to the video player", error)
                false
            }
        }

        private fun scheduleDeferredVideoReleaseIfNeeded() {
            if (videoPlayer == null || !screenIsOn) return
            handler.removeCallbacks(deferredVideoRelease)
            handler.postDelayed(deferredVideoRelease, VIDEO_PLAYER_RELEASE_DELAY_MS)
        }

        private fun startVideoPlayer(uri: Uri) {
            if (!canDraw() || !screenIsOn) return

            val player = try {
                MediaPlayer()
            } catch (error: RuntimeException) {
                handleVideoFailure(uri, null, "Could not create the video player", error)
                return
            }

            videoPlayer = player
            activeVideoUri = uri
            playerPrepared = false
            seekInFlight = false
            desiredSeekPositionMs = null
            startAfterSeek = false
            try {
                player.setDataSource(this@DriftWallpaperService, uri)
                player.setSurface(surfaceHolder.surface)
                videoSurfaceAttached = true
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                player.isLooping = false
                player.setVolume(0f, 0f)
                player.setOnPreparedListener { preparedPlayer ->
                    if (
                        videoPlayer !== preparedPlayer ||
                        selectedVideoUri != uri
                    ) {
                        if (videoPlayer === preparedPlayer) {
                            releaseVideoPlayer(savePosition = false)
                        }
                        return@setOnPreparedListener
                    }

                    playerPrepared = true
                    val actualDurationMs = preparedPlayer.duration.toLong()
                    if (actualDurationMs > 0L) {
                        videoDurationMs = actualDurationMs
                        normalizeLoopRange()
                    }
                    if (!canDraw() || !screenIsOn) return@setOnPreparedListener

                    try {
                        requestPlaybackPosition(
                            preparedPlayer,
                            resumePositionMs.coerceIn(
                                0L,
                                videoDurationMs.coerceAtLeast(0L)
                            ),
                            shouldStart = screenIsOn && canDraw()
                        )
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
                        jumpToLoopStart(completedPlayer)
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
                player.setOnSeekCompleteListener { seekedPlayer ->
                    if (videoPlayer !== seekedPlayer) return@setOnSeekCompleteListener
                    seekInFlight = false
                    val desiredPosition = desiredSeekPositionMs ?: return@setOnSeekCompleteListener
                    if (desiredPosition != issuedSeekPositionMs) {
                        issuePendingSeek(seekedPlayer)
                        return@setOnSeekCompleteListener
                    }

                    desiredSeekPositionMs = null
                    resumePositionMs = desiredPosition
                    if (
                        desiredPosition == 0L &&
                        restartAtNextScreenOn &&
                        !screenIsOn
                    ) {
                        restartSeekReady = true
                    }
                    val shouldStart = startAfterSeek
                    startAfterSeek = false
                    if (shouldStart) startPreparedPlayer(seekedPlayer)
                }
                player.prepareAsync()
            } catch (error: Exception) {
                handleVideoFailure(uri, player, "Could not prepare the selected wallpaper video", error)
            }
        }

        private fun normalizeLoopRange() {
            val maxTenths = (videoDurationMs / MILLISECONDS_PER_TENTH)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (maxTenths < 1) {
                loopStartTenths = 0
                loopEndTenths = 0
                cancelLoopBoundaryTimer()
                return
            }

            val storedStart = preferences.getInt(VIDEO_LOOP_START_TENTHS_KEY, 0)
            val storedEnd = preferences.getInt(VIDEO_LOOP_END_TENTHS_KEY, maxTenths)
            var safeStart = storedStart.coerceIn(0, maxTenths - 1)
            var safeEnd = storedEnd.coerceIn(1, maxTenths)
            if (safeEnd <= safeStart) {
                if (safeStart < maxTenths - 1) {
                    safeEnd = safeStart + 1
                } else {
                    safeStart = safeEnd - 1
                }
            }

            loopStartTenths = safeStart
            loopEndTenths = safeEnd
            if (
                storedStart != safeStart ||
                storedEnd != safeEnd ||
                !preferences.contains(VIDEO_LOOP_START_TENTHS_KEY) ||
                !preferences.contains(VIDEO_LOOP_END_TENTHS_KEY)
            ) {
                preferences.edit()
                    .putInt(VIDEO_LOOP_START_TENTHS_KEY, safeStart)
                    .putInt(VIDEO_LOOP_END_TENTHS_KEY, safeEnd)
                    .apply()
            }
        }

        private fun isLoopRangeValid(): Boolean {
            if (!videoLoopEnabled || videoDurationMs <= 0L) return false
            val startMs = loopStartTenths.toLong() * MILLISECONDS_PER_TENTH
            val endMs = loopEndTenths.toLong() * MILLISECONDS_PER_TENTH
            return startMs >= 0L && startMs < endMs && endMs <= videoDurationMs
        }

        private fun loopStartMs(): Long =
            (loopStartTenths.toLong() * MILLISECONDS_PER_TENTH)
                .coerceIn(0L, videoDurationMs.coerceAtLeast(0L))

        private fun loopEndMs(): Long =
            (loopEndTenths.toLong() * MILLISECONDS_PER_TENTH)
                .coerceIn(0L, videoDurationMs.coerceAtLeast(0L))

        private fun scheduleLoopBoundary(player: MediaPlayer, delayMs: Long) {
            cancelLoopBoundaryTimer()
            if (
                videoPlayer !== player ||
                !playerPrepared ||
                !videoLoopEnabled ||
                !isLoopRangeValid() ||
                loopEndMs() >= videoDurationMs
            ) return

            loopTimerPlayer = player
            scheduledLoopTimerGeneration = loopTimerGeneration
            handler.postDelayed(loopEndBoundaryCheck, delayMs.coerceAtLeast(1L))
        }

        private fun cancelLoopBoundaryTimer() {
            handler.removeCallbacks(loopEndBoundaryCheck)
            loopTimerPlayer = null
            loopTimerGeneration += 1L
        }

        private fun jumpToLoopStart(player: MediaPlayer) {
            if (videoPlayer !== player || loopSeekPending) return
            cancelLoopBoundaryTimer()
            loopSeekPending = true
            val targetMs = if (isLoopRangeValid()) loopStartMs() else 0L
            requestPlaybackPosition(
                player,
                targetMs,
                shouldStart = true,
                forceSeek = true
            )
        }

        private fun applyLoopRangeToActivePlayer() {
            cancelLoopBoundaryTimer()
            loopSeekPending = false
            val player = videoPlayer ?: return
            if (!playerPrepared || !isPlayerPlaying(player)) return
            if (!isLoopRangeValid()) return

            val currentPositionMs = try {
                player.currentPosition.toLong()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Could not update the active video loop range", error)
                return
            }
            val endMs = loopEndMs()
            if (currentPositionMs >= endMs) {
                jumpToLoopStart(player)
            } else {
                scheduleLoopBoundary(player, endMs - currentPositionMs)
            }
        }

        private fun requestPlaybackPosition(
            player: MediaPlayer,
            positionMs: Long,
            shouldStart: Boolean,
            forceSeek: Boolean = false
        ) {
            if (videoPlayer !== player) return
            val safePositionMs = positionMs.coerceIn(
                0L,
                videoDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
            )
            resumePositionMs = safePositionMs
            persistResumePosition(safePositionMs)

            if (!playerPrepared) {
                desiredSeekPositionMs = safePositionMs
                startAfterSeek = shouldStart
                return
            }

            if (seekInFlight) {
                desiredSeekPositionMs = safePositionMs
                startAfterSeek = shouldStart
                return
            }

            val currentPositionMs = try {
                player.currentPosition.toLong()
            } catch (_: IllegalStateException) {
                null
            }
            if (
                currentPositionMs != null &&
                (
                    currentPositionMs == safePositionMs ||
                        (!forceSeek &&
                            kotlin.math.abs(currentPositionMs - safePositionMs) <=
                            SEEK_TOLERANCE_MS)
                    )
            ) {
                desiredSeekPositionMs = null
                startAfterSeek = false
                if (shouldStart) {
                    startPreparedPlayer(player)
                } else if (isPlayerPlaying(player)) {
                    try {
                        player.pause()
                    } catch (_: IllegalStateException) {
                        // The player is being released or reset by Android.
                    }
                }
                return
            }

            desiredSeekPositionMs = safePositionMs
            startAfterSeek = shouldStart
            issuePendingSeek(player)
        }

        private fun issuePendingSeek(player: MediaPlayer) {
            val requestedPositionMs = desiredSeekPositionMs ?: return
            if (videoPlayer !== player || !playerPrepared) return
            try {
                if (isPlayerPlaying(player)) player.pause()
                seekInFlight = true
                issuedSeekPositionMs = requestedPositionMs
                seekTo(player, requestedPositionMs)
            } catch (error: IllegalStateException) {
                seekInFlight = false
                handleVideoFailure(
                    activeVideoUri ?: selectedVideoUri ?: return,
                    player,
                    "Could not seek the selected wallpaper video",
                    error
                )
            }
        }

        private fun startPreparedPlayer(player: MediaPlayer) {
            if (videoPlayer !== player) return
            loopSeekPending = false
            if (
                !playerPrepared ||
                !canDraw() ||
                !screenIsOn
            ) return

            try {
                if (isLoopRangeValid()) {
                    val currentPositionMs = player.currentPosition.toLong()
                    if (currentPositionMs >= loopEndMs()) {
                        jumpToLoopStart(player)
                        return
                    }
                }

                if (!attachVideoSurface(player)) return
                if (!player.isPlaying) player.start()
                val currentPositionMs = player.currentPosition.toLong().coerceAtLeast(0L)
                resumePositionMs = currentPositionMs
                persistResumePosition(currentPositionMs)
                if (isLoopRangeValid() && loopEndMs() < videoDurationMs) {
                    scheduleLoopBoundary(
                        player,
                        (loopEndMs() - currentPositionMs).coerceAtLeast(1L)
                    )
                } else {
                    cancelLoopBoundaryTimer()
                }
            } catch (error: IllegalStateException) {
                handleVideoFailure(
                    activeVideoUri ?: selectedVideoUri ?: return,
                    player,
                    "Could not resume the selected wallpaper video",
                    error
                )
            }
        }

        private fun isPlayerPlaying(player: MediaPlayer): Boolean =
            try {
                player.isPlaying
            } catch (_: IllegalStateException) {
                false
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
            handler.removeCallbacks(deferredVideoRelease)
            cancelLoopBoundaryTimer()
            restartSeekReady = false
            val player = videoPlayer ?: return
            if (
                savePosition &&
                activeVideoUri == selectedVideoUri
            ) {
                try {
                    resumePositionMs = player.currentPosition.toLong().coerceAtLeast(0L)
                } catch (_: IllegalStateException) {
                    // Keep the last known position if preparation had not completed.
                }
                persistResumePosition(resumePositionMs)
            }

            videoPlayer = null
            activeVideoUri = null
            videoSurfaceAttached = false
            playerPrepared = false
            seekInFlight = false
            desiredSeekPositionMs = null
            startAfterSeek = false
            loopSeekPending = false
            try {
                player.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not release the video player cleanly", error)
            }
        }

        private fun readSelectedVideoUri(): Uri? =
            preferences.getString(VIDEO_URI_KEY, null)?.let { Uri.parse(it) }

        private fun persistResumePosition(positionMs: Long) {
            preferences.edit()
                .putLong(VIDEO_RESUME_POSITION_MS_KEY, positionMs.coerceAtLeast(0L))
                .apply()
        }

        private fun drawFrame() {
            if (!canDraw()) return

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (shouldUseHardwareBlurCanvas()) {
                    try {
                        holder.lockHardwareCanvas()
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Hardware canvas unavailable for the wallpaper transition", error)
                        holder.lockCanvas()
                    }
                } else {
                    holder.lockCanvas()
                }
                if (canvas != null) renderFrame(canvas, animationTimeMs())
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

        private fun shouldUseHardwareBlurCanvas(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                wallpaperZoom > 0f &&
                shouldDrawBuiltInWallpaper() &&
                blurRadiusPx() > 0

        private fun renderFrame(canvas: Canvas, timeMs: Long) {
            val blurRadius = blurRadiusPx()
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !canvas.isHardwareAccelerated ||
                !shouldDrawBuiltInWallpaper() ||
                blurRadius == 0
            ) {
                // Video stays on MediaPlayer's direct Surface path; no frame copy or blur pass.
                render(canvas, timeMs)
                return
            }

            val renderer = api31BlurRenderer ?: Api31BlurRenderer().also {
                api31BlurRenderer = it
            }
            val recordingCanvas = renderer.beginRecording(surfaceWidth, surfaceHeight)
            try {
                render(recordingCanvas, timeMs)
            } finally {
                renderer.endRecording()
            }
            renderer.draw(canvas, blurRadius)
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
        const val VIDEO_RESUME_POSITION_MS_KEY = "video_resume_position_ms"
        const val BUILT_IN_ANIMATION_TIME_MS_KEY = "builtin_animation_time_ms"
        const val VIDEO_LOOP_ENABLED_KEY = "video_loop_enabled"
        const val VIDEO_LOOP_START_TENTHS_KEY = "video_loop_start_tenths"
        const val VIDEO_LOOP_END_TENTHS_KEY = "video_loop_end_tenths"
        const val RESTART_VIDEO_ON_SCREEN_ON_KEY = "restart_video_on_screen_on"

        private const val TAG = "LumaDrift"
        private const val FRAME_INTERVAL_MS = 1_000L / 24L
        private const val VIDEO_PLAYER_RELEASE_DELAY_MS = 5_000L
        private const val MAX_BLUR_RADIUS_PX = 8
        private const val MAX_BLUR_RADIUS_FRACTION = 0.006f
        private const val MILLISECONDS_PER_TENTH = 100L
        private const val SEEK_TOLERANCE_MS = 80L
    }
}
