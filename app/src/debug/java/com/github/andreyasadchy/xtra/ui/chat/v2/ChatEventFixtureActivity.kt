package com.github.andreyasadchy.xtra.ui.chat.v2

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ViewPinnedChatMessageBinding
import com.github.andreyasadchy.xtra.ui.chat.HappeningNowGift
import com.github.andreyasadchy.xtra.ui.chat.HappeningNowView
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGiftSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscription
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowCompiler
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.resolveChatEventPalette
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatEventParser
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatTransport
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatTransportConfig
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatMessageTextView
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatEventProcessor
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatTimelineStore
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ModerationNoticeCoalescer
import com.google.android.material.color.MaterialColors
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Debug-only deterministic fixture for reviewing the event family as one screen. */
class ChatEventFixtureActivity : AppCompatActivity() {
    private val rows = ArrayList<ChatMessageTextView>()
    private val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var contentRoot: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeName = intent.getStringExtra(EXTRA_THEME)?.lowercase()
        setTheme(themeFor(themeName))
        super.onCreate(savedInstanceState)

        val scale = intent.getFloatExtra(EXTRA_SCALE, 1f).coerceIn(0.75f, 1.75f)
        val moderationFixture = intent.getBooleanExtra(EXTRA_MODERATION_FIXTURE, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentRoot = root
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

        val catalog = ChatCatalogSnapshot(
            revision = 1,
            channelPointRewards = mapOf("hydrate" to ChatReward("Hydrate", 420)),
            automaticChannelPointRewards = mapOf(
                com.github.andreyasadchy.xtra.ui.chat.v2.domain.HIGHLIGHTED_MESSAGE_REWARD_TYPE to
                    ChatReward("Highlight My Message", 2_000),
            ),
        )
        val happeningNow = HappeningNowView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            render(
                state = fixtureHappeningNowState(),
                onOpenChannelPoints = {},
                onOpenHistoricalPrediction = {},
                onOpenGiftProfile = {},
                onDismiss = {},
                isPredictionTracked = { false },
                onTogglePredictionTracking = {},
            )
        }
        val pinnedMessage = ViewPinnedChatMessageBinding.inflate(layoutInflater, root, false).apply {
            pinnedMessageOverlay.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            pinnedMessageOverlay.isVisible = true
            pinnedMessageBy.text = "PinnedViewer"
            pinnedMessageText.text = "Community game night starts in ten minutes!"
            pinnedMessageSender.text = "Broadcaster"
            pinnedMessageSentAt.text = "sent at 07:41 PM"
            pinnedMessageSentAt.isVisible = true
            pinnedMessageSeen.setImageResource(android.R.drawable.ic_menu_view)
            pinnedMessageSeen.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    pinnedMessageSeen,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
            pinnedMessageProgress.progress = 620
            pinnedMessageProgress.isVisible = true
        }
        if (!moderationFixture) {
            root.addView(pinnedMessage.root)
            root.addView(happeningNow)
        }

