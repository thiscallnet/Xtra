package com.github.andreyasadchy.xtra.ui.multiview

import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewRaidMonitorTest {
    @Test
    fun monitorsEveryStreamWithoutRenderedChat() = runBlocking {
        val subscriptions = linkedMapOf<String, FakeSubscription>()
        val received = mutableListOf<String>()
        val monitor = MultiviewRaidMonitor(
            scope = this,
            subscribe = { channelId, onRaid ->
                FakeSubscription(onRaid).also { subscriptions[channelId] = it }
            },
            resolveChannelId = { it.channelId },
            onRaid = { identity, _ -> received += identity },
        )

        monitor.sync(
            listOf(
                Stream(channelId = "100", channelLogin = "alpha"),
                Stream(channelId = "200", channelLogin = "beta"),
                Stream(channelId = "300", channelLogin = "gamma"),
            ),
        )

        assertEquals(listOf("100", "200", "300"), subscriptions.keys.toList())

        subscriptions.getValue("100").emit(Raid(targetId = "400", openStream = true))

        assertEquals(listOf("id:100"), received)
        monitor.close()
        assertTrue(subscriptions.values.all(FakeSubscription::closed))
    }

    @Test
    fun resolvesLoginOnlyStreamBeforeSubscribing() = runBlocking {
        val subscriptions = linkedMapOf<String, FakeSubscription>()
        val monitor = MultiviewRaidMonitor(
            scope = this,
            subscribe = { channelId, onRaid ->
                FakeSubscription(onRaid).also { subscriptions[channelId] = it }
            },
            resolveChannelId = { "100" },
            onRaid = { _, _ -> },
        )

        monitor.sync(listOf(Stream(channelLogin = "alpha")))
        yield()

        assertEquals(listOf("100"), subscriptions.keys.toList())
        monitor.close()
    }

    private class FakeSubscription(
        private val onRaid: suspend (Raid) -> Unit,
    ) : MultiviewRaidSubscription {
        var closed = false

        suspend fun emit(raid: Raid) {
            onRaid(raid)
        }

        override fun close() {
            closed = true
        }
    }
}
