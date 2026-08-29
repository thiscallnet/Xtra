package com.github.andreyasadchy.xtra.util.updater

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class UpdateAsset(
    val name: String,
    val contentType: String?,
    val downloadUrl: String,
    val size: Long?,
)

@Serializable
data class UpdateRelease(
    val tagName: String,
    val versionName: String,
    val buildNumber: Long?,
    val title: String,
    val releaseNotes: List<String>,
    val rawBody: String,
    val releaseUrl: String,
    val publishedAt: String?,
    val assets: List<UpdateAsset>,
    val prerelease: Boolean,
    val draft: Boolean,
    val expectedVersionCode: Long? = null,
    val expectedSha256: String? = null,
    val artifactSha256: Map<String, String> = emptyMap(),
    val releaseNoteKinds: List<ChangeKind> = emptyList(),
) {
    val id: String
        get() = tagName

    val displayVersion: String
        get() = buildNumber?.let { "$versionName (build $it)" }
            ?: versionName

    fun expectedSha256For(asset: UpdateAsset): String? = artifactSha256[asset.name]
        ?: expectedSha256.takeIf { asset.name == "app-release.apk" }

    val structuredReleaseNotes: StructuredReleaseNotes
        get() = StructuredReleaseNotes(
            releaseNotes.mapIndexed { index, text ->
                ChangeItem(text, releaseNoteKinds.getOrNull(index) ?: ReleaseNotes.kindFor(text))
            },
        )

}

@Serializable
data class CachedUpdateRelease(
    val tagName: String,
    val versionName: String,
    val buildNumber: Long?,
    val releaseNotes: List<String>,
    val releaseNoteKinds: List<ChangeKind> = emptyList(),
)

fun UpdateRelease.toCachedHistory(): CachedUpdateRelease = CachedUpdateRelease(
    tagName = tagName,
    versionName = versionName,
    buildNumber = buildNumber,
    releaseNotes = releaseNotes,
    releaseNoteKinds = releaseNoteKinds,
)

