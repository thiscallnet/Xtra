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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class LocalClip(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

class ClipsViewModel(private val context: Context) : ViewModel() {

    // Null means no successful catalog read yet; an empty list means a successful empty read.
    private val _clips = MutableStateFlow<List<LocalClip>?>(null)
    val clips = _clips.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError = _hasError.asStateFlow()

    private val _sortMode = MutableStateFlow(
        context.prefs().getString(C.CLIP_LIBRARY_SORT, SORT_NEWEST) ?: SORT_NEWEST,
    )
    val sortMode = _sortMode.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private var deleteJob: Job? = null
    private val _deleteFailed = MutableStateFlow(false)
    val deleteFailed = _deleteFailed.asStateFlow()
    fun acknowledgeDeleteFailure() { _deleteFailed.value = false }

    fun refresh() {
        if (deleteJob?.isActive == true) return
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            _hasError.value = false
            try {
                _clips.value = withContext(Dispatchers.IO) { queryClips() }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _hasError.value = true
            } finally {
                if (generation == refreshGeneration) _isLoading.value = false
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

    fun delete(clip: LocalClip) = deleteSelected(setOf(clip.uri.toString()))

    fun deleteSelected(ids: Set<String>) = deleteMatching(ids)

    fun deleteAll() = deleteMatching(null)

    private fun deleteMatching(ids: Set<String>?) {
        if (deleteJob?.isActive == true) return
        refreshGeneration += 1
        refreshJob?.cancel()
        deleteJob = viewModelScope.launch {
            _isLoading.value = true
            _hasError.value = false
            try {
                withContext(Dispatchers.IO) {
                    val targets = queryClips().filter { ids == null || it.uri.toString() in ids }
                    var failed = false
                    for (clip in targets) {
                        try {
                            context.contentResolver.delete(clip.uri, null, null)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed = true
                        }
                    }
                    val remaining = queryClips()
                    val targetUris = targets.mapTo(HashSet()) { it.uri }
                    if (failed || remaining.any { it.uri in targetUris }) _deleteFailed.value = true
                    _clips.value = remaining
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _deleteFailed.value = true
                _hasError.value = true
            } finally {
                _isLoading.value = false
            }
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
        val cursor = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        ) ?: throw IOException("Unable to read the clip library")
        cursor.use {
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
