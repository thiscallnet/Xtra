package com.github.andreyasadchy.xtra.util

import android.content.Context
import java.io.File
import java.util.Properties

/** Keeps pre-restore files available until Room has opened the replacement. */
internal object DatabaseRestoreRecovery {
    private const val DATABASE_NAME = "database"
    private const val RESTORE_NEW_SUFFIX = ".restore-new"
    private const val RESTORE_OLD_SUFFIX = ".restore-old"
    private const val RESTORE_STATE_NAME = "database.restore-state"
    private const val RESTORE_STATE_TEMP_SUFFIX = ".tmp"
    private const val RESTORE_SWAPPING_NAME = "database.restore-swapping"
    private const val RESTORE_INSTALLED_NAME = "database.restore-installed"
    private const val STATE_VERSION = "1"

    private data class Slot(val selected: Boolean, val existed: Boolean)

    private data class RestorePlan(
        val database: Slot,
        val preferences: Slot,
    )

    private enum class Phase { PREPARED, SWAPPING, INSTALLED }

    fun begin(
        context: Context,
        databaseSelected: Boolean,
        databaseExisted: Boolean,
        preferencesSelected: Boolean,
        preferencesExisted: Boolean,
    ) {
        check(!hasCommittedRestore(context)) { "Another settings restore is already pending" }
        writePlan(
            context,
            RestorePlan(
                database = Slot(databaseSelected, databaseExisted),
                preferences = Slot(preferencesSelected, preferencesExisted),
            ),
        )
    }

    fun markSwapping(context: Context) {
        check(readPlan(context) != null) { "Restore plan is missing" }
        check(swappingFile(context).createNewFile() || swappingFile(context).exists()) {
            "Unable to mark restore as swapping"
        }
    }

    fun markInstalled(context: Context) {
        check(readPlan(context) != null) { "Restore plan is missing" }
        check(swappingFile(context).exists()) { "Restore was not marked as swapping" }
        check(installedFile(context).createNewFile() || installedFile(context).exists()) {
            "Unable to mark restore as installed"
        }
    }

    /** Repairs interrupted swaps before Room can create/open the live database path. */
    fun recoverBeforeDatabaseOpen(context: Context) {
        val stateFileExists = stateFile(context).exists() ||
            stateTempFile(context).exists() ||
            swappingFile(context).exists() ||
            installedFile(context).exists()
        val plan = readPlan(context)
        if (stateFileExists && plan == null) {
            rollback(context, RestorePlan(Slot(true, true), Slot(true, true)))
            return
        }
        if (plan != null) {
            val phase = phase(context)
            if (phase != Phase.INSTALLED || selectedTargetMissing(context, plan)) {
                rollback(context, plan)
            } else if (plan.database.selected) {
                deleteDatabaseSidecars(context)
            }
            return
        }

        // Compatibility with swaps created before the phase marker existed.
        if (legacyTargetMissing(context) || legacySwapIsAmbiguous(context)) {
            rollback(context, RestorePlan(Slot(true, true), Slot(true, true)))
        } else {
            // A staged file with no old target was never part of a committed swap.
            if (!databaseFile(context, RESTORE_OLD_SUFFIX).exists()) databaseFile(context, RESTORE_NEW_SUFFIX).delete()
            if (!preferencesFile(context, RESTORE_OLD_SUFFIX).exists()) preferencesFile(context, RESTORE_NEW_SUFFIX).delete()
        }
    }

    fun hasPendingRestore(context: Context): Boolean =
        stateFile(context).exists() ||
            stateTempFile(context).exists() ||
            swappingFile(context).exists() ||
            installedFile(context).exists() ||
            databaseFile(context, RESTORE_OLD_SUFFIX).exists() ||
            databaseFile(context, RESTORE_NEW_SUFFIX).exists() ||
            preferencesFile(context, RESTORE_OLD_SUFFIX).exists() ||
            preferencesFile(context, RESTORE_NEW_SUFFIX).exists()

    private fun hasCommittedRestore(context: Context): Boolean =
        stateFile(context).exists() ||
            stateTempFile(context).exists() ||
            swappingFile(context).exists() ||
            installedFile(context).exists() ||
            databaseFile(context, RESTORE_OLD_SUFFIX).exists() ||
            preferencesFile(context, RESTORE_OLD_SUFFIX).exists()

    fun complete(context: Context) {
        deleteArtifacts(context)
    }

    fun rollback(context: Context) {
        rollback(context, readPlan(context) ?: RestorePlan(Slot(true, true), Slot(true, true)))
    }

