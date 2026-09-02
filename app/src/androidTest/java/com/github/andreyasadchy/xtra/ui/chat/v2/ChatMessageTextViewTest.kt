package com.github.andreyasadchy.xtra.ui.chat.v2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.view.View
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoader
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatImageHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatPiece
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowUiModel
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView
import com.github.andreyasadchy.xtra.ui.chat.resolveChatSizing
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
    fun usernameHasExplicitFallbackColorAndMissingAssetHasStableGeometry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ChatAssetRepository(scope, ChatAssetLoader { null })
        val view = TestTextView(context, repository)
        val spec = ChatAssetSpec(ChatAssetKey("missing"), 20, 20, 28)
        runOnMain {
            view.bind(row(spec, username = "login"))
            val spanned = view.text as Spanned
            val usernameColor = spanned.getSpans(0, 5, ForegroundColorSpan::class.java).single().foregroundColor
            assertNotEquals(Color.WHITE, usernameColor)
            assertTrue(view.text.toString().contains("login"))
            val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
            val metrics = Paint.FontMetricsInt()
            assertEquals(spec.compositionWidth, span.getSize(Paint(), spanned, 0, 1, metrics))
            assertTrue(metrics.descent - metrics.ascent >= spec.compositionHeight)
            draw(span, spanned, metrics)
        }
        scope.cancel()
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
    fun animationPreferenceCanDisableAnAlreadyBoundRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var animated: RecordingAnimatedDrawable
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            ChatImageHandle { RecordingAnimatedDrawable().also { animated = it } }
        })
        val view = TestTextView(context, repository)
        val spec = ChatAssetSpec(ChatAssetKey("disabled-animation"), 20, 20, 28)
        runOnMain {
            view.bind(row(spec))
            val spanned = view.text as Spanned
            val span = spanned.getSpans(0, spanned.length, ReplacementSpan::class.java).single()
            draw(span, spanned, Paint.FontMetricsInt())
            view.attachedForTest()
            assertTrue(animated.startCount > 0)
            val startsBeforeDisable = animated.startCount
            view.setAnimateGifs(false)
            assertTrue(animated.stopCount > 0)
            view.setRenderingActive(false)
            view.setRenderingActive(true)
            view.detachedForTest()
            view.attachedForTest()
            assertEquals(startsBeforeDisable, animated.startCount)
        }
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

    private fun row(spec: ChatAssetSpec, username: String? = null) = ChatRowUiModel(
        id = ChatMessageId("row"), channelId = "channel", timestampText = null,
        pieces = buildList {
            username?.let { add(ChatPiece.Username(it, 0xffff8a80.toInt())) }
            add(ChatPiece.Emote(spec, ":asset:"))
        },
        background = 0xff101010.toInt(), accessibilityText = "row", reply = null,
        source = null, isAction = false,
    )

    private fun draw(span: ReplacementSpan, text: Spanned, metrics: Paint.FontMetricsInt): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        span.draw(canvas, text, 0, 1, 8f, 8, 64, 72, Paint())
        return bitmap
    }

    private fun awaitSettled(repository: ChatAssetRepository, keys: List<ChatAssetKey>) = runBlocking {
        withTimeout(2_000) {
            while (keys.any { repository.peek(it) is ChatAssetState.Missing || repository.peek(it) is ChatAssetState.Loading }) {
                delay(1)
            }
        }
    }

    private fun ChatAssetSpec.allKeysForTest(): List<ChatAssetKey> =
        listOf(key) + overlays.flatMap { it.allKeysForTest() }

    private fun runOnMainResult(block: () -> Bitmap): Bitmap {
        var result: Bitmap? = null
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

    private class TestTextView(context: Context, repository: ChatAssetRepository) : ChatMessageTextView(context, repository) {
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
