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
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.DEFAULT_CHAT_HIGHLIGHT_COLOR
import com.github.andreyasadchy.xtra.ui.chat.parseChatHighlightColor
import com.github.andreyasadchy.xtra.ui.common.usesExpressiveInterface
import com.github.andreyasadchy.xtra.util.C
import com.google.android.material.color.MaterialColors

abstract class MaterialPreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val expressive = requireContext().usesExpressiveInterface()
        if (expressive) {
            val density = resources.displayMetrics.density
            val inset = (16 * density).toInt()
            listView.setPadding(inset, (8 * density).toInt(), inset, listView.paddingBottom)
            listView.clipToPadding = false
            setDivider(null)
            listView.addItemDecoration(
                SettingsCardDecoration(
                    MaterialColors.getColor(listView, com.google.android.material.R.attr.colorSurfaceContainerLow),
                    20 * density,
                ),
            )
        }
        preferenceScreen?.let { applySettingIcons(it, expressive) }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        (requireActivity() as? SettingsActivity)?.getSelectedSearchItem()?.let { key ->
            listView.post { scrollToPreference(key) }
        }
    }

    private fun applySettingIcons(group: PreferenceGroup, expressive: Boolean) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceCategory) {
                if (expressive) {
                    SettingsIcons.forCategory(pref.title) { getString(it) }?.let { icon ->
                        pref.layoutResource = R.layout.preference_category_badge
                        pref.setIcon(icon)
                    }
                }
                applySettingIcons(pref, expressive)
                continue
            }
            if (pref.icon != null || pref is PreferenceGroup) continue
            if (pref.layoutResource != 0 && pref.javaClass.name.contains("Preview", ignoreCase = true)) continue
            val page = SettingsIcons.forPage(pref.key)
            if (page != null && expressive && pref.javaClass == Preference::class.java) {
                pref.layoutResource = R.layout.preference_badge
                pref.setIcon(page)
            } else {
                pref.setIcon(page ?: SettingsIcons.forSetting(pref.key))
            }
            pref.isIconSpaceReserved = false
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (colorPickerConfig(preference) != null) {
            showPreferenceDialog(preference, MaterialColorPreference())
            return
        }
        when (preference) {
            is ListPreference -> showPreferenceDialog(preference, MaterialListPreference())
            is MultiSelectListPreference -> showPreferenceDialog(preference, MaterialMultiSelectListPreference())
            is EditTextPreference -> showPreferenceDialog(preference, MaterialEditTextPreference())
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    internal fun createColorPicker(preference: EditTextPreference, savedState: Bundle?): ColorPickerDialog.Session {
        val config = requireNotNull(colorPickerConfig(preference))
        val defaultAction: (() -> Boolean)? = if (preference.key == C.PLAYER_MESSAGE_TEXT_COLOR ||
            preference.key == C.PLAYER_METADATA_TEXT_COLOR
        ) {
            {
                preference.text = null
                preference.summary = getString(R.string.settings_chat_text_default_summary)
                findPreference<ChatAppearancePreviewPreference>("chat_appearance_preview")?.refreshPreview()
                (requireActivity() as? SettingsActivity)?.setResult()
                true
            }
        } else null
        return ColorPickerDialog.create(
            requireContext(),
            preference.title ?: "",
            config.initialColor,
            config.defaultColor,
            config.allowAlpha,
            { value ->
                if (preference.callChangeListener(value)) {
                    preference.text = value
                    preference.summary = value
                    findPreference<ChatAppearancePreviewPreference>("chat_appearance_preview")?.refreshPreview()
                    (requireActivity() as? SettingsActivity)?.setResult()
                    true
                } else {
                    false
                }
            },
            defaultAction,
            savedState,
        )
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
            C.PLAYER_MESSAGE_TEXT_COLOR -> MaterialColors.getColor(
                listView,
                com.google.android.material.R.attr.colorOnSurface,
            ) to false
            C.PLAYER_METADATA_TEXT_COLOR -> MaterialColors.getColor(
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
