package com.github.andreyasadchy.xtra.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.ui.player.captions.formatCaptionTextOffset
import com.github.andreyasadchy.xtra.ui.player.captions.LIVE_CAPTION_ENGINE_MOONSHINE
import com.github.andreyasadchy.xtra.ui.player.captions.LIVE_CAPTION_ENGINE_SYSTEM
import com.github.andreyasadchy.xtra.ui.player.captions.MoonshineModelState
import com.github.andreyasadchy.xtra.ui.player.captions.engine.SystemSpeechAvailability
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlayerSettingsFragment : MaterialPreferenceFragment() {
    private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }
    private val moonshineModelManager
        get() = (requireContext().applicationContext as XtraApp).xtraModule.moonshineModelManager
    private var engineJob: Job? = null
    private var moonshineModelDialog: AlertDialog? = null
    private var moonshineModelDialogMessage: TextView? = null
    private var moonshineModelDialogProgress: ProgressBar? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (arguments?.getString("settings_screen") == SCREEN_LIVE_CAPTIONS) {
            setPreferencesFromResource(R.xml.live_caption_preferences, rootKey)
            configureLiveCaptionPreferences()
            return
        }
        setPreferencesFromResource(R.xml.playback_preferences, rootKey)
        findPreference<ListPreference>(C.PLAYER_VAFT_PLAYBACK_PRIORITY)?.summaryProvider =
            Preference.SummaryProvider<ListPreference> { preference ->
                getString(if (preference.value == C.VAFT_PRIORITY_LIVE) {
                    R.string.settings_vaft_priority_live_summary
                } else {
                    R.string.settings_vaft_priority_continuity_summary
                })
            }
        findPreference<Preference>("live_caption_page")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.liveCaptionsFragment)
            true
        }
        findPreference<Preference>("system_media_controls_page")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_global_systemMediaNotificationSettingsFragment)
            true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            findPreference<SwitchPreferenceCompat>(C.PLAYER_PICTURE_IN_PICTURE)?.isVisible = false
            findPreference<SwitchPreferenceCompat>(C.PLAYER_PIP_CHAT)?.isVisible = false
        }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_BACKGROUND_PLAYBACK)?.setOnPreferenceChangeListener { _, value ->
            val enabled = value as Boolean
            requireContext().prefs().edit { putBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, enabled) }
            true
        }
        findPreference<SwitchPreferenceCompat>("settings_mix_audio")?.apply {
            isChecked = !requireContext().prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false)
            setOnPreferenceChangeListener { _, value ->
                requireContext().prefs().edit { putBoolean(C.PLAYER_AUDIO_FOCUS, !(value as Boolean)) }
                true
            }
        }
        findPreference<Preference>("delete_video_positions")?.setOnPreferenceClickListener {
            requireActivity().getAlertDialogBuilder()
                .setMessage(getString(R.string.delete_video_positions_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    viewModel.deletePositions()
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
            true
        }
    }

    private fun configureLiveCaptionPreferences() {
            configureLiveCaptionEngine()
            findPreference<Preference>(C.PLAYER_LIVE_CAPTION_MODEL)?.setOnPreferenceClickListener {
                showMoonshineModelDialog()
                true
            }
            val background = findPreference<ListPreference>(C.PLAYER_LIVE_CAPTION_BACKGROUND)
            val customColor = findPreference<EditTextPreference>(C.PLAYER_LIVE_CAPTION_BACKGROUND_COLOR)
            customColor?.isVisible = background?.value == "custom"
            background?.setOnPreferenceChangeListener { _, value ->
                customColor?.isVisible = value == "custom"
                true
            }
            customColor?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT
                editText.hint = "#1A1A1A"
            }
            customColor?.setOnPreferenceChangeListener { _, value ->
                val valid = parsePickerColor(value.toString(), allowAlpha = true) != null
                if (!valid) {
                    Toast.makeText(requireContext(), R.string.live_caption_custom_color_summary, Toast.LENGTH_SHORT).show()
                }
                valid
            }
            findPreference<EditTextPreference>(C.PLAYER_LIVE_CAPTION_TEXT_OFFSET_SECONDS)?.apply {
                fun updateSummary(value: String?) {
                    summary = getString(
                        R.string.live_caption_text_offset_summary,
                        formatCaptionTextOffset(value),
                    )
                }

                updateSummary(text)
                setOnBindEditTextListener { editText ->
                    editText.inputType =
                        InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                    editText.hint = "0.0"
                }
                setOnPreferenceChangeListener { preference, value ->
                    val rawValue = value.toString().trim()
                    val seconds = rawValue.toDoubleOrNull()
                    if (seconds == null || !seconds.isFinite() || seconds !in -2.0..2.0) {
                        Toast.makeText(
                            requireContext(),
                            R.string.live_caption_text_offset_invalid,
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    } else {
                        // Normalize the displayed value but preserve the
                        // user's signed seconds in SharedPreferences.
                        updateSummary(rawValue)
                        requireContext().prefs().edit {
                            putString(C.PLAYER_LIVE_CAPTION_TEXT_OFFSET_SECONDS, rawValue)
                        }
                        (requireContext().applicationContext as XtraApp).xtraModule
                            .liveCaptionManager.reloadCaptionSettings()
                        true
                    }
                }
            }
            findPreference<EditTextPreference>(C.PLAYER_LIVE_CAPTION_TEXT_COLOR)?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_TEXT
                    editText.hint = "#FFFFFF"
                }
                setOnPreferenceChangeListener { _, value ->
                    val valid = parsePickerColor(value.toString(), allowAlpha = false) != null
                    if (!valid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.live_caption_text_color_summary,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    valid
                }
            }
            findPreference<Preference>(C.PLAYER_LIVE_CAPTION_RESET_POSITION)?.setOnPreferenceClickListener {
                requireContext().prefs().edit {
                    remove(C.PLAYER_LIVE_CAPTION_POSITION_CENTER_X)
                    remove(C.PLAYER_LIVE_CAPTION_POSITION_CENTER_Y)
                    remove(C.PLAYER_LIVE_CAPTION_POSITION_X)
                    remove(C.PLAYER_LIVE_CAPTION_POSITION_Y)
                }
                true
            }
    }

    private companion object {
        const val SCREEN_LIVE_CAPTIONS = "live_captions"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as? SettingsActivity)?.consumeSettingsHighlightPreference()?.let { key ->
            listView.post { scrollToPreference(key) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                moonshineModelManager.state.collectLatest(::updateMoonshineModelUi)
            }
        }
    }

    override fun onDestroyView() {
        moonshineModelDialog?.dismiss()
        moonshineModelDialog = null
        moonshineModelDialogMessage = null
        moonshineModelDialogProgress = null
        super.onDestroyView()
    }

    /**
     * Moonshine is always available. The system recognizer is only selectable once the device
     * reports it, so a missing AICore never leaves captions pointing at an engine that cannot start.
     */
    private fun configureLiveCaptionEngine() {
        findPreference<ListPreference>(C.PLAYER_LIVE_CAPTION_ENGINE)?.setOnPreferenceChangeListener { preference, newValue ->
            if (newValue != LIVE_CAPTION_ENGINE_SYSTEM) {
                applyLiveCaptionEngine(preference as ListPreference, LIVE_CAPTION_ENGINE_MOONSHINE)
                return@setOnPreferenceChangeListener false
            }
            val list = preference as ListPreference
            val previousSummary = list.summary
            list.summary = getString(R.string.live_caption_engine_system_checking)
            // One check or download at a time; picking again restarts it cleanly.
            engineJob?.cancel()
            engineJob = viewLifecycleOwner.lifecycleScope.launch {
                val status = SystemSpeechAvailability.status()
                when (status) {
                    FeatureStatus.AVAILABLE -> applyLiveCaptionEngine(list, LIVE_CAPTION_ENGINE_SYSTEM)
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        list.summary = getString(R.string.live_caption_engine_system_downloading)
                        val result = try {
                            SystemSpeechAvailability.download().first {
                                it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            null
                        }
                        if (result is DownloadStatus.DownloadCompleted) {
                            applyLiveCaptionEngine(list, LIVE_CAPTION_ENGINE_SYSTEM)
                        } else {
                            list.summary = previousSummary
                            showEngineToast(R.string.live_caption_engine_system_download_failed)
                        }
                    }
                    else -> {
                        list.summary = previousSummary
                        showEngineToast(R.string.live_caption_engine_system_unavailable)
                    }
                }
            }
            false
        }
    }

    private fun applyLiveCaptionEngine(preference: ListPreference, value: String) {
        preference.value = value
        preference.summary = preference.entry
        (requireContext().applicationContext as XtraApp).xtraModule.liveCaptionManager.reloadConfiguration()
    }

    private fun showEngineToast(message: Int) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun updateMoonshineModelUi(state: MoonshineModelState) {
        findPreference<Preference>(C.PLAYER_LIVE_CAPTION_MODEL)?.summary = when (state) {
            MoonshineModelState.Checking -> getString(R.string.live_caption_model_checking)
            MoonshineModelState.NotInstalled -> getString(R.string.live_caption_model_not_installed)
            is MoonshineModelState.Downloading -> getString(
                R.string.live_caption_model_downloading,
                downloadPercent(state.downloadedBytes, state.totalBytes),
            )
            MoonshineModelState.Verifying -> getString(R.string.live_caption_model_verifying)
            MoonshineModelState.Ready -> getString(R.string.live_caption_model_ready)
            is MoonshineModelState.Error -> getString(R.string.live_caption_model_error)
        }
        updateMoonshineModelDialog(state)
    }
    private fun showMoonshineModelDialog() {
        if (moonshineModelDialog != null) return
        val horizontalPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            24f,
            resources.displayMetrics,
        ).toInt()
        val message = TextView(requireContext())
        val progress = ProgressBar(
            requireContext(),
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            visibility = View.GONE
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                message,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                progress,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        16f,
                        resources.displayMetrics,
                    ).toInt()
                },
            )
        }
        moonshineModelDialogMessage = message
        moonshineModelDialogProgress = progress
        moonshineModelDialog = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.live_caption_model)
            .setView(container)
            .setPositiveButton(R.string.live_caption_model_download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                moonshineModelDialog = dialog
                dialog.setOnShowListener {
                    updateMoonshineModelDialog(moonshineModelManager.state.value)
                }
                dialog.setOnDismissListener {
                    moonshineModelDialog = null
                    moonshineModelDialogMessage = null
                    moonshineModelDialogProgress = null
                }
                dialog.show()
            }
    }

    private fun updateMoonshineModelDialog(state: MoonshineModelState) {
        val dialog = moonshineModelDialog ?: return
        val message = moonshineModelDialogMessage ?: return
        val progress = moonshineModelDialogProgress ?: return
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        when (state) {
            MoonshineModelState.Checking -> {
                message.text = getString(R.string.live_caption_model_checking)
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                positive.isEnabled = false
            }
            MoonshineModelState.NotInstalled -> {
                message.text = getString(R.string.live_caption_model_dialog_message)
                progress.visibility = View.GONE
                positive.text = getString(R.string.live_caption_model_download)
                positive.isEnabled = true
                positive.setOnClickListener { moonshineModelManager.download() }
            }
            is MoonshineModelState.Downloading -> {
                message.text = getString(
                    R.string.live_caption_model_downloading,
                    downloadPercent(state.downloadedBytes, state.totalBytes),
                )
                progress.isIndeterminate = false
                progress.progress = downloadPercent(state.downloadedBytes, state.totalBytes)
                progress.visibility = View.VISIBLE
                positive.text = getString(R.string.live_caption_model_cancel_download)
                positive.isEnabled = true
                positive.setOnClickListener { moonshineModelManager.cancelDownload() }
            }
            MoonshineModelState.Verifying -> {
                message.text = getString(R.string.live_caption_model_verifying)
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                positive.isEnabled = false
            }
            MoonshineModelState.Ready -> {
                message.text = getString(R.string.live_caption_model_ready)
                progress.visibility = View.GONE
                positive.text = getString(R.string.live_caption_model_remove)
                positive.isEnabled = true
                positive.setOnClickListener {
                    dialog.dismiss()
                    confirmRemoveMoonshineModel()
                }
            }
            is MoonshineModelState.Error -> {
                message.text = getString(R.string.live_caption_model_dialog_error)
                progress.visibility = View.GONE
                positive.text = getString(R.string.live_caption_model_download)
                positive.isEnabled = true
                positive.setOnClickListener { moonshineModelManager.download() }
            }
        }
    }

    private fun confirmRemoveMoonshineModel() {
        if (requireContext().prefs().getBoolean(C.PLAYER_LIVE_CAPTIONS, false)) {
            Toast.makeText(
                requireContext(),
                R.string.live_caption_model_remove_disabled,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.live_caption_model_remove)
            .setMessage(R.string.live_caption_model_remove_message)
            .setPositiveButton(R.string.yes) { _, _ -> moonshineModelManager.removeDownloadedModel() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun downloadPercent(downloadedBytes: Long, totalBytes: Long): Int =
        if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)

}
