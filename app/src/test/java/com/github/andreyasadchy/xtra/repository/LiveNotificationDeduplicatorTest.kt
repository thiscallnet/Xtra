package com.github.andreyasadchy.xtra.repository

import android.net.http.HttpEngine
import com.github.andreyasadchy.xtra.db.NotificationEventsDao
import com.github.andreyasadchy.xtra.db.NotificationUsersDao
import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.Instant

class LiveNotificationDeduplicatorTest {

    @Test
    fun sameBroadcastAcrossRepeatedChecks_isNotifiedOnce() = runBlocking {
        val notifier = Notifier()
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())

        repeat(10) {
            notifier.notify(subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))))
        }

        assertEquals(1, notifier.count("A"))
    }

    @Test
    fun sameBroadcastAfterTransientEmptyPoll_isNotifiedOnce() = runBlocking {
        val notifier = Notifier()
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())

        notifier.notify(subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))))
        notifier.notify(subject.processStreams(emptyList()))
        notifier.notify(subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))))

        assertEquals(1, notifier.count("A"))
    }

    @Test
    fun partialPollDoesNotResetTheOmittedBroadcaster() = runBlocking {
        val notifier = Notifier()
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())

        notifier.notify(subject.processStreams(listOf(
            stream("A", "123", "2026-08-27T10:00:00Z"),
            stream("B", "456", "2026-08-27T10:01:00Z"),
        )))
        notifier.notify(subject.processStreams(listOf(stream("B", "456", "2026-08-27T10:01:00Z"))))
        notifier.notify(subject.processStreams(listOf(
            stream("A", "123", "2026-08-27T10:00:00Z"),
            stream("B", "456", "2026-08-27T10:01:00Z"),
        )))

        assertEquals(1, notifier.count("A"))
        assertEquals(1, notifier.count("B"))
    }

    @Test
    fun newStreamIdIsANewBroadcast() = runBlocking {
        val notifier = Notifier()
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())

        notifier.notify(subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))))
        notifier.notify(subject.processStreams(listOf(stream("A", "999", "2026-08-27T11:00:00Z"))))

        assertEquals(2, notifier.count("A"))
    }

    @Test
    fun incompleteLivePayloadWithoutStreamIdIsIgnored() = runBlocking {
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())

        assertTrue(subject.processStreams(listOf(stream("A", null, "2026-08-27T10:00:00Z"))).isEmpty())
    }

    @Test
    fun concurrentChecksNotifyOnce() = runBlocking {
        val notifier = Notifier()
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())

        coroutineScope {
            listOf(1, 2).map {
                async(Dispatchers.Default) {
                    subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z")))
                }
            }.awaitAll().forEach(notifier::notify)
        }

        assertEquals(1, notifier.count("A"))
    }

    @Test
    fun sharedDatabaseStateSurvivesDeduplicatorRecreation() = runBlocking {
        val notifier = Notifier()
        val store = InMemoryShownNotifications()

        notifier.notify(LiveNotificationDeduplicator(store).processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))))
        notifier.notify(LiveNotificationDeduplicator(store).processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))))

        assertEquals(1, notifier.count("A"))
    }

    @Test
    fun failedPollPreservesClaimedBroadcast() = runBlocking {
        var fetchResult = Result.success(listOf(stream("A", "123", "2026-08-27T10:00:00Z")))
        val repository = repository(InMemoryShownNotifications()) { fetchResult.getOrThrow() }

        val first = repository.getNewStreams(
            networkLibrary = null,
            gqlHeaders = mapOf(C.HEADER_TOKEN to "test-token"),
            helixHeaders = emptyMap(),
        )
        assertEquals(listOf("123"), first.map { it.id })

        fetchResult = Result.failure(IOException("timeout"))
        try {
            repository.getNewStreams(
                networkLibrary = null,
                gqlHeaders = mapOf(C.HEADER_TOKEN to "test-token"),
                helixHeaders = emptyMap(),
            )
            fail("the failed Twitch fetch must propagate out of getNewStreams")
        } catch (error: IOException) {
            assertEquals("timeout", error.message)
        }

        fetchResult = Result.success(listOf(stream("A", "123", "2026-08-27T10:00:00Z")))
        val afterRecovery = repository.getNewStreams(
            networkLibrary = null,
            gqlHeaders = mapOf(C.HEADER_TOKEN to "test-token"),
            helixHeaders = emptyMap(),
        )

        assertTrue(afterRecovery.isEmpty())
    }

    @Test
    fun migratedLegacyBroadcastIsNotNotifiedAgainWhenRealStreamIdAppears() = runBlocking {
        val store = InMemoryShownNotifications()
        val startedAt = Instant.parse("2026-08-27T10:00:00Z").toEpochMilliseconds()
        val legacyStreamId = ShownNotification.legacyStreamId("A", startedAt)
        store.insert(
            ShownNotification(
                channelId = "A",
                streamId = legacyStreamId,
                startedAt = startedAt,
            )
        )

        val subject = LiveNotificationDeduplicator(store)

        assertTrue(subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))).isEmpty())
        assertEquals("123", store.getByStreamId("123")?.streamId)
        assertNull(store.getByStreamId(legacyStreamId))
        assertTrue(subject.processStreams(listOf(stream("A", "123", "2026-08-27T10:00:00Z"))).isEmpty())
    }

    @Test
    fun separateBroadcastersAreDeduplicatedIndependently() = runBlocking {
        val notifier = Notifier()
        val subject = LiveNotificationDeduplicator(InMemoryShownNotifications())
        val streams = listOf(
            stream("A", "123", "2026-08-27T10:00:00Z"),
            stream("B", "456", "2026-08-27T10:01:00Z"),
        )

        notifier.notify(subject.processStreams(streams))
        notifier.notify(subject.processStreams(streams))

        assertEquals(1, notifier.count("A"))
        assertEquals(1, notifier.count("B"))
    }

    private fun stream(userId: String, streamId: String?, createdAt: String) = Stream(
        id = streamId,
        channelId = userId,
        createdAt = createdAt,
    )

    private fun repository(
        shownNotifications: ShownNotificationsDao,
        provider: LiveNotificationStreamsProvider,
    ) = NotificationsRepository(
        shownNotificationsDao = shownNotifications,
        notificationUsersDao = InMemoryNotificationUsers(),
        notificationEventsDao = InMemoryNotificationEvents(),
        graphQLRepository = emptyGraphQlRepository(),
        helixRepository = emptyHelixRepository(),
        liveNotificationStreamsProvider = provider,
    )

    private fun emptyGraphQlRepository() = GraphQLRepository(
        httpEngine = lazyOf<HttpEngine?>(null),
        cronetEngine = lazyOf<CronetEngine?>(null),
        cronetExecutor = lazyOf(Executors.newSingleThreadExecutor()),
        okHttpClient = lazyOf(OkHttpClient()),
        json = Json.Default,
    )

    private fun emptyHelixRepository() = HelixRepository(
        httpEngine = lazyOf<HttpEngine?>(null),
        cronetEngine = lazyOf<CronetEngine?>(null),
        cronetExecutor = lazyOf(Executors.newSingleThreadExecutor()),
        okHttpClient = lazyOf(OkHttpClient()),
        json = Json.Default,
    )

    private class Notifier {
        private val notifications = mutableListOf<Stream>()

        fun notify(streams: List<Stream>) {
            notifications += streams
        }

        fun count(channelId: String): Int = notifications.count { it.channelId == channelId }
    }

    private class InMemoryNotificationUsers : NotificationUsersDao {
        private val users = listOf(NotificationUser("A"))

        override fun getAll(): List<NotificationUser> = users

        override fun getById(id: String): NotificationUser? = users.firstOrNull { it.channelId == id }

        override fun insert(item: NotificationUser) = Unit

        override fun insertList(items: List<NotificationUser>) = Unit

        override fun delete(item: NotificationUser) = Unit

        override fun deleteAll() = Unit
    }

    private class InMemoryNotificationEvents : NotificationEventsDao {
        override fun getAll(): List<NotificationEvent> = emptyList()

        override fun insertList(items: List<NotificationEvent>) = Unit

        override fun insert(item: NotificationEvent) = Unit

        override fun delete(eventId: String) = Unit

        override fun deleteForChannel(channelId: String) = Unit

        override fun deleteAll() = Unit
    }

    private open class InMemoryShownNotifications : ShownNotificationsDao {
        protected val items = mutableListOf<ShownNotification>()

        override fun getAll(): List<ShownNotification> = items.toList()

        override fun getById(channelId: String): ShownNotification? = items.firstOrNull { it.channelId == channelId }

        override fun getByStreamId(streamId: String): ShownNotification? = items.firstOrNull { it.streamId == streamId }

        @Synchronized
        override fun insert(item: ShownNotification): Long {
            if (items.any { it.streamId == item.streamId }) return -1L
            val stored = ShownNotification(
                id = (items.maxOfOrNull { it.id } ?: 0L) + 1L,
                channelId = item.channelId,
                streamId = item.streamId,
                startedAt = item.startedAt,
            )
            items += stored
            return stored.id
        }

        override fun insertList(items: List<ShownNotification>) {
            items.forEach { item ->
                insert(item)
            }
        }

        override fun deleteList(items: List<ShownNotification>) {
            this.items.removeAll(items.toSet())
        }
    }

}
