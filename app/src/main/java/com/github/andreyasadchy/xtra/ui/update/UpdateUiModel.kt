package com.github.andreyasadchy.xtra.ui.update

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.updater.DownloadProgress
import com.github.andreyasadchy.xtra.util.updater.UpdateError
import com.github.andreyasadchy.xtra.util.updater.UpdatePrimaryAction
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.util.updater.UpdateSelectedAssetInfo
import com.github.andreyasadchy.xtra.util.updater.primaryAction
import com.github.andreyasadchy.xtra.util.updater.retryAction

enum class UpdateUiStatus {
    IDLE,
    CHECKING,
    CURRENT,
    AVAILABLE,
    DOWNLOADING,
    READY,
    VERIFYING,
    INSTALLING,
    AWAITING_USER_ACTION,
    ERROR,
    SKIPPED,
    DEFERRED,
}

sealed interface UpdateUiAction {
    data object Check : UpdateUiAction
    data object Download : UpdateUiAction
    data object CancelDownload : UpdateUiAction
    data object Install : UpdateUiAction
    data object ContinueInstall : UpdateUiAction
    data object Retry : UpdateUiAction
    data object NotNow : UpdateUiAction
    data object SkipVersion : UpdateUiAction
    data object UndoSkip : UpdateUiAction
}

data class UpdateUiModel(
    val status: UpdateUiStatus,
    val titleRes: Int = R.string.update_available,
    val release: com.github.andreyasadchy.xtra.util.updater.UpdateRelease? = null,
    val selectedAsset: UpdateSelectedAssetInfo? = null,
    val progress: DownloadProgress? = null,
    val downloadManagerStatus: Int? = null,
    val downloadManagerReason: Int? = null,
    val error: UpdateError? = null,
    val primaryAction: UpdateUiAction? = null,
    val secondaryAction: UpdateUiAction? = null,
    val overflowActions: List<UpdateUiAction> = emptyList(),
    val showReleaseNotes: Boolean = false,
    val showDiagnostics: Boolean = false,
)

fun UpdateState.toUiModel(selectedAsset: UpdateSelectedAssetInfo? = null): UpdateUiModel {
    val model = when (this) {
    UpdateState.Idle -> UpdateUiModel(UpdateUiStatus.IDLE, primaryAction = UpdateUiAction.Check)
    UpdateState.Checking -> UpdateUiModel(UpdateUiStatus.CHECKING, titleRes = R.string.update_checking, primaryAction = null)
    is UpdateState.UpToDate -> UpdateUiModel(UpdateUiStatus.CURRENT, titleRes = R.string.update_up_to_date, release = release, primaryAction = UpdateUiAction.Check)
    is UpdateState.Available -> UpdateUiModel(
        status = UpdateUiStatus.AVAILABLE,
        release = release,
        primaryAction = UpdateUiAction.Download,
        secondaryAction = if (previouslySkipped) UpdateUiAction.UndoSkip else UpdateUiAction.NotNow,
        overflowActions = if (previouslySkipped) emptyList() else listOf(UpdateUiAction.SkipVersion),
        showReleaseNotes = true,
    )
    is UpdateState.Skipped -> UpdateUiModel(
        status = UpdateUiStatus.SKIPPED,
        release = release,
        primaryAction = UpdateUiAction.Check,
        secondaryAction = UpdateUiAction.UndoSkip,
        showReleaseNotes = true,
    )
    is UpdateState.Deferred -> UpdateUiModel(
        status = UpdateUiStatus.DEFERRED,
        release = release,
        primaryAction = UpdateUiAction.Download,
        secondaryAction = UpdateUiAction.Check,
        showReleaseNotes = true,
    )
    is UpdateState.Downloading -> UpdateUiModel(
        status = UpdateUiStatus.DOWNLOADING,
        titleRes = R.string.downloading_update,
        release = release,
        progress = progress,
        downloadManagerStatus = downloadManagerStatus,
        downloadManagerReason = downloadManagerReason,
        primaryAction = UpdateUiAction.CancelDownload,
        showReleaseNotes = true,
        showDiagnostics = true,
    )
    is UpdateState.Downloaded -> UpdateUiModel(
        status = UpdateUiStatus.READY,
        titleRes = R.string.update_ready_title,
        release = release,
        primaryAction = UpdateUiAction.Install,
        showReleaseNotes = true,
    )
    is UpdateState.Installing -> UpdateUiModel(
        status = if (sessionId == null) UpdateUiStatus.VERIFYING else UpdateUiStatus.INSTALLING,
        titleRes = if (sessionId == null) R.string.update_verifying else R.string.update_installing,
        release = release,
        primaryAction = null,
        showReleaseNotes = true,
        showDiagnostics = true,
    )
    is UpdateState.AwaitingUserAction -> UpdateUiModel(
        status = UpdateUiStatus.AWAITING_USER_ACTION,
        titleRes = R.string.update_awaiting_user_action,
        release = release,
        primaryAction = UpdateUiAction.ContinueInstall,
        showReleaseNotes = true,
        showDiagnostics = true,
    )
    is UpdateState.Error -> UpdateUiModel(
        status = UpdateUiStatus.ERROR,
        titleRes = errorTitleRes(cause),
        release = release,
        error = cause,
        primaryAction = when {
            primaryAction() == UpdatePrimaryAction.ALLOW_INSTALL -> UpdateUiAction.Install
            primaryAction() == UpdatePrimaryAction.INSTALL -> UpdateUiAction.Install
            retryAction() != null -> UpdateUiAction.Retry
            else -> null
        },
        showReleaseNotes = release != null,
        showDiagnostics = true,
        downloadManagerReason = downloadManagerReason,
    )
    }
    return model.copy(selectedAsset = selectedAsset)
}

private fun errorTitleRes(error: UpdateError): Int = when (error) {
    UpdateError.NoConnection,
    UpdateError.DownloadNoConnection,
    -> R.string.update_download_failed_connection
    UpdateError.DownloadNotEnoughStorage -> R.string.update_download_failed_storage
    UpdateError.DownloadStorageUnavailable -> R.string.update_download_storage_unavailable
    UpdateError.InstallPermissionDenied -> R.string.update_install_permission_title
    UpdateError.InstallCancelled,
    UpdateError.InstallFailed,
    -> R.string.update_install_failed_title
    UpdateError.IncompatibleApk -> R.string.update_verification_failed_title
    else -> R.string.update_download_failed_server
}
