package com.github.andreyasadchy.xtra.ui.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.widget.PopupMenu
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.SheetUpdateDetailsBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.updater.UpdateDiagnostics
import com.github.andreyasadchy.xtra.util.updater.UpdateRelease
import com.github.andreyasadchy.xtra.util.updater.UpdateReleaseHistory
import com.github.andreyasadchy.xtra.util.updater.UpdateSelectedAssetInfo
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.util.updater.UpdateVersionDisplay
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdateDetailsBottomSheet : BottomSheetDialogFragment() {
    private var _binding: SheetUpdateDetailsBinding? = null
    private val binding get() = _binding!!
    private val repository
        get() = (requireContext().applicationContext as XtraApp).xtraModule.updateRepository
    private var diagnosticsExpanded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetUpdateDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.let { parent ->
            BottomSheetBehavior.from(parent).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        binding.diagnosticsButton.setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            render(repository.state.value)
        }
        binding.diagnosticsText.setOnClickListener { copyDiagnostics() }
        binding.copyDiagnosticsButton.setOnClickListener { copyDiagnostics() }
        binding.detailsPrimaryButton.setOnClickListener { perform(repository.state.value.toUiModel(repository.selectedAssetInfo()).primaryAction) }
        binding.detailsSecondaryButton.setOnClickListener {
            val action = repository.state.value.toUiModel(repository.selectedAssetInfo()).secondaryAction
            if (action != null) perform(action)
        }
        binding.detailsOverflowButton.setOnClickListener { showOverflowMenu() }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repository.state.collectLatest(::render) }
                launch { repository.releaseHistory.collectLatest { render(repository.state.value) } }
                launch { repository.releaseHistoryComplete.collectLatest { render(repository.state.value) } }
            }
        }
    }

    private fun render(state: UpdateState) {
        if (_binding == null) return
        val model = state.toUiModel(repository.selectedAssetInfo())
        val release = model.release
        binding.detailsTitle.text = getString(model.titleRes)
        binding.detailsVersion.text = release?.displayVersion ?: getString(
            R.string.update_version,
            UpdateVersionDisplay.installed(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.CI_VERSION_CODE_BASE.toLong()),
        )
        binding.detailsMeta.text = release?.let { releaseMeta(it, model.selectedAsset) }.orEmpty()
        binding.detailsStatus.text = statusText(model)
        binding.detailsProgressView.root.visibility = if (model.status == UpdateUiStatus.DOWNLOADING) View.VISIBLE else View.GONE
        UpdateStatusBinder.bindDownloadProgress(
            requireContext(),
            binding.detailsProgressView.downloadProgress,
            binding.detailsProgressView.downloadBytes,
            binding.detailsProgressView.downloadRate,
            model.progress,
            model.downloadManagerStatus,
        )
        val showNotes = model.showReleaseNotes && release != null
        binding.detailsNotesTitle.visibility = if (showNotes) View.VISIBLE else View.GONE
        binding.detailsNotesContainer.visibility = if (showNotes) View.VISIBLE else View.GONE
        if (showNotes) {
            val notes = UpdateReleaseHistory.notesForUpdate(
                historyComplete = repository.releaseHistoryComplete.value,
                cumulativeReleases = repository.releasesSinceInstalled(release),
                latestRelease = release,
            )
            UpdateNotesBinder.bindHistory(binding.detailsNotesContainer, notes)
        } else {
            binding.detailsNotesContainer.removeAllViews()
        }
        binding.earlierChangesButton.visibility = View.GONE
        binding.earlierChangesContainer.visibility = View.GONE
        binding.earlierChangesContainer.removeAllViews()
        binding.diagnosticsButton.visibility = if (model.showDiagnostics) View.VISIBLE else View.GONE
        binding.diagnosticsText.visibility = if (diagnosticsExpanded && model.showDiagnostics) View.VISIBLE else View.GONE
        binding.copyDiagnosticsButton.visibility = if (diagnosticsExpanded && model.showDiagnostics) View.VISIBLE else View.GONE
        binding.diagnosticsButton.text = getString(
            if (diagnosticsExpanded) R.string.hide_technical_details else R.string.update_diagnostics,
        )
        if (diagnosticsExpanded) binding.diagnosticsText.text = UpdateDiagnostics.format(requireContext(), repository.diagnostics()) +
            "\n\n" + getString(R.string.copy_diagnostics)
        bindActions(model)
    }

    private fun bindActions(model: UpdateUiModel) {
        val primary = model.primaryAction
        binding.detailsPrimaryButton.visibility = if (primary == null) View.GONE else View.VISIBLE
        binding.detailsPrimaryButton.text = actionText(primary)
        val secondary = model.secondaryAction
        binding.detailsSecondaryButton.visibility = if (secondary == null) View.GONE else View.VISIBLE
        binding.detailsSecondaryButton.text = actionText(secondary)
        val overflow = model.overflowActions.isNotEmpty()
        binding.detailsOverflowButton.visibility = if (overflow) View.VISIBLE else View.GONE
    }

    private fun perform(action: UpdateUiAction?) {
        when (action) {
            UpdateUiAction.Check -> repository.check(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), C.DEFAULT_UPDATE_URL)
            UpdateUiAction.Download -> repository.downloadCurrent()
            UpdateUiAction.RestartDownload -> repository.restartDownload()
            UpdateUiAction.CancelDownload -> repository.cancelDownload()
            UpdateUiAction.Retry -> repository.retry()
            UpdateUiAction.NotNow -> (repository.state.value as? UpdateState.Available)?.release?.let(repository::defer)
            UpdateUiAction.SkipVersion -> (repository.state.value as? UpdateState.Available)?.release?.let(repository::skip)
            UpdateUiAction.UndoSkip -> repository.undoSkip()
            UpdateUiAction.Install -> install()
            UpdateUiAction.ContinueInstall -> repository.launchPendingInstall()
            null -> Unit
        }
    }

    private fun showOverflowMenu() {
        val menu = PopupMenu(requireContext(), binding.detailsOverflowButton)
        repository.state.value.toUiModel(repository.selectedAssetInfo()).overflowActions.forEach { action ->
            menu.menu.add(actionText(action))
                .setOnMenuItemClickListener {
                    perform(action)
                    true
                }
        }
        menu.show()
    }

    private fun install() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !requireContext().packageManager.canRequestPackageInstalls() &&
            repository.state.value is UpdateState.Error
        ) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${requireContext().packageName}".toUri()))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.update_error_install, Toast.LENGTH_SHORT).show()
            }
        } else {
            repository.refreshInstallPermission()
            repository.install()
        }
    }

    private fun copyDiagnostics() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.update_diagnostics),
                UpdateDiagnostics.format(requireContext(), repository.diagnostics()),
            ),
        )
        Toast.makeText(requireContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun releaseMeta(release: UpdateRelease, selectedAsset: UpdateSelectedAssetInfo?): String {
        val size = selectedAsset?.size?.let { Formatter.formatFileSize(requireContext(), it) }
        val date = release.publishedAt?.substringBefore('T')
        return listOfNotNull(size, date).joinToString(getString(R.string.update_meta_separator))
    }

    private fun statusText(model: UpdateUiModel): String = when (model.status) {
        UpdateUiStatus.DOWNLOADING -> model.statusMessageRes?.let(::getString)
            ?: UpdateStatusBinder.downloadStatusText(requireContext(), model.downloadManagerStatus, model.downloadManagerReason)
        UpdateUiStatus.CURRENT -> getString(R.string.update_up_to_date)
        UpdateUiStatus.IDLE,
        UpdateUiStatus.AVAILABLE,
        -> ""
        else -> model.statusMessageRes?.let(::getString).orEmpty()
    }

    private fun actionText(action: UpdateUiAction?): String = getString(
        when (action) {
            UpdateUiAction.Check -> R.string.check_for_updates
            UpdateUiAction.Download -> R.string.download_update
            UpdateUiAction.RestartDownload -> R.string.restart_download
            UpdateUiAction.CancelDownload -> R.string.cancel
            UpdateUiAction.Install -> R.string.install_update
            UpdateUiAction.ContinueInstall -> R.string.continue_install
            UpdateUiAction.Retry -> R.string.retry
            UpdateUiAction.NotNow -> R.string.update_not_now
            UpdateUiAction.SkipVersion -> R.string.skip_version
            UpdateUiAction.UndoSkip -> R.string.undo_skip
            null -> R.string.cancel
        },
    )

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "UpdateDetailsBottomSheet"
    }
}
