package com.example.silentcamera

import android.content.Context
import android.os.Environment
import androidx.preference.PreferenceManager

object Prefs {
    fun shortcutMode(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString("shortcut_mode", "video") ?: "video"

    fun burstInterval(ctx: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString("burst_interval", "5")?.toLongOrNull() ?: 5L

    fun backgroundLens(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString("background_lens", "back") ?: "back"

    fun videoQuality(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString("video_quality", "fhd") ?: "fhd"

    fun photoQuality(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString("photo_quality", "high") ?: "high"

    fun savePath(ctx: Context): String {
        val moviesDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath
            ?: "${Environment.getExternalStorageDirectory()}/Movies/SilentCamera"
        return moviesDir
    }
}
