package com.github.andreyasadchy.xtra.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.appearance.AppearanceRepository
import com.github.andreyasadchy.xtra.ui.appearance.DEFAULT_BACKGROUND_VISIBILITY
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

class ThemeSettingsFragment : MaterialPreferenceFragment() {
    private var backgroundPhotoLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundPhotoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val selectedUri = uri ?: return@registerForActivityResult
            val persisted = runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
            if (!persisted) {
                Toast.makeText(requireContext(), R.string.settings_app_background_persist_failed, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            AppearanceRepository(requireContext()).setAppBackgroundUri(selectedUri)
            findPreference<SwitchPreferenceCompat>(C.APP_BACKGROUND_ENABLED)?.isChecked = true
            updateAppBackgroundPreferences()
            findPreference<AppBackgroundPreviewPreference>("app_background_preview")?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.theme_preferences, rootKey)
        configureAppBackgroundPreferences()
        val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
            (requireActivity() as? SettingsActivity)?.changed = true
            requireActivity().recreate()
            true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVICE_COLORS)?.isVisible = false
        }
        findPreference<ListPreference>(C.SETTINGS_THEME_MODE)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.SETTINGS_UI_STYLE)?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            (requireActivity() as? SettingsActivity)?.setResult()
            requireActivity().recreate()
            true
        }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVICE_COLORS)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.SETTINGS_DENSITY)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.SETTINGS_FONT_FAMILY)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.UI_THEME_ROUNDED_CORNERS)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.SETTINGS_PROFILE_PICTURE_STYLE)?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit { putBoolean(C.UI_ROUND_USER_IMAGE, value == "round") }
            true
        }
        findPreference<Preference>("appearance_display_compatibility")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.appearanceDisplayCompatibilityFragment)
            true
        }
    }

    private fun configureAppBackgroundPreferences() {
        val repository = AppearanceRepository(requireContext())
        val enabled = findPreference<SwitchPreferenceCompat>(C.APP_BACKGROUND_ENABLED)
        val visibility = findPreference<SeekBarPreference>(C.APP_BACKGROUND_VISIBILITY)
        val preview = findPreference<AppBackgroundPreviewPreference>("app_background_preview")

        enabled?.setOnPreferenceChangeListener { _, value ->
            repository.setAppBackgroundEnabled(value as Boolean)
            updateAppBackgroundPreferences()
            preview?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        visibility?.setOnPreferenceChangeListener { _, value ->
            repository.setAppBackgroundVisibility(value as Int)
            preview?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        findPreference<Preference>("app_background_choose")?.setOnPreferenceClickListener {
            backgroundPhotoLauncher?.launch(arrayOf("image/*"))
            true
        }
        findPreference<Preference>("app_background_reset")?.setOnPreferenceClickListener {
            repository.resetAppBackground()
            enabled?.isChecked = false
            visibility?.value = DEFAULT_BACKGROUND_VISIBILITY
            updateAppBackgroundPreferences()
            preview?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        updateAppBackgroundPreferences()
    }

    private fun updateAppBackgroundPreferences() {
        val repository = AppearanceRepository(requireContext())
        val configuration = repository.appBackground()
        findPreference<Preference>("app_background_choose")?.summary = when {
            configuration.uri == null -> getString(R.string.settings_app_background_choose_summary)
            repository.isUriReadable(configuration.uri) -> getString(R.string.settings_app_background_change_summary)
            else -> getString(R.string.settings_app_background_unavailable)
        }
        findPreference<SeekBarPreference>(C.APP_BACKGROUND_VISIBILITY)?.isEnabled = configuration.enabled
    }

}
