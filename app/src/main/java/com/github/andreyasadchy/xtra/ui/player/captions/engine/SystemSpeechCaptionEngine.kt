package com.github.andreyasadchy.xtra.ui.player.captions.engine

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.ui.player.captions.resampleTo16k
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Live captions from the device's own recognizer (ML Kit GenAI speech recognition, which
 * runs on AICore / the system speech model) instead of the downloaded Moonshine model.
 *
 * Playback audio is streamed into the recognizer through a pipe, as the API requires:
 * headerless 16 kHz mono 16-bit PCM delivered at real-time rate. The pipe is non-blocking and
 * lossy, so a stalled recognizer can never block the caption worker.
 */
class SystemSpeechCaptionEngine(
    @Suppress("unused") private val context: Context,
) : LiveCaptionEngine {
    override val id: String = SYSTEM_SPEECH_ENGINE_ID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recognizer: SpeechRecognizer = SpeechRecognition.getClient(systemSpeechOptions())
    private val pendingEvents = ConcurrentLinkedQueue<CaptionRecognitionEvent>()
    private var session: Session? = null
    private var consecutiveFailures = 0
    private var nextSessionAtMs = 0L

    init {
        val status = runBlocking {
            withTimeoutOrNull(STATUS_TIMEOUT_MS) { recognizer.checkStatus() }
        } ?: FeatureStatus.UNAVAILABLE
        if (status != FeatureStatus.AVAILABLE) {
            runCatching { recognizer.close() }
            scope.cancel()
            throw SystemSpeechUnavailableException(status)
        }
    }

    override fun accept(
        samples: FloatArray,
        sampleRateHz: Int,
    ): List<CaptionRecognitionEvent> {
        if (samples.isNotEmpty()) {
            val active = session?.takeIf { it.isAlive } ?: nextSession()
            active?.write(floatToPcm16(resampleTo16k(samples, sampleRateHz)))
        }
        return drainEvents()
    }

    override fun reset() {
        session?.close()
        session = null
        nextSessionAtMs = 0L
        pendingEvents.clear()
    }

    override fun close() {
        session?.close()
        session = null
        pendingEvents.clear()
        runCatching { recognizer.close() }
        scope.cancel()
    }

    /**
     * Opens a replacement session once the previous one finished or failed. Failures are
     * retried a few times, then reported instead of looping silently. A recognizer that simply
     * completes (for example after silence) is a normal restart, not a failure.
     */
    private fun nextSession(): Session? {
        session?.let { finished ->
            session = null
            finished.close()
            if (finished.failure != null && !finished.wasHealthy()) {
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw IllegalStateException(finished.failure)
                }
            } else {
                consecutiveFailures = 0
            }
            nextSessionAtMs = SystemClock.elapsedRealtime() + SESSION_RETRY_DELAY_MS
        }
        if (SystemClock.elapsedRealtime() < nextSessionAtMs) return null
        return Session().also { session = it }
    }

    private fun drainEvents(): List<CaptionRecognitionEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val events = ArrayList<CaptionRecognitionEvent>(2)
        while (true) events += pendingEvents.poll() ?: break
        return events
    }

    private inner class Session {
        private val startedAtMs = SystemClock.elapsedRealtime()
        private val pipe = ParcelFileDescriptor.createPipe()
        private val readEnd = pipe[0]
        private val writeEnd = pipe[1]

        @Volatile
        var isAlive = true
            private set

        @Volatile
        var failure: String? = null
            private set

        init {
            // Os.fcntlInt needs API 30; the system recognizer itself only exists on API 31+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tunePipe()
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun tunePipe() {
            val fd = writeEnd.fileDescriptor
            // Bursts after startup or a seek must not be dropped: hold about 8 s of audio.
            runCatching { Os.fcntlInt(fd, F_SETPIPE_SZ, PIPE_BYTES) }
            runCatching {
                val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            }
        }

        private val job: Job = scope.launch {
            try {
                val request = speechRecognizerRequest {
                    audioSource = AudioSource.fromPfd(readEnd)
                }
                recognizer.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse ->
                            offer(CaptionRecognitionEvent.Partial(response.text.trim()))
                        is SpeechRecognizerResponse.FinalTextResponse ->
                            offer(CaptionRecognitionEvent.Final(response.text.trim()))
                        is SpeechRecognizerResponse.ErrorResponse -> {
                            Log.w(TAG, "recognizer_error ${response.e.message}")
                            failure = response.e.message ?: "System speech recognition failed"
                            isAlive = false
                        }
                        is SpeechRecognizerResponse.CompletedResponse -> isAlive = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "recognizer_failed ${error::class.java.simpleName}: ${error.message}")
                failure = error.message ?: error::class.java.simpleName
            } finally {
                isAlive = false
            }
        }

        fun wasHealthy(): Boolean =
            SystemClock.elapsedRealtime() - startedAtMs >= HEALTHY_SESSION_MS

        /** Drops audio the recognizer is not keeping up with instead of waiting for it. */
        fun write(bytes: ByteArray) {
            var offset = 0
            try {
                while (offset < bytes.size) {
                    offset += Os.write(writeEnd.fileDescriptor, bytes, offset, bytes.size - offset)
                }
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "pipe_full dropped=${bytes.size - offset}")
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "pipe_write_failed ${error.message}")
                    isAlive = false
                }
            }
        }

        private fun offer(event: CaptionRecognitionEvent) {
            // A closed session may still deliver one last result; it belongs to old audio.
            if (!isAlive) return
            val text = when (event) {
                is CaptionRecognitionEvent.Partial -> event.text
                is CaptionRecognitionEvent.Final -> event.text
            }
            if (text.isNotEmpty()) pendingEvents.add(event)
        }

        /** Stops this recognition completely so a following session never overlaps it. */
        fun close() {
            val wasRunning = job.isActive
            isAlive = false
            runCatching { writeEnd.close() }
            if (wasRunning) {
                runBlocking {
                    withTimeoutOrNull(STOP_TIMEOUT_MS) {
                        runCatching { recognizer.stopRecognition() }
                        job.cancel()
                        job.join()
                    }
                }
            }
            runCatching { readEnd.close() }
        }
    }

    private companion object {
        const val TAG = "SystemSpeechEngine"
        const val STATUS_TIMEOUT_MS = 5_000L
        const val STOP_TIMEOUT_MS = 1_000L
        const val HEALTHY_SESSION_MS = 10_000L
        const val SESSION_RETRY_DELAY_MS = 1_000L
        const val MAX_CONSECUTIVE_FAILURES = 4

        // Linux F_SETPIPE_SZ; not exposed by OsConstants.
        const val F_SETPIPE_SZ = 1031
        const val PIPE_BYTES = 256 * 1024
    }
}

