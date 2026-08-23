package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseRestoreRecoveryTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach(File::deleteRecursively)
    }

    @Test
    fun crashAfterOldRenameRollsBackEverySelectedFile() {
        val context = isolatedContext()
        val database = databaseFile(context)
        val preferences = preferencesFile(context)
        database.writeText("old database")
        preferences.writeText("old preferences")
        prepareSwap(context, "new database", "new preferences")

        assertTrue(database.renameTo(File(database.path + ".restore-old")))
        assertTrue(preferences.renameTo(File(preferences.path + ".restore-old")))

        DatabaseRestoreRecovery.recoverBeforeDatabaseOpen(context)

        assertEquals("old database", database.readText())
        assertEquals("old preferences", preferences.readText())
        assertFalse(DatabaseRestoreRecovery.hasPendingRestore(context))
    }

    @Test
    fun crashDuringNewRenameRollsBackTheAlreadyInstalledFileToo() {
        val context = isolatedContext()
        val database = databaseFile(context)
        val preferences = preferencesFile(context)
        database.writeText("old database")
        preferences.writeText("old preferences")
        prepareSwap(context, "new database", "new preferences")

        val oldDatabase = File(database.path + ".restore-old")
        val oldPreferences = File(preferences.path + ".restore-old")
        assertTrue(database.renameTo(oldDatabase))
        assertTrue(preferences.renameTo(oldPreferences))
        assertTrue(File(database.path + ".restore-new").renameTo(database))

        DatabaseRestoreRecovery.recoverBeforeDatabaseOpen(context)

        assertEquals("old database", database.readText())
        assertEquals("old preferences", preferences.readText())
        assertFalse(DatabaseRestoreRecovery.hasPendingRestore(context))
    }

    @Test
    fun installedSwapSurvivesStartupValidationUntilComplete() {
        val context = isolatedContext()
        val database = databaseFile(context)
        val preferences = preferencesFile(context)
        database.writeText("old database")
        preferences.writeText("old preferences")
        prepareSwap(context, "new database", "new preferences")

        assertTrue(database.renameTo(File(database.path + ".restore-old")))
        assertTrue(preferences.renameTo(File(preferences.path + ".restore-old")))
        assertTrue(File(database.path + ".restore-new").renameTo(database))
        assertTrue(File(preferences.path + ".restore-new").renameTo(preferences))
        DatabaseRestoreRecovery.markSwapping(context)
        DatabaseRestoreRecovery.markInstalled(context)

        DatabaseRestoreRecovery.recoverBeforeDatabaseOpen(context)

        assertEquals("new database", database.readText())
        assertEquals("new preferences", preferences.readText())
        assertTrue(DatabaseRestoreRecovery.hasPendingRestore(context))

        DatabaseRestoreRecovery.complete(context)
        assertFalse(DatabaseRestoreRecovery.hasPendingRestore(context))
        assertEquals("new database", database.readText())
        assertEquals("new preferences", preferences.readText())
    }

    @Test
    fun stateLessMixedSwapRollsBackInsteadOfCommittingPartialRestore() {
        val context = isolatedContext()
        val database = databaseFile(context)
        val preferences = preferencesFile(context)
        database.writeText("old database")
        preferences.writeText("old preferences")
        prepareSwap(context, "new database", "new preferences")

        assertTrue(database.renameTo(File(database.path + ".restore-old")))
        assertTrue(preferences.renameTo(File(preferences.path + ".restore-old")))
        assertTrue(File(database.path + ".restore-new").renameTo(database))
        File(database.path + ".restore-state").delete()
        File(database.path + ".restore-swapping").delete()

        DatabaseRestoreRecovery.recoverBeforeDatabaseOpen(context)

        assertEquals("old database", database.readText())
        assertEquals("old preferences", preferences.readText())
        assertFalse(DatabaseRestoreRecovery.hasPendingRestore(context))
    }

    private fun prepareSwap(context: Context, databaseText: String, preferencesText: String) {
        val database = databaseFile(context)
        val preferences = preferencesFile(context)
        DatabaseRestoreRecovery.begin(
            context,
            databaseSelected = true,
            databaseExisted = database.exists(),
            preferencesSelected = true,
            preferencesExisted = preferences.exists(),
        )
        File(database.path + ".restore-new").writeText(databaseText)
        File(preferences.path + ".restore-new").writeText(preferencesText)
        DatabaseRestoreRecovery.markSwapping(context)
    }

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "restore-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        roots += root
        return IsolatedContext(base, root)
    }

    private fun databaseFile(context: Context) = context.getDatabasePath("database")

    private fun preferencesFile(context: Context) = File(
        context.applicationInfo.dataDir,
        "shared_prefs/${context.packageName}_preferences.xml",
    ).apply { parentFile?.mkdirs() }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply {
            parentFile?.mkdirs()
        }

        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            dataDir = File(root, "data").apply { mkdirs() }.path
        }

        override fun getPackageName(): String = "restore.recovery.test"
    }
}
