package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.forEach
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentUpdateSettingsBinding
import com.github.andreyasadchy.xtra.ui.update.UpdateNotesBinder
import com.github.andreyasadchy.xtra.ui.update.UpdateStatusBinder
import com.github.andreyasadchy.xtra.ui.update.UpdateUiAction
import com.github.andreyasadchy.xtra.ui.update.UpdateUiMapper
import com.github.andreyasadchy.xtra.ui.update.UpdateUiModel
import com.github.andreyasadchy.xtra.ui.update.UpdateUiStatus
import com.github.andreyasadchy.xtra.util.updater.UpdateDiagnostics
import com.github.andreyasadchy.xtra.util.updater.UpdateReleaseHistory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.updater.UpdateCheckFrequency
import com.github.andreyasadchy.xtra.util.updater.UpdateCheckScheduler
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.util.updater.UpdateTimeFormatter
import com.github.andreyasadchy.xtra.util.updater.UpdateVersionDisplay
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdateSettingsFragment : Fragment() {

    private var _binding: FragmentUpdateSettingsBinding? = null
    private val binding get() = _binding!!
    private var technicalDetailsExpanded = false
    private var visibleHistoryCount = INITIAL_HISTORY_COUNT
    private lateinit var updateNotificationPermissionLauncher: ActivityResultLauncher<String>
    private val repository
        get() = (requireContext().applicationContext as XtraApp).xtraModule.updateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visibleHistoryCount = savedInstanceState?.getInt(KEY_VISIBLE_HISTORY_COUNT, INITIAL_HISTORY_COUNT)
            ?: INITIAL_HISTORY_COUNT
        updateNotificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            if (_binding != null) renderUpdateNotificationPermission()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUpdateSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preferences = requireContext().prefs()
        val frequencies = UpdateCheckFrequency.entries
        val selectedFrequency = UpdateCheckFrequency.fromPreference(
            preferences.getString(C.UPDATE_CHECK_FREQUENCY, null),
        )
        preferences.edit { putString(C.UPDATE_CHECK_FREQUENCY, selectedFrequency.preferenceValue) }
        binding.frequencyInput.setSimpleItems(frequencies.map { getString(it.labelRes) }.toTypedArray())
        binding.frequencyInput.setText(getString(selectedFrequency.labelRes), false)
        binding.frequencySummary.text = getString(
            R.string.update_check_frequency_description,
            getString(selectedFrequency.labelRes),
        )
        fun renderAutomaticSettings(enabled: Boolean) {
            binding.frequencyInputLayout.isEnabled = enabled
            binding.frequencyInput.isEnabled = enabled
        }
        val automaticChecksEnabled = preferences.getBoolean(C.UPDATE_CHECK_ENABLED, true)
        binding.automaticCheck.isChecked = automaticChecksEnabled
        renderAutomaticSettings(automaticChecksEnabled)
        binding.notificationPermissionButton.setOnClickListener {
            requestUpdateNotificationPermission()
        }
        renderUpdateNotificationPermission()
        binding.automaticCheck.setOnCheckedChangeListener { _, enabled ->
            preferences.edit { putBoolean(C.UPDATE_CHECK_ENABLED, enabled) }
            renderAutomaticSettings(enabled)
            renderUpdateNotificationPermission()
            if (enabled && updateNotificationsNeedUserAction()) requestUpdateNotificationPermission()
            UpdateCheckScheduler.schedule(requireContext())
        }
        binding.frequencyInput.setOnItemClickListener { _, _, position, _ ->
            val frequency = frequencies[position]
            preferences.edit { putString(C.UPDATE_CHECK_FREQUENCY, frequency.preferenceValue) }
            binding.frequencySummary.text = getString(
                R.string.update_check_frequency_description,
                getString(frequency.labelRes),
            )
            UpdateCheckScheduler.schedule(requireContext())
        }
        binding.primaryButton.setOnClickListener {
            performUpdateAction(UpdateUiMapper.map(repository.state.value, repository.selectedAssetInfo()).primaryAction)
        }
        binding.secondaryButton.setOnClickListener {
            performUpdateAction(UpdateUiMapper.map(repository.state.value, repository.selectedAssetInfo()).secondaryAction)
        }
        binding.updateOverflowButton.setOnClickListener { showUpdateOverflowMenu() }
        binding.earlierChangesButton.setOnClickListener {
            visibleHistoryCount += HISTORY_PAGE_SIZE
            render(repository.state.value)
        }
        binding.technicalDetailsToggle.setOnClickListener {
            technicalDetailsExpanded = !technicalDetailsExpanded
            render(repository.state.value)
        }
        binding.copyDiagnosticsButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(
                    getString(R.string.update_diagnostics),
                    UpdateDiagnostics.format(requireContext(), repository.diagnostics()),
                ),
            )
            Toast.makeText(requireContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repository.state.collectLatest(::render) }
                launch { repository.releaseHistory.collectLatest { render(repository.state.value) } }
                launch { repository.releaseHistoryComplete.collectLatest { render(repository.state.value) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        repository.refreshInstallPermission()
        repository.resumePendingInstall()
        if (_binding != null) renderUpdateNotificationPermission()
    }

    private fun renderUpdateNotificationPermission() {
        val automaticChecksEnabled = requireContext().prefs().getBoolean(C.UPDATE_CHECK_ENABLED, true)
        val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val needsUserAction = updateNotificationsNeedUserAction()
        binding.notificationPermissionButton.visibility = if (automaticChecksEnabled && needsUserAction) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.notificationPermissionButton.text = getString(
            if (permissionMissing) {
                R.string.update_notifications_enable
            } else {
                R.string.update_notifications_open_settings
            },
        )
    }

    private fun updateNotificationsNeedUserAction(): Boolean {
        val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val notificationsBlocked = !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        return needsUpdateNotificationUserAction(
            permissionMissing = permissionMissing,
            notificationsBlocked = notificationsBlocked,
            updatesChannelBlocked = isUpdatesNotificationChannelBlocked(),
        )
    }

    private fun requestUpdateNotificationPermission() {
        if (!updateNotificationsNeedUserAction()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.POST_NOTIFICATIONS)) {
                openUpdateNotificationSettings()
            } else {
                updateNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            openUpdateNotificationSettings()
        }
    }

    private fun openUpdateNotificationSettings() {
        val notificationsBlocked = !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        val intent = if (!notificationsBlocked && isUpdatesNotificationChannelBlocked()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, getString(R.string.notification_updates_channel_id))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        }
        runCatching {
            startActivity(intent)
        }
    }

    private fun isUpdatesNotificationChannelBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val notificationManager = requireContext().getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.getNotificationChannel(getString(R.string.notification_updates_channel_id))?.importance ==
            NotificationManager.IMPORTANCE_NONE
    }

    private fun performUpdateAction(action: UpdateUiAction?) {
        when (action) {
            UpdateUiAction.Check -> repository.check(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), C.DEFAULT_UPDATE_URL)
            UpdateUiAction.Download -> repository.downloadCurrent()
            UpdateUiAction.RestartDownload -> repository.restartDownload()
            UpdateUiAction.CancelDownload -> repository.cancelDownload()
            UpdateUiAction.Retry -> repository.retry()
            UpdateUiAction.NotNow -> (repository.state.value as? UpdateState.Available)?.release?.let(repository::defer)
            UpdateUiAction.SkipVersion -> (repository.state.value as? UpdateState.Available)?.release?.let(repository::skip)
            UpdateUiAction.UndoSkip -> repository.undoSkip()
            UpdateUiAction.Install -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !requireContext().packageManager.canRequestPackageInstalls() &&
                    repository.state.value is UpdateState.Error
                ) {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${requireContext().packageName}".toUri()))
                    }.onFailure { Toast.makeText(requireContext(), R.string.update_error_install, Toast.LENGTH_SHORT).show() }
                } else {
                    repository.refreshInstallPermission()
                    repository.install()
                }
            }
            UpdateUiAction.ContinueInstall -> repository.launchPendingInstall()
            null -> Unit
        }
    }

    private fun render(state: UpdateState) {
        renderModern(UpdateUiMapper.map(state, repository.selectedAssetInfo()))
    }

    private fun renderModern(model: UpdateUiModel) {
        val release = model.release
        binding.statusTitle.text = getString(model.titleRes)
        binding.versionText.text = release?.displayVersion ?: getString(
            R.string.update_version,
            UpdateVersionDisplay.installed(BuildConfig.VERSION_NAME, BuildConfig.CI_BUILD_NUMBER.toLong()),
        )
        binding.currentVersionText.text = if (release != null && model.status != UpdateUiStatus.CURRENT) {
            getString(
                R.string.update_current_version,
                UpdateVersionDisplay.installed(BuildConfig.VERSION_NAME, BuildConfig.CI_BUILD_NUMBER.toLong()),
            )
        } else ""
        binding.statusMessage.text = when (model.status) {
            UpdateUiStatus.CURRENT -> getString(R.string.update_checked_recently, UpdateTimeFormatter.format(requireContext(), repository.lastSuccessfulCheck))
            UpdateUiStatus.DOWNLOADING -> model.statusMessageRes?.let(::getString)
                ?: UpdateStatusBinder.downloadStatusText(requireContext(), model.downloadManagerStatus, model.downloadManagerReason)
            else -> model.statusMessageRes?.let(::getString).orEmpty()
        }
        binding.downloadProgressView.root.visibility = if (model.status == UpdateUiStatus.DOWNLOADING) View.VISIBLE else View.GONE
        UpdateStatusBinder.bindDownloadProgress(
            requireContext(),
            binding.downloadProgressView.downloadProgress,
            binding.downloadProgressView.downloadBytes,
            binding.downloadProgressView.downloadRate,
            model.progress,
            model.downloadManagerStatus,
        )
        binding.primaryButton.visibility = if (model.primaryAction == null) View.GONE else View.VISIBLE
        binding.primaryButton.text = actionText(model.primaryAction)
        val secondary = model.secondaryAction
        binding.secondaryButton.visibility = if (secondary == null) View.GONE else View.VISIBLE
        binding.secondaryButton.text = actionText(secondary)
        binding.updateOverflowButton.visibility = if (model.overflowActions.isEmpty()) View.GONE else View.VISIBLE
        val showNotes = model.showReleaseNotes && release != null
        binding.whatsNewTitle.visibility = if (showNotes) View.VISIBLE else View.GONE
        binding.notesContainer.visibility = if (showNotes) View.VISIBLE else View.GONE
        val currentNotes = if (showNotes) {
            UpdateReleaseHistory.notesForUpdate(
                historyComplete = repository.releaseHistoryComplete.value,
                cumulativeReleases = repository.releasesSinceInstalled(release),
                latestRelease = release,
            )
        } else {
            emptyList()
        }
        if (showNotes) {
            UpdateNotesBinder.bindHistory(binding.notesContainer, currentNotes)
        } else {
            binding.notesContainer.removeAllViews()
        }
        binding.lastCheckedText.text = getString(
            R.string.last_successful_update_check,
            UpdateTimeFormatter.format(requireContext(), repository.lastSuccessfulCheck),
        )
        binding.technicalDetailsToggle.visibility = if (model.showDiagnostics) View.VISIBLE else View.GONE
        binding.technicalDetails.text = UpdateDiagnostics.format(requireContext(), repository.diagnostics()) +
            "\n\n" + getString(R.string.copy_diagnostics)
        binding.technicalDetails.visibility = if (model.showDiagnostics && technicalDetailsExpanded) View.VISIBLE else View.GONE
        binding.copyDiagnosticsButton.visibility = if (model.showDiagnostics && technicalDetailsExpanded) View.VISIBLE else View.GONE
        binding.technicalDetailsToggle.text = getString(
            if (technicalDetailsExpanded) R.string.hide_technical_details else R.string.update_diagnostics,
        )
        val currentNoteIds = currentNotes.mapTo(hashSetOf()) { it.id }
        val recent = repository.recentReleases(release).filterNot { it.id in currentNoteIds }
        binding.recentUpdatesCard.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        UpdateNotesBinder.bindHistory(
            binding.recentUpdatesContainer,
            recent.take(visibleHistoryCount),
        )
        val hasMoreHistory = recent.size > visibleHistoryCount
        binding.earlierChangesButton.visibility = if (hasMoreHistory) View.VISIBLE else View.GONE
        binding.earlierChangesButton.text = getString(
            if (hasMoreHistory) R.string.update_release_notes_earlier
            else R.string.update_release_notes_earlier_expanded,
        )
    }

    private fun showUpdateOverflowMenu() {
        val menu = PopupMenu(requireContext(), binding.updateOverflowButton)
        UpdateUiMapper.map(repository.state.value, repository.selectedAssetInfo()).overflowActions.forEach { action ->
            menu.menu.add(actionText(action)).setOnMenuItemClickListener {
                performUpdateAction(action)
                true
            }
        }
        menu.show()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_VISIBLE_HISTORY_COUNT, visibleHistoryCount)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val INITIAL_HISTORY_COUNT = 5
        const val HISTORY_PAGE_SIZE = 5
        const val KEY_VISIBLE_HISTORY_COUNT = "visible_update_history_count"
    }
}
