package com.github.andreyasadchy.xtra.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.DEFAULT_CHAT_HIGHLIGHT_COLOR
import com.github.andreyasadchy.xtra.ui.chat.parseChatHighlightColor
import com.github.andreyasadchy.xtra.util.C
import com.google.android.material.color.MaterialColors

abstract class MaterialPreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { key ->
            listView.post { scrollToPreference(key) }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val editTextPreference = preference as? EditTextPreference
        colorPickerConfig(preference)?.let { config ->
            val defaultAction: (() -> Boolean)? = if (editTextPreference?.key == C.CHAT_MESSAGE_TEXT_COLOR ||
                editTextPreference?.key == C.CHAT_METADATA_TEXT_COLOR
            ) {
                {
                    editTextPreference.text = null
                    editTextPreference.summary = getString(R.string.settings_chat_text_default_summary)
                    findPreference<ChatAppearancePreviewPreference>("chat_appearance_preview")?.refreshPreview()
                    (requireActivity() as? SettingsActivity)?.setResult()
                    true
                }
            } else null
            ColorPickerDialog.show(
                requireContext(),
                preference.title ?: "",
                config.initialColor,
                config.defaultColor,
                config.allowAlpha,
                { value ->
                    if (preference.callChangeListener(value)) {
                        (preference as EditTextPreference).text = value
                        preference.summary = value
                        findPreference<ChatAppearancePreviewPreference>("chat_appearance_preview")?.refreshPreview()
                        (requireActivity() as? SettingsActivity)?.setResult()
                        true
                    } else {
                        false
                    }
                },
                defaultAction
            )
            return
        }
        when (preference) {
            is ListPreference -> showPreferenceDialog(preference, MaterialListPreference())
            is MultiSelectListPreference -> showPreferenceDialog(preference, MaterialMultiSelectListPreference())
            is EditTextPreference -> showPreferenceDialog(preference, MaterialEditTextPreference())
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    private data class ColorPickerConfig(
        val initialColor: Int,
        val defaultColor: Int?,
        val allowAlpha: Boolean,
    )

    private fun colorPickerConfig(preference: Preference): ColorPickerConfig? {
        val editTextPreference = preference as? EditTextPreference ?: return null
        val (defaultColor, allowAlpha) = when (editTextPreference.key) {
            C.CHAT_HIGHLIGHT_COLOR -> DEFAULT_CHAT_HIGHLIGHT_COLOR to true
            C.PLAYER_LIVE_CAPTION_BACKGROUND_COLOR -> Color.BLACK to true
            C.PLAYER_LIVE_CAPTION_TEXT_COLOR -> Color.WHITE to false
            C.CHAT_MESSAGE_TEXT_COLOR -> MaterialColors.getColor(
                listView,
                com.google.android.material.R.attr.colorOnSurface,
            ) to false
            C.CHAT_METADATA_TEXT_COLOR -> MaterialColors.getColor(
                listView,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            ) to false
            else -> return null
        }
        val initialColor = editTextPreference.text?.let { value ->
            when (editTextPreference.key) {
                C.CHAT_HIGHLIGHT_COLOR -> parseChatHighlightColor(value)
                else -> parsePickerColor(value, allowAlpha)
            }
        } ?: defaultColor
        return ColorPickerConfig(initialColor, defaultColor, allowAlpha)
    }

    @Suppress("DEPRECATION")
    private fun showPreferenceDialog(preference: Preference, fragment: DialogFragment) {
        fragment.arguments = bundleOf("key" to preference.key)
        fragment.setTargetFragment(this, 0)
        fragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
    }
}