fun CachedUpdateRelease.toUpdateRelease(): UpdateRelease = UpdateRelease(
    tagName = tagName,
    versionName = versionName,
    buildNumber = buildNumber,
    title = "",
    releaseNotes = releaseNotes,
    releaseNoteKinds = releaseNoteKinds,
    rawBody = "",
    releaseUrl = "",
    publishedAt = null,
    assets = emptyList(),
    prerelease = false,
    draft = false,
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val stalled: Boolean = false,
) {
    val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let {
                (downloadedBytes.toDouble() / it.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }

    val percent: Int?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { ((downloadedBytes * 100L) / it).toInt().coerceIn(0, 99) }
}

data class DownloadedArtifact(
    val downloadId: Long,
    val uri: Uri?,
    val fileName: String,
    val size: Long,
)

data class UpdateSelectedAssetInfo(
    val name: String,
    val size: Long?,
)

object UpdateVersionDisplay {
    fun installed(versionName: String, versionCode: Long, versionCodeBase: Long): String {
        val buildNumber = installedBuildNumber(versionCode, versionCodeBase)
        return buildNumber?.let { "$versionName (build $it)" } ?: versionName
    }

    fun installedBuildNumber(versionCode: Long, versionCodeBase: Long): Long? =
        (versionCode - versionCodeBase).takeIf { it > 0L }
}

enum class UpdateStage {
    CHECK,
    PARSE,
    ASSET_SELECTION,
    DOWNLOAD,
    INSTALL,
}

enum class UpdateErrorTitle {
    CHECK,
    PARSE,
    ASSET_SELECTION,
    DOWNLOAD,
    INSTALL,
}

fun UpdateStage.errorTitle(): UpdateErrorTitle = when (this) {
    UpdateStage.CHECK -> UpdateErrorTitle.CHECK
    UpdateStage.PARSE -> UpdateErrorTitle.PARSE
    UpdateStage.ASSET_SELECTION -> UpdateErrorTitle.ASSET_SELECTION
    UpdateStage.DOWNLOAD -> UpdateErrorTitle.DOWNLOAD
    UpdateStage.INSTALL -> UpdateErrorTitle.INSTALL
}

sealed interface UpdateError {
    data object NoConnection : UpdateError
    data object Timeout : UpdateError
    data object RateLimited : UpdateError
    data object NotFound : UpdateError
    data object Server : UpdateError
    data object InvalidResponse : UpdateError
    data object UnexpectedResponse : UpdateError
    data object MissingApk : UpdateError
    data object AmbiguousApk : UpdateError
    data object IncompatibleApk : UpdateError
    data object DownloadFailed : UpdateError
    data object DownloadNoConnection : UpdateError
    data object DownloadNotEnoughStorage : UpdateError
    data object DownloadStorageUnavailable : UpdateError
    data object DownloadServer : UpdateError
    data object DownloadCancelled : UpdateError
    data object DownloadedFileMissing : UpdateError
    data object InstallPermissionDenied : UpdateError
    data object InstallCancelled : UpdateError
    data object InstallFailed : UpdateError
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(
        val release: UpdateRelease? = null,
        val lastSuccessfulCheck: Long? = null,
    ) : UpdateState

    data class Available(
        val release: UpdateRelease,
        val previouslySkipped: Boolean = false,
        val previouslyDeferred: Boolean = false,
    ) : UpdateState
    data class Skipped(val release: UpdateRelease) : UpdateState
    data class Deferred(val release: UpdateRelease) : UpdateState

    data class Downloading(
        val release: UpdateRelease,
        val progress: DownloadProgress?,
        val downloadManagerStatus: Int? = null,
        val downloadManagerReason: Int? = null,
    ) : UpdateState

    data class Downloaded(
        val release: UpdateRelease,
        val artifact: DownloadedArtifact,
    ) : UpdateState

    data class Installing(
        val release: UpdateRelease,
        val artifact: DownloadedArtifact?,
        val sessionId: Int?,
    ) : UpdateState

    data class AwaitingUserAction(
        val release: UpdateRelease,
        val artifact: DownloadedArtifact?,
        val sessionId: Int,
    ) : UpdateState

    data class Error(
        val stage: UpdateStage,
        val cause: UpdateError,
        val retryable: Boolean,
        val release: UpdateRelease? = null,
        val artifact: DownloadedArtifact? = null,
        val preservedAction: UpdatePrimaryAction? = null,
        val downloadManagerReason: Int? = null,
    ) : UpdateState
}

sealed interface UpdateDecision {
    data object Available : UpdateDecision
    data object Current : UpdateDecision
    data object Ignored : UpdateDecision
    data object Deferred : UpdateDecision
}

enum class UpdateRetryAction {
    CHECK,
    DOWNLOAD,
    INSTALL,
}

enum class UpdatePrimaryAction {
    DOWNLOAD,
    INSTALL,
    ALLOW_INSTALL,
}

enum class UpdateInstallRecoveryAction {
    RESUME,
    RECOMMIT,
    RETRY,
}

enum class UpdateInstallRecoveryState {
    MISSING,
    PREPARED,
    COMMIT_UNCERTAIN,
    COMMITTED,
    AWAITING_USER_ACTION,
    TERMINAL,
}

class UpdateInstallGate(initialSessionId: Int? = null) {
    private var preparing = false
    private var activeSessionId: Int? = initialSessionId

    @Synchronized
    fun tryBegin(): Boolean {
        if (preparing || activeSessionId != null) return false
        preparing = true
        return true
    }

    @Synchronized
    fun markCommitted(sessionId: Int) {
        preparing = false
        activeSessionId = sessionId
    }

    @Synchronized
    fun abort() {
        preparing = false
    }

    @Synchronized
    fun finish(sessionId: Int?) {
        if (sessionId == null || activeSessionId == sessionId) {
            activeSessionId = null
            preparing = false
        }
    }
}

fun UpdateState.Error.retryAction(): UpdateRetryAction? = UpdatePolicy.retryAction(
    stage = stage,
    retryable = retryable,
    hasRelease = release != null,
    hasArtifact = artifact != null,
)

fun UpdateState.Error.primaryAction(): UpdatePrimaryAction? = UpdatePolicy.primaryAction(this)

fun UpdateState.downloadableRelease(): UpdateRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Deferred -> release
    is UpdateState.Error -> release?.takeIf { primaryAction() == UpdatePrimaryAction.DOWNLOAD }
    else -> null
}

/** Whether Settings should call attention to an update that still has a user action. */
fun UpdateState.hasActionableUpdate(): Boolean = when (this) {
    is UpdateState.Available,
    is UpdateState.Downloading,
    is UpdateState.Downloaded,
    is UpdateState.Installing,
    is UpdateState.AwaitingUserAction -> true
    is UpdateState.Error -> when {
        // A failed download still owns the persisted release and can be retried even though it
        // does not use primaryAction(), which is reserved for preserved UI actions.
        retryAction() == UpdateRetryAction.DOWNLOAD && release != null -> true
        primaryAction() == UpdatePrimaryAction.DOWNLOAD -> downloadableRelease() != null
        primaryAction() == UpdatePrimaryAction.INSTALL ||
            primaryAction() == UpdatePrimaryAction.ALLOW_INSTALL -> release != null && artifact != null
        else -> false
    }
    is UpdateState.Idle,
    is UpdateState.Checking,
    is UpdateState.UpToDate,
    is UpdateState.Skipped,
    is UpdateState.Deferred -> false
}

fun UpdatePolicy.restoreAfterInstallPermission(state: UpdateState, permissionGranted: Boolean): UpdateState =
    if (permissionGranted && state is UpdateState.Error &&
        state.stage == UpdateStage.INSTALL &&
        state.cause == UpdateError.InstallPermissionDenied &&
        state.release != null && state.artifact != null
    ) {
        UpdateState.Downloaded(state.release, state.artifact)
    } else {
        state
    }

sealed interface ReleaseParseResult {
    data class Success(val release: UpdateRelease) : ReleaseParseResult
    data class Failure(val error: UpdateError) : ReleaseParseResult
}

class UpdateException(
    val error: UpdateError,
    cause: Throwable? = null,
    val stage: UpdateStage? = null,
) : Exception(error.toString(), cause)
