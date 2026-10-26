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
import android.os.PowerManager
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
        private val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var visible = false
        private var surfaceAvailable = false
        private var destroyed = false
        private var animationEnabled = preferences.getBoolean(ANIMATION_ENABLED_KEY, true)
        private var lockSplitEnabled = preferences.getBoolean(LOCK_SPLIT_ENABLED_KEY, false)
        private var lockSegmentTenths = readSavedLockSegmentTenths(preferences)
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
        private var lastKnownLocked: Boolean? = null
        private var playRemainderAfterUnlock = false
        private var lockSegmentComplete = false
        private var receiverRegistered = false
        private var activeLockedSegmentEndMs = 0L
        private var screenIsOn = powerManager.isInteractive
        private var userPresentConfirmedUntilNextScreenOff = false
        private var resetLockPositionUntilStarted = false
        private var playerPrepared = false
        private var seekInFlight = false
        private var desiredSeekPositionMs: Long? = null
        private var issuedSeekPositionMs = 0L
        private var startAfterSeek = false
        private var lockTimerPlayer: MediaPlayer? = null
        private var lockTimerGeneration = 0L
        private var scheduledLockTimerGeneration = 0L
        private var lockPauseDeadlineUptimeMs = 0L
        private var animationElapsedBeforePauseMs = preferences
            .getLong(BUILT_IN_ANIMATION_TIME_MS_KEY, 0L)
            .coerceAtLeast(0L)
        private var animationStartedAtElapsedMs: Long? = null

        private val deferredVideoRelease = Runnable {
            if (!canDraw() && screenIsOn && !isPlaybackKeyguardLocked()) {
                releaseVideoPlayer(savePosition = !resetLockPositionUntilStarted)
            }
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

        private val lockSegmentBoundaryCheck = object : Runnable {
            override fun run() {
                val player = videoPlayer ?: return
                if (
                    player !== lockTimerPlayer ||
                    scheduledLockTimerGeneration != lockTimerGeneration ||
                    !lockSplitEnabled
                ) return

                val remainingMs = lockPauseDeadlineUptimeMs - SystemClock.uptimeMillis()
                if (remainingMs > 0L) {
                    handler.postDelayed(this, remainingMs)
                    return
                }

                if (!isPlaybackKeyguardLocked()) {
                    cancelLockPauseTimer()
                    refreshScreenStateFromPowerManager()
                    updateRendering()
                    return
                }

                completeLockedSegment(player)
            }
        }

        private val lockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON -> handleScreenOn()
                    Intent.ACTION_USER_PRESENT -> handleUserPresent()
                }
            }
        }

        private fun handleScreenOff() {
            userPresentConfirmedUntilNextScreenOff = false
            screenIsOn = false
            updateRendering()
        }

        private fun handleScreenOn() {
            screenIsOn = true
            updateRendering()
        }

        private fun handleUserPresent() {
            screenIsOn = true
            // ACTION_USER_PRESENT confirms authentication. Trust it if KeyguardManager
            // has not caught up yet; clear the override on screen-off or once it reports
            // that the keyguard is dismissed.
            userPresentConfirmedUntilNextScreenOff = true
            resetLockPositionUntilStarted = false
            updateRendering()
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
            updateRendering(refreshScreenState = true)
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
            cancelLockPauseTimer()
            releaseVideoPlayer(savePosition = !resetLockPositionUntilStarted)
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
                LOCK_SEGMENT_TENTHS_KEY -> {
                    lockSegmentTenths = preferences.getInt(
                        LOCK_SEGMENT_TENTHS_KEY,
                        DEFAULT_LOCK_SEGMENT_TENTHS
                    ).coerceAtLeast(MIN_LOCK_SEGMENT_TENTHS)
                    resetSplitPlaybackForPreferenceChange()
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
            synchronizeLockState()
            updateAnimationClock()
            if (!canDraw() || !screenIsOn) {
                pauseVideoPlayerForTransition(detachSurface = !surfaceAvailable)
                scheduleDeferredVideoReleaseIfNeeded()
                return
            }

            val videoUri = selectedVideoUri
            if (videoUri != null && videoUri != failedVideoUri) {
                if (activeVideoUri == videoUri && videoPlayer != null) {
                    val player = videoPlayer ?: return
                    if (!attachVideoSurface(player)) return
                    if (playerPrepared && !isPlayerPlaying(player)) {
                        requestPlaybackPosition(
                            player,
                            resumePositionMs,
                            shouldStart = true
                        )
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
                (selectedVideoUri == null || selectedVideoUri == failedVideoUri) &&
                videoPlayer == null &&
                canDraw() &&
                screenIsOn

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
            cancelLockPauseTimer()
            val player = videoPlayer ?: return

            if (playerPrepared) {
                try {
                    if (
                        !resetLockPositionUntilStarted &&
                        activeVideoUri == selectedVideoUri
                    ) {
                        val currentPositionMs = player.currentPosition.toLong()
                        val lockSegmentEnd = activeLockedSegmentEndMs
                        if (lockSegmentEnd > 0L && currentPositionMs >= lockSegmentEnd) {
                            resumePositionMs = lockSegmentEnd
                            lockSegmentComplete = true
                        } else {
                            resumePositionMs = currentPositionMs
                        }
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
            if (
                videoPlayer == null ||
                !screenIsOn ||
                isPlaybackKeyguardLocked()
            ) return
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
                    if (actualDurationMs > 0L) videoDurationMs = actualDurationMs
                    if (!canDraw() || !screenIsOn) return@setOnPreparedListener

                    try {
                        synchronizeLockState()
                        val isLocked = isPlaybackKeyguardLocked()
                        val lockSegmentActive =
                            lockSplitEnabled && isLocked && lockBoundaryMs() > 0L
                        val remainderActive =
                            lockSplitEnabled && !isLocked && playRemainderAfterUnlock &&
                                lockBoundaryMs() > 0L
                        val segmentEndMs = lockBoundaryMs()
                        val startPositionMs = when {
                            lockSegmentActive ->
                                resumePositionMs.coerceIn(0L, segmentEndMs)
                            remainderActive && segmentEndMs < videoDurationMs ->
                                resumePositionMs.coerceIn(segmentEndMs, videoDurationMs)
                            remainderActive -> 0L
                            else -> resumePositionMs
                        }
                        if (remainderActive && segmentEndMs >= videoDurationMs) {
                            resumePositionMs = 0L
                            playRemainderAfterUnlock = false
                        }
                        activeLockedSegmentEndMs =
                            if (lockSegmentActive) segmentEndMs else 0L
                        val shouldStart = screenIsOn && canDraw() &&
                            !(lockSegmentActive && lockSegmentComplete)
                        requestPlaybackPosition(
                            preparedPlayer,
                            startPositionMs,
                            shouldStart,
                            forceSeek = remainderActive
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
                        val isLocked = isPlaybackKeyguardLocked()
                        if (lockSplitEnabled && isLocked) {
                            cancelLockPauseTimer()
                            lockSegmentComplete = true
                            resumePositionMs = completedPlayer.currentPosition.toLong()
                                .coerceIn(0L, lockBoundaryMs())
                            activeLockedSegmentEndMs = 0L
                            return@setOnCompletionListener
                        }

                        val boundaryMs = lockBoundaryMs()
                        val loopStartMs = if (
                            lockSplitEnabled &&
                            playRemainderAfterUnlock &&
                            !isLocked &&
                            boundaryMs < videoDurationMs
                        ) boundaryMs else 0L
                        requestPlaybackPosition(
                            completedPlayer,
                            loopStartMs,
                            shouldStart = true,
                            forceSeek = loopStartMs > 0L
                        )
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
                    val shouldStart = startAfterSeek
                    startAfterSeek = false
                    if (shouldStart) startPreparedPlayer(seekedPlayer)
                }
                player.prepareAsync()
            } catch (error: Exception) {
                handleVideoFailure(uri, player, "Could not prepare the selected wallpaper video", error)
            }
        }

        private fun synchronizeLockState() {
            val isLocked = isPlaybackKeyguardLocked()
            if (!lockSplitEnabled) {
                lastKnownLocked = isLocked
                playRemainderAfterUnlock = false
                lockSegmentComplete = false
                if (!isLocked) resetLockPositionUntilStarted = false
                return
            }

            // Screen-off/on does not begin a second lock segment. Wait for an actual
            // keyguard transition, then start or finish the segment exactly once.
            if (!screenIsOn) return

            when {
                isLocked && lastKnownLocked != true -> beginLockedPlayback()
                !isLocked && (
                    lastKnownLocked == true ||
                        lockSegmentComplete ||
                        activeLockedSegmentEndMs > 0L
                    ) -> endLockedPlayback()
                !isLocked -> resetLockPositionUntilStarted = false
            }
            lastKnownLocked = isLocked
        }

        private fun isPlaybackKeyguardLocked(): Boolean {
            val keyguardLocked = keyguardManager.isKeyguardLocked
            if (!keyguardLocked) userPresentConfirmedUntilNextScreenOff = false
            return keyguardLocked && !userPresentConfirmedUntilNextScreenOff
        }

        private fun beginLockedPlayback() {
            cancelLockPauseTimer()
            playRemainderAfterUnlock = false
            lockSegmentComplete = false
            resumePositionMs = 0L
            persistResumePosition(0L)
            activeLockedSegmentEndMs = lockBoundaryMs()
            resetLockPositionUntilStarted = true

            val player = videoPlayer
            if (player != null && playerPrepared) {
                requestPlaybackPosition(
                    player,
                    0L,
                    shouldStart = screenIsOn && canDraw(),
                    forceSeek = playerPrepared
                )
            }
        }

        private fun endLockedPlayback() {
            cancelLockPauseTimer()
            activeLockedSegmentEndMs = 0L
            val boundaryMs = lockBoundaryMs()
            val hasRemainder = boundaryMs > 0L && boundaryMs < videoDurationMs
            val completedLockSegment = lockSegmentComplete
            playRemainderAfterUnlock = completedLockSegment && hasRemainder
            val restartWholeVideo = completedLockSegment && !hasRemainder
            lockSegmentComplete = false
            if (restartWholeVideo) {
                resumePositionMs = 0L
                persistResumePosition(0L)
            }
            resetLockPositionUntilStarted = false

            val player = videoPlayer
            if (player != null && playerPrepared) {
                if (restartWholeVideo) {
                    if (screenIsOn && canDraw()) {
                        requestPlaybackPosition(player, 0L, shouldStart = true)
                    }
                } else if (playRemainderAfterUnlock) {
                    resumePositionMs = boundaryMs
                    persistResumePosition(boundaryMs)
                    if (screenIsOn && canDraw()) {
                        requestPlaybackPosition(
                            player,
                            boundaryMs,
                            shouldStart = true,
                            forceSeek = true
                        )
                    }
                } else {
                    val currentPositionMs = try {
                        player.currentPosition.toLong()
                    } catch (_: IllegalStateException) {
                        resumePositionMs
                    }
                    if (currentPositionMs >= 0L) {
                        resumePositionMs = currentPositionMs
                        persistResumePosition(currentPositionMs)
                    }
                    if (screenIsOn && canDraw() && !isPlayerPlaying(player)) {
                        requestPlaybackPosition(player, resumePositionMs, shouldStart = true)
                    }
                }
            }
        }

        private fun resetSplitPlaybackForPreferenceChange() {
            lastKnownLocked = null
            playRemainderAfterUnlock = false
            lockSegmentComplete = false
            resumePositionMs = 0L
            persistResumePosition(0L)
            resetLockPositionUntilStarted = false
            activeLockedSegmentEndMs = 0L
            releaseVideoPlayer(savePosition = false)
        }

        private fun lockBoundaryMs(): Long {
            if (videoDurationMs <= 0L) return 0L
            val requestedDurationMs = lockSegmentTenths
                .coerceAtLeast(MIN_LOCK_SEGMENT_TENTHS)
                .toLong() * MILLISECONDS_PER_TENTH
            return requestedDurationMs.coerceAtMost(videoDurationMs)
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
            if (
                videoPlayer !== player ||
                !playerPrepared ||
                !canDraw() ||
                !screenIsOn
            ) return

            val isLocked = isPlaybackKeyguardLocked()
            if (lockSplitEnabled && isLocked && lockSegmentComplete) {
                if (isPlayerPlaying(player)) {
                    try {
                        player.pause()
                    } catch (_: IllegalStateException) {
                        // The player is being released or reset by Android.
                    }
                }
                return
            }

            try {
                if (lockSplitEnabled && isLocked) {
                    val boundaryMs = lockBoundaryMs()
                    val currentPositionMs = player.currentPosition.toLong()
                    activeLockedSegmentEndMs = boundaryMs
                    if (boundaryMs > 0L && currentPositionMs >= boundaryMs) {
                        if (player.isPlaying) player.pause()
                        resumePositionMs = currentPositionMs.coerceAtMost(boundaryMs)
                        lockSegmentComplete = true
                        activeLockedSegmentEndMs = 0L
                        cancelLockPauseTimer()
                        return
                    }
                }

                if (!player.isPlaying) player.start()
                if (lockSplitEnabled && isLocked) {
                    val boundaryMs = lockBoundaryMs()
                    val currentPositionMs = player.currentPosition.toLong()

                    if (boundaryMs > 0L) {
                        resumePositionMs = currentPositionMs
                        scheduleLockPause(
                            player,
                            (boundaryMs - currentPositionMs).coerceAtLeast(1L)
                        )
                    } else {
                        cancelLockPauseTimer()
                        activeLockedSegmentEndMs = 0L
                    }
                } else {
                    cancelLockPauseTimer()
                    activeLockedSegmentEndMs = 0L
                    resumePositionMs = player.currentPosition.toLong()
                }
                resetLockPositionUntilStarted = false
            } catch (error: IllegalStateException) {
                handleVideoFailure(
                    activeVideoUri ?: selectedVideoUri ?: return,
                    player,
                    "Could not resume the selected wallpaper video",
                    error
                )
            }
        }

        private fun completeLockedSegment(player: MediaPlayer) {
            if (videoPlayer !== player) return
            val segmentEndMs = activeLockedSegmentEndMs
            if (segmentEndMs <= 0L) {
                cancelLockPauseTimer()
                return
            }

            cancelLockPauseTimer()
            try {
                if (player.isPlaying) player.pause()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Could not pause the video at the lock-screen limit", error)
            }

            resumePositionMs = segmentEndMs
            lockSegmentComplete = true
            activeLockedSegmentEndMs = 0L
            persistResumePosition(resumePositionMs)
        }

        private fun scheduleLockPause(player: MediaPlayer, delayMs: Long) {
            cancelLockPauseTimer()
            lockTimerPlayer = player
            scheduledLockTimerGeneration = lockTimerGeneration
            val safeDelayMs = delayMs.coerceAtLeast(1L)
            lockPauseDeadlineUptimeMs = SystemClock.uptimeMillis() + safeDelayMs
            handler.postDelayed(lockSegmentBoundaryCheck, safeDelayMs)
        }

        private fun cancelLockPauseTimer() {
            handler.removeCallbacks(lockSegmentBoundaryCheck)
            lockTimerPlayer = null
            lockPauseDeadlineUptimeMs = 0L
            lockTimerGeneration += 1L
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
            cancelLockPauseTimer()
            val player = videoPlayer ?: return
            if (
                savePosition &&
                !resetLockPositionUntilStarted &&
                activeVideoUri == selectedVideoUri
            ) {
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
                persistResumePosition(resumePositionMs)
            }

            videoPlayer = null
            activeVideoUri = null
            videoSurfaceAttached = false
            activeLockedSegmentEndMs = 0L
            playerPrepared = false
            seekInFlight = false
            desiredSeekPositionMs = null
            startAfterSeek = false
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
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas, animationTimeMs())
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
        const val VIDEO_RESUME_POSITION_MS_KEY = "video_resume_position_ms"
        const val BUILT_IN_ANIMATION_TIME_MS_KEY = "builtin_animation_time_ms"
        const val LOCK_SPLIT_ENABLED_KEY = "lock_split_enabled"
        const val LOCK_SEGMENT_TENTHS_KEY = "lock_segment_tenths"
        private const val LEGACY_LOCK_SEGMENT_SECONDS_KEY = "lock_segment_seconds"

        private const val TAG = "LumaDrift"
        private const val FRAME_INTERVAL_MS = 1_000L / 24L
        private const val VIDEO_PLAYER_RELEASE_DELAY_MS = 5_000L
        private const val MILLISECONDS_PER_TENTH = 100L
        private const val DEFAULT_LOCK_SEGMENT_TENTHS = 50
        private const val MIN_LOCK_SEGMENT_TENTHS = 1
        private const val LEGACY_DEFAULT_LOCK_SEGMENT_SECONDS = 5
        private const val SEEK_TOLERANCE_MS = 80L

        fun readSavedLockSegmentTenths(
            preferences: android.content.SharedPreferences
        ): Int {
            if (preferences.contains(LOCK_SEGMENT_TENTHS_KEY)) {
                return preferences.getInt(
                    LOCK_SEGMENT_TENTHS_KEY,
                    DEFAULT_LOCK_SEGMENT_TENTHS
                ).coerceAtLeast(MIN_LOCK_SEGMENT_TENTHS)
            }

            val legacySeconds = preferences.getInt(
                LEGACY_LOCK_SEGMENT_SECONDS_KEY,
                LEGACY_DEFAULT_LOCK_SEGMENT_SECONDS
            ).coerceAtLeast(1)
            val tenths = (legacySeconds.toLong() * 10L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(MIN_LOCK_SEGMENT_TENTHS)
            preferences.edit().putInt(LOCK_SEGMENT_TENTHS_KEY, tenths).apply()
            return tenths
        }
    }
}
