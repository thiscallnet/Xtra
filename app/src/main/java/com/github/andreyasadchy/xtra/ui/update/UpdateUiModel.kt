package com.github.andreyasadchy.xtra.ui.update

import android.app.DownloadManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.updater.DownloadProgress
import com.github.andreyasadchy.xtra.util.updater.UpdateError
import com.github.andreyasadchy.xtra.util.updater.UpdatePrimaryAction
import com.github.andreyasadchy.xtra.util.updater.UpdateStage
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.util.updater.UpdateSelectedAssetInfo
import com.github.andreyasadchy.xtra.util.updater.primaryAction
import com.github.andreyasadchy.xtra.util.updater.retryAction
import androidx.annotation.StringRes

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
    data object RestartDownload : UpdateUiAction
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
    @StringRes
    val titleRes: Int = R.string.update_available,
    val release: com.github.andreyasadchy.xtra.util.updater.UpdateRelease? = null,
    val selectedAsset: UpdateSelectedAssetInfo? = null,
    @StringRes val statusMessageRes: Int? = null,
    val progress: DownloadProgress? = null,
    val downloadManagerStatus: Int? = null,
    val downloadManagerReason: Int? = null,
    val error: UpdateError? = null,
    val errorUi: UpdateErrorUi? = null,
    val primaryAction: UpdateUiAction? = null,
    val secondaryAction: UpdateUiAction? = null,
    val overflowActions: List<UpdateUiAction> = emptyList(),
    val showReleaseNotes: Boolean = false,
    val showDiagnostics: Boolean = false,
)

data class UpdateErrorUi(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
)

fun UpdateState.toUiModel(selectedAsset: UpdateSelectedAssetInfo? = null): UpdateUiModel {
    val model = when (this) {
    UpdateState.Idle -> UpdateUiModel(UpdateUiStatus.IDLE, primaryAction = UpdateUiAction.Check)
    UpdateState.Checking -> UpdateUiModel(
        UpdateUiStatus.CHECKING,
        titleRes = R.string.update_checking,
        statusMessageRes = R.string.update_checking,
        primaryAction = null,
    )
    is UpdateState.UpToDate -> UpdateUiModel(
        UpdateUiStatus.CURRENT,
        titleRes = R.string.update_up_to_date,
        release = release,
        primaryAction = UpdateUiAction.Check,
        statusMessageRes = R.string.update_up_to_date,
    )
    is UpdateState.Available -> UpdateUiModel(
        status = UpdateUiStatus.AVAILABLE,
        release = release,
        primaryAction = UpdateUiAction.Download,
        secondaryAction = if (previouslySkipped) UpdateUiAction.UndoSkip else UpdateUiAction.NotNow,
        overflowActions = if (previouslySkipped) emptyList() else listOf(UpdateUiAction.SkipVersion),
        statusMessageRes = when {
            previouslySkipped -> R.string.update_previously_skipped
            previouslyDeferred -> R.string.update_deferred
            else -> null
        },
        showReleaseNotes = true,
    )
    is UpdateState.Skipped -> UpdateUiModel(
        status = UpdateUiStatus.SKIPPED,
        release = release,
        primaryAction = UpdateUiAction.Check,
        secondaryAction = UpdateUiAction.UndoSkip,
        statusMessageRes = R.string.update_previously_skipped,
        showReleaseNotes = true,
    )
    is UpdateState.Deferred -> UpdateUiModel(
        status = UpdateUiStatus.DEFERRED,
        release = release,
        primaryAction = UpdateUiAction.Download,
        secondaryAction = UpdateUiAction.Check,
        statusMessageRes = R.string.update_deferred,
        showReleaseNotes = true,
    )
    is UpdateState.Downloading -> {
        val canRestart = progress?.stalled == true ||
            downloadManagerStatus == DownloadManager.STATUS_PAUSED &&
            downloadManagerReason == DownloadManager.PAUSED_WAITING_TO_RETRY
        UpdateUiModel(
            status = UpdateUiStatus.DOWNLOADING,
            titleRes = R.string.downloading_update,
            release = release,
            progress = progress,
            downloadManagerStatus = downloadManagerStatus,
            downloadManagerReason = downloadManagerReason,
            statusMessageRes = if (progress?.stalled == true) R.string.update_download_stalled else null,
            primaryAction = if (canRestart) UpdateUiAction.RestartDownload else UpdateUiAction.CancelDownload,
            secondaryAction = if (canRestart) UpdateUiAction.CancelDownload else null,
            showReleaseNotes = true,
            showDiagnostics = true,
        )
    }
    is UpdateState.Downloaded -> UpdateUiModel(
        status = UpdateUiStatus.READY,
        titleRes = R.string.update_ready_title,
        release = release,
        statusMessageRes = R.string.update_downloaded_ready,
        primaryAction = UpdateUiAction.Install,
        showReleaseNotes = true,
    )
    is UpdateState.Installing -> UpdateUiModel(
        status = if (sessionId == null) UpdateUiStatus.VERIFYING else UpdateUiStatus.INSTALLING,
        titleRes = if (sessionId == null) R.string.update_verifying else R.string.update_installing,
        release = release,
        statusMessageRes = if (sessionId == null) R.string.update_verifying else R.string.update_installing,
        primaryAction = null,
        showReleaseNotes = true,
        showDiagnostics = true,
    )
    is UpdateState.AwaitingUserAction -> UpdateUiModel(
        status = UpdateUiStatus.AWAITING_USER_ACTION,
        titleRes = R.string.update_awaiting_user_action,
        release = release,
        statusMessageRes = R.string.update_awaiting_user_action,
        primaryAction = UpdateUiAction.ContinueInstall,
        showReleaseNotes = true,
        showDiagnostics = true,
    )
    is UpdateState.Error -> UpdateUiModel(
        status = UpdateUiStatus.ERROR,
        titleRes = toErrorUi().titleRes,
        release = release,
        error = cause,
        errorUi = toErrorUi(),
        primaryAction = when {
            primaryAction() == UpdatePrimaryAction.ALLOW_INSTALL -> UpdateUiAction.Install
            primaryAction() == UpdatePrimaryAction.INSTALL -> UpdateUiAction.Install
            retryAction() != null -> UpdateUiAction.Retry
            else -> null
        },
        showReleaseNotes = release != null,
        showDiagnostics = true,
        downloadManagerReason = downloadManagerReason,
        statusMessageRes = toErrorUi().messageRes,
    )
    }
    return model.copy(selectedAsset = selectedAsset)
}

