package com.lumadrift.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton

class HomeActivity : Activity() {
    private lateinit var preview: WallpaperPreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.wallpaperPreview)

        val animationToggle = findViewById<ToggleButton>(R.id.animationToggle)
        val preferences = getSharedPreferences(
            DriftWallpaperService.PREFERENCES_NAME,
            MODE_PRIVATE
        )
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

        findViewById<Button>(R.id.setWallpaperButton).setOnClickListener {
            openWallpaperPicker()
        }

        findViewById<TextView>(R.id.versionLabel).text = getString(R.string.version_label)
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) preview.setPreviewActive(true)
    }

    override fun onPause() {
        if (::preview.isInitialized) preview.setPreviewActive(false)
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
}
