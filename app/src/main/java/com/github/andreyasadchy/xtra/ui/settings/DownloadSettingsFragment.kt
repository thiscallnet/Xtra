package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C

class DownloadSettingsFragment : MaterialPreferenceFragment() {
    private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.download_preferences, rootKey)
        findPreference<Preference>("import_app_downloads")?.setOnPreferenceClickListener {
            viewModel.importDownloads()
            true
        }
        configureLiveDownloadPreferences()
    }

    private fun configureLiveDownloadPreferences() {
        val start = findPreference<ListPreference>(C.DOWNLOAD_STREAM_START_WAIT)
        val end = findPreference<ListPreference>(C.DOWNLOAD_STREAM_END_WAIT)
        appendCustomListValue(start, "minutes")
        appendCustomListValue(end, "minutes")
        updateLiveDownloadSummary(start, "Minutes to wait for a queued live download to start.")
        updateLiveDownloadSummary(end, "Keep waiting briefly after a stream ends so a quick restart can continue the capture.")
        start?.setOnPreferenceChangeListener { preference, newValue ->
            updateLiveDownloadSummary(preference as ListPreference, "Minutes to wait for a queued live download to start.", newValue.toString())
            true
        }
        end?.setOnPreferenceChangeListener { preference, newValue ->
            updateLiveDownloadSummary(preference as ListPreference, "Keep waiting briefly after a stream ends so a quick restart can continue the capture.", newValue.toString())
            true
        }
    }

    private fun appendCustomListValue(preference: ListPreference?, unit: String) {
        preference ?: return
        val values = preference.entryValues.toMutableList()
        val entries = preference.entries.toMutableList()
        if (values.none { it.toString() == "custom" }) {
            values.add("custom")
            entries.add("Custom $unit")
            preference.entryValues = values.toTypedArray()
            preference.entries = entries.toTypedArray()
        }
    }

    private fun updateLiveDownloadSummary(preference: ListPreference?, explanation: String, value: String? = preference?.value) {
        preference ?: return
        val index = preference.findIndexOfValue(value)
        val selected = preference.entries.getOrNull(index)?.toString()
            ?: value?.let { "$it minutes (custom)" }
        preference.summary = listOfNotNull(selected, explanation).joinToString("\n")
    }

}
