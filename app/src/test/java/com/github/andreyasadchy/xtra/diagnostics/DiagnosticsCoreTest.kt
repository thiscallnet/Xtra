package com.github.andreyasadchy.xtra.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class DiagnosticsCoreTest {
    @Test
    fun ringRetainsOnlyNewestEntries() {
        val ring = DiagnosticsEntryRing(500)
        repeat(501) { sequence -> ring.append(entry(sequence.toLong())) }

        val snapshot = ring.snapshotNewestFirst()
        assertEquals(500, snapshot.size)
        assertEquals(500L, snapshot.first().sequence)
        assertEquals(1L, snapshot.last().sequence)
    }

    @Test
    fun ringIsBoundedAndCorrelationIdsAreUniqueWithConcurrentProducers() {
        val ring = DiagnosticsEntryRing(500)
        val ids = Collections.synchronizedSet(mutableSetOf<String>())
        val idGenerator = DiagnosticsCorrelationIdGenerator()
        val executor = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(800)
        repeat(800) { sequence ->
            executor.execute {
                ring.append(entry(sequence.toLong()))
                ids += idGenerator.next()
                done.countDown()
            }
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(500, ring.snapshotNewestFirst().size)
        assertEquals(800, ids.size)
    }

    @Test
    fun fieldValuesAreRestrictedByTheirKeys() {
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.COUNT, "12")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.PROGRESS, "4/?")))
        assertNotNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.STATE, "reconnected")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.COUNT, "twelve")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.STATE, "this is prose")))
        assertNull(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.CHANNEL_ID, "viewer-login")))
        assertFalse(DiagnosticsSanitizer.field(DiagnosticsField(DiagnosticsFieldKey.STATE, "session_id=secret")) != null)
    }

    @Test
    fun graphqlErrorsAreNotClassifiedAsSuccessfulHttpResponses() {
        assertFalse(diagnosticsRequestSucceeded(200, graphQlError = true, malformedResponse = false))
        assertTrue(diagnosticsRequestSucceeded(200, graphQlError = false, malformedResponse = false))
        assertFalse(diagnosticsRequestSucceeded(503, graphQlError = false, malformedResponse = false))
    }

    @Test
    fun requestTokenHasExactlyOneTerminalTransition() {
        val token = DiagnosticsLogger.RequestToken(
            correlationId = "d000001",
            startedAtElapsedMs = 1L,
            category = DiagnosticsCategory.GQL,
            transport = DiagnosticsTransport.GQL,
            operation = "test",
        )

        assertTrue(token.tryFinish())
        assertFalse(token.tryFinish())
    }

    private fun entry(sequence: Long) = DiagnosticsEntry(
        sequence = sequence,
        timestampMs = sequence,
        category = DiagnosticsCategory.GQL,
        severity = DiagnosticsSeverity.INFO,
        transport = DiagnosticsTransport.GQL,
        operation = "test",
        event = "event",
        phase = DiagnosticsPhase.EVENT,
    )
}
