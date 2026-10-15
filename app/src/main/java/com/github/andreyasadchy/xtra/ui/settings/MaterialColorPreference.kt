package com.github.andreyasadchy.xtra.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceDialogFragmentCompat

class MaterialColorPreference : PreferenceDialogFragmentCompat() {
    private var picker: ColorPickerDialog.Session? = null

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val owner = targetFragment as MaterialPreferenceFragment
        return owner.createColorPicker(preference as EditTextPreference, savedInstanceState).also { picker = it }.dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        picker?.saveState?.invoke(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDialogClosed(positiveResult: Boolean) = Unit

    override fun onDestroyView() {
        super.onDestroyView()
        picker = null
    }
}
