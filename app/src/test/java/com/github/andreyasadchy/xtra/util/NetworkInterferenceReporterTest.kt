package com.github.andreyasadchy.xtra.util

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterferenceReporterTest {
    private val state = NetworkInterferenceState()

    @Test
    fun `recognizes unknown host failures`() {
        assertTrue(NetworkInterferenceReporter.isDnsResolutionFailure(UnknownHostException("gql.twitch.tv")))
    }

    @Test
    fun `recognizes nested DNS transport failures`() {
        val error = IOException("request failed", UnknownHostException("gql.twitch.tv"))

        assertTrue(NetworkInterferenceReporter.isDnsResolutionFailure(error))
    }

    @Test
    fun `recognizes Cronet name resolution failures`() {
        assertTrue(NetworkInterferenceReporter.isDnsResolutionFailure(IOException("net::ERR_NAME_NOT_RESOLVED")))
    }

    @Test
    fun `does not classify ordinary request failures as DNS failures`() {
        assertFalse(NetworkInterferenceReporter.isDnsResolutionFailure(IOException("connection timed out")))
    }

    @Test
    fun `one failure is not enough to produce a candidate`() {
        assertFalse(state.recordFailure(1_000L))
    }

    @Test
    fun `two failures within the window produce a candidate`() {
        assertFalse(state.recordFailure(1_000L))

        assertTrue(state.recordFailure(90_000L))
    }

    @Test
    fun `failures outside the window do not produce a candidate`() {
        assertFalse(state.recordFailure(1_000L))

        assertFalse(state.recordFailure(91_001L))
    }

    @Test
    fun `candidate detection does not consume the display cooldown`() {
        assertFalse(state.recordFailure(1_000L))
        assertTrue(state.recordFailure(2_000L))

        assertTrue(state.tryAcquireWarningPermit(2_000L))
    }

    @Test
    fun `display permit is suppressed for fifteen minutes`() {
        assertTrue(state.tryAcquireWarningPermit(1_000L))
        assertFalse(state.tryAcquireWarningPermit(900_999L))
        assertTrue(state.tryAcquireWarningPermit(901_000L))
    }
}
