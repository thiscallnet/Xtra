package com.github.andreyasadchy.xtra.ui.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DownloadIoTest {
    @Test
    fun `resizing a partial download preserves committed bytes`() {
        val file = Files.createTempFile("xtra-download", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))

            DownloadIo.resizeLocalFile(file.absolutePath, 4)

            assertEquals(listOf<Byte>(1, 2, 3, 4), file.readBytes().toList())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parallel fetches are committed in playlist order`() = runBlocking {
        val committed = mutableListOf<Int>()

        DownloadIo.forEachParallelOrdered(
            items = listOf(1, 2, 3, 4),
            concurrency = 3,
            dispatcher = Dispatchers.Default,
            fetch = { value ->
                delay((5 - value) * 10L)
                value
            },
            consume = { _, value -> committed += value },
        )

        assertEquals(listOf(1, 2, 3, 4), committed)
    }
}
