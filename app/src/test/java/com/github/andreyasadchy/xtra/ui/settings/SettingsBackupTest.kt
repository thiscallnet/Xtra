package com.github.andreyasadchy.xtra.ui.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.github.andreyasadchy.xtra.util.C
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class SettingsBackupTest {
    @Test
    fun `v2 archive round trip verifies typed settings and database`() {
        val directory = Files.createTempDirectory("xtra-backup").toFile()
        try {
            val database = directory.resolve("source.db").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val archive = ByteArrayOutputStream()

            SettingsBackup.writeArchive(
                output = archive,
                preferences = mapOf(
                    "enabled" to true,
                    "columns" to 4,
                    "pages" to setOf("home", "saved"),
                    "settings_version" to 26,
                    "token" to "must-remain-local",
                ),
                settingsSchemaVersion = 27,
                database = database,
                databaseSchemaVersion = 52,
                appVersionCode = 400,
            )
            val restoredDirectory = directory.resolve("restored").apply { mkdirs() }
            val restored = SettingsBackup.extractArchive(ByteArrayInputStream(archive.toByteArray()), restoredDirectory)

            assertEquals(null, restored.preferences)
            assertEquals(2, restored.formatVersion)
            assertEquals(27, restored.settingsSchemaVersion)
            assertEquals(52, restored.databaseSchemaVersion)
            assertEquals(
                mapOf("enabled" to true, "columns" to 4, "pages" to setOf("home", "saved")),
                SettingsBackup.readTypedPreferences(restored.settings!!),
            )
            assertEquals(
                mapOf("token" to "must-remain-local"),
                SettingsBackup.localPreferences(mapOf("settings_version" to 26, "token" to "must-remain-local")),
            )
            assertArrayEquals(database.readBytes(), restored.database!!.readBytes())
            assertEquals(SettingsBackup.FileType.ARCHIVE, directory.resolve("backup.zip").apply { writeBytes(archive.toByteArray()) }.let(SettingsBackup::detectType))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `restore keeps the source schema cursor while preserving local secrets`() {
        val navigation = "0:1:1,4:0:0,1:0:0,2:0:1,3:0:0,5:0:1,6:0:1"

        val restored = SettingsBackup.restoredPreferences(
            imported = mapOf(
                C.UI_NAVIGATION_TAB_LIST to navigation,
                "token" to "must-not-import",
            ),
            existingLocal = mapOf(
                "token" to "keep-current-token",
                "proxy_password" to "keep-current-proxy-password",
            ),
            schemaVersion = 27,
        )

        assertEquals(27, restored[C.SETTINGS_VERSION])
        assertEquals(navigation, restored[C.UI_NAVIGATION_TAB_LIST])
        assertEquals("keep-current-token", restored["token"])
        assertEquals("keep-current-proxy-password", restored["proxy_password"])
    }

    @Test
    fun `private proxy rollback snapshot preserves encrypted backing values`() {
        val file = Files.createTempFile("xtra-proxy-before", ".json").toFile()
        try {
            val encryptedValue = "enc:v1:opaque-ciphertext"
            SettingsBackup.writeTypedPreferences(
                file,
                mapOf("proxy_password" to encryptedValue),
                schemaVersion = 0,
                includeExcluded = true,
            )

            assertEquals(
                encryptedValue,
                SettingsBackup.readTypedPreferences(file, includeExcluded = true)["proxy_password"],
            )
            assertEquals(true, file.readText().contains(encryptedValue))
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `v2 settings reject a duplicate schema marker in the payload`() {
        val file = Files.createTempFile("xtra-duplicate-schema", ".json").toFile()
        try {
            file.writeText("""{"schemaVersion":27,"values":{"settings_version":{"type":"int","value":26}}}""")
            SettingsBackup.validateTypedPreferences(file, 27)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `v1 archive remains readable`() {
        val directory = Files.createTempDirectory("xtra-v1-backup").toFile()
        try {
            val preferences = directory.resolve("source.xml").apply {
                writeText("<?xml version='1.0'?><map><boolean name='enabled' value='true'/><int name='settings_version' value='10'/></map>")
            }
            val database = directory.resolve("source.db").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val archive = ByteArrayOutputStream()
            SettingsBackup.writeLegacyArchive(archive, preferences, database)

            val restored = SettingsBackup.extractArchive(
                ByteArrayInputStream(archive.toByteArray()),
                directory.resolve("restored").apply { mkdirs() },
            )
            assertEquals(1, restored.formatVersion)
            assertArrayEquals(preferences.readBytes(), restored.preferences!!.readBytes())
            val legacyValues = SettingsBackup.readLegacyPreferences(restored.preferences)
            assertEquals(mapOf("enabled" to true, "settings_version" to 10), legacyValues)
            assertEquals(10, SettingsBackup.legacySettingsSchemaVersion(legacyValues))
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
            SettingsBackup.writeArchive(
                output = archive,
                preferences = mapOf("columns" to 3),
                settingsSchemaVersion = 27,
                database = database,
                databaseSchemaVersion = 52,
                proxy = proxy,
                appVersionCode = 400,
            )

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