        if (moderationFixture) {
            lifecycleScope.launch { renderRows(moderationActionFixtureMessages(), ChatCatalogSnapshot(revision = 1)) }
        } else {
            renderRows(fixtureMessages(), catalog)
        }
    }

    private fun renderRows(messages: List<ChatMessage>, catalog: ChatCatalogSnapshot) {
        val root = contentRoot
        val scale = intent.getFloatExtra(EXTRA_SCALE, 1f).coerceIn(0.75f, 1.75f)
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        val compiler = ChatRowCompiler(
            background = { surface },
            eventPalette = { kind, baseColor ->
                resolveChatEventPalette(kind, baseColor) { attribute ->
                    MaterialColors.getColor(root, attribute)
                }
            },
        )
        val catalog = ChatCatalogSnapshot(revision = 1)
        messages.forEach { message ->
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
        fixtureScope.cancel()
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
        communityGiftFixtureMessage(),
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
        message(
            id = "announcement",
            userName = "Broadcaster",
            text = "Community game night starts in ten minutes.",
            kind = ChatMessageKind.ANNOUNCEMENT,
            systemText = "Community announcement",
        ),
        message(
            id = "raid",
            userName = "RaidLeader",
            text = "Welcome raiders!",
            kind = ChatMessageKind.RAID,
            systemText = "Raid incoming",
        ),
        message(
            id = "notice",
            userName = "System",
            text = "Chat is in slow mode.",
            kind = ChatMessageKind.NOTICE,
            systemText = "Channel notice",
        ),
        message("normal-2", "ChatViewer", "Another normal chat line"),
    )

    private fun communityGiftFixtureMessage(): ChatMessage {
        val event = TwitchChatEventParser.fromIrc(
            ChatUtils.parseIRCMessage(
                "@display-name=CommunityGifter;id=community-gift-irc;login=communitygifter;msg-id=submysterygift;msg-param-mass-gift-count=5;msg-param-sub-plan=1000;user-id=fixture-gifter :communitygifter!communitygifter@communitygifter.tmi.twitch.tv USERNOTICE #fixture",
            ),
            "fixture-channel",
        ) as ChatEvent.Message
        return event.message
    }

    /** Debug-only Twitch-format fixture; it exercises the production EventSub parser and row factory. */
    private suspend fun moderationActionFixtureMessages(): List<ChatMessage> {
        val transport = TwitchChatTransport(
            config = TwitchChatTransportConfig(
                channelId = "fixture-channel",
                channelLogin = "amy19b",
                useEventSub = true,
                enableModerationActionNotices = true,
                showClearChat = true,
                moderatorBanMessage = { moderator, target ->
                    getString(R.string.chat_mod_action_ban).format(moderator, target)
                },
                moderatorTimeoutMessage = { moderator, target, _ ->
                    getString(R.string.chat_mod_action_timeout).format(moderator, target, "1 minute")
                },
                moderatorUnbanMessage = { moderator, target ->
                    getString(R.string.chat_mod_action_unban).format(moderator, target)
                },
                moderatorActionReason = { reason ->
                    getString(R.string.chat_mod_action_reason).format(reason)
                },
            ),
            trustManager = (application as XtraApp).xtraModule.trustManager,
        )
        val session = ChatSessionKey("fixture-channel", generation = 1L)
        val now = "2026-09-23T10:00:00Z"
        val timeout = requireNotNull(TwitchChatEventParser.fromEventSubBan(
            JSONObject(
                """
                {
                  "user_id":"viewer-17",
                  "user_login":"cool_user",
                  "user_name":"Cool_User",
                  "moderator_user_id":"mod-4",
                  "moderator_user_login":"mod_user",
                  "moderator_user_name":"Mod_User",
                  "reason":"Offensive language",
                  "banned_at":"2026-09-23T10:00:00Z",
                  "ends_at":"2026-09-23T10:01:00Z",
                  "is_permanent":false
                }
                """.trimIndent(),
            ),
            timestamp = now,
            notificationId = "fixture-timeout-1",
        ))
        val ban = requireNotNull(TwitchChatEventParser.fromEventSubBan(
            JSONObject(
                """
                {
                  "user_id":"viewer-21",
                  "user_login":"repeat_user",
                  "user_name":"Repeat_User",
                  "moderator_user_id":"mod-4",
                  "moderator_user_name":"Mod_User",
                  "reason":"Repeated spam",
                  "banned_at":"2026-09-23T10:01:00Z",
                  "ends_at":null,
                  "is_permanent":true
                }
                """.trimIndent(),
            ),
            timestamp = "2026-09-23T10:01:00Z",
            notificationId = "fixture-ban-1",
        ))
        val unban = requireNotNull(TwitchChatEventParser.fromEventSubUnban(
            JSONObject(
                """
                {
                  "user_id":"viewer-33",
                  "user_login":"returning_user",
                  "user_name":"Returning_User",
                  "moderator_user_id":"mod-4",
                  "moderator_user_name":"Mod_User"
                }
                """.trimIndent(),
            ),
            timestamp = "2026-09-23T10:02:00Z",
            notificationId = "fixture-unban-1",
        ))

        val timeline = ChatTimelineStore(fixtureScope)
        val processor = ChatEventProcessor(fixtureScope, timeline)
        processor.activate(session)
        val clearAt = timeout.occurredAtMs
        val chatter = ChatMessage(
            id = ChatMessageId("fixture-cleared-message"),
            channelId = session.channelId,
            timestampMs = clearAt - 100L,
            user = ChatUser(id = timeout.targetId, login = timeout.targetLogin, displayName = timeout.target, color = null),
            badges = emptyList(),
            segments = listOf(ChatSegment.Text("Spam message removed by the timeout")),
            rawText = "Spam message removed by the timeout",
            kind = ChatMessageKind.CHAT,
        )
        processor.submit(session, ChatEvent.Message(chatter))
        awaitTimeline(timeline) { messages -> messages.any { it.id == chatter.id } }

        val clearEvent = ChatEvent.ClearUser(
            userId = timeout.targetId,
            userLogin = timeout.targetLogin,
            userName = timeout.target,
            eventId = "fixture-clear-1",
            receivedAtMs = clearAt,
            timeoutSeconds = 60,
            displayMode = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModerationDisplayMode.HIDE,
        )
        val noticeCoalescer = ModerationNoticeCoalescer(fixtureScope)
        noticeCoalescer.clear(
            event = clearEvent,
            emitClear = { processor.submit(session, clearEvent) },
            emitFallback = {
                transport.moderationSystemMessage(session, clearEvent)?.let { processor.submit(session, it) }
            },
        )
        awaitTimeline(timeline) { messages -> messages.none { it.id == chatter.id } }

        val timeoutMessage = requireNotNull(transport.moderatorActionSystemMessage(session, timeout))
        noticeCoalescer.action(timeout) {
            processor.submit(session, timeoutMessage)
        }
        requireNotNull(transport.moderatorActionSystemMessage(session, ban)).let { processor.submit(session, it) }
        requireNotNull(transport.moderatorActionSystemMessage(session, unban)).let { processor.submit(session, it) }
        delay(1_100L)
        return awaitTimeline(timeline) { messages -> messages.size >= 3 }
    }

    private suspend fun awaitTimeline(
        timeline: ChatTimelineStore,
        predicate: (List<ChatMessage>) -> Boolean,
    ): List<ChatMessage> {
        repeat(100) {
            val messages = timeline.snapshot()
            if (predicate(messages)) return messages
            delay(10L)
        }
        error("Moderator action fixture did not reach its expected timeline state")
    }

    private fun fixtureHappeningNowState() = HappeningNowView.RenderState(
        gift = HappeningNowGift(
            stableId = "fixture-gift",
            occurredAt = 0L,
            gifterDisplayName = "GiftViewer",
            gifterUserId = "gift-viewer",
            gifterLogin = "giftviewer",
            isAnonymous = false,
            count = 5,
            source = ChatGiftSource.EVENTSUB,
        ),
        activePrediction = Prediction(
            id = "fixture-prediction-active",
            createdAt = 0L,
            startedAt = 0L,
            outcomes = listOf(
                Prediction.PredictionOutcome("yes", "Yes", 1_240, 18, "BLUE"),
                Prediction.PredictionOutcome("no", "No", 860, 12, "PINK"),
            ),
            predictionWindowSeconds = 120,
            status = "ACTIVE",
            title = "Will the next play be a win?",
            winningOutcomeId = null,
        ),
        recentPredictionResult = Prediction(
            id = "fixture-prediction-result",
            createdAt = 0L,
            outcomes = listOf(
                Prediction.PredictionOutcome("yes", "Yes", 2_800, 30, "BLUE"),
                Prediction.PredictionOutcome("no", "No", 1_100, 14, "PINK"),
            ),
            predictionWindowSeconds = 120,
            status = "RESOLVED",
            title = "Did chat call the clutch play?",
            winningOutcomeId = "yes",
        ),
        activePoll = Poll(
            id = "fixture-poll",
            title = "Which emote should chat use next?",
            status = "ACTIVE",
            choices = listOf(
                Poll.PollChoice("pog", "Pog", 42),
                Poll.PollChoice("hype", "Hype", 27),
                Poll.PollChoice("gg", "GG", 18),
            ),
            totalVotes = 87,
            remainingMilliseconds = 90_000L,
        ),
        canBetPrediction = true,
        canVotePoll = true,
        newIds = setOf(
            "gift:fixture-gift",
            "prediction:fixture-prediction-active",
            "prediction-result:fixture-prediction-result",
            "poll:fixture-poll",
        ),
        dismissedIds = emptySet(),
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        const val EXTRA_MODERATION_FIXTURE = "moderation_fixture"
    }
}
