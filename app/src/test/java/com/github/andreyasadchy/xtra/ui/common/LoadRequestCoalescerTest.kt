package com.github.andreyasadchy.xtra.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadRequestCoalescerTest {

    @Test
    fun `queued revalidation starts once after active load completes`() {
        val started = mutableListOf<String>()
        val coalescer = LoadRequestCoalescer<String> { started += it }

        coalescer.request("offline-load")
        coalescer.request("ordinary-duplicate")
        coalescer.request("restored-load-1", revalidate = true)
        coalescer.request("restored-load-2", revalidate = true)

        assertEquals(listOf("offline-load"), started)

        coalescer.complete()

        assertEquals(listOf("offline-load", "restored-load-2"), started)

        coalescer.complete()

        assertEquals(listOf("offline-load", "restored-load-2"), started)
    }
}
