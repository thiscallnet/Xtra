package com.github.andreyasadchy.xtra.ui.player.clip

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exports a self-contained local HLS VOD playlist to an ordinary MP4 file. */
@OptIn(UnstableApi::class)
class ClipExporter(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun export(inputPlaylist: File, outputFile: File): ExportedClip = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            outputFile.parentFile?.mkdirs()
            outputFile.delete()
            val transformer = Transformer.Builder(applicationContext)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (continuation.isActive) {
                            continuation.resume(ExportedClip(outputFile, exportResult))
                        }
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                mainHandler.post { transformer.cancel() }
            }
            transformer.start(
                MediaItem.Builder()
                    .setUri(inputPlaylist.toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
                outputFile.absolutePath,
            )
        }
    }

    data class ExportedClip(
        val file: File,
        val result: ExportResult,
    )
}
