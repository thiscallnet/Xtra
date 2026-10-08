package com.github.andreyasadchy.xtra.ui.settings

import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.player.PhoneChatOverlayConfig
import com.github.andreyasadchy.xtra.ui.player.persistPhoneChatOverlayConfig
import com.github.andreyasadchy.xtra.ui.tv.TvChatOverlayAnchor
import com.github.andreyasadchy.xtra.ui.tv.TvChatOverlayConfig
import com.github.andreyasadchy.xtra.ui.tv.TvChatOverlayPreset
import com.github.andreyasadchy.xtra.ui.tv.TvChatMode
import com.github.andreyasadchy.xtra.ui.tv.persistTvChatOverlayConfig
import com.github.andreyasadchy.xtra.ui.tv.tvChatOverlayConfig
import com.github.andreyasadchy.xtra.ui.tv.tvChatPreset
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

class ChatSettingsFragment : MaterialPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.chat_preferences, rootKey)
        configureTvChatPreferences()
        configurePhoneChatPreferences()
        findPreference<Preference>("chat_appearance_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatAppearanceFragment); true }
        findPreference<Preference>("chat_emotes_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatEmotesFragment); true }
        findPreference<Preference>("chat_features_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatFeaturesFragment); true }
        findPreference<Preference>("chat_translation_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatTranslationFragment); true }
        findPreference<Preference>("chat_visibility_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatVisibilityFragment); true }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_CHAT_ENABLED)?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit {
                putBoolean(C.SETTINGS_CHAT_ENABLED, value as Boolean)
                putBoolean(C.CHAT_DISABLE, !(value as Boolean))
            }
            true
        }
        val translationSupported = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"
        findPreference<Preference>("chat_translation_page")?.isVisible = translationSupported
        if (translationSupported) {
            val languages = TranslateLanguage.getAllLanguages()
            val names = languages.map { Locale.forLanguageTag(it).displayLanguage }.toTypedArray()
            findPreference<Preference>("downloaded_languages")?.setOnPreferenceClickListener {
                val modelManager = RemoteModelManager.getInstance()
                modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                    .addOnSuccessListener { models ->
                        val downloaded = models.map { it.language }
                        val checked = languages.map { downloaded.contains(it) }.toBooleanArray()
                        val selectedItems = downloaded.toMutableList()
                        requireActivity().getAlertDialogBuilder()
                            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                                languages.getOrNull(which)?.let { language ->
                                    if (isChecked) {
                                        if (!selectedItems.contains(language)) {
                                            selectedItems.add(language)
                                        }
                                    } else {
                                        selectedItems.remove(language)
                                    }
                                }
                            }
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                downloaded.filter { !selectedItems.contains(it) }.forEach {
                                    modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build())
                                }
                                selectedItems.filter { !downloaded.contains(it) }.forEach {
                                    modelManager.download(
                                        TranslateRemoteModel.Builder(it).build(),
                                        DownloadConditions.Builder().build()
                                    )
                                }
                            }
                            .setNegativeButton(getString(android.R.string.cancel), null)
                            .show()
                    }
                true
            }
            findPreference<ListPreference>("chat_translate_target")?.apply {
                entries = names
                entryValues = languages.toTypedArray()
            }
        } else {
            findPreference<SwitchPreferenceCompat>("chat_translate")?.isVisible = false
            findPreference<Preference>("downloaded_languages")?.isVisible = false
            findPreference<ListPreference>("chat_translate_target")?.isVisible = false
        }
    }

    private fun configureTvChatPreferences() {
        val category = findPreference<PreferenceCategory>("tv_chat_category") ?: return
        category.isVisible = requireContext().isTelevision()
        if (!category.isVisible) return

        val preset = findPreference<ListPreference>(C.TV_CHAT_OVERLAY_PRESET)
        val mode = findPreference<ListPreference>(C.TV_CHAT_MODE)
        val anchor = findPreference<ListPreference>(C.TV_CHAT_OVERLAY_ANCHOR)
        val sideWidth = findPreference<SeekBarPreference>(C.TV_CHAT_SIDE_PANEL_WIDTH_PERCENT)
        val width = findPreference<SeekBarPreference>(C.TV_CHAT_OVERLAY_WIDTH_PERCENT)
        val height = findPreference<SeekBarPreference>(C.TV_CHAT_OVERLAY_HEIGHT_PERCENT)
        val opacity = findPreference<SeekBarPreference>(C.TV_CHAT_OVERLAY_OPACITY)

        preset?.setOnPreferenceChangeListener { _, value ->
            val selected = tvChatPreset(value.toString())
            val config = if (selected == TvChatOverlayPreset.CUSTOM) {
                tvChatOverlayConfig(requireContext()).copy(preset = selected)
            } else {
                com.github.andreyasadchy.xtra.ui.tv.tvChatPresetConfig(selected)
            }
            persistTvChatOverlayConfig(requireContext(), config)
            anchor?.value = config.anchor.name
            width?.value = config.widthPercent
            height?.value = config.heightPercent
            opacity?.value = config.opacityPercent
            true
        }

        fun makeCustom(key: String, value: Int): Boolean {
            val current = tvChatOverlayConfig(requireContext())
            val updated = when (key) {
                C.TV_CHAT_OVERLAY_WIDTH_PERCENT -> current.copy(widthPercent = value)
                C.TV_CHAT_OVERLAY_HEIGHT_PERCENT -> current.copy(heightPercent = value)
                C.TV_CHAT_OVERLAY_OPACITY -> current.copy(opacityPercent = value)
                else -> current
            }.copy(preset = TvChatOverlayPreset.CUSTOM)
            persistTvChatOverlayConfig(requireContext(), updated)
            preset?.value = TvChatOverlayPreset.CUSTOM.name
            return true
        }

        sideWidth?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit {
                putInt(C.TV_CHAT_SIDE_PANEL_WIDTH_PERCENT, (value as Int).coerceIn(15, 50))
            }
            true
        }

        anchor?.setOnPreferenceChangeListener { _, value ->
            val current = tvChatOverlayConfig(requireContext())
            persistTvChatOverlayConfig(
                requireContext(),
                current.copy(
                    anchor = runCatching { TvChatOverlayAnchor.valueOf(value.toString()) }
                        .getOrDefault(current.anchor),
                    preset = TvChatOverlayPreset.CUSTOM,
                ),
            )
            preset?.value = TvChatOverlayPreset.CUSTOM.name
            true
        }
        width?.setOnPreferenceChangeListener { _, value -> makeCustom(C.TV_CHAT_OVERLAY_WIDTH_PERCENT, value as Int) }
        height?.setOnPreferenceChangeListener { _, value -> makeCustom(C.TV_CHAT_OVERLAY_HEIGHT_PERCENT, value as Int) }
        opacity?.setOnPreferenceChangeListener { _, value -> makeCustom(C.TV_CHAT_OVERLAY_OPACITY, value as Int) }
        findPreference<Preference>("tv_chat_reset")?.setOnPreferenceClickListener {
            val config = TvChatOverlayConfig()
            requireContext().prefs().edit {
                putString(C.TV_CHAT_MODE, "side_panel")
                putInt(C.TV_CHAT_SIDE_PANEL_WIDTH_PERCENT, 25)
            }
            persistTvChatOverlayConfig(requireContext(), config)
            mode?.value = TvChatMode.SIDE_PANEL.name.lowercase()
            sideWidth?.value = 25
            preset?.value = TvChatOverlayPreset.AUTO.name
            anchor?.value = config.anchor.name
            width?.value = config.widthPercent
            height?.value = config.heightPercent
            opacity?.value = config.opacityPercent
            true
        }
    }

    private fun configurePhoneChatPreferences() {
        val category = findPreference<PreferenceCategory>("phone_chat_category") ?: return
        category.isVisible = !requireContext().isTelevision()
        if (!category.isVisible) return

        val enabled = findPreference<SwitchPreferenceCompat>(C.PHONE_CHAT_OVERLAY_ENABLED)
        val width = findPreference<SeekBarPreference>(C.PHONE_CHAT_OVERLAY_WIDTH_PERCENT)
        val height = findPreference<SeekBarPreference>(C.PHONE_CHAT_OVERLAY_HEIGHT_PERCENT)
        val opacity = findPreference<SeekBarPreference>(C.PHONE_CHAT_OVERLAY_OPACITY)
        val markChanged = { (requireActivity() as? SettingsActivity)?.setResult() }

        enabled?.setOnPreferenceChangeListener { _, _ ->
            markChanged()
            true
        }
        width?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit {
                putInt(C.PHONE_CHAT_OVERLAY_WIDTH_PERCENT, (value as Int).coerceIn(22, 70))
            }
            markChanged()
            true
        }
        height?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit {
                putInt(C.PHONE_CHAT_OVERLAY_HEIGHT_PERCENT, (value as Int).coerceIn(25, 90))
            }
            markChanged()
            true
        }
        opacity?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit {
                putInt(C.PHONE_CHAT_OVERLAY_OPACITY, (value as Int).coerceIn(40, 100))
            }
            markChanged()
            true
        }
        findPreference<Preference>("phone_chat_overlay_reset")?.setOnPreferenceClickListener {
            val config = PhoneChatOverlayConfig()
            persistPhoneChatOverlayConfig(requireContext(), config)
            width?.value = config.widthPercent
            height?.value = config.heightPercent
            opacity?.value = config.opacityPercent
            markChanged()
            true
        }
    }

}
