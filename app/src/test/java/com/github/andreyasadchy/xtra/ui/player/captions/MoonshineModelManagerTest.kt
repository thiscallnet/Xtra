package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.ContextWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.media3.common.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineModelManagerTest {

    @Test
    fun `fresh install creates model parent and reaches ready`() {
        val fixture = Fixture.create()
        val scope = testScope()
        try {
            val manager = fixture.manager(scope)
            awaitState(manager.state) { it is MoonshineModelState.NotInstalled }

            manager.download()

            awaitState(manager.state) { it is MoonshineModelState.Ready }
            assertTrue(fixture.modelDirectory.isDirectory)
            assertTrue(File(fixture.modelDirectory, "encoder_model.ort").isFile)
            assertTrue(File(fixture.modelDirectory, "decoder_model_merged.ort").isFile)
            assertTrue(File(fixture.modelDirectory, "tokens.txt").isFile)
            assertTrue(File(fixture.modelDirectory, "silero_vad.onnx").isFile)

            manager.removeDownloadedModel()
            awaitState(manager.state) { it is MoonshineModelState.NotInstalled && !fixture.modelDirectory.exists() }
            manager.download()
            awaitState(manager.state) { it is MoonshineModelState.Ready }
        } finally {
            scope.cancel()
            fixture.delete()
        }
    }

    @Test
    fun `valid installed model is ready after manager restart`() {
        val fixture = Fixture.create()
        val firstScope = testScope()
        try {
            val firstManager = fixture.manager(firstScope)
            awaitState(firstManager.state) { it is MoonshineModelState.NotInstalled }
            firstManager.download()
            awaitState(firstManager.state) { it is MoonshineModelState.Ready }
        } finally {
            firstScope.cancel()
        }

        val secondScope = testScope()
        try {
            val secondManager = fixture.manager(secondScope)
            val state = runBlocking { secondManager.awaitVerification() }
            assertTrue(state is MoonshineModelState.Ready)
        } finally {
            secondScope.cancel()
            fixture.delete()
        }
    }

    @Test
    fun `cancel during active read ends in not installed and cleans temporary files`() {
        val fixture = Fixture.create()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val scope = testScope()
        try {
            val manager = fixture.manager(
                scope = scope,
                httpClient = fixture.client(blockingArchive = readStarted to releaseRead),
            )
            awaitState(manager.state) { it is MoonshineModelState.NotInstalled }
            manager.download()
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))

            manager.cancelDownload()
            releaseRead.countDown()

            awaitState(manager.state) {
                it is MoonshineModelState.NotInstalled &&
                    !fixture.downloadDirectory.exists() &&
                    !fixture.stagingDirectory.exists()
            }
            assertFalse(fixture.modelDirectory.exists())
            assertFalse(fixture.downloadDirectory.exists())
            assertFalse(fixture.stagingDirectory.exists())
        } finally {
            releaseRead.countDown()
            scope.cancel()
            fixture.delete()
        }
    }

    @Test
    fun `bad archive never exposes a model`() {
        val fixture = Fixture.create()
        val scope = testScope()
        try {
            val manager = fixture.manager(
                scope = scope,
                httpClient = fixture.client(archiveBytes = fixture.archiveBytes + 1),
            )
            awaitState(manager.state) { it is MoonshineModelState.NotInstalled }
            manager.download()

            awaitState(manager.state) { it is MoonshineModelState.Error }
            assertFalse(fixture.modelDirectory.exists())
            assertFalse(fixture.stagingDirectory.exists())
        } finally {
            scope.cancel()
            fixture.delete()
        }
    }

    @Test
    fun `missing model blocks caption worker without restart loop`() {
        val fixture = Fixture.create()
        val modelScope = testScope()
        try {
            val modelManager = fixture.manager(modelScope)
            awaitState(modelManager.state) { it is MoonshineModelState.NotInstalled }
            val captionManager = LiveCaptionManager(
                context = ContextWrapper(null),
                engineFactory = { error("engine must not be created") },
                modelManager = modelManager,
                runtimeSettingsLoader = {},
            )
            try {
                captionManager.setEnabled(true)
                captionManager.audioBufferSink.flush(16_000, 1, C.ENCODING_PCM_16BIT)
                repeat(8) {
                    captionManager.audioBufferSink.handleBuffer(ByteBuffer.allocate(320))
                }

                awaitState(captionManager.state) {
                    it.enabled &&
                        it.status == LiveCaptionState.Status.ERROR &&
                        it.error == "MODEL_REQUIRED"
                }
                Thread.sleep(250)
                assertTrue(captionManager.state.value.enabled)
                captionManager.setEnabled(false)
                awaitState(captionManager.state) { !it.enabled }
            } finally {
                captionManager.close()
            }
        } finally {
            modelScope.cancel()
            fixture.delete()
        }
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun <T> awaitState(
        state: StateFlow<T>,
        predicate: (T) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate(state.value)) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for ${state.value}")
            }
            Thread.sleep(10)
        }
    }

    private class Fixture private constructor(
        val root: File,
        val manifest: MoonshineModelManifest,
        val archiveBytes: ByteArray,
        val vadBytes: ByteArray,
    ) {
        val modelDirectory = File(root, manifest.modelDirectoryName)
        val downloadDirectory = File(root, "live-captions/.moonshine-v2-download")
        val stagingDirectory = File(root, "live-captions/.moonshine-v2-staging")

        fun manager(
            scope: CoroutineScope,
            httpClient: OkHttpClient = client(),
        ) = MoonshineModelManager(root, httpClient, manifest, scope)

        fun client(
            archiveBytes: ByteArray = this.archiveBytes,
            blockingArchive: Pair<CountDownLatch, CountDownLatch>? = null,
        ): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val bytes = if (request.url.toString() == manifest.archiveUrl) {
                    archiveBytes
                } else {
                    vadBytes
                }
                val body = if (blockingArchive != null && request.url.toString() == manifest.archiveUrl) {
                    BlockingResponseBody(bytes, blockingArchive.first, blockingArchive.second)
                } else {
                    bytes.toResponseBody("application/octet-stream".toMediaType())
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

        fun delete() {
            root.deleteRecursively()
        }

        companion object {
            fun create(): Fixture {
                val root = createTempDirectory()
                val archiveRoot = "test-moonshine/"
                val modelFiles = linkedMapOf(
                    "encoder_model.ort" to "encoder".encodeToByteArray(),
                    "decoder_model_merged.ort" to "decoder".encodeToByteArray(),
                    "tokens.txt" to "tokens".encodeToByteArray(),
                )
                val vadBytes = "vad".encodeToByteArray()
                val archiveBytes = createArchive(archiveRoot, modelFiles)
                val expectedFiles = modelFiles.map { (name, bytes) ->
                    MoonshineExpectedFile(name, bytes.size.toLong(), sha256(bytes))
                } + MoonshineExpectedFile("silero_vad.onnx", vadBytes.size.toLong(), sha256(vadBytes))
                val manifest = MoonshineModelManifest(
                    modelDirectoryName = "live-captions/moonshine-v2-tiny-en",
                    archiveFileName = "moonshine-test.tar.bz2",
                    vadFileName = "silero_vad.onnx",
                    archiveRoot = archiveRoot,
                    archiveUrl = "https://example.test/moonshine.tar.bz2",
                    vadUrl = "https://example.test/silero_vad.onnx",
                    archiveBytes = archiveBytes.size.toLong(),
                    archiveSha256 = sha256(archiveBytes),
                    vadBytes = vadBytes.size.toLong(),
                    vadSha256 = sha256(vadBytes),
                    expectedFiles = expectedFiles,
                )
                return Fixture(root, manifest, archiveBytes, vadBytes)
            }

            private fun createTempDirectory(): File =
                java.nio.file.Files.createTempDirectory("moonshine-model-test").toFile()

            private fun createArchive(
                archiveRoot: String,
                files: Map<String, ByteArray>,
            ): ByteArray = ByteArrayOutputStream().use { output ->
                BZip2CompressorOutputStream(output).use { compressed ->
                    TarArchiveOutputStream(compressed).use { tar ->
                        files.forEach { (name, bytes) ->
                            tar.putArchiveEntry(TarArchiveEntry(archiveRoot + name).apply {
                                size = bytes.size.toLong()
                            })
                            tar.write(bytes)
                            tar.closeArchiveEntry()
                        }
                        tar.finish()
                    }
                }
                output.toByteArray()
            }

            private fun sha256(bytes: ByteArray): String =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    private class BlockingResponseBody(
        private val bytes: ByteArray,
        private val readStarted: CountDownLatch,
        private val releaseRead: CountDownLatch,
    ) : ResponseBody() {
        override fun contentType() = null

        override fun contentLength() = bytes.size.toLong()

        override fun source(): BufferedSource = object : ForwardingSource(
            bytes.inputStream().source(),
        ) {
            private var firstRead = true

            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (firstRead) {
                    firstRead = false
                    readStarted.countDown()
                    releaseRead.await(5, TimeUnit.SECONDS)
                    throw IOException("connection canceled")
                }
                return super.read(sink, byteCount)
            }
        }.buffer()
    }
}
