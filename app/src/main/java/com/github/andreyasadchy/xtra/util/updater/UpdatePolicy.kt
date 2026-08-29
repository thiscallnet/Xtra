package com.github.andreyasadchy.xtra.util.updater

object UpdatePolicy {

    private val semanticVersion = Regex("^(\\d+(?:\\.\\d+)*)")

    fun decide(
        installedVersionName: String,
        installedBuildNumber: Long?,
        release: UpdateRelease,
        ignoredReleaseId: String?,
        automatic: Boolean,
        deferred: Boolean = false,
    ): UpdateDecision {
        if (release.draft || release.prerelease) return UpdateDecision.Current
        if (!isNewer(installedVersionName, installedBuildNumber, release)) return UpdateDecision.Current
        val ignored = ignoredReleaseId == release.id || ignoredReleaseId == release.displayVersion
        if (ignored && automatic) return UpdateDecision.Ignored
        if (ignored) return UpdateDecision.Available
        if (deferred && automatic) return UpdateDecision.Deferred
        return UpdateDecision.Available
    }

    fun primaryAction(error: UpdateState.Error): UpdatePrimaryAction? =
        error.preservedAction ?: when (error.cause) {
            UpdateError.InstallPermissionDenied -> UpdatePrimaryAction.ALLOW_INSTALL.takeIf {
                error.release != null && error.artifact != null
            }
            UpdateError.InstallCancelled,
            UpdateError.InstallFailed -> UpdatePrimaryAction.INSTALL.takeIf {
                error.retryable && error.release != null && error.artifact != null
            }
            else -> null
        }

    fun retryAction(
        stage: UpdateStage,
        retryable: Boolean,
        hasRelease: Boolean,
        hasArtifact: Boolean,
    ): UpdateRetryAction? {
        if (!retryable) return null
        return when (stage) {
            UpdateStage.CHECK,
            UpdateStage.PARSE,
            UpdateStage.ASSET_SELECTION -> UpdateRetryAction.CHECK
            UpdateStage.DOWNLOAD -> UpdateRetryAction.DOWNLOAD.takeIf { hasRelease }
            UpdateStage.INSTALL -> UpdateRetryAction.INSTALL.takeIf { hasRelease && hasArtifact }
        }
    }

    fun isRetryable(stage: UpdateStage, error: UpdateError): Boolean = when (stage) {
        UpdateStage.CHECK -> error in setOf(
            UpdateError.NoConnection,
            UpdateError.Timeout,
            UpdateError.RateLimited,
            UpdateError.Server,
        )
        UpdateStage.PARSE,
        UpdateStage.ASSET_SELECTION -> true
        UpdateStage.DOWNLOAD -> error in setOf(
            UpdateError.DownloadFailed,
            UpdateError.DownloadCancelled,
            UpdateError.DownloadedFileMissing,
            UpdateError.DownloadNoConnection,
            UpdateError.DownloadNotEnoughStorage,
            UpdateError.DownloadStorageUnavailable,
            UpdateError.DownloadServer,
        )
        UpdateStage.INSTALL -> error in setOf(
            UpdateError.InstallPermissionDenied,
            UpdateError.InstallCancelled,
            UpdateError.InstallFailed,
        )
    }

    fun installCallbackMatches(
        expectedReleaseId: String?,
        expectedSessionId: Int?,
        callbackReleaseId: String?,
        callbackSessionId: Int?,
    ): Boolean = expectedReleaseId != null &&
        expectedReleaseId == callbackReleaseId &&
        (expectedSessionId == null || expectedSessionId == callbackSessionId)

    fun isNewer(
        installedVersionName: String,
        installedBuildNumber: Long?,
        release: UpdateRelease,
    ): Boolean {
        return when (compareSemanticVersions(release.versionName, installedVersionName)) {
            1 -> true
            -1 -> false
            else -> release.buildNumber != null && release.buildNumber > (installedBuildNumber ?: -1L)
        }
    }

