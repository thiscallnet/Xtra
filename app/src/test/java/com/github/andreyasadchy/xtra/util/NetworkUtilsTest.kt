package com.github.andreyasadchy.xtra.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun `limited streaming copy writes the complete response`() {
        val output = ByteArrayOutputStream()

        val copied = NetworkUtils.copyToLimited(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            output,
            maxBytes = 4,
        )

        assertEquals(4L, copied)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test(expected = IOException::class)
    fun `limited streaming copy rejects an oversized unknown length response`() {
        NetworkUtils.copyToLimited(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
            ByteArrayOutputStream(),
            maxBytes = 4,
        )
    }
}
