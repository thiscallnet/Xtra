package com.github.andreyasadchy.xtra.ui.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import com.github.andreyasadchy.xtra.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

class MaterialEditTextPreference : EditTextPreferenceDialogFragmentCompat() {

    companion object {
        const val VALIDATION_ERROR = "validationError"
    }

    private var mEditText: EditText? = null
    private var errorLayout: TextInputLayout? = null
    private val mShowSoftInputRunnable = Runnable { scheduleShowSoftInputInner() }
    private var showSoftInputDeadline = 0L

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        mEditText = view.findViewById(android.R.id.edit)
        mEditText?.let { input ->
            val parent = input.parent as ViewGroup
            val index = parent.indexOfChild(input)
            val params = input.layoutParams
            parent.removeView(input)
            val field = TextInputLayout(requireContext()).apply {
                isHintEnabled = false
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
                addView(input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            parent.addView(field, index, params)
            errorLayout = field
            input.addTextChangedListener { field.error = null }
            input.requestFocus()
        }
    }

    private fun scheduleShowSoftInputInner() {
        mEditText?.let { mEditText ->
            if (mEditText.isFocused && SystemClock.uptimeMillis() < showSoftInputDeadline) {
                val imm = mEditText.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (!imm.showSoftInput(mEditText, 0)) {
                    mEditText.removeCallbacks(mShowSoftInputRunnable)
                    mEditText.postDelayed(mShowSoftInputRunnable, 50)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.dialogTitle)
            .setIcon(preference.dialogIcon)
            .setPositiveButton(preference.positiveButtonText, this)
            .setNegativeButton(preference.negativeButtonText, this)
        val contentView = onCreateDialogView(requireContext())
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(NestedScrollView(requireContext()).apply { addView(contentView) })
        } else {
            builder.setMessage(preference.dialogMessage)
        }
        onPrepareDialogBuilder(builder)

        // Create the dialog
        val dialog = builder.create()
        // Validation and persistence happen in the positive click handler. Keep the
        // superclass dismissal path negative so it cannot validate or save a second time.
        super.onClick(dialog, DialogInterface.BUTTON_NEGATIVE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? AlertDialog ?: return
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val input = mEditText ?: return@setOnClickListener
            val editPreference = preference as EditTextPreference
            val value = input.text.toString()
            if (editPreference.callChangeListener(value)) {
                editPreference.text = value
                dismiss()
            } else {
                errorLayout?.error = editPreference.extras.getString(VALIDATION_ERROR)
                    ?: getString(R.string.settings_invalid_value)
                input.requestFocus()
            }
        }
        requestInputMethod(dialog)
    }

    private fun requestInputMethod(dialog: Dialog) {
        val window = dialog.window ?: return
        val input = mEditText ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            input.post {
                if (this.dialog === dialog && input.isAttachedToWindow) {
                    WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
                }
            }
        } else {
            showSoftInputDeadline = SystemClock.uptimeMillis() + 1000L
            scheduleShowSoftInputInner()
        }
    }

    override fun onDestroyView() {
        mEditText?.removeCallbacks(mShowSoftInputRunnable)
        mEditText = null
        errorLayout = null
        showSoftInputDeadline = 0L
        super.onDestroyView()
    }
}