    fun compareSemanticVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val length = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until length) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        return 0
    }

    fun isCompatibleArchive(
        archivePackageName: String?,
        archiveVersionCode: Long,
        installedPackageName: String,
        installedVersionCode: Long,
    ): Boolean = archivePackageName == installedPackageName && archiveVersionCode > installedVersionCode

    fun isCompatibleArchive(
        archivePackageName: String?,
        archiveVersionName: String?,
        archiveVersionCode: Long,
        installedPackageName: String,
        installedVersionCode: Long,
        releaseVersionName: String,
        expectedVersionCode: Long? = null,
    ): Boolean = archiveVersionName == releaseVersionName &&
        (expectedVersionCode == null || archiveVersionCode == expectedVersionCode) &&
        isCompatibleArchive(
            archivePackageName = archivePackageName,
            archiveVersionCode = archiveVersionCode,
            installedPackageName = installedPackageName,
            installedVersionCode = installedVersionCode,
        )

    /**
     * commitStarted records intent, not proof that Session.commit() ran. When the platform can
     * report commit state, an uncommitted and unsealed session must be recommitted.
     */
    fun installRecoveryState(
        snapshot: UpdateInstallSessionSnapshot?,
        pendingIntentPersisted: Boolean,
        commitStarted: Boolean,
    ): UpdateInstallRecoveryState = when {
        snapshot == null -> UpdateInstallRecoveryState.MISSING
        snapshot.terminal -> UpdateInstallRecoveryState.TERMINAL
        pendingIntentPersisted -> UpdateInstallRecoveryState.AWAITING_USER_ACTION
        snapshot.commitStateKnown -> when {
            snapshot.committed || snapshot.sealed -> UpdateInstallRecoveryState.COMMITTED
            commitStarted -> UpdateInstallRecoveryState.COMMIT_UNCERTAIN
            else -> UpdateInstallRecoveryState.PREPARED
        }
        commitStarted -> UpdateInstallRecoveryState.COMMIT_UNCERTAIN
        snapshot.committed || snapshot.sealed -> UpdateInstallRecoveryState.COMMITTED
        else -> UpdateInstallRecoveryState.PREPARED
    }

    fun installRecoveryAction(
        snapshot: UpdateInstallSessionSnapshot?,
        pendingIntentPersisted: Boolean,
        commitStarted: Boolean,
    ): UpdateInstallRecoveryAction = when (installRecoveryState(snapshot, pendingIntentPersisted, commitStarted)) {
        UpdateInstallRecoveryState.COMMITTED,
        UpdateInstallRecoveryState.AWAITING_USER_ACTION -> UpdateInstallRecoveryAction.RESUME
        UpdateInstallRecoveryState.COMMIT_UNCERTAIN -> UpdateInstallRecoveryAction.RECOMMIT
        UpdateInstallRecoveryState.MISSING,
        UpdateInstallRecoveryState.PREPARED,
        UpdateInstallRecoveryState.TERMINAL -> UpdateInstallRecoveryAction.RETRY
    }

    fun shouldDiscardPersistedRelease(
        persistedReleaseId: String?,
        remoteReleaseId: String,
    ): Boolean = persistedReleaseId != remoteReleaseId

    private fun versionParts(value: String): List<Int> = semanticVersion.find(value)?.groupValues?.get(1)
        ?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    fun selectAsset(release: UpdateRelease?, supportedAbis: List<String>): Result<UpdateAsset> {
        if (release == null) return Result.failure(UpdateException(UpdateError.UnexpectedResponse, stage = UpdateStage.ASSET_SELECTION))
        supportedAbis.forEach { abi ->
            val expectedName = abiAssetNames[abi] ?: return@forEach
            release.assets.firstOrNull { it.name == expectedName && isDownloadableApk(it) }?.let {
                return Result.success(it)
            }
        }
        release.assets.firstOrNull { it.name == UNIVERSAL_APK_NAME && isDownloadableApk(it) }?.let {
            return Result.success(it)
        }
        return Result.failure(UpdateException(UpdateError.MissingApk, stage = UpdateStage.ASSET_SELECTION))
    }

    private fun isDownloadableApk(asset: UpdateAsset): Boolean {
        if (asset.downloadUrl.isBlank()) return false
        return asset.contentType.isNullOrBlank() || asset.contentType.equals("application/vnd.android.package-archive", true) ||
            asset.contentType.equals("application/octet-stream", true)
    }

    private const val UNIVERSAL_APK_NAME = "app-release.apk"
    private val abiAssetNames = mapOf(
        "arm64-v8a" to "app-arm64-v8a-release.apk",
        "armeabi-v7a" to "app-armeabi-v7a-release.apk",
        "x86" to "app-x86-release.apk",
        "x86_64" to "app-x86_64-release.apk",
    )
}

object UpdateErrorMapper {
    fun fromHttpCode(code: Int): UpdateError = when {
        code == 403 || code == 429 -> UpdateError.RateLimited
        code == 404 -> UpdateError.NotFound
        code in 500..599 -> UpdateError.Server
        else -> UpdateError.InvalidResponse
    }

    fun fromJson(raw: String): UpdateError = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }
        .fold(onSuccess = { UpdateError.UnexpectedResponse }, onFailure = { UpdateError.InvalidResponse })

    fun fromThrowable(error: Throwable): UpdateError = when (error) {
        is UpdateException -> error.error
        is java.net.SocketTimeoutException -> UpdateError.Timeout
        is java.io.IOException -> if (error.message.orEmpty().contains("timed out", ignoreCase = true) ||
            error.message.orEmpty().contains("timeout", ignoreCase = true)
        ) UpdateError.Timeout else UpdateError.NoConnection
        is java.net.UnknownHostException,
        is java.net.ConnectException -> UpdateError.NoConnection
        else -> UpdateError.InvalidResponse
    }

    fun fromDownloadThrowable(error: Throwable): UpdateError = when (error) {
        is UpdateException -> error.error
        is java.io.FileNotFoundException -> UpdateError.DownloadedFileMissing
        is java.io.IOException -> UpdateError.DownloadFailed
        else -> UpdateError.DownloadFailed
    }

    fun fromDownloadReason(reason: Int?): UpdateError = when (reason) {
        android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE -> UpdateError.DownloadNotEnoughStorage
        android.app.DownloadManager.ERROR_DEVICE_NOT_FOUND,
        android.app.DownloadManager.ERROR_FILE_ERROR,
        android.app.DownloadManager.ERROR_FILE_ALREADY_EXISTS,
        -> UpdateError.DownloadStorageUnavailable
        android.app.DownloadManager.ERROR_HTTP_DATA_ERROR,
        android.app.DownloadManager.ERROR_TOO_MANY_REDIRECTS,
        android.app.DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
        -> UpdateError.DownloadServer
        android.app.DownloadManager.ERROR_CANNOT_RESUME -> UpdateError.DownloadFailed
        in 400..599 -> UpdateError.DownloadServer
        else -> UpdateError.DownloadFailed
    }

    fun fromInstallThrowable(error: Throwable): UpdateError = when (error) {
        is UpdateException -> error.error
        is SecurityException -> UpdateError.InstallPermissionDenied
        is java.io.FileNotFoundException -> UpdateError.DownloadedFileMissing
        is java.io.IOException -> UpdateError.InstallFailed
        else -> UpdateError.InstallFailed
    }
}