    private fun rollback(context: Context, plan: RestorePlan) {
        if (plan.database.selected) {
            deleteDatabaseSidecars(context)
            restoreFile(
                databaseFile(context, RESTORE_OLD_SUFFIX),
                databaseFile(context, ""),
                plan.database.existed,
            )
        }
        if (plan.preferences.selected) {
            restoreFile(
                preferencesFile(context, RESTORE_OLD_SUFFIX),
                preferencesFile(context, ""),
                plan.preferences.existed,
            )
        }
        deleteArtifacts(context)
    }

    private fun selectedTargetMissing(context: Context, plan: RestorePlan): Boolean =
        (plan.database.selected && !databaseFile(context, "").exists()) ||
            (plan.preferences.selected && !preferencesFile(context, "").exists())

    private fun legacyTargetMissing(context: Context): Boolean =
        (databaseFile(context, RESTORE_OLD_SUFFIX).exists() && !databaseFile(context, "").exists()) ||
            (preferencesFile(context, RESTORE_OLD_SUFFIX).exists() && !preferencesFile(context, "").exists())

    private fun legacySwapIsAmbiguous(context: Context): Boolean {
        val hasOld = databaseFile(context, RESTORE_OLD_SUFFIX).exists() ||
            preferencesFile(context, RESTORE_OLD_SUFFIX).exists()
        val hasNew = databaseFile(context, RESTORE_NEW_SUFFIX).exists() ||
            preferencesFile(context, RESTORE_NEW_SUFFIX).exists()
        // Without a journal, old+new artifacts cannot distinguish a complete
        // swap from a process death between the per-file rename loops. Restore
        // the originals rather than accepting a mixed database/preferences set.
        return hasOld && hasNew
    }

    private fun restoreFile(previous: File, target: File, existed: Boolean) {
        if (previous.exists()) {
            target.delete()
            check(previous.renameTo(target)) { "Unable to roll back ${target.name}" }
        } else if (!existed) {
            target.delete()
        }
    }

    private fun deleteArtifacts(context: Context) {
        stateFile(context).delete()
        stateTempFile(context).delete()
        swappingFile(context).delete()
        installedFile(context).delete()
        databaseFile(context, RESTORE_NEW_SUFFIX).delete()
        databaseFile(context, RESTORE_OLD_SUFFIX).delete()
        preferencesFile(context, RESTORE_NEW_SUFFIX).delete()
        preferencesFile(context, RESTORE_OLD_SUFFIX).delete()
    }

    private fun deleteDatabaseSidecars(context: Context) {
        val database = databaseFile(context, "")
        File(database.parentFile, "database-shm").delete()
        File(database.parentFile, "database-wal").delete()
    }

    private fun phase(context: Context): Phase = when {
        installedFile(context).exists() -> Phase.INSTALLED
        swappingFile(context).exists() -> Phase.SWAPPING
        else -> Phase.PREPARED
    }

    private fun writePlan(context: Context, plan: RestorePlan) {
        val file = stateFile(context)
        file.parentFile?.mkdirs()
        val temporary = stateTempFile(context)
        val properties = Properties().apply {
            setProperty("version", STATE_VERSION)
            setProperty("database", encode(plan.database))
            setProperty("preferences", encode(plan.preferences))
        }
        temporary.outputStream().use { output -> properties.store(output, null) }
        check(temporary.renameTo(file)) { "Unable to persist restore plan" }
    }

    private fun readPlan(context: Context): RestorePlan? = runCatching {
        val properties = Properties()
        stateFile(context).inputStream().use(properties::load)
        require(properties.getProperty("version") == STATE_VERSION)
        RestorePlan(
            database = decode(properties.getProperty("database")),
            preferences = decode(properties.getProperty("preferences")),
        )
    }.getOrNull()

    private fun encode(slot: Slot): String = "${slot.selected},${slot.existed}"

    private fun decode(value: String?): Slot {
        val parts = requireNotNull(value).split(',', limit = 2)
        require(parts.size == 2)
        return Slot(parts[0].toBooleanStrict(), parts[1].toBooleanStrict())
    }

    private fun stateFile(context: Context): File =
        File(databaseFile(context, "").parentFile, RESTORE_STATE_NAME)

    private fun stateTempFile(context: Context): File =
        File(databaseFile(context, "").parentFile, RESTORE_STATE_NAME + RESTORE_STATE_TEMP_SUFFIX)

    private fun swappingFile(context: Context): File =
        File(databaseFile(context, "").parentFile, RESTORE_SWAPPING_NAME)

    private fun installedFile(context: Context): File =
        File(databaseFile(context, "").parentFile, RESTORE_INSTALLED_NAME)

    private fun databaseFile(context: Context, suffix: String): File =
        File(context.getDatabasePath(DATABASE_NAME).path + suffix)

    private fun preferencesFile(context: Context, suffix: String): File = File(
        context.applicationInfo.dataDir,
        "shared_prefs/${context.packageName}_preferences.xml$suffix",
    )
}
