package com.github.andreyasadchy.xtra.ui.chat.v2

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoader
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoadException
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatImageHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGifInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatTimelineAdapter
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatNamePaint
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.resolveChatSizing
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.doOnPreDraw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageTextViewTest {
    @Test
    fun chatSizeModifierScalesTextEmoteAndBadgeDimensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.prefs()
        val oldModifier = prefs.getInt(C.CHAT_SIZE_MODIFIER, 100)
        val oldText = prefs.getString(C.CHAT_TEXT_SIZE, null)
        val oldEmote = prefs.getString(C.CHAT_EMOTE_SIZE, null)
        val oldBadge = prefs.getString(C.CHAT_BADGE_SIZE, null)
        try {
            prefs.edit()
                .putString(C.CHAT_TEXT_SIZE, "14")
                .putString(C.CHAT_EMOTE_SIZE, "20")
                .putString(C.CHAT_BADGE_SIZE, "10")
                .putInt(C.CHAT_SIZE_MODIFIER, 50)
                .commit()
            val half = resolveChatSizing(context)
            prefs.edit().putInt(C.CHAT_SIZE_MODIFIER, 100).commit()
            val normal = resolveChatSizing(context)
            prefs.edit().putInt(C.CHAT_SIZE_MODIFIER, 200).commit()
            val double = resolveChatSizing(context)
            assertTrue(half.textSizeSp < normal.textSizeSp)
            assertTrue(normal.textSizeSp < double.textSizeSp)
            assertTrue(half.emoteHeightPx < normal.emoteHeightPx)
            assertTrue(normal.emoteHeightPx < double.emoteHeightPx)
            assertTrue(half.badgeHeightPx < normal.badgeHeightPx)
            assertTrue(normal.badgeHeightPx < double.badgeHeightPx)
        } finally {
            val editor = prefs.edit().putInt(C.CHAT_SIZE_MODIFIER, oldModifier)
            if (oldText == null) editor.remove(C.CHAT_TEXT_SIZE) else editor.putString(C.CHAT_TEXT_SIZE, oldText)
            if (oldEmote == null) editor.remove(C.CHAT_EMOTE_SIZE) else editor.putString(C.CHAT_EMOTE_SIZE, oldEmote)
            if (oldBadge == null) editor.remove(C.CHAT_BADGE_SIZE) else editor.putString(C.CHAT_BADGE_SIZE, oldBadge)
            editor.apply()
        }
    }

    @Test
    fun pendingAssetKeepsFinalGeometryAndRowIsNotExposed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asset = CompletableDeferred<ChatImageHandle?>()
        val repository = ChatAssetRepository(scope, ChatAssetLoader { asset.await() })
        val attached = attachView(repository)
        val view = attached.view
        val spec = ChatAssetSpec(ChatAssetKey("missing"), 20, 20, 28)
        lateinit var span: ReplacementSpan
        try {
            runOnMain {
                view.bind(row(spec, username = "login", fallback = "OMEGALUL"))
                val spanned = view.text as Spanned
                val usernameColor = spanned.getSpans(0, 5, ForegroundColorSpan::class.java).single().foregroundColor
                assertNotEquals(Color.WHITE, usernameColor)
                assertEquals(0f, view.alpha)
                span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                val metrics = Paint.FontMetricsInt()
                assertEquals(spec.compositionWidth, span.getSize(Paint(), spanned, 0, 1, metrics))
                assertTrue(metrics.descent - metrics.ascent >= spec.compositionHeight)
                assertFalse(hasVisiblePixels(draw(span, spanned, metrics)))
            }
            asset.complete(ChatImageHandle { SolidDrawable(Color.RED) })
            withTimeout(2_000) {
                while (repository.peek(spec.key) !is ChatAssetState.Ready) delay(1)
            }
            awaitPreDraw(view)
            runOnMain {
                val spanned = view.text as Spanned
                val metrics = Paint.FontMetricsInt()
                assertEquals(1f, view.alpha)
                assertEquals(spec.compositionWidth, span.getSize(Paint(), spanned, 0, 1, metrics))
                assertTrue(containsColor(draw(span, spanned, metrics), Color.RED))
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedBadgeNeverDrawsItsName() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { throw ChatAssetLoadException(404) })
        val attached = attachView(repository)
        val view = attached.view
        val spec = ChatAssetSpec(ChatAssetKey("badge"), 18, 18, 18)
        try {
            runOnMain { view.bind(badgeRow(spec, "subscriber")) }
            awaitPresentationTerminal(repository, listOf(spec.key))
            awaitPreDraw(view)
            runOnMain {
                val spanned = view.text as Spanned
                val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                assertEquals(1f, view.alpha)
                assertEquals(spec.compositionWidth, span.getSize(Paint(), spanned, 0, 1, Paint.FontMetricsInt()))
                assertFalse(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedEmoteUsesTextOnlyAfterFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFailure = CompletableDeferred<Unit>()
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            releaseFailure.await()
            throw ChatAssetLoadException(404)
        })
        val attached = attachView(repository)
        val view = attached.view
        val spec = ChatAssetSpec(ChatAssetKey("failed-emote"), 20, 20, 28)
        lateinit var span: ReplacementSpan
        try {
            runOnMain {
                view.bind(row(spec, fallback = "OMEGALUL"))
                val spanned = view.text as Spanned
                span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                assertEquals(0f, view.alpha)
                assertFalse(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }

            releaseFailure.complete(Unit)
            awaitPresentationTerminal(repository, listOf(spec.key))
            awaitPreDraw(view)
            runOnMain {
                val spanned = view.text as Spanned
                assertEquals(1f, view.alpha)
                assertTrue(span.getSize(Paint(), spanned, 0, 1, Paint.FontMetricsInt()) >= spec.compositionWidth)
                assertTrue(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun rowRevealsOnlyAfterAllInitialAssetsSettle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<ChatImageHandle?>()
        val second = CompletableDeferred<ChatImageHandle?>()
        val repository = ChatAssetRepository(scope, ChatAssetLoader { key ->
            when (key.value) {
                "first" -> first.await()
                "second" -> {
                    second.await()
                    throw ChatAssetLoadException(404)
                }
                else -> null
            }
        })
        val attached = attachView(repository)
        val view = attached.view
        val firstSpec = ChatAssetSpec(ChatAssetKey("first"), 20, 20, 28)
        val secondSpec = ChatAssetSpec(ChatAssetKey("second"), 20, 20, 28)
        val row = ChatRowUiModel(
            id = ChatMessageId("two-assets"),
            channelId = "channel",
            timestampText = null,
            pieces = listOf(
                ChatPiece.Emote(firstSpec, "FIRST"),
                ChatPiece.Emote(secondSpec, "SECOND"),
            ),
            background = 0xff101010.toInt(),
            accessibilityText = "two assets",
            reply = null,
            source = null,
            isAction = false,
        )
        try {
            runOnMain {
                view.bind(row)
                assertEquals(0f, view.alpha)
            }

            first.complete(ChatImageHandle { SolidDrawable(Color.RED) })
            awaitSettled(repository, listOf(firstSpec.key))
            awaitPreDraw(view)
            runOnMain { assertEquals(0f, view.alpha) }

            second.complete(null)
            awaitPresentationTerminal(repository, listOf(secondSpec.key))
            awaitPreDraw(view)
            runOnMain { assertEquals(1f, view.alpha) }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun retryableFailureKeepsInitialRowHiddenUntilRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstFailed = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val repository = ChatAssetRepository(
            scope = scope,
            loader = ChatAssetLoader {
                if (attempts.incrementAndGet() == 1) {
                    firstFailed.complete(Unit)
                    null
                } else {
                    secondStarted.complete(Unit)
                    secondRelease.await()
                    ChatImageHandle { SolidDrawable(Color.RED) }
                }
            },
        )
        val attached = attachView(repository)
        val view = attached.view
        val spec = ChatAssetSpec(ChatAssetKey("retryable"), 20, 20, 28)
        try {
            runOnMain { view.bind(row(spec, fallback = "OMEGALUL")) }
            firstFailed.await()
            withTimeout(2_000) {
                while (repository.peek(spec.key) !is ChatAssetState.Failed) delay(1)
            }
            awaitPreDraw(view)
            runOnMain {
                val spanned = view.text as Spanned
                val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                assertEquals(0f, view.alpha)
                assertFalse(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }

            withTimeout(3_000) { secondStarted.await() }
            awaitPreDraw(view)
            runOnMain {
                val spanned = view.text as Spanned
                val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                assertEquals(0f, view.alpha)
                assertFalse(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }

            secondRelease.complete(Unit)
            withTimeout(2_000) {
                while (repository.peek(spec.key) !is ChatAssetState.Ready) delay(1)
            }
            awaitPreDraw(view)
            runOnMain { assertEquals(1f, view.alpha) }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun sameKeyRebindKeepsTheExistingAssetLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<ChatImageHandle?>()
        val loads = AtomicInteger()
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            loads.incrementAndGet()
            release.await()
        })
        val attached = attachView(repository)
        val view = attached.view
        val spec = ChatAssetSpec(ChatAssetKey("same-key"), 20, 20, 28)
        try {
            val row = row(spec)
            runOnMain {
                view.bind(row)
                assertEquals(1, repository.observerCount(spec.key))
                view.bind(row)
                assertEquals(1, repository.observerCount(spec.key))
            }
            withTimeout(2_000) { while (loads.get() < 1) delay(1) }
            assertEquals(1, loads.get())
            release.complete(ChatImageHandle { SolidDrawable(Color.RED) })
            withTimeout(2_000) {
                while (repository.peek(spec.key) !is ChatAssetState.Ready) delay(1)
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun revealedRowDoesNotChangeDuringALaterRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retryStarted = CompletableDeferred<Unit>()
        val retryRelease = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            if (attempts.incrementAndGet() == 1) throw ChatAssetLoadException(404)
            retryStarted.complete(Unit)
            retryRelease.await()
            ChatImageHandle { SolidDrawable(Color.RED) }
        })
        val attached = attachView(repository)
        val view = attached.view
        val spec = ChatAssetSpec(ChatAssetKey("latched"), 20, 20, 28)
        try {
            runOnMain { view.bind(row(spec, fallback = "OMEGALUL")) }
            awaitPresentationTerminal(repository, listOf(spec.key))
            awaitPreDraw(view)
            runOnMain {
                assertEquals(1f, view.alpha)
                val spanned = view.text as Spanned
                val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                assertTrue(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }

            repository.invalidate(setOf(spec.key))
            retryStarted.await()
            awaitPreDraw(view)
            runOnMain {
                assertEquals(1f, view.alpha)
                val spanned = view.text as Spanned
                val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                assertTrue(hasVisiblePixels(draw(span, spanned, Paint.FontMetricsInt())))
            }

            retryRelease.complete(Unit)
            withTimeout(2_000) {
                while (repository.peek(spec.key) !is ChatAssetState.Ready) delay(1)
            }
            awaitPreDraw(view)
            runOnMain { assertEquals(1f, view.alpha) }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun recycledRowGetsANewInitialGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<ChatImageHandle?>()
        val second = CompletableDeferred<ChatImageHandle?>()
        val repository = ChatAssetRepository(scope, ChatAssetLoader { key ->
            if (key.value == "first-row") first.await() else second.await()
        })
        val attached = attachView(repository)
        val view = attached.view
        try {
            val firstSpec = ChatAssetSpec(ChatAssetKey("first-row"), 20, 20, 28)
            val secondSpec = ChatAssetSpec(ChatAssetKey("second-row"), 20, 20, 28)
            runOnMain {
                view.bind(row(firstSpec))
                assertEquals(0f, view.alpha)
                view.recycle()
                view.bind(row(secondSpec))
                assertEquals(0f, view.alpha)
            }
            second.complete(ChatImageHandle { SolidDrawable(Color.RED) })
            withTimeout(2_000) {
                while (repository.peek(secondSpec.key) !is ChatAssetState.Ready) delay(1)
            }
            awaitPreDraw(view)
            runOnMain { assertEquals(1f, view.alpha) }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun clipRowReservesGeometryAndWaitsForMetadataAndThumbnail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseMetadata = CompletableDeferred<ChatClipPreview?>()
        val releaseThumbnail = CompletableDeferred<ChatImageHandle?>()
        val clipRepository = ChatClipPreviewRepository(scope) { releaseMetadata.await() }
        val assetRepository = ChatAssetRepository(scope, ChatAssetLoader { releaseThumbnail.await() })
        val attached = attachView(assetRepository, clipRepository)
        val view = attached.view
        val link = ChatClipPreviewLink("clip-slug", "https://clips.twitch.tv/clip-slug")
        val row = ChatRowUiModel(
            id = ChatMessageId("clip-row"),
            channelId = "channel",
            timestampText = null,
            pieces = listOf(ChatPiece.Text(link.url)),
            background = 0xff101010.toInt(),
            accessibilityText = link.url,
            reply = null,
            source = null,
            isAction = false,
            clipPreviews = listOf(link),
        )
        try {
            runOnMain {
                view.bind(row)
                assertEquals(0f, view.alpha)
            }
            awaitPreDraw(view)
            val reservedHeight = view.measuredHeight
            releaseMetadata.complete(ChatClipPreview(
                title = "clip",
                broadcasterName = "broadcaster",
                creatorName = "creator",
                thumbnailUrl = "thumbnail",
                gameName = "game",
                durationSeconds = 30,
                createdAt = null,
            ))
            withTimeout(2_000) {
                while (assetRepository.peek(ChatAssetKey("thumbnail")) is ChatAssetState.Missing) delay(1)
            }
            awaitPreDraw(view)
            runOnMain {
                assertEquals(0f, view.alpha)
                assertEquals(reservedHeight, view.measuredHeight)
            }
            releaseThumbnail.complete(ChatImageHandle { SolidDrawable(Color.RED) })
            withTimeout(2_000) {
                while (assetRepository.peek(ChatAssetKey("thumbnail")) !is ChatAssetState.Ready) delay(1)
            }
            awaitPreDraw(view)
            runOnMain {
                assertEquals(1f, view.alpha)
                assertEquals(reservedHeight, view.measuredHeight)
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedCompositeLatchesOnlyItsComposition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { key ->
            if (key.value == "A") ChatImageHandle { SolidDrawable(Color.RED) }
            else throw ChatAssetLoadException(404)
        })
        val attached = attachView(repository)
        val view = attached.view
        val plain = ChatAssetSpec(ChatAssetKey("A"), 20, 20, 28)
        val composite = plain.copy(
            overlays = listOf(ChatAssetSpec(ChatAssetKey("B"), 20, 20, 28)),
        )
        val row = ChatRowUiModel(
            id = ChatMessageId("composite-latch"),
            channelId = "channel",
            timestampText = null,
            pieces = listOf(
                ChatPiece.Emote(plain, "A"),
                ChatPiece.Emote(composite, "A+B"),
            ),
            background = 0xff101010.toInt(),
            accessibilityText = "A A+B",
            reply = null,
            source = null,
            isAction = false,
        )
        try {
            runOnMain { view.bind(row) }
            awaitPresentationTerminal(repository, listOf(plain.key, composite.overlays.single().key))
            awaitPreDraw(view)
            runOnMain {
                val spanned = view.text as Spanned
                val spans = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java)
                    .sortedBy { spanned.getSpanStart(it) }
                assertEquals(2, spans.size)
                assertTrue(containsColor(draw(spans[0], spanned, Paint.FontMetricsInt()), Color.RED))
                assertTrue(hasVisiblePixels(draw(spans[1], spanned, Paint.FontMetricsInt())))
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedNamePaintDoesNotChangeWhenItsImageRecovers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retryStarted = CompletableDeferred<Unit>()
        val retryRelease = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val key = ChatAssetKey("paint")
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            if (attempts.incrementAndGet() == 1) throw ChatAssetLoadException(404)
            retryStarted.complete(Unit)
            retryRelease.await()
            ChatImageHandle { SolidDrawable(Color.RED) }
        })
        val attached = attachView(repository)
        val view = attached.view
        val row = ChatRowUiModel(
            id = ChatMessageId("paint-latch"),
            channelId = "channel",
            timestampText = null,
            pieces = listOf(
                ChatPiece.Username(
                    value = "painted",
                    color = Color.WHITE,
                    paint = ChatNamePaint(colors = listOf(Color.RED, Color.BLUE), imageUrl = key.value),
                ),
            ),
            background = 0xff101010.toInt(),
            accessibilityText = "painted",
            reply = null,
            source = null,
            isAction = false,
        )
        try {
            runOnMain { view.bind(row) }
            awaitPresentationTerminal(repository, listOf(key))
            awaitPreDraw(view)
            fun nameShader() = runOnMainValue {
                val spanned = view.text as Spanned
                spanned.getSpans(0, 7, CharacterStyle::class.java).mapNotNull { span ->
                    TextPaint().also(span::updateDrawState).shader
                }.first()
            }
            val initialShader = nameShader()
            assertEquals(LinearGradient::class.java, initialShader.javaClass)

            repository.invalidate(setOf(key))
            retryStarted.await()
            awaitPreDraw(view)
            val retryShader = nameShader()
            assertEquals(LinearGradient::class.java, retryShader.javaClass)

            retryRelease.complete(Unit)
            withTimeout(2_000) { while (repository.peek(key) !is ChatAssetState.Ready) delay(1) }
            awaitPreDraw(view)
            val recoveredShader = nameShader()
            assertEquals(LinearGradient::class.java, recoveredShader.javaClass)
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedClipMetadataRevealsWithoutBlankCard() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clipRepository = ChatClipPreviewRepository(scope) { null }
        val assetRepository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val attached = attachView(assetRepository, clipRepository)
        val view = attached.view
        val link = ChatClipPreviewLink("failed-clip", "https://clips.twitch.tv/failed-clip")
        try {
            runOnMain { view.bind(textRow(link.url, ChatMessageId("failed-clip-row")).copy(clipPreviews = listOf(link))) }
            withTimeout(2_000) {
                while (clipRepository.peekState(link.slug) !is com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewState.Ready) delay(1)
            }
            awaitPreDraw(view)
            runOnMain {
                assertEquals(1f, view.alpha)
                assertEquals(400, view.measuredHeight)
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedClipMetadataDoesNotGrowLaterOnSameBinding() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loads = AtomicInteger()
        val clipRepository = ChatClipPreviewRepository(scope) {
            if (loads.incrementAndGet() == 1) null else ChatClipPreview(
                title = "clip",
                broadcasterName = "broadcaster",
                creatorName = "creator",
                thumbnailUrl = null,
                gameName = null,
                durationSeconds = null,
                createdAt = null,
            )
        }
        val assetRepository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val attached = attachView(assetRepository, clipRepository)
        val view = attached.view
        val link = ChatClipPreviewLink("retry-clip", "https://clips.twitch.tv/retry-clip")
        try {
            runOnMain { view.bind(textRow(link.url, ChatMessageId("retry-clip-row")).copy(clipPreviews = listOf(link))) }
            withTimeout(2_000) {
                while (clipRepository.peekState(link.slug) !is com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewState.Ready) delay(1)
            }
            awaitPreDraw(view)
            var failedHeight = 0
            runOnMain { failedHeight = view.measuredHeight }
            runOnMain { view.setRenderingActive(false); view.setRenderingActive(true) }
            withTimeout(2_000) { while (loads.get() < 2) delay(1) }
            awaitPreDraw(view)
            runOnMain {
                assertEquals(1f, view.alpha)
                assertEquals(failedHeight, view.measuredHeight)
            }
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun failedClipThumbnailDoesNotChangeWhenItsImageRecovers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clipRepository = ChatClipPreviewRepository(scope) {
            ChatClipPreview(
                title = "clip",
                broadcasterName = "broadcaster",
                creatorName = "creator",
                thumbnailUrl = "thumbnail",
                gameName = null,
                durationSeconds = null,
                createdAt = null,
            )
        }
        val retryStarted = CompletableDeferred<Unit>()
        val retryRelease = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val thumbnailKey = ChatAssetKey("thumbnail")
        val assetRepository = ChatAssetRepository(scope, ChatAssetLoader {
            if (attempts.incrementAndGet() == 1) throw ChatAssetLoadException(404)
            retryStarted.complete(Unit)
            retryRelease.await()
            ChatImageHandle { SolidDrawable(Color.RED) }
        })
        val attached = attachView(assetRepository, clipRepository)
        val view = attached.view
        val link = ChatClipPreviewLink("thumbnail-clip", "https://clips.twitch.tv/thumbnail-clip")
        try {
            runOnMain { view.bind(textRow(link.url, ChatMessageId("thumbnail-clip-row")).copy(clipPreviews = listOf(link))) }
            awaitPresentationTerminal(assetRepository, listOf(thumbnailKey))
            awaitPreDraw(view)
            val initial = runOnMainResult {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }

            assetRepository.invalidate(setOf(thumbnailKey))
            retryStarted.await()
            awaitPreDraw(view)
            val duringRetry = runOnMainResult {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            assertTrue(initial.sameAs(duringRetry))

            retryRelease.complete(Unit)
            withTimeout(2_000) { while (assetRepository.peek(thumbnailKey) !is ChatAssetState.Ready) delay(1) }
            awaitPreDraw(view)
            val recovered = runOnMainResult {
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            assertTrue(initial.sameAs(recovered))
        } finally {
            attached.close()
            scope.cancel()
        }
    }

    @Test
    fun animatedDrawableIsVerifiedAndFollowsAttachDetachLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var animated: RecordingAnimatedDrawable
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            ChatImageHandle { RecordingAnimatedDrawable().also { animated = it } }
        })
        val view = TestTextView(context, repository)
        val spec = ChatAssetSpec(ChatAssetKey("animated"), 20, 20, 28)
        runOnMain {
            view.bind(row(spec))
            val spanned = view.text as Spanned
            val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
            val metrics = Paint.FontMetricsInt()
            draw(span, spanned, metrics)
            assertNotNull(animated)
            view.attachedForTest()
            assertTrue(animated.startCount > 0)
            assertTrue(view.verifyForTest(animated))
            animated.invalidateSelf()
            animated.scheduleSelf({}, 0L)
            view.detachedForTest()
            assertTrue(animated.stopCount > 0)
            assertEquals(null, animated.callback)
            val starts = animated.startCount
            view.attachedForTest()
            assertTrue(animated.startCount > starts)
        }
        scope.cancel()
    }

    @Test
    fun disabledAnimationStaysStoppedAcrossHideAndWindowLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var animated: RecordingAnimatedDrawable
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            ChatImageHandle { RecordingAnimatedDrawable().also { animated = it } }
        })
        val view = TestTextView(context, repository)
        val spec = ChatAssetSpec(ChatAssetKey("disabled-animation"), 20, 20, 28)
        runOnMain {
            view.setAnimateGifs(false)
            view.bind(row(spec))
            val spanned = view.text as Spanned
            val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
            draw(span, spanned, Paint.FontMetricsInt())
            view.attachedForTest()
            view.setRenderingActive(false)
            view.setRenderingActive(true)
            view.detachedForTest()
            view.attachedForTest()
            assertEquals(0, animated.startCount)
        }
        scope.cancel()
    }

    @Test
    fun adapterAppliesRuntimeAnimationSettingToReboundRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val adapter = ChatTimelineAdapter(repository, textSizeSp = 14f, animateGifs = true)
        val row = row(ChatAssetSpec(ChatAssetKey("plain"), 16, 16, 24))
        lateinit var holder: ChatTimelineAdapter.Holder
        val submitted = CountDownLatch(1)

        runOnMain {
            holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.submitList(listOf(row)) { submitted.countDown() }
        }
        assertTrue(submitted.await(1, TimeUnit.SECONDS))

        fun boundAnimationState(): Boolean {
            runOnMain { adapter.onBindViewHolder(holder, 0) }
            val field = ChatMessageTextView::class.java.getDeclaredField("animateGifs")
            field.isAccessible = true
            return field.getBoolean(holder.view)
        }

        assertTrue(boundAnimationState())
        runOnMain { adapter.setAnimateGifs(false) }
        assertTrue(!boundAnimationState())
        runOnMain { adapter.setAnimateGifs(true) }
        assertTrue(boundAnimationState())
        scope.cancel()
    }

    @Test
    fun emoteSpanInvokesOnlyEmoteCallbackAndLongPressInvokesMessageCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        val interaction = ChatEmoteInteraction(
            id = "emote-id",
            name = "Party",
            url = "https://cdn.example.test/party.webp",
            animated = false,
            provider = ChatAssetProvider.BTTV,
            scope = ChatEmoteScope.CHANNEL,
        )
        var messageClicks = 0
        var emoteClicks = 0
        var clickedInteraction: ChatEmoteInteraction? = null

        runOnMain {
            view.setInteractionCallbacks(
                onMessageLongClick = { messageClicks++ },
                onEmoteClick = {
                    emoteClicks++
                    clickedInteraction = it
                },
            )
            view.bind(row(ChatAssetSpec(ChatAssetKey("party"), 16, 16, 24), interaction = interaction))
            val spanned = view.text as Spanned
            val clickables = spanned.getSpans(0, spanned.length, ClickableSpan::class.java)
            assertEquals(1, clickables.size)
            clickables.single().onClick(view)
            assertEquals(1, emoteClicks)
            assertEquals(interaction, clickedInteraction)
            assertEquals(0, messageClicks)

            assertTrue(view.performLongClick())
            assertEquals(1, messageClicks)
            assertEquals(1, emoteClicks)
        }
        scope.cancel()
    }

    @Test
    fun realLongPressOnEmoteSuppressesSpanActionUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        val interaction = ChatEmoteInteraction(
            id = "gesture-emote",
            name = "Gesture",
            url = "https://cdn.example.test/gesture.webp",
            animated = false,
            provider = ChatAssetProvider.SEVEN_TV,
            scope = ChatEmoteScope.CHANNEL,
        )
        var messageClicks = 0
        var emoteClicks = 0
        runOnMain {
            view.layoutParams = ViewGroup.LayoutParams(200, 80)
            view.setInteractionCallbacks({ messageClicks++ }, { emoteClicks++ })
            view.bind(row(ChatAssetSpec(ChatAssetKey("gesture"), 16, 16, 24), interaction = interaction))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 200, 80)
            val now = android.os.SystemClock.uptimeMillis()
            view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 8f, 40f, 0))
        }
        Thread.sleep((ViewConfiguration.getLongPressTimeout() + 150).toLong())
        runOnMain {
            val now = android.os.SystemClock.uptimeMillis()
            view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 8f, 40f, 0))
        }
        assertEquals(1, messageClicks)
        assertEquals(0, emoteClicks)
        scope.cancel()
    }

    @Test
    fun ordinaryTextHasNoEmoteHitTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        var emoteClicks = 0

        runOnMain {
            view.setInteractionCallbacks(null, { emoteClicks++ })
            view.bind(textRow("ordinary text"))
            val spanned = view.text as Spanned
            assertEquals(0, spanned.getSpans(0, spanned.length, ClickableSpan::class.java).size)
            assertEquals(0, emoteClicks)
        }
        scope.cancel()
    }

    @Test
    fun ordinaryUrlsHaveClickableUrlSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)

        runOnMain {
            view.bind(textRow("Visit https://example.com/watch?v=42 or www.example.com."))
            val spanned = view.text as Spanned
            val urls = spanned.getSpans(0, spanned.length, URLSpan::class.java)
                .sortedBy(spanned::getSpanStart)
            assertEquals(2, urls.size)
            assertEquals("https://example.com/watch?v=42", urls[0].url)
            assertEquals("http://www.example.com", urls[1].url)
        }
        scope.cancel()
    }

    @Test
    fun gifSpanInvokesGifCallbackWithItsDescriptionAndUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        val interaction = ChatGifInteraction(
            id = "gif-id",
            description = "Joe Biden Vote GIF by Creative Courage",
            url = "https://media.example.test/joe.gif?token=a%26b&format=gif",
        )
        var clicked: ChatGifInteraction? = null

        runOnMain {
            view.setInteractionCallbacks(null, null) { clicked = it }
            view.bind(
                ChatRowUiModel(
                    id = ChatMessageId("gif-row"), channelId = "channel", timestampText = null,
                    pieces = listOf(
                        ChatPiece.Gif(
                            asset = ChatAssetSpec(ChatAssetKey("gif"), 16, 9, 180),
                            url = interaction.url,
                            fallback = interaction.description,
                            interaction = interaction,
                        ),
                    ),
                    background = 0xff101010.toInt(), accessibilityText = interaction.description,
                    reply = null, source = null, isAction = false,
                ),
            )
            val spanned = view.text as Spanned
            val clickable = spanned.getSpans(0, spanned.length, ClickableSpan::class.java).single()
            clickable.onClick(view)
        }

        assertEquals(interaction, clicked)
        scope.cancel()
    }

    @Test
    fun recycledHolderCallbacksUseTheNewlyBoundMessageId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val clickedIds = mutableListOf<ChatMessageId>()
        val adapter = ChatTimelineAdapter(
            repository,
            textSizeSp = 14f,
            animateGifs = true,
            onMessageLongClick = { clickedIds += it },
        )
        lateinit var holder: ChatTimelineAdapter.Holder
        runOnMain {
            holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.submitList(listOf(textRow("first", ChatMessageId("first"))))
            adapter.onBindViewHolder(holder, 0)
            assertTrue(holder.view.performLongClick())
        }
        val committed = CountDownLatch(1)
        runOnMain {
            adapter.submitList(listOf(textRow("second", ChatMessageId("second")))) { committed.countDown() }
        }
        assertTrue(committed.await(1, TimeUnit.SECONDS))
        runOnMain {
            adapter.onBindViewHolder(holder, 0)
            assertTrue(holder.view.performLongClick())
        }
        assertEquals(listOf(ChatMessageId("first"), ChatMessageId("second")), clickedIds)
        scope.cancel()
    }

    @Test
    fun largerOverlayStaysInsideReservedCompositionBox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        val spec = ChatAssetSpec(
            ChatAssetKey("base"), 16, 16, 24,
            overlays = listOf(ChatAssetSpec(ChatAssetKey("overlay"), 80, 20, 24)),
        )
        runOnMain {
            view.bind(row(spec))
            val spanned = view.text as Spanned
            val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
            val metrics = Paint.FontMetricsInt()
            assertEquals(spec.compositionWidth, span.getSize(Paint(), spanned, 0, 1, metrics))
            assertTrue(metrics.descent - metrics.ascent >= spec.compositionHeight)
            draw(span, spanned, metrics)
        }
        scope.cancel()
    }

    @Test
    fun compositeAssetUsesOneFallbackUntilEveryLayerIsReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spec = ChatAssetSpec(
            ChatAssetKey("base"), 16, 16, 24,
            overlays = listOf(
                ChatAssetSpec(ChatAssetKey("overlay-1"), 80, 20, 24),
                ChatAssetSpec(ChatAssetKey("overlay-2"), 24, 40, 24),
            ),
        )

        val cases = listOf(
            mapOf("base" to Color.RED),
            mapOf("overlay-1" to Color.BLUE, "overlay-2" to Color.GREEN),
            mapOf("base" to Color.RED, "overlay-1" to Color.BLUE),
            mapOf("base" to Color.RED, "overlay-1" to Color.BLUE, "overlay-2" to Color.GREEN),
        )
        cases.forEachIndexed { index, available ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = ChatAssetRepository(scope, ChatAssetLoader { key ->
                available[key.value]?.let { color -> ChatImageHandle { SolidDrawable(color) } }
            })
            val view = TestTextView(context, repository)
            runOnMain { view.bind(row(spec)) }
            awaitSettled(repository, spec.allKeysForTest())
            val bitmap = runOnMainResult {
                val spanned = view.text as Spanned
                val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
                draw(span, spanned, Paint.FontMetricsInt())
            }
            if (index < cases.lastIndex) {
                assertTrue(!containsColor(bitmap, Color.RED))
                assertTrue(!containsColor(bitmap, Color.BLUE))
            } else {
                assertTrue(containsColor(bitmap, Color.GREEN))
            }
            scope.cancel()
        }
    }

    @Test
    fun rebindRemovesObserversForThePreviousRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        val first = ChatAssetSpec(ChatAssetKey("first"), 16, 16, 24)
        val second = ChatAssetSpec(ChatAssetKey("second"), 16, 16, 24)
        runOnMain {
            view.bind(row(first))
            assertEquals(1, repository.observerCount(first.key))
            view.bind(row(second))
            assertEquals(0, repository.observerCount(first.key))
            assertEquals(1, repository.observerCount(second.key))
        }
        scope.cancel()
    }

    private fun row(
        spec: ChatAssetSpec,
        username: String? = null,
        interaction: ChatEmoteInteraction? = null,
        fallback: String = ":asset:",
    ) = ChatRowUiModel(
        id = ChatMessageId("row"), channelId = "channel", timestampText = null,
        pieces = buildList {
            username?.let { add(ChatPiece.Username(it, 0xffff8a80.toInt())) }
            add(ChatPiece.Emote(spec, fallback, interaction = interaction))
        },
        background = 0xff101010.toInt(), accessibilityText = "row", reply = null,
        source = null, isAction = false,
    )

    private fun badgeRow(spec: ChatAssetSpec, name: String) = ChatRowUiModel(
        id = ChatMessageId("badge-row"),
        channelId = "channel",
        timestampText = null,
        pieces = listOf(
            ChatPiece.Badge(
                spec,
                ChatEmoteInteraction(
                    id = "badge-id",
                    name = name,
                    url = spec.key.value,
                    animated = false,
                    provider = ChatAssetProvider.TWITCH,
                    scope = null,
                ),
            ),
        ),
        background = 0xff101010.toInt(),
        accessibilityText = name,
        reply = null,
        source = null,
        isAction = false,
    )

    private fun textRow(value: String, id: ChatMessageId = ChatMessageId("text")) = ChatRowUiModel(
        id = id, channelId = "channel", timestampText = null,
        pieces = listOf(ChatPiece.Text(value)),
        background = 0xff101010.toInt(), accessibilityText = value, reply = null,
        source = null, isAction = false,
    )

    private fun draw(span: ReplacementSpan, text: Spanned, metrics: Paint.FontMetricsInt): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        span.draw(canvas, text, 0, 1, 8f, 8, 64, 72, Paint())
        return bitmap
    }

    private fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) != Color.TRANSPARENT) return true
            }
        }
        return false
    }

    private fun awaitSettled(repository: ChatAssetRepository, keys: List<ChatAssetKey>) = runBlocking {
        withTimeout(2_000) {
            while (keys.any { repository.peek(it) is ChatAssetState.Missing || repository.peek(it) is ChatAssetState.Loading }) {
                delay(1)
            }
        }
    }

    private fun awaitPresentationTerminal(repository: ChatAssetRepository, keys: List<ChatAssetKey>) = runBlocking {
        withTimeout(2_000) {
            while (keys.any { key ->
                    when (val state = repository.peek(key)) {
                        ChatAssetState.Missing,
                        ChatAssetState.Loading -> true
                        is ChatAssetState.Ready -> false
                        is ChatAssetState.Failed -> !state.isPresentationTerminal
                    }
                }
            ) delay(1)
        }
    }

    private fun ChatAssetSpec.allKeysForTest(): List<ChatAssetKey> =
        listOf(key) + overlays.flatMap { it.allKeysForTest() }

    private fun runOnMainResult(block: () -> Bitmap): Bitmap {
        var result: Bitmap? = null
        runOnMain { result = block() }
        return checkNotNull(result)
    }

    private fun <T> runOnMainValue(block: () -> T): T {
        var result: T? = null
        runOnMain { result = block() }
        return checkNotNull(result)
    }

    private fun containsColor(bitmap: Bitmap, color: Int): Boolean {
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == color) return true
            }
        }
        return false
    }

    private fun runOnMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun awaitPreDraw(view: View) {
        val drawn = CountDownLatch(1)
        runOnMain {
            view.doOnPreDraw { drawn.countDown() }
            view.requestLayout()
            view.invalidate()
        }
        assertTrue(drawn.await(2, TimeUnit.SECONDS))
    }

    private fun attachView(
        repository: ChatAssetRepository,
        clipPreviews: ChatClipPreviewRepository? = null,
    ): AttachedTestView {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch<ChatMessageTextViewTestActivity>(
            Intent().setComponent(ComponentName(targetContext, ChatMessageTextViewTestActivity::class.java)),
        )
        lateinit var view: TestTextView
        scenario.onActivity { activity ->
            view = TestTextView(activity, repository, clipPreviews)
            activity.root.addView(view, FrameLayout.LayoutParams(600, 400))
        }
        return AttachedTestView(scenario, view)
    }

    private data class AttachedTestView(
        val scenario: ActivityScenario<ChatMessageTextViewTestActivity>,
        val view: TestTextView,
    ) {
        fun close() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.recycle()
                (view.context as Activity).finish()
            }
        }
    }

    private class TestTextView(
        context: Context,
        repository: ChatAssetRepository,
        clipPreviews: ChatClipPreviewRepository? = null,
    ) : ChatMessageTextView(context, repository, clipPreviews) {
        fun attachedForTest() = onAttachedToWindow()
        fun detachedForTest() = onDetachedFromWindow()
        fun verifyForTest(drawable: Drawable) = verifyDrawable(drawable)
    }

    private class RecordingAnimatedDrawable : Drawable(), Animatable {
        var startCount = 0
        var stopCount = 0

        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
        override fun isRunning(): Boolean = stopCount < startCount
    }

    private class SolidDrawable(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            paint.color = color
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
