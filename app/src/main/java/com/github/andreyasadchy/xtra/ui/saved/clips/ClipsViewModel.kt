package com.github.andreyasadchy.xtra.ui.saved.clips

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalClip(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

class ClipsViewModel(private val context: Context) : ViewModel() {

    private val _clips = MutableStateFlow<List<LocalClip>>(emptyList())
    val clips = _clips.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError = _hasError.asStateFlow()

    private val _sortMode = MutableStateFlow(
        context.prefs().getString(C.CLIP_LIBRARY_SORT, SORT_NEWEST) ?: SORT_NEWEST,
    )
    val sortMode = _sortMode.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _hasError.value = false
            try {
                _clips.value = withContext(Dispatchers.IO) { queryClips() }
            } catch (_: Throwable) {
                _hasError.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setSortMode(mode: String) {
        if (mode == _sortMode.value) return
        _sortMode.value = mode
        context.prefs().edit { putString(C.CLIP_LIBRARY_SORT, mode) }
        refresh()
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        context.prefs().edit { putBoolean(C.CLIP_LIBRARY_AUTOPLAY, enabled) }
    }

    fun autoplayEnabled(): Boolean = context.prefs().getBoolean(C.CLIP_LIBRARY_AUTOPLAY, true)

    fun delete(clip: LocalClip) {
        viewModelScope.launch(Dispatchers.IO) {
            context.contentResolver.delete(clip.uri, null, null)
            refresh()
        }
    }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            queryClips().forEach { context.contentResolver.delete(it.uri, null, null) }
            refresh()
        }
    }

    private fun queryClips(): List<LocalClip> {
        val resolver = context.contentResolver
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf(CLIP_RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            val legacyDirectory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Xtra/Clips",
            ).absolutePath
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("$legacyDirectory${File.separator}%")
        }

        val results = mutableListOf<LocalClip>()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                results += LocalClip(
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    displayName = displayName,
                    durationMs = cursor.getLong(durationIndex).coerceAtLeast(0L),
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    modifiedAtMs = cursor.getLong(modifiedIndex).coerceAtLeast(0L) * 1_000L,
                )
            }
        }
        return when (_sortMode.value) {
            SORT_OLDEST -> results.sortedBy { it.modifiedAtMs }
            SORT_NAME -> results.sortedBy { it.displayName.lowercase() }
            else -> results.sortedByDescending { it.modifiedAtMs }
        }
    }

    companion object {
        const val SORT_NEWEST = "newest"
        const val SORT_OLDEST = "oldest"
        const val SORT_NAME = "name"
        private const val CLIP_RELATIVE_PATH = "Movies/Xtra/Clips/"

        val ClipsViewModelFactory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as XtraApp
                ClipsViewModel(application.applicationContext)
            }
        }
    }
}
