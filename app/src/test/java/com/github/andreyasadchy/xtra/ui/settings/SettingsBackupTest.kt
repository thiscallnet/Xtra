package com.github.andreyasadchy.xtra.ui.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class SettingsBackupTest {
    @Test
    fun `archive round trip verifies and restores both files`() {
        val directory = Files.createTempDirectory("xtra-backup").toFile()
        try {
            val preferences = directory.resolve("source.xml").apply { writeText("<?xml version='1.0'?><map><boolean name='x' value='true'/></map>") }
            val database = directory.resolve("source.db").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val archive = ByteArrayOutputStream()

            SettingsBackup.writeArchive(archive, preferences, database)
            val restoredDirectory = directory.resolve("restored").apply { mkdirs() }
            val restored = SettingsBackup.extractArchive(ByteArrayInputStream(archive.toByteArray()), restoredDirectory)

            assertArrayEquals(preferences.readBytes(), restored.preferences!!.readBytes())
            assertArrayEquals(database.readBytes(), restored.database!!.readBytes())
            assertEquals(SettingsBackup.FileType.ARCHIVE, directory.resolve("backup.zip").apply { writeBytes(archive.toByteArray()) }.let(SettingsBackup::detectType))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `archive round trip carries non secret proxy configuration`() {
        val directory = Files.createTempDirectory("xtra-proxy-backup").toFile()
        try {
            val preferences = directory.resolve("source.xml").apply { writeText("<map/>") }
            val database = directory.resolve("source.db").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val proxy = directory.resolve("proxy.json").apply {
                writeText("{\"enabled\":true,\"allowDirectFallback\":false,\"host\":\"127.0.0.1\",\"port\":\"8080\",\"user\":\"alice\"}")
            }
            val archive = ByteArrayOutputStream()
            SettingsBackup.writeArchive(archive, preferences, database, proxy)

            val restored = SettingsBackup.extractArchive(
                ByteArrayInputStream(archive.toByteArray()),
                directory.resolve("restored").apply { mkdirs() },
            )
            assertEquals(proxy.readText(), restored.proxy!!.readText())
            SettingsBackup.validateProxyConfiguration(restored.proxy)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `preferences validation rejects unrelated xml`() {
        val file = Files.createTempFile("xtra-invalid", ".xml").toFile()
        try {
            file.writeText("<html/>")
            SettingsBackup.validatePreferences(file)
        } finally {
            file.delete()
        }
    }
}
