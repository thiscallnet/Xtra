package com.github.andreyasadchy.xtra.ui.player.clip

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.File

internal object ClipMediaStore {
    @Suppress("DEPRECATION")
    fun legacyFile(displayName: String): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Xtra/Clips"),
        displayName,
    )

    @Suppress("DEPRECATION")
    fun legacyValues(displayName: String, file: File): ContentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.DATA, file.absolutePath)
    }
}
