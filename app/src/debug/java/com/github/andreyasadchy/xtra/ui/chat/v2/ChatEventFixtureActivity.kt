package com.github.andreyasadchy.xtra.ui.chat.v2

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscription
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView
import com.google.android.material.color.MaterialColors
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Debug-only deterministic fixture for reviewing the event family as one screen. */
class ChatEventFixtureActivity : Activity() {
    private val rows = ArrayList<ChatMessageTextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeName = intent.getStringExtra(EXTRA_THEME)?.lowercase()
        setTheme(themeFor(themeName))
        super.onCreate(savedInstanceState)

        val scale = intent.getFloatExtra(EXTRA_SCALE, 1f).coerceIn(0.75f, 1.75f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        root.setBackgroundColor(surface)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        if (themeName == "light") {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        val compiler = ChatRowCompiler(background = { surface })
        val catalog = ChatCatalogSnapshot(
            revision = 1,
            channelPointRewards = mapOf("hydrate" to ChatReward("Hydrate", 420)),
            automaticChannelPointRewards = mapOf(
                com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE to
                    ChatReward("Highlight My Message", 2_000),
            ),
        )
        fixtureMessages().forEach { message ->
            ChatMessageTextView(this, (application as XtraApp).xtraModule.chatAssetRepository).also { view ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
                view.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                view.bind(compiler.compile(message, catalog))
                root.addView(view)
                rows += view
            }
        }
    }

    override fun onDestroy() {
        rows.forEach(ChatMessageTextView::recycle)
        rows.clear()
        super.onDestroy()
    }

    private fun fixtureMessages(): List<ChatMessage> = listOf(
        message("normal-1", "ChatViewer", "Normal chat line"),
        message(
            id = "prime",
            userName = "PrimeViewer",
            text = "Happy to be here!",
            kind = ChatMessageKind.NOTICE,
            noticeType = "sub",
            subscriptionPlan = "Prime",
            isPrimeSubscription = true,
            subscription = ChatSubscription(tier = "Prime"),
            systemText = "PrimeViewer subscribed with Prime Gaming.",
        ),
        message(
            id = "paid",
            userName = "PaidViewer",
            text = "Thanks for the welcome",
            kind = ChatMessageKind.NOTICE,
            noticeType = "resub",
            subscriptionPlan = "1000",
            subscription = ChatSubscription(tier = "1000", months = 6, streakMonths = 4),
            systemText = "PaidViewer subscribed at Tier 1.",
        ),
        message(
            id = "gift",
            userName = "Gifter",
            text = "",
            kind = ChatMessageKind.NOTICE,
            noticeType = "sub_gift",
            subscription = ChatSubscription(tier = "1000", recipientName = "Recipient"),
        ),
        message(
            id = "community",
            userName = "CommunityGifter",
            text = "",
            kind = ChatMessageKind.NOTICE,
            noticeType = "community_sub_gift",
            subscription = ChatSubscription(tier = "1000", giftCount = 5, isCommunityGift = true),
        ),
        message(
            id = "reward",
            userName = "RewardViewer",
            text = "drink up",
            kind = ChatMessageKind.REWARD,
            rewardId = "hydrate",
        ),
        message(
            id = "highlight",
            userName = "HighlightViewer",
            text = "Lock them up!",
            kind = ChatMessageKind.NOTICE,
            twitchType = TwitchChatMessageType.Highlighted,
        ),
        message(
            id = "streak",
            userName = "StreakViewer",
            text = "I will keep watching",
            kind = ChatMessageKind.NOTICE,
            noticeType = "watch_streak",
            watchStreakCount = 7,
            watchStreakPoints = 700,
        ),
        message(
            id = "first",
            userName = "NewViewer",
            text = "Hello chat",
            isFirst = true,
        ),
        message("normal-2", "ChatViewer", "Another normal chat line"),
    )

    private fun message(
        id: String,
        userName: String,
        text: String,
        kind: ChatMessageKind = ChatMessageKind.CHAT,
        noticeType: String? = null,
        subscriptionPlan: String? = null,
        isPrimeSubscription: Boolean? = null,
        subscription: ChatSubscription? = null,
        systemText: String? = null,
        rewardId: String? = null,
        isFirst: Boolean = false,
        twitchType: TwitchChatMessageType = TwitchChatMessageType.Text,
        watchStreakCount: Int? = null,
        watchStreakPoints: Int? = null,
    ) = ChatMessage(
        id = ChatMessageId(id),
        channelId = "fixture",
        timestampMs = 0,
        user = ChatUser(id, userName.lowercase(), userName, Color.rgb(0x91, 0x47, 0xFF)),
        badges = emptyList(),
        segments = text.takeIf(String::isNotEmpty)?.let { listOf(ChatSegment.Text(it)) }.orEmpty(),
        kind = kind,
        noticeType = noticeType,
        subscriptionPlan = subscriptionPlan,
        isPrimeSubscription = isPrimeSubscription,
        subscription = subscription,
        systemText = systemText,
        rewardId = rewardId,
        isFirst = isFirst,
        twitchType = twitchType,
        watchStreakCount = watchStreakCount,
        watchStreakPoints = watchStreakPoints,
    )

    private fun themeFor(name: String?): Int = when (name?.lowercase()) {
        "light" -> R.style.LightTheme
        "amoled" -> R.style.AmoledTheme
        "modern" -> R.style.ModernTheme
        "modern_amoled" -> R.style.ModernAmoledTheme
        "blue" -> R.style.BlueTheme
        else -> R.style.DarkTheme
    }

    companion object {
        const val EXTRA_THEME = "event_theme"
        const val EXTRA_SCALE = "event_scale"
    }
}
