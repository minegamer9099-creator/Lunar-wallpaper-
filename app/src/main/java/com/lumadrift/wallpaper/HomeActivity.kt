package com.lumadrift.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import android.widget.VideoView

class HomeActivity : Activity() {
    private lateinit var preview: WallpaperPreviewView
    private lateinit var preferences: SharedPreferences
    private lateinit var selectedVideoLabel: TextView
    private lateinit var clearVideoButton: Button
    private lateinit var videoPreview: VideoView
    private lateinit var videoLoopEnabledSwitch: Switch
    private lateinit var loopStartSeekBar: SeekBar
    private lateinit var loopEndSeekBar: SeekBar
    private lateinit var loopStartValue: TextView
    private lateinit var loopEndValue: TextView
    private lateinit var loopRangeSummary: TextView
    private lateinit var loopRangeValidation: TextView
    private lateinit var loopRangeTimeline: LoopRangeTimelineView
    private lateinit var resetLoopRangeButton: Button
    private var previewUri: Uri? = null
    private var previewActive = false
    private var previewFailed = false
    private var metadataLoadingUri: Uri? = null
    private var metadataAttemptedUri: Uri? = null
    private var loopControlsUpdating = false
    private var loopDurationTenths = 0
    private var loopStartTenths = 0
    private var loopEndTenths = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.wallpaperPreview)
        preferences = getSharedPreferences(
            DriftWallpaperService.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        preferences.edit()
            .remove("lock_split_enabled")
            .remove("lock_segment_tenths")
            .remove("lock_segment_seconds")
            .apply()
        selectedVideoLabel = findViewById(R.id.selectedVideoLabel)
        clearVideoButton = findViewById(R.id.clearVideoButton)
        videoPreview = findViewById(R.id.videoPreview)
        videoLoopEnabledSwitch = findViewById(R.id.videoLoopEnabledSwitch)
        loopStartSeekBar = findViewById(R.id.loopStartSeekBar)
        loopEndSeekBar = findViewById(R.id.loopEndSeekBar)
        loopStartValue = findViewById(R.id.loopStartValue)
        loopEndValue = findViewById(R.id.loopEndValue)
        loopRangeSummary = findViewById(R.id.loopRangeSummary)
        loopRangeValidation = findViewById(R.id.loopRangeValidation)
        loopRangeTimeline = findViewById(R.id.loopRangeTimeline)
        resetLoopRangeButton = findViewById(R.id.resetLoopRangeButton)

        videoPreview.setOnPreparedListener { player ->
            player.isLooping = true
            player.setVolume(0f, 0f)
            if (previewActive) videoPreview.start()
        }
        videoPreview.setOnErrorListener { _, _, _ ->
            previewFailed = true
            videoPreview.visibility = View.GONE
            preview.visibility = View.VISIBLE
            preview.setPreviewActive(previewActive)
            findViewById<TextView>(R.id.previewCaption)
                .setText(R.string.video_preview_unavailable)
            true
        }

        val animationToggle = findViewById<ToggleButton>(R.id.animationToggle)
        animationToggle.isChecked = preferences.getBoolean(
            DriftWallpaperService.ANIMATION_ENABLED_KEY,
            true
        )
        preview.setAnimationEnabled(animationToggle.isChecked)
        animationToggle.setOnCheckedChangeListener { _, enabled ->
            preferences.edit()
                .putBoolean(DriftWallpaperService.ANIMATION_ENABLED_KEY, enabled)
                .apply()
            preview.setAnimationEnabled(enabled)
        }

        videoLoopEnabledSwitch.isChecked = preferences.getBoolean(
            DriftWallpaperService.VIDEO_LOOP_ENABLED_KEY,
            false
        )
        videoLoopEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
            if (loopControlsUpdating) return@setOnCheckedChangeListener
            preferences.edit()
                .putBoolean(DriftWallpaperService.VIDEO_LOOP_ENABLED_KEY, enabled)
                .apply()
            updateVideoLoopControls(
                preferences.getString(DriftWallpaperService.VIDEO_URI_KEY, null)
                    ?.let { Uri.parse(it) }
            )
        }
        loopStartSeekBar.setOnSeekBarChangeListener(loopRangeSeekListener(isStart = true))
        loopEndSeekBar.setOnSeekBarChangeListener(loopRangeSeekListener(isStart = false))
        resetLoopRangeButton.setOnClickListener { resetVideoLoopRange() }

        findViewById<Button>(R.id.setWallpaperButton).setOnClickListener {
            openWallpaperPicker()
        }
        findViewById<Button>(R.id.chooseVideoButton).setOnClickListener {
            chooseVideo()
        }
        clearVideoButton.setOnClickListener {
            releaseStoredVideoGrant()
            preferences.edit()
                .remove(DriftWallpaperService.VIDEO_URI_KEY)
                .remove(DriftWallpaperService.VIDEO_NAME_KEY)
                .remove(DriftWallpaperService.VIDEO_DURATION_MS_KEY)
                .remove(DriftWallpaperService.VIDEO_LOOP_START_TENTHS_KEY)
                .remove(DriftWallpaperService.VIDEO_LOOP_END_TENTHS_KEY)
                .apply()
            metadataAttemptedUri = null
            metadataLoadingUri = null
            refreshVideoSelection()
        }

        findViewById<TextView>(R.id.versionLabel).text = getString(R.string.version_label)
        refreshVideoSelection()
    }

    override fun onResume() {
        super.onResume()
        previewActive = true
        if (::preview.isInitialized) {
            preview.setPreviewActive(preview.visibility == View.VISIBLE)
        }
        if (::videoPreview.isInitialized && previewUri != null && !previewFailed) {
            videoPreview.start()
        }
    }

    override fun onPause() {
        previewActive = false
        if (::preview.isInitialized) preview.setPreviewActive(false)
        if (::videoPreview.isInitialized && previewUri != null) videoPreview.pause()
        super.onPause()
    }

    private fun openWallpaperPicker() {
        val changeIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@HomeActivity, DriftWallpaperService::class.java)
            )
        }

        try {
            startActivity(changeIntent)
        } catch (error: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (fallbackError: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.wallpaper_picker_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        try {
            startActivityForResult(intent, REQUEST_VIDEO)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.video_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform document picker without adding an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return

        val resultIntent = data ?: return
        val uri = resultIntent.data ?: return
        val grantedReadFlags =
            resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (grantedReadFlags == 0) {
            Toast.makeText(this, R.string.video_access_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, grantedReadFlags)
        } catch (error: SecurityException) {
            Log.w(TAG, "The selected video provider did not grant persistent read access", error)
            Toast.makeText(this, R.string.video_access_unavailable, Toast.LENGTH_LONG).show()
            return
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "The selected video provider does not support persistent access", error)
            Toast.makeText(this, R.string.video_access_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        releaseStoredVideoGrant(except = uri)
        val displayName = getDisplayName(uri)
        val videoChanged = preferences
            .getString(DriftWallpaperService.VIDEO_URI_KEY, null) != uri.toString()
        val videoPreferences = preferences.edit()
            .putString(DriftWallpaperService.VIDEO_URI_KEY, uri.toString())
            .putString(DriftWallpaperService.VIDEO_NAME_KEY, displayName)
            .remove(DriftWallpaperService.VIDEO_DURATION_MS_KEY)
        if (videoChanged) {
            videoPreferences
                .remove(DriftWallpaperService.VIDEO_LOOP_START_TENTHS_KEY)
                .remove(DriftWallpaperService.VIDEO_LOOP_END_TENTHS_KEY)
        }
        videoPreferences.apply()
        metadataAttemptedUri = null
        refreshVideoSelection()
        Toast.makeText(this, R.string.video_selected, Toast.LENGTH_SHORT).show()
    }

    private fun releaseStoredVideoGrant(except: Uri? = null) {
        val storedVideo = preferences.getString(DriftWallpaperService.VIDEO_URI_KEY, null)
            ?: return
        val storedUri = Uri.parse(storedVideo)
        if (storedUri == except) return

        try {
            contentResolver.releasePersistableUriPermission(
                storedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not release the previous video access grant", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "The previous video access grant was already released", error)
        }
    }

    private fun refreshVideoSelection() {
        val videoUri = preferences.getString(DriftWallpaperService.VIDEO_URI_KEY, null)
        if (videoUri.isNullOrBlank()) {
            selectedVideoLabel.setText(R.string.no_video_selected)
            clearVideoButton.visibility = View.GONE
            findViewById<TextView>(R.id.previewCaption).setText(R.string.preview_caption)
            metadataAttemptedUri = null
            metadataLoadingUri = null
            syncVideoPreview(null)
            updateVideoLoopControls(null)
            return
        }

        val uri = Uri.parse(videoUri)
        syncVideoPreview(uri)
        val durationMs = preferences.getLong(DriftWallpaperService.VIDEO_DURATION_MS_KEY, 0L)
        if (durationMs <= 0L && metadataAttemptedUri != uri) {
            loadVideoDuration(uri)
        }
        val displayName = preferences.getString(DriftWallpaperService.VIDEO_NAME_KEY, null)
            ?: getString(R.string.selected_video_fallback)
        selectedVideoLabel.text = getString(R.string.selected_video_format, displayName)
        clearVideoButton.visibility = View.VISIBLE
        findViewById<TextView>(R.id.previewCaption).setText(R.string.video_preview_caption)
        updateVideoLoopControls(uri)
    }

    private fun syncVideoPreview(uri: Uri?) {
        if (uri == null) {
            if (previewUri != null) videoPreview.stopPlayback()
            previewUri = null
            previewFailed = false
            videoPreview.visibility = View.GONE
            preview.visibility = View.VISIBLE
            preview.setPreviewActive(previewActive)
            return
        }

        if (previewUri == uri && !previewFailed) {
            videoPreview.visibility = View.VISIBLE
            preview.visibility = View.GONE
            preview.setPreviewActive(false)
            return
        }

        videoPreview.stopPlayback()
        previewUri = uri
        previewFailed = false
        preview.visibility = View.GONE
        preview.setPreviewActive(false)
        videoPreview.visibility = View.VISIBLE
        videoPreview.setVideoURI(uri)
    }

    private fun loadVideoDuration(uri: Uri) {
        metadataAttemptedUri = uri
        metadataLoadingUri = uri
        updateVideoLoopControls(uri)

        Thread({
            var durationMs: Long? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()?.takeIf { it > 0L }
            } catch (error: Exception) {
                Log.w(TAG, "Could not read the selected video's duration", error)
            } finally {
                try {
                    retriever.release()
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Could not release the video metadata reader", error)
                }
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val currentUri = preferences
                    .getString(DriftWallpaperService.VIDEO_URI_KEY, null)
                    ?.let { Uri.parse(it) }
                if (currentUri != uri) return@runOnUiThread

                metadataLoadingUri = null
                durationMs?.let { loadedDuration ->
                    preferences.edit()
                        .putLong(DriftWallpaperService.VIDEO_DURATION_MS_KEY, loadedDuration)
                        .apply()
                }
                updateVideoLoopControls(uri)
            }
        }, "video-metadata-reader").start()
    }

    private fun loopRangeSeekListener(isStart: Boolean) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || loopControlsUpdating || loopDurationTenths < 1) return
                loopControlsUpdating = true
                if (isStart) {
                    loopStartTenths = progress.coerceIn(0, loopDurationTenths - 1)
                    if (loopEndTenths <= loopStartTenths) {
                        loopEndTenths = loopStartTenths + 1
                    }
                } else {
                    loopEndTenths = progress.coerceIn(1, loopDurationTenths)
                    if (loopEndTenths <= loopStartTenths) {
                        loopEndTenths = loopStartTenths + 1
                    }
                }
                loopStartSeekBar.progress = loopStartTenths
                loopEndSeekBar.progress = loopEndTenths
                loopControlsUpdating = false
                updateLoopRangeLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persistVideoLoopRange()
            }
        }

    private fun updateVideoLoopControls(uri: Uri?) {
        val controls = findViewById<View>(R.id.videoLoopControls)
        controls.visibility = if (uri == null) View.GONE else View.VISIBLE
        if (uri == null) {
            loopDurationTenths = 0
            loopRangeValidation.visibility = View.GONE
            return
        }

        val durationMs = preferences.getLong(DriftWallpaperService.VIDEO_DURATION_MS_KEY, 0L)
        loopDurationTenths = (durationMs / MILLISECONDS_PER_TENTH)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (loopDurationTenths < 1) {
            val durationMessage = when {
                durationMs > 0L -> getString(R.string.loop_range_too_short)
                metadataLoadingUri == uri -> getString(R.string.loop_range_loading)
                else -> getString(R.string.loop_range_unknown)
            }
            videoLoopEnabledSwitch.isEnabled = false
            loopStartSeekBar.isEnabled = false
            loopEndSeekBar.isEnabled = false
            resetLoopRangeButton.isEnabled = false
            loopRangeValidation.text = durationMessage
            loopRangeValidation.visibility = View.VISIBLE
            loopRangeSummary.text = getString(R.string.loop_range_unavailable)
            loopStartValue.text = getString(R.string.loop_range_dash)
            loopEndValue.text = getString(R.string.loop_range_dash)
            loopRangeTimeline.setRange(0, 0, 0)
            return
        }

        val storedStart = preferences.getInt(
            DriftWallpaperService.VIDEO_LOOP_START_TENTHS_KEY,
            0
        )
        val storedEnd = preferences.getInt(
            DriftWallpaperService.VIDEO_LOOP_END_TENTHS_KEY,
            loopDurationTenths
        )
        var safeStart = storedStart.coerceIn(0, loopDurationTenths - 1)
        var safeEnd = storedEnd.coerceIn(1, loopDurationTenths)
        if (safeEnd <= safeStart) {
            if (safeStart < loopDurationTenths - 1) {
                safeEnd = safeStart + 1
            } else {
                safeStart = safeEnd - 1
            }
        }
        val adjusted = safeStart != storedStart || safeEnd != storedEnd
        loopStartTenths = safeStart
        loopEndTenths = safeEnd
        loopControlsUpdating = true
        loopStartSeekBar.max = (loopDurationTenths - 1).coerceAtLeast(0)
        loopEndSeekBar.max = loopDurationTenths
        loopStartSeekBar.progress = loopStartTenths
        loopEndSeekBar.progress = loopEndTenths
        val loopEnabled = preferences.getBoolean(
            DriftWallpaperService.VIDEO_LOOP_ENABLED_KEY,
            false
        )
        videoLoopEnabledSwitch.isChecked = loopEnabled
        videoLoopEnabledSwitch.isEnabled = true
        val rangeEnabled = loopEnabled && loopDurationTenths > 0
        loopStartSeekBar.isEnabled = rangeEnabled
        loopEndSeekBar.isEnabled = rangeEnabled
        resetLoopRangeButton.isEnabled = true
        loopControlsUpdating = false
        updateLoopRangeLabels()

        loopRangeValidation.text = when {
            adjusted -> getString(R.string.loop_range_adjusted)
            !isValidLoopRange() -> getString(R.string.loop_range_invalid)
            else -> ""
        }
        loopRangeValidation.visibility =
            if (loopRangeValidation.text.isEmpty()) View.GONE else View.VISIBLE

        if (
            adjusted ||
            !preferences.contains(DriftWallpaperService.VIDEO_LOOP_START_TENTHS_KEY) ||
            !preferences.contains(DriftWallpaperService.VIDEO_LOOP_END_TENTHS_KEY)
        ) {
            persistVideoLoopRange()
        }
    }

    private fun updateLoopRangeLabels() {
        loopStartValue.text = getString(
            R.string.loop_range_time,
            formatTenthsAsSeconds(loopStartTenths)
        )
        loopEndValue.text = getString(
            R.string.loop_range_time,
            formatTenthsAsSeconds(loopEndTenths)
        )
        loopRangeSummary.text = getString(
            R.string.loop_range_summary,
            formatTenthsAsSeconds(loopStartTenths),
            formatTenthsAsSeconds(loopEndTenths)
        )
        loopRangeTimeline.setRange(
            loopStartTenths,
            loopEndTenths,
            loopDurationTenths
        )
        loopRangeTimeline.contentDescription = getString(
            R.string.loop_range_timeline_accessibility,
            formatTenthsAsSeconds(loopStartTenths),
            formatTenthsAsSeconds(loopEndTenths)
        )
        if (isValidLoopRange()) {
            loopRangeValidation.visibility = View.GONE
            loopRangeValidation.text = ""
        }
    }

    private fun isValidLoopRange(): Boolean =
        loopDurationTenths > 0 &&
            loopStartTenths >= 0 &&
            loopStartTenths < loopEndTenths &&
            loopEndTenths <= loopDurationTenths

    private fun persistVideoLoopRange() {
        if (!isValidLoopRange()) return
        preferences.edit()
            .putInt(DriftWallpaperService.VIDEO_LOOP_START_TENTHS_KEY, loopStartTenths)
            .putInt(DriftWallpaperService.VIDEO_LOOP_END_TENTHS_KEY, loopEndTenths)
            .apply()
    }

    private fun resetVideoLoopRange() {
        if (loopDurationTenths < 1) return
        loopStartTenths = 0
        loopEndTenths = loopDurationTenths
        loopControlsUpdating = true
        loopStartSeekBar.progress = loopStartTenths
        loopEndSeekBar.progress = loopEndTenths
        loopControlsUpdating = false
        persistVideoLoopRange()
        updateLoopRangeLabels()
    }

    private fun formatTenthsAsSeconds(tenths: Int): String =
        "${tenths / 10}.${tenths % 10}"

    private fun getDisplayName(uri: Uri): String {
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameColumn >= 0) {
                    cursor.getString(nameColumn)?.let { return it }
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not read the selected video's display name", error)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
            ?: getString(R.string.selected_video_fallback)
    }

    companion object {
        private const val REQUEST_VIDEO = 108
        private const val TAG = "LumaDriftHome"
        private const val MILLISECONDS_PER_TENTH = 100L
    }
}
