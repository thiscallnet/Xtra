package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlin.coroutines.coroutineContext

sealed interface MoonshineModelState {
    data object Checking : MoonshineModelState
    data object NotInstalled : MoonshineModelState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : MoonshineModelState
    data object Verifying : MoonshineModelState
    data object Ready : MoonshineModelState
    data class Error(val message: String?) : MoonshineModelState
}

internal data class MoonshineExpectedFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

internal data class MoonshineModelManifest(
    val modelDirectoryName: String,
    val archiveFileName: String,
    val vadFileName: String,
    val archiveRoot: String,
    val archiveUrl: String,
    val vadUrl: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val vadBytes: Long,
    val vadSha256: String,
    val expectedFiles: List<MoonshineExpectedFile>,
) {
    val archiveModelFiles: Set<String> = expectedFiles
        .asSequence()
        .map(MoonshineExpectedFile::name)
        .filter { it != vadFileName }
        .toSet()
    val downloadBytes: Long = archiveBytes + vadBytes
}

/** Owns the optional Moonshine files and never exposes an unverified model to Sherpa. */
class MoonshineModelManager internal constructor(
    private val filesDirectory: File,
    private val httpClient: OkHttpClient,
    private val manifest: MoonshineModelManifest = productionManifest,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(context: Context, httpClient: OkHttpClient) : this(
        filesDirectory = context.applicationContext.filesDir,
        httpClient = httpClient,
        manifest = productionManifest,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val modelDirectory = File(filesDirectory, manifest.modelDirectoryName)
    private val modelParentDirectory = checkNotNull(modelDirectory.parentFile)
    private val downloadDirectory = File(modelParentDirectory, ".moonshine-v2-download")
    private val stagingDirectory = File(modelParentDirectory, ".moonshine-v2-staging")
    private val operationLock = Any()
    private val activeCall = AtomicReference<Call?>(null)
    private val cancellationRequested = AtomicReference(false)
    private var downloadJob: Job? = null
    private val stateMutable = MutableStateFlow<MoonshineModelState>(MoonshineModelState.Checking)

    val state: StateFlow<MoonshineModelState> = stateMutable.asStateFlow()

    init {
        scope.launch { verifyExistingModel() }
    }

    suspend fun awaitVerification(): MoonshineModelState =
        state.first { it !is MoonshineModelState.Checking && it !is MoonshineModelState.Verifying }

    fun download() {
        synchronized(operationLock) {
            if (downloadJob?.isActive == true) return
            when (state.value) {
                MoonshineModelState.Checking,
                MoonshineModelState.Verifying,
                MoonshineModelState.Ready,
                -> return
                MoonshineModelState.NotInstalled,
                is MoonshineModelState.Downloading,
                is MoonshineModelState.Error,
                -> Unit
            }
            cancellationRequested.set(false)
            stateMutable.value = MoonshineModelState.Downloading(0L, manifest.downloadBytes)
            downloadJob = scope.launch {
                val currentJob = checkNotNull(coroutineContext[Job])
                try {
                    downloadDirectory.mkdirs()
                    val archive = File(downloadDirectory, manifest.archiveFileName)
                    val vad = File(downloadDirectory, manifest.vadFileName)
                    downloadOrReuse(
                        file = archive,
                        url = manifest.archiveUrl,
                        expectedBytes = manifest.archiveBytes,
                        expectedSha256 = manifest.archiveSha256,
                    ) { archiveBytes ->
                        publishDownloadProgress(archiveBytes, 0L)
                    }
                    downloadOrReuse(
                        file = vad,
                        url = manifest.vadUrl,
                        expectedBytes = manifest.vadBytes,
                        expectedSha256 = manifest.vadSha256,
                    ) { vadBytes ->
                        publishDownloadProgress(manifest.archiveBytes, vadBytes)
                    }

                    coroutineContext.ensureActive()
                    stateMutable.value = MoonshineModelState.Verifying
                    installModel(archive, vad)
                    downloadDirectory.deleteRecursively()
                    stateMutable.value = MoonshineModelState.Ready
                } catch (cancelled: CancellationException) {
                    cleanupIncompleteDownload()
                    if (cancellationRequested.get()) {
                        stateMutable.value = MoonshineModelState.NotInstalled
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    if (cancellationRequested.get() || !currentJob.isActive) {
                        cleanupIncompleteDownload()
                        stateMutable.value = MoonshineModelState.NotInstalled
                    } else {
                        stagingDirectory.deleteRecursively()
                        stateMutable.value = MoonshineModelState.Error(
                            error.message ?: error::class.java.simpleName,
                        )
                    }
                } finally {
                    synchronized(operationLock) {
                        if (downloadJob === currentJob) {
                            downloadJob = null
                            cancellationRequested.set(false)
                        }
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        synchronized(operationLock) {
            if (state.value !is MoonshineModelState.Downloading) return
            cancellationRequested.set(true)
            activeCall.getAndSet(null)?.cancel()
            downloadJob?.cancel()
            stateMutable.value = MoonshineModelState.NotInstalled
        }
    }

    fun removeDownloadedModel() {
        synchronized(operationLock) {
            if (downloadJob?.isActive == true) return
            downloadJob = scope.launch {
                modelDirectory.deleteRecursively()
                downloadDirectory.deleteRecursively()
                stagingDirectory.deleteRecursively()
                val currentJob = coroutineContext[Job]
                synchronized(operationLock) {
                    if (downloadJob === currentJob) downloadJob = null
                }
                stateMutable.value = MoonshineModelState.NotInstalled
            }
        }
    }

    private suspend fun verifyExistingModel() {
        stateMutable.value = MoonshineModelState.Verifying
        val modelIsValid = runCatching { verifyModelDirectory(modelDirectory) }.getOrDefault(false)
        stateMutable.value = if (modelIsValid) {
            MoonshineModelState.Ready
        } else {
            if (modelDirectory.exists()) modelDirectory.deleteRecursively()
            MoonshineModelState.NotInstalled
        }
    }

    private suspend fun downloadOrReuse(
        file: File,
        url: String,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Long) -> Unit,
    ) {
        coroutineContext.ensureActive()
        if (file.isFile && file.length() == expectedBytes && sha256(file) == expectedSha256) {
            onProgress(expectedBytes)
            return
        }
        file.delete()
        val partial = File(file.parentFile, "${file.name}.part")
        partial.delete()
        val call = httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .build(),
        )
        activeCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val body = response.body
                if (body.contentLength() >= 0L && body.contentLength() != expectedBytes) {
                    error("Unexpected download size")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                BufferedInputStream(body.byteStream()).use { input ->
                    BufferedOutputStream(FileOutputStream(partial)).use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            onProgress(downloaded)
                        }
                    }
                }
                check(downloaded == expectedBytes) { "Unexpected download size" }
                check(digest.digest().toHex() == expectedSha256) { "Download verification failed" }
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
        coroutineContext.ensureActive()
        check(partial.renameTo(file)) { "Could not store downloaded model" }
    }

    private suspend fun installModel(archive: File, vad: File) {
        check(modelParentDirectory.isDirectory || modelParentDirectory.mkdirs()) {
            "Could not create model directory"
        }
        stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "Could not create model staging directory" }
        extractModelFiles(archive, stagingDirectory)
        vad.copyTo(File(stagingDirectory, manifest.vadFileName), overwrite = true)
        check(verifyModelDirectory(stagingDirectory)) { "Model verification failed" }
        coroutineContext.ensureActive()
        if (modelDirectory.exists()) modelDirectory.deleteRecursively()
        check(stagingDirectory.renameTo(modelDirectory)) { "Could not install model" }
    }

    @Suppress("DEPRECATION")
    private fun extractModelFiles(archive: File, destination: File) {
        val extracted = mutableSetOf<String>()
        BZip2CompressorInputStream(
            BufferedInputStream(FileInputStream(archive)),
        ).use { compressed ->
            TarArchiveInputStream(compressed).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    val modelFileName = manifest.archiveModelFiles.firstOrNull { expectedName ->
                        entry.name == manifest.archiveRoot + expectedName
                    } ?: continue
                    val output = File(destination, modelFileName)
                    check(output.parentFile?.canonicalFile == destination.canonicalFile) {
                        "Unsafe model archive entry"
                    }
                    output.outputStream().use { tar.copyTo(it, BUFFER_SIZE) }
                    extracted += modelFileName
                }
            }
        }
        check(extracted.containsAll(manifest.archiveModelFiles)) { "Model archive is incomplete" }
    }

    private fun publishDownloadProgress(archiveBytes: Long, vadBytes: Long) {
        stateMutable.value = MoonshineModelState.Downloading(
            downloadedBytes = (archiveBytes + vadBytes).coerceAtMost(manifest.downloadBytes),
            totalBytes = manifest.downloadBytes,
        )
    }

    private fun verifyModelDirectory(directory: File): Boolean {
        return manifest.expectedFiles.all { expected ->
            val file = File(directory, expected.name)
            file.isFile && file.length() == expected.bytes && sha256(file) == expected.sha256
        }
    }

    private fun cleanupIncompleteDownload() {
        downloadDirectory.deleteRecursively()
        stagingDirectory.deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@toHex) {
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    companion object {
        private const val MODEL_DIRECTORY_NAME = "live-captions/moonshine-v2-tiny-en"
        private const val ARCHIVE_FILE_NAME = "moonshine-v2-tiny-en.tar.bz2"
        private const val VAD_FILE_NAME = "silero_vad.onnx"
        private const val ARCHIVE_ROOT = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/"
        private const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2"
        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        private const val MODEL_ARCHIVE_BYTES = 29_858_559L
        private const val MODEL_ARCHIVE_SHA256 =
            "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d"
        private const val VAD_BYTES = 643_854L
        private const val VAD_SHA256 =
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        private const val BUFFER_SIZE = 64 * 1024

        const val DOWNLOAD_BYTES = MODEL_ARCHIVE_BYTES + VAD_BYTES

        private val productionManifest = MoonshineModelManifest(
            modelDirectoryName = MODEL_DIRECTORY_NAME,
            archiveFileName = ARCHIVE_FILE_NAME,
            vadFileName = VAD_FILE_NAME,
            archiveRoot = ARCHIVE_ROOT,
            archiveUrl = MODEL_ARCHIVE_URL,
            vadUrl = VAD_URL,
            archiveBytes = MODEL_ARCHIVE_BYTES,
            archiveSha256 = MODEL_ARCHIVE_SHA256,
            vadBytes = VAD_BYTES,
            vadSha256 = VAD_SHA256,
            expectedFiles = listOf(
                MoonshineExpectedFile(
                    name = "encoder_model.ort",
                    bytes = 13_281_600L,
                    sha256 = "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221",
                ),
                MoonshineExpectedFile(
                    name = "decoder_model_merged.ort",
                    bytes = 30_412_256L,
                    sha256 = "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0",
                ),
                MoonshineExpectedFile(
                    name = "tokens.txt",
                    bytes = 549_350L,
                    sha256 = "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049",
                ),
                MoonshineExpectedFile(
                    name = VAD_FILE_NAME,
                    bytes = VAD_BYTES,
                    sha256 = VAD_SHA256,
                ),
            ),
        )

        fun modelDirectory(context: Context): File =
            File(context.applicationContext.filesDir, MODEL_DIRECTORY_NAME)
    }
}
