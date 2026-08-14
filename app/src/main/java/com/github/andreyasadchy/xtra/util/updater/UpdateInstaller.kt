package com.github.andreyasadchy.xtra.util.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import com.github.andreyasadchy.xtra.util.C
import java.io.File

/** Verifies a completed download and hands only a complete Xtra APK to PackageInstaller. */
interface UpdateInstallPreparer {
    fun prepare(release: UpdateRelease, artifact: DownloadedArtifact): PreparedUpdateInstall
}

interface PreparedUpdateInstall {
    val sessionId: Int
    fun commit()
    fun abandon()
}

class UpdateInstaller(private val context: Context) : UpdateInstallPreparer {

    override fun prepare(release: UpdateRelease, artifact: DownloadedArtifact): PreparedUpdateInstall {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            throw UpdateException(UpdateError.InstallPermissionDenied)
        }
        val temporaryApk = File.createTempFile("xtra-update-", ".apk", context.cacheDir)
        var sessionId: Int? = null
        var session: PackageInstaller.Session? = null
        try {
            copyAndVerify(artifact, temporaryApk)
            val packageManager = context.packageManager
            val installed = context.packageInfo(context.packageName)
            val archive = packageManager.getPackageArchiveInfo(temporaryApk.absolutePath, packageInfoFlags())
                ?: throw UpdateException(UpdateError.IncompatibleApk)
            val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archive.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archive.versionCode.toLong()
            }
            val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                installed.versionCode.toLong()
            }
            // PackageInstaller remains authoritative for signing identity and certificate lineage.
            // Preflight also binds the archive to the release metadata, without deriving a
            // synthetic remote version code from the local build configuration. Releases that
            // predate the metadata asset retain the semantic version-name check only.
            if (!UpdatePolicy.isCompatibleArchive(
                    archivePackageName = archive.packageName,
                    archiveVersionName = archive.versionName,
                    archiveVersionCode = archiveVersionCode,
                    installedPackageName = context.packageName,
                    installedVersionCode = installedVersionCode,
                    releaseVersionName = release.versionName,
                    expectedVersionCode = release.expectedVersionCode,
                )
            ) {
                throw UpdateException(UpdateError.IncompatibleApk)
            }
            val installer = packageManager.packageInstaller
            val createdSessionId = installer.createSession(
                PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setAppPackageName(context.packageName)
                    setSize(temporaryApk.length())
                }
            )
            sessionId = createdSessionId
            val createdSession = installer.openSession(createdSessionId)
            session = createdSession
            temporaryApk.inputStream().use { input ->
                createdSession.openWrite("base.apk", 0L, temporaryApk.length()).use { output ->
                    input.copyTo(output)
                    createdSession.fsync(output)
                }
            }
            val resultIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
                putExtra(UpdateInstallReceiver.EXTRA_RELEASE_ID, release.id)
                putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, createdSessionId)
            }
            val resultSender = PendingIntent.getBroadcast(
                context,
                createdSessionId,
                resultIntent,
                pendingIntentFlags(),
            ).intentSender
            return PreparedPackageInstallerSession(createdSession, createdSessionId, resultSender)
        } catch (error: Throwable) {
            runCatching { session?.abandon() }
            session?.close()
            if (session == null) {
                sessionId?.let { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
            }
            throw error
        } finally {
            temporaryApk.delete()
        }
    }

    fun install(release: UpdateRelease, artifact: DownloadedArtifact): Int {
        val prepared = prepare(release, artifact)
        return try {
            prepared.commit()
            prepared.sessionId
        } catch (error: Throwable) {
            prepared.abandon()
            throw error
        }
    }

    private class PreparedPackageInstallerSession(
        private val session: PackageInstaller.Session,
        override val sessionId: Int,
        private val resultSender: android.content.IntentSender,
    ) : PreparedUpdateInstall {
        private var closed = false

        override fun commit() {
            check(!closed) { "PackageInstaller session is already closed" }
            try {
                session.commit(resultSender)
            } catch (error: Throwable) {
                runCatching { session.abandon() }
                throw error
            } finally {
                session.close()
                closed = true
            }
        }

        override fun abandon() {
            if (closed) return
            runCatching { session.abandon() }
            session.close()
            closed = true
        }
    }

    private fun copyAndVerify(artifact: DownloadedArtifact, destination: File) {
        if (artifact.size <= 0L) throw UpdateException(UpdateError.DownloadedFileMissing)
        val uri = artifact.uri ?: throw UpdateException(UpdateError.DownloadedFileMissing)
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw UpdateException(UpdateError.DownloadedFileMissing)
        if (destination.length() <= 0L || destination.length() != artifact.size) {
            throw UpdateException(UpdateError.DownloadFailed)
        }
    }

    private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun Context.packageInfo(packageName: String): PackageInfo = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, packageInfoFlags())
    } catch (error: PackageManager.NameNotFoundException) {
        throw UpdateException(UpdateError.IncompatibleApk, error)
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
}
