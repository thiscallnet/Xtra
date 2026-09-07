package com.github.andreyasadchy.xtra.repository.streamfeed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFeedPrewarmSchedulerTest {
    @Test
    fun cancellationAfterDelayBeforeEnqueueInvalidatesTheArm() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var armController: StreamFeedPrewarmArmController? = null
        var enqueueCount = 0

        try {
            armController = StreamFeedPrewarmArmController(
                scope = scope,
                delayBlock = {
                    armController?.cancel()
                },
            )
            armController.schedule(delayMs = 1L) {
                enqueueCount++
            }

            assertEquals(0, enqueueCount)
        } finally {
            scope.cancel()
        }
    }
}
