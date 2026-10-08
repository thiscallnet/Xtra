package com.github.andreyasadchy.xtra.ui.chat.v2

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogLoadResult
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogProviderUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ActiveChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatEventParser
import com.github.andreyasadchy.xtra.ui.chat.v2.ui.ChatV2RendererController
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.chat.PubSubUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/** Local-only reward ingress and lifecycle fixture. No sockets or account actions are used. */
class ChatRewardResumeFixtureActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val key = ChatSessionKey("fixture-channel", 1)
    private lateinit var session: ChatSession
    private lateinit var catalog: ChatCatalogRepository
    private lateinit var renderer: ChatV2RendererController
    private lateinit var status: TextView
    private var queued = false
    private var duplicatePublication = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            setPadding(16, 96, 16, 32)
        }
        status = TextView(this).apply { text = "Local reward resume fixture. Ready." }
        root.addView(status)
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "Queue + background"
            setOnClickListener { queue("background") }
        })
        controls.addView(Button(this).apply {
            text = "Queue + PiP"
            setOnClickListener { queue("pip") }
        })
        root.addView(controls)
        val chat = RecyclerView(this)
        root.addView(chat, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        session = ChatSession(scope, object : ChatTransport {
            override fun events(session: ChatSessionKey) = emptyFlow<ChatEvent>()
        }, maxTimelineSize = if (intent.getBooleanExtra("stress", false)) 2 else 600)
        catalog = ChatCatalogRepository(scope, ChatCatalogSource {
            ChatCatalogLoadResult(
                twitch = ChatCatalogProviderUpdate(emptyMap()),
                sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                bttv = ChatCatalogProviderUpdate(emptyMap()),
                ffz = ChatCatalogProviderUpdate(emptyMap()),
                badges = ChatCatalogProviderUpdate(emptyMap()),
                cheermotes = ChatCatalogProviderUpdate(emptyMap()),
            )
        })
        val active = ActiveChatSession(
            LiveChatSessionSpec(key.channelId, "fixture"), key, session, catalog,
        )
        renderer = ChatV2RendererController(
            recyclerView = chat,
            activeSessions = flowOf(active),
            assets = (application as XtraApp).xtraModule.chatAssetRepository,
            expectedChannelId = key.channelId,
            expectedChannelLogin = "fixture",
            onPublicationChanged = { messages, _, _, _ ->
                val distinct = messages.map { it.id }.toSet().size
                duplicatePublication = duplicatePublication || distinct != messages.size
                val colors = messages.map { it.user?.color }
                status.text = "${messages.size} rows, $distinct unique message IDs. ${if (!duplicatePublication && messages.size == 2 && colors.all { it == 0xFFE8712A.toInt() }) "PASS" else "Waiting / duplicate"}"
                Log.i("RewardResumeFixture", "rows=${messages.size} unique=$distinct colors=$colors")
            },
        )
        renderer.attach(this)
        catalog.refresh()
        scope.launch {
            session.start(key)
            delay(1_500)
            if (intent.getBooleanExtra("stress", false)) stressCatchUp()
            else intent.getStringExtra("auto_mode")?.let(::queue)
        }
    }

    private fun queue(mode: String) {
        if (queued) return
        queued = true
        status.text = "Two local redeems will arrive while chat is hidden."
        renderer.setVisible(false)
        if (mode == "pip") {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } else if (mode == "background") {
            moveTaskToBack(true)
        }
        scope.launch {
            delay(3_000)
            val occurredAt = 1_791_456_000_000L
            repeat(2) { index ->
                val at = occurredAt + index * 1_000
                val input = if (intent.getBooleanExtra("same_text", false)) "Same viewer input" else "Viewer input ${index + 1}"
                val chat = TwitchChatEventParser.fromIrc(
                    ChatUtils.parseIRCMessage("@id=chat-$index;user-id=fixture-viewer;display-name=FixtureViewer;color=#E8712A;custom-reward-id=tts;tmi-sent-ts=$at :fixtureviewer!fixtureviewer@fixtureviewer.tmi.twitch.tv PRIVMSG #fixture :$input"),
                    key.channelId,
                )!!
                val notice = if (intent.getBooleanExtra("eventsub", false)) {
                    TwitchChatEventParser.fromEventSubRewardRedemption(
                        JSONObject().put("id", "redemption-$index").put("broadcaster_user_id", key.channelId)
                            .put("user_id", "fixture-viewer").put("user_login", "fixtureviewer").put("user_name", "FixtureViewer")
                            .put("user_input", input).put("redeemed_at", Instant.ofEpochMilli(at).toString())
                            .put("reward", reward()),
                        Instant.ofEpochMilli(at + 90_000).toString(),
                    )
                } else {
                    TwitchChatEventParser.fromPubSubReward(
                        PubSubUtils.parseRewardMessage(JSONObject().put("data", JSONObject()
                            .put("timestamp", Instant.ofEpochMilli(at + 90_000).toString())
                            .put("redemption", JSONObject().put("id", "redemption-$index")
                                .put("redeemed_at", Instant.ofEpochMilli(at).toString()).put("user_input", input)
                                .put("user", JSONObject().put("id", "fixture-viewer").put("login", "fixtureviewer").put("display_name", "FixtureViewer"))
                                .put("reward", reward())))),
                        key.channelId,
                    )
                }
                if (intent.getBooleanExtra("notice_first", false)) {
                    session.submit(key, notice)
                    session.submit(key, chat)
                } else {
                    session.submit(key, chat)
                    session.submit(key, notice)
                }
            }
            Log.i("RewardResumeFixture", "canonical_rows=${session.snapshot().size}")
            if (mode == "hidden") {
                delay(1_000)
                renderer.setVisible(true)
            }
        }
    }

    private fun reward() = JSONObject().put("id", "tts").put("title", "🍑 Tangia TTS").put("cost", 1_000)

    private suspend fun stressCatchUp() {
        repeat(100) { batch ->
            renderer.setVisible(batch % 4 != 0)
            if (batch % 3 == 0) catalog.refresh()
            repeat(12) { offset ->
                val index = batch * 12 + offset
                session.submit(key, TwitchChatEventParser.fromIrc(
                    ChatUtils.parseIRCMessage("@id=stress-$index;user-id=fixture-viewer;display-name=FixtureViewer;color=#E8712A;tmi-sent-ts=${1_791_456_000_000L + index} :fixtureviewer!fixtureviewer@fixtureviewer.tmi.twitch.tv PRIVMSG #fixture :Catch-up message $index"),
                    key.channelId,
                )!!)
                delay(2)
            }
            delay(20)
        }
        renderer.setVisible(true)
        delay(1_000)
        Log.i("RewardResumeFixture", "stress_complete canonical_ids=${session.snapshot().map { it.id }} duplicate_publication=$duplicatePublication")
    }

    override fun onPause() {
        renderer.setVisible(false)
        super.onPause()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        renderer.setVisible(!isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        if (::renderer.isInitialized) renderer.setVisible(!isInPictureInPictureMode)
    }

    override fun onDestroy() {
        renderer.detach()
        catalog.close()
        scope.cancel()
        super.onDestroy()
    }
}
