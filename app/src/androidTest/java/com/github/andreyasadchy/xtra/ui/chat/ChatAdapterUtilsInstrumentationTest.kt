package com.github.andreyasadchy.xtra.ui.chat

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.core.text.getSpans
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.ImageKind
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import com.github.andreyasadchy.xtra.util.chat.chatMessageBackgroundResource
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatAdapterUtilsInstrumentationTest {

    @Test
    fun directHolderAttachmentStartsAnimatedDrawableAfterBindBeforeAttach() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val adapter = ChatAdapter(
            initialMessages = emptyList(),
            localTwitchEmotes = emptyList(),
            thirdPartyEmotes = emptyList(),
            globalBadges = emptyList(),
            channelBadges = emptyList(),
            cheerEmotes = emptyList(),
            namePaints = emptyList(),
            stvBadges = emptyList(),
            personalEmoteSets = emptyMap(),
            stvUsers = emptyList(),
            enableTimestamps = true,
            timestampFormat = "0",
            firstMsgVisibility = 0,
            firstChatMsg = "First",
            redeemedChatMsg = "Redeemed",
            redeemedNoMsg = "Redeemed",
            replyMessage = "Reply",
            useRandomColors = false,
            useReadableColors = false,
            isLightTheme = false,
            nameDisplay = "1",
            useBoldNames = false,
            showNamePaints = true,
            showBadges = true,
            showSTVBadges = true,
            showPersonalEmotes = true,
            showSystemMessageEmotes = true,
            chatUrl = null,
            fragment = androidx.fragment.app.Fragment(),
            backgroundColor = Color.BLACK,
            dialogBackgroundColor = Color.BLACK,
            imageLibrary = "0",
            messageTextSize = 14f,
            emoteSize = 24,
            badgeSize = 18,
            inlineIconSize = 16,
            emoteQuality = "4",
            animateGifs = true,
            enableOverlayEmotes = true,
            translateMessage = { _, _ -> },
            showLanguageDownloadDialog = { _, _ -> },
            channelId = null,
            loggedInUser = null,
            messageClickListener = null,
            replyClickListener = null,
            imageClickListener = null,
        )
        val drawable = RecordingAnimatableDrawable()
        val textView = TextView(context)
        textView.text = SpannableStringBuilder("\uFFFC").apply {
            setSpan(CenteredImageSpan(drawable, 24, 24), 0, 1, 0)
        }
        assertEquals(true, textView.text is android.text.Spanned)
        assertEquals(1, (textView.text as android.text.Spanned).getSpans(0, 1, CenteredImageSpan::class.java).size)
        val holder = adapter.ViewHolder(textView)

        adapter.attachDirectViewHolder(holder)
        assertEquals(true, drawable.started)
        adapter.detachDirectViewHolder(holder)
        assertEquals(false, drawable.started)
    }

    @Test
    fun directPreparedMessagesKeepTheirReadyKeysAcrossBindAndRecycle() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(
                instrumentation.targetContext,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val fragment = androidx.fragment.app.Fragment()
        lateinit var adapter: ChatAdapter
        try {
            instrumentation.runOnMainSync {
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment, "chat-render-test")
                    .commitNow()
                adapter = createDirectTestAdapter(fragment)
            }
            val messages = listOf(
                ChatMessage(type = ChatMessage.USER_MESSAGE, userName = "one", message = "first", timestamp = 1L),
                ChatMessage(type = ChatMessage.USER_MESSAGE, userName = "two", message = "second", timestamp = 2L),
                ChatMessage(type = ChatMessage.USER_MESSAGE, userName = "three", message = "third", timestamp = 3L),
            )
            runBlocking {
                for (message in messages) adapter.prepareDirectMessage(message)
            }
            val holders = messages.map { adapter.ViewHolder(TextView(activity)) }
            instrumentation.runOnMainSync {
                messages.forEachIndexed { index, message ->
                    adapter.setDirectMessage(message)
                    adapter.onBindViewHolder(holders[index], 0)
                    assertEquals(message.message, holders[index].textView.text.toString().substringAfter(": "))
                }
                adapter.onViewRecycled(holders[0])
                adapter.setDirectMessage(messages[0])
                adapter.onBindViewHolder(holders[0], 0)
            }
            assertEquals("first", holders[0].textView.text.toString().substringAfter(": "))
        } finally {
            instrumentation.runOnMainSync {
                if (fragment.isAdded) {
                    activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
                }
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    @Test
    fun readyImageInstallationCreatesOneFinalSpanPerRange() {
        val builder = SpannableStringBuilder("\uFFFC")
        val image = Image(url1x = "https://example.test/emote.png", kind = ImageKind.EMOTE, start = 0, end = 1)
        val finalDrawable = ColorDrawable(Color.RED)

        ChatAdapterUtils.installResolvedImages(builder, listOf(image), listOf(finalDrawable), emoteSize = 24)

        val spans = builder.getSpans<CenteredImageSpan>(0, 1)
        assertEquals(1, spans.size)
        assertSame(finalDrawable, spans.single().drawable)
    }

    @Test
    fun failedImageAndNamePaintStillHaveOneFinalSpan() {
        val builder = SpannableStringBuilder("user \uFFFC")
        val image = Image(url1x = "https://example.test/emote.png", kind = ImageKind.EMOTE, start = 5, end = 6)
        val paint = NamePaint(imageUrl = "https://example.test/paint.png")
        val finalPaintDrawable = ColorDrawable(Color.BLUE)

        ChatAdapterUtils.installResolvedImages(builder, listOf(image), listOf(null), paint, finalPaintDrawable, "user", 0)

        assertEquals(1, builder.getSpans<CenteredImageSpan>(5, 6).size)
        val paintSpans = builder.getSpans<NamePaintImageSpan>(0, 4)
        assertEquals(1, paintSpans.size)
        assertSame(finalPaintDrawable, paintSpans.single().drawable)
    }

    @Test
    fun failedNamePaintFallsBackToNormalUsername() {
        val builder = SpannableStringBuilder("user")
        builder.setSpan(ForegroundColorSpan(Color.GREEN), 0, 4, 0)
        val paint = NamePaint(imageUrl = "https://example.test/paint.png")

        ChatAdapterUtils.installResolvedImages(
            builder = builder,
            images = emptyList(),
            drawables = emptyList(),
            imagePaint = paint,
            imagePaintDrawable = null,
            userName = "user",
            userNameStartIndex = 0,
        )

        assertEquals(0, builder.getSpans<NamePaintImageSpan>(0, 4).size)
        assertEquals("user", builder.toString())
        assertEquals(1, builder.getSpans<ForegroundColorSpan>(0, 4).size)
    }

    @Test
    fun namePaintBackgroundIsAppliedAfterRowBackground() {
        val builder = SpannableStringBuilder("user")
        val paint = NamePaint(imageUrl = "https://example.test/paint.png")
        val drawable = ColorDrawable(Color.BLUE)
        ChatAdapterUtils.installResolvedImages(
            builder = builder,
            images = emptyList(),
            drawables = emptyList(),
            imagePaint = paint,
            imagePaintDrawable = drawable,
            userName = "user",
            userNameStartIndex = 0,
        )

        applyNamePaintBackground(builder, ColorDrawable(Color.RED))

        assertEquals(Color.RED, builder.getSpans<NamePaintImageSpan>(0, 4).single().backgroundColor)
    }

    @Test
    fun imageCompositionIdentityIncludesEveryOverlay() {
        fun image(url: String, overlay: Image? = null) = Image(
            url1x = "https://example.test/$url.png",
            kind = ImageKind.EMOTE,
            sourceWidth = 28,
            sourceHeight = 28,
            overlayEmote = overlay,
            start = 0,
            end = 1,
        )

        val baseA = image("base", image("overlay-a"))
        val baseB = image("base", image("overlay-b"))
        val chainedA = image("base", image("overlay-a", image("overlay-2")))
        val chainedB = image("base", image("overlay-a", image("overlay-3")))

        val key = { value: Image ->
            ChatAdapterUtils.imageCompositionKey(value, "4", 24, 18, 16)
        }

        assertEquals(false, key(baseA) == key(baseB))
        assertEquals(false, key(baseA) == key(image("base")))
        assertEquals(false, key(chainedA) == key(chainedB))
    }

    @Test
    fun terminalFailureRenderPreservesRowClassification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val colors = HashMap<String, Int>()
        fun render(message: ChatMessage, firstVisibility: Int = 0) =
            ChatAdapterUtils.prepareTerminalFailureRender(
                chatMessage = message,
                context = context,
                enableTimestamps = true,
                timestampFormat = "0",
                firstMsgVisibility = firstVisibility,
                nameDisplay = "1",
                useReadableColors = false,
                isLightTheme = false,
                savedColors = colors,
            )

        assertEquals(R.drawable.bg_chat_highlight, render(ChatMessage(userName = "u", message = "x", reward = com.github.andreyasadchy.xtra.model.chat.ChannelPointReward(title = "Send Highlighted Message"))).backgroundResource)
        assertEquals(R.drawable.bg_chat_watch_streak, render(ChatMessage(msgId = "watch_streak", watchStreakCount = 2)).backgroundResource)
        assertEquals(R.color.chatMessageNotice, render(ChatMessage(msgId = "notice", systemMsg = "notice")).backgroundResource)
        assertEquals(R.drawable.bg_chat_first_chatter, render(ChatMessage(userName = "u", message = "x", isFirst = true)).backgroundResource)
        assertEquals(R.color.chatMessageFirst, render(ChatMessage(userName = "u", message = "x", isFirst = true), firstVisibility = 1).backgroundResource)
        assertEquals(0, render(ChatMessage(userName = "u", message = "x", isFirst = true), firstVisibility = 2).backgroundResource)

        val firstWithNotice = ChatMessage(userName = "u", message = "x", isFirst = true, msgId = "notice")
        assertEquals(
            chatMessageBackgroundResource(firstWithNotice, 0),
            render(firstWithNotice).backgroundResource,
        )
        val rewardWithNotice = ChatMessage(
            userName = "u",
            message = "x",
            msgId = "notice",
            reward = com.github.andreyasadchy.xtra.model.chat.ChannelPointReward(title = "Reward", id = "reward"),
        )
        assertEquals(
            chatMessageBackgroundResource(rewardWithNotice, 0),
            render(rewardWithNotice).backgroundResource,
        )
    }

    private fun createDirectTestAdapter(fragment: androidx.fragment.app.Fragment) = ChatAdapter(
        initialMessages = emptyList(),
        localTwitchEmotes = emptyList(),
        thirdPartyEmotes = emptyList(),
        globalBadges = emptyList(),
        channelBadges = emptyList(),
        cheerEmotes = emptyList(),
        namePaints = emptyList(),
        stvBadges = emptyList(),
        personalEmoteSets = emptyMap(),
        stvUsers = emptyList(),
        enableTimestamps = true,
        timestampFormat = "0",
        firstMsgVisibility = 0,
        firstChatMsg = "First",
        redeemedChatMsg = "Redeemed",
        redeemedNoMsg = "Redeemed",
        replyMessage = "Reply",
        useRandomColors = false,
        useReadableColors = false,
        isLightTheme = false,
        nameDisplay = "1",
        useBoldNames = false,
        showNamePaints = true,
        showBadges = true,
        showSTVBadges = true,
        showPersonalEmotes = true,
        showSystemMessageEmotes = true,
        chatUrl = null,
        fragment = fragment,
        backgroundColor = Color.BLACK,
        dialogBackgroundColor = Color.BLACK,
        imageLibrary = "0",
        messageTextSize = 14f,
        emoteSize = 24,
        badgeSize = 18,
        inlineIconSize = 16,
        emoteQuality = "4",
        animateGifs = true,
        enableOverlayEmotes = true,
        translateMessage = { _, _ -> },
        showLanguageDownloadDialog = { _, _ -> },
        channelId = null,
        loggedInUser = null,
        messageClickListener = null,
        replyClickListener = null,
        imageClickListener = null,
    )
}

private class RecordingAnimatableDrawable : Drawable(), android.graphics.drawable.Animatable {
    var started = false

    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun start() { started = true }
    override fun stop() { started = false }
    override fun isRunning(): Boolean = started
}
