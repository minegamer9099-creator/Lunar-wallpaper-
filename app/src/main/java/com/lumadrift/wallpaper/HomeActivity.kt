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
    private lateinit var lockSplitSwitch: Switch
    private lateinit var lockSegmentSeekBar: SeekBar
    private lateinit var lockSegmentAmount: TextView
    private var previewUri: Uri? = null
    private var previewActive = false
    private var previewFailed = false
    private var metadataLoadingUri: Uri? = null
    private var metadataAttemptedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.wallpaperPreview)
        preferences = getSharedPreferences(
            DriftWallpaperService.PREFERENCES_NAME,
            MODE_PRIVATE
        )
        selectedVideoLabel = findViewById(R.id.selectedVideoLabel)
        clearVideoButton = findViewById(R.id.clearVideoButton)
        videoPreview = findViewById(R.id.videoPreview)
        lockSplitSwitch = findViewById(R.id.lockSplitSwitch)
        lockSegmentSeekBar = findViewById(R.id.lockSegmentSeekBar)
        lockSegmentAmount = findViewById(R.id.lockSegmentAmount)

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

        lockSplitSwitch.isChecked = preferences.getBoolean(
            DriftWallpaperService.LOCK_SPLIT_ENABLED_KEY,
            false
        )
        lockSplitSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.edit()
                .putBoolean(DriftWallpaperService.LOCK_SPLIT_ENABLED_KEY, enabled)
                .apply()
            updateLockPlaybackControls(
                preferences.getString(DriftWallpaperService.VIDEO_URI_KEY, null)
                    ?.let { Uri.parse(it) }
            )
        }
        lockSegmentSeekBar.progress = preferences.getInt(
            DriftWallpaperService.LOCK_SEGMENT_SECONDS_KEY,
            DEFAULT_LOCK_SEGMENT_SECONDS
        ).coerceAtLeast(MIN_LOCK_SEGMENT_SECONDS)
        lockSegmentSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val safeProgress = progress.coerceIn(
                    MIN_LOCK_SEGMENT_SECONDS,
                    (seekBar?.max ?: MIN_LOCK_SEGMENT_SECONDS).coerceAtLeast(
                        MIN_LOCK_SEGMENT_SECONDS
                    )
                )
                if (safeProgress != progress) seekBar?.progress = safeProgress
                updateLockSegmentAmount()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                preferences.edit()
                    .putInt(
                        DriftWallpaperService.LOCK_SEGMENT_SECONDS_KEY,
                        lockSegmentSeekBar.progress.coerceIn(
                            MIN_LOCK_SEGMENT_SECONDS,
                            lockSegmentSeekBar.max.coerceAtLeast(MIN_LOCK_SEGMENT_SECONDS)
                        )
                    )
                    .apply()
            }
        })

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
        preferences.edit()
            .putString(DriftWallpaperService.VIDEO_URI_KEY, uri.toString())
            .putString(DriftWallpaperService.VIDEO_NAME_KEY, displayName)
            .remove(DriftWallpaperService.VIDEO_DURATION_MS_KEY)
            .apply()
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
            updateLockPlaybackControls(null)
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
        updateLockPlaybackControls(uri)
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
        updateLockPlaybackControls(uri)

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
                updateLockPlaybackControls(uri)
            }
        }, "video-metadata-reader").start()
    }

    private fun updateLockPlaybackControls(uri: Uri?) {
        val controls = findViewById<View>(R.id.lockPlaybackControls)
        controls.visibility = if (uri == null) View.GONE else View.VISIBLE
        if (uri == null) return

        val durationMs = preferences.getLong(DriftWallpaperService.VIDEO_DURATION_MS_KEY, 0L)
        val durationKnown = durationMs > 0L
        val splitEnabled = preferences.getBoolean(
            DriftWallpaperService.LOCK_SPLIT_ENABLED_KEY,
            false
        )
        if (lockSplitSwitch.isChecked != splitEnabled) {
            lockSplitSwitch.isChecked = splitEnabled
        }
        lockSplitSwitch.isEnabled = durationKnown
        if (durationKnown) {
            val maxSeconds = (durationMs / 1_000L)
                .coerceAtLeast(MIN_LOCK_SEGMENT_SECONDS.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            lockSegmentSeekBar.max = maxSeconds
            val configuredSeconds = preferences.getInt(
                DriftWallpaperService.LOCK_SEGMENT_SECONDS_KEY,
                DEFAULT_LOCK_SEGMENT_SECONDS
            )
            val safeSeconds = configuredSeconds.coerceIn(
                MIN_LOCK_SEGMENT_SECONDS,
                maxSeconds
            )
            lockSegmentSeekBar.progress = safeSeconds
            if (configuredSeconds != safeSeconds) {
                preferences.edit()
                    .putInt(DriftWallpaperService.LOCK_SEGMENT_SECONDS_KEY, safeSeconds)
                    .apply()
            }
        }
        lockSegmentSeekBar.isEnabled = durationKnown && splitEnabled

        when {
            durationKnown -> updateLockSegmentAmount()
            metadataLoadingUri == uri -> lockSegmentAmount.setText(R.string.lock_segment_loading)
            else -> lockSegmentAmount.setText(R.string.lock_segment_unknown)
        }
    }

    private fun updateLockSegmentAmount() {
        val durationMs = preferences.getLong(DriftWallpaperService.VIDEO_DURATION_MS_KEY, 0L)
        if (durationMs <= 0L) return

        val maxSeconds = (durationMs / 1_000L)
            .coerceAtLeast(MIN_LOCK_SEGMENT_SECONDS.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val seconds = lockSegmentSeekBar.progress.coerceIn(
            MIN_LOCK_SEGMENT_SECONDS,
            maxSeconds
        )
        lockSegmentAmount.text = getString(
            R.string.lock_segment_amount,
            seconds
        )
    }

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
        private const val DEFAULT_LOCK_SEGMENT_SECONDS = 5
        private const val MIN_LOCK_SEGMENT_SECONDS = 1
    }
}