/** Availability and model download for the system recognizer, without starting a session. */
object SystemSpeechAvailability {
    private const val STATUS_TIMEOUT_MS = 5_000L

    /** A [FeatureStatus] value: AVAILABLE, DOWNLOADABLE, DOWNLOADING or UNAVAILABLE. */
    suspend fun status(): Int {
        val client = SpeechRecognition.getClient(systemSpeechOptions())
        return try {
            withTimeoutOrNull(STATUS_TIMEOUT_MS) { client.checkStatus() } ?: FeatureStatus.UNAVAILABLE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("SystemSpeechEngine", "status_failed ${error::class.java.simpleName}: ${error.message}")
            FeatureStatus.UNAVAILABLE
        } finally {
            runCatching { client.close() }
        }
    }

    fun download(): Flow<DownloadStatus> = flow {
        val client = SpeechRecognition.getClient(systemSpeechOptions())
        try {
            emitAll(client.download())
        } finally {
            runCatching { client.close() }
        }
    }
}

private fun systemSpeechOptions(): SpeechRecognizerOptions = speechRecognizerOptions {
    // English only, matching the Moonshine model.
    locale = Locale.US
    preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
}

class SystemSpeechUnavailableException(val featureStatus: Int) :
    IllegalStateException(
        "System recognizer is not available on this device. Pick another engine in caption settings.",
    )

internal const val SYSTEM_SPEECH_ENGINE_ID = "system_speech"

internal fun floatToPcm16(samples: FloatArray): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    for (index in samples.indices) {
        val value = (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        bytes[index * 2] = value.toByte()
        bytes[index * 2 + 1] = (value shr 8).toByte()
    }
    return bytes
}