fun UpdateState.Error.toErrorUi(): UpdateErrorUi = when (cause) {
    UpdateError.NoConnection -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_no_connection)
    UpdateError.Timeout -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_timeout)
    UpdateError.RateLimited -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_rate_limited)
    UpdateError.NotFound -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_not_found)
    UpdateError.Server -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_server)
    UpdateError.InvalidResponse,
    UpdateError.UnexpectedResponse,
    -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_invalid_response)
    UpdateError.MissingApk -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_missing_apk)
    UpdateError.AmbiguousApk -> UpdateErrorUi(stageTitleRes(stage), R.string.update_error_ambiguous_apk)
    UpdateError.IncompatibleApk -> UpdateErrorUi(
        R.string.update_verification_failed_title,
        R.string.update_verification_failed_message,
    )
    UpdateError.DownloadFailed,
    UpdateError.DownloadCancelled,
    UpdateError.DownloadedFileMissing,
    -> UpdateErrorUi(R.string.update_download_failed_title, R.string.update_download_failed_generic_message)
    UpdateError.DownloadNoConnection -> UpdateErrorUi(
        R.string.update_download_failed_connection,
        R.string.update_download_failed_connection_message,
    )
    UpdateError.DownloadNotEnoughStorage -> UpdateErrorUi(
        R.string.update_download_failed_storage,
        R.string.update_download_failed_storage_message,
    )
    UpdateError.DownloadStorageUnavailable -> UpdateErrorUi(
        R.string.update_download_storage_unavailable,
        R.string.update_download_storage_unavailable_message,
    )
    UpdateError.DownloadServer -> UpdateErrorUi(
        R.string.update_download_failed_server,
        R.string.update_download_failed_server_message,
    )
    UpdateError.InstallPermissionDenied -> UpdateErrorUi(
        R.string.update_install_permission_title,
        R.string.update_install_permission_message,
    )
    UpdateError.InstallCancelled -> UpdateErrorUi(
        R.string.update_install_failed_title,
        R.string.update_install_cancelled_message,
    )
    UpdateError.InstallFailed -> UpdateErrorUi(
        R.string.update_install_failed_title,
        R.string.update_install_failed_message,
    )
}

private fun stageTitleRes(stage: UpdateStage): Int = when (stage) {
    UpdateStage.CHECK -> R.string.update_check_failed
    UpdateStage.PARSE -> R.string.update_parse_failed
    UpdateStage.ASSET_SELECTION -> R.string.update_asset_selection_failed
    UpdateStage.DOWNLOAD -> R.string.update_download_failed_title
    UpdateStage.INSTALL -> R.string.update_install_failed_title
}
