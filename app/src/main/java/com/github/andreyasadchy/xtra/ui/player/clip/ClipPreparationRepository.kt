package com.github.andreyasadchy.xtra.ui.player.clip

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Downloads a frozen clip snapshot into a self-contained local HLS VOD playlist. */
@OptIn(UnstableApi::class)
class ClipPreparationRepository(
    private val dataSourceFactory: DataSource.Factory,
    private val rootDirectory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    suspend fun prepare(snapshot: ClipSnapshot): PreparedLiveClip = withContext(Dispatchers.IO) {
        require(snapshot.segments.isNotEmpty()) { "No complete HLS segments are available" }
        check(!snapshot.drmInitDataPresent) { "DRM-protected live clips are not supported" }
        val directory = File(rootDirectory, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Unable to create temporary clip directory" }
        val byteCounter = ByteCounter()
        try {
            val initFiles = linkedMapOf<ClipResourceRef, String>()
            val keyFiles = linkedMapOf<String, String>()
            val preparedSegments = mutableListOf<PreparedClipSegment>()

            snapshot.segments.forEachIndexed { index, segment ->
                currentCoroutineContext().ensureActive()
                val initFile = segment.initSegment?.let { init ->
                    initFiles.getOrPut(init) {
                        val name = "init_${initFiles.size.toString().padStart(3, '0')}.bin"
                        download(init, File(directory, name), byteCounter)
                        name
                    }
                }
                val keyFile = segment.encryptionKeyUri?.let { keyUri ->
                    keyFiles.getOrPut(keyUri) {
                        val name = "key_${keyFiles.size.toString().padStart(3, '0')}.bin"
                        download(ClipResourceRef(keyUri), File(directory, name), byteCounter)
                        name
                    }
                }
                val name = "segment_${index.toString().padStart(4, '0')}${sourceSuffix(segment.absoluteUri)}"
                download(segment.resource, File(directory, name), byteCounter)
                preparedSegments += PreparedClipSegment(
                    mediaSequence = segment.mediaSequence,
                    durationUs = segment.durationUs,
                    discontinuitySequence = segment.discontinuitySequence,
                    segmentFile = name,
                    initFile = initFile,
                    keyFile = keyFile,
                    encryptionIv = segment.encryptionIv,
                )
            }

            val playlist = LocalHlsPlaylistWriter.write(
                directory = directory,
                segments = preparedSegments,
            )
            PreparedLiveClip(
                directory = directory,
                playlist = playlist,
                segments = preparedSegments,
                downloadedBytes = byteCounter.value,
            ).also { it.writeMetadata() }
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private suspend fun download(
        resource: ClipResourceRef,
        output: File,
        byteCounter: ByteCounter,
    ) {
        var lastError: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            var attemptBytes = 0L
            try {
                output.parentFile?.mkdirs()
                val dataSource = dataSourceFactory.createDataSource()
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(resource.uri))
                    .setPosition(resource.byteRangeOffset)
                    .apply {
                        if (resource.byteRangeLength != C.LENGTH_UNSET.toLong()) {
                            setLength(resource.byteRangeLength)
                        }
                    }
                    .build()
                try {
                    dataSource.open(dataSpec)
                    FileOutputStream(output).use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = dataSource.read(buffer, 0, buffer.size)
                            if (read == C.RESULT_END_OF_INPUT) break
                            outputStream.write(buffer, 0, read)
                            byteCounter.add(read.toLong())
                            attemptBytes += read
                        }
                    }
                } finally {
                    dataSource.close()
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                byteCounter.value -= attemptBytes
                output.delete()
                lastError = error
                if (attempt + 1 == MAX_DOWNLOAD_ATTEMPTS) throw error
            }
        }
        throw lastError ?: IOException("Unable to download clip resource")
    }

    private fun ByteCounter.add(amount: Long) {
        check(value <= maxBytes - amount) { "Clip exceeds the temporary storage limit" }
        value += amount
    }

    private fun sourceSuffix(uri: String): String {
        val path = Uri.parse(uri).lastPathSegment.orEmpty()
        val extension = path.substringAfterLast('.', "")
            .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        return extension?.let { ".$it" } ?: ".bin"
    }

    private class ByteCounter(var value: Long = 0L)

    data class PreparedLiveClip(
        val directory: File,
        val playlist: File,
        val segments: List<PreparedClipSegment>,
        val downloadedBytes: Long = 0L,
    ) {
        val durationUs: Long
            get() = segments.sumOf { it.durationUs }

        val boundariesUs: LongArray
            get() {
                val result = LongArray(segments.size + 1)
                var positionUs = 0L
                segments.forEachIndexed { index, segment ->
                    result[index] = positionUs
                    positionUs += segment.durationUs
                }
                result[segments.size] = positionUs
                return result
            }

        fun writeMetadata() {
            val metadata = JSONObject().apply {
                put("version", METADATA_VERSION)
                put("segments", JSONArray().apply {
                    segments.forEach { segment ->
                        put(JSONObject().apply {
                            put("mediaSequence", segment.mediaSequence)
                            put("durationUs", segment.durationUs)
                            put("discontinuitySequence", segment.discontinuitySequence)
                            put("segmentFile", segment.segmentFile)
                            put("initFile", segment.initFile ?: JSONObject.NULL)
                            put("keyFile", segment.keyFile ?: JSONObject.NULL)
                            put("encryptionIv", segment.encryptionIv ?: JSONObject.NULL)
                        })
                    }
                })
            }
            File(directory, METADATA_NAME).writeText(metadata.toString())
        }

        companion object {
            fun read(directory: File): PreparedLiveClip {
                val metadata = JSONObject(File(directory, METADATA_NAME).readText())
                require(metadata.getInt("version") == METADATA_VERSION) { "Unsupported clip metadata" }
                val jsonSegments = metadata.getJSONArray("segments")
                val segments = List(jsonSegments.length()) { index ->
                    val json = jsonSegments.getJSONObject(index)
                    PreparedClipSegment(
                        mediaSequence = json.getLong("mediaSequence"),
                        durationUs = json.getLong("durationUs"),
                        discontinuitySequence = json.getInt("discontinuitySequence"),
                        segmentFile = json.getString("segmentFile").also(::requireLocalName),
                        initFile = json.nullableString("initFile")?.also(::requireLocalName),
                        keyFile = json.nullableString("keyFile")?.also(::requireLocalName),
                        encryptionIv = json.nullableString("encryptionIv"),
                    )
                }
                require(segments.isNotEmpty()) { "Prepared clip has no segments" }
                return PreparedLiveClip(
                    directory = directory,
                    playlist = File(directory, LocalHlsPlaylistWriter.PLAYLIST_NAME),
                    segments = segments,
                )
            }

            private fun JSONObject.nullableString(name: String): String? =
                if (isNull(name)) null else getString(name)

            private fun requireLocalName(name: String) {
                require(name.isNotBlank() && File(name).name == name) { "Invalid local clip resource" }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 128L * 1024L * 1024L
        internal const val METADATA_NAME = "clip.json"
        private const val METADATA_VERSION = 1
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_DOWNLOAD_ATTEMPTS = 2

        fun cleanupStale(rootDirectory: File, maxAgeMs: Long = 24L * 60L * 60L * 1000L) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val root = runCatching { rootDirectory.canonicalFile }.getOrNull() ?: return
            root.listFiles()?.forEach { candidate ->
                val directory = runCatching { candidate.canonicalFile }.getOrNull()
                if (directory?.parentFile == root && directory.lastModified() < cutoff) {
                    directory.deleteRecursively()
                }
            }
        }
    }
}
