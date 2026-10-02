package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.res.use
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment


class RadioButtonDialogFragment : BottomSheetDialogFragment() {

    interface OnSortOptionChanged {
        fun onChange(requestCode: Int, index: Int, text: CharSequence, tag: String?, tag2: String?)
    }

    companion object {

        private const val REQUEST_CODE = "requestCode"
        private const val LABELS = "labels"
        private const val TAGS = "tags"
        private const val TAGS2 = "tags2"
        private const val CHECKED = "checked"
        private const val LOADING = "loading"

        fun newInstance(requestCode: Int, labels: Collection<CharSequence>, tags: Array<String>? = null, tags2: Array<String>? = null, checkedIndex: Int): RadioButtonDialogFragment {
            return RadioButtonDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(REQUEST_CODE, requestCode)
                    putCharSequenceArrayList(LABELS, ArrayList(labels))
                    putStringArray(TAGS, tags)
                    putStringArray(TAGS2, tags2)
                    putInt(CHECKED, checkedIndex)
                }
            }
        }
    }

    private lateinit var listenerSort: OnSortOptionChanged
    private var optionsGroup: RadioGroup? = null
    private var optionsGeneration = 0

    fun updateOptions(requestCode: Int, labels: Collection<CharSequence>, tags: Array<String>?, tags2: Array<String>?, checkedIndex: Int): Boolean {
        val arguments = requireArguments()
        if (arguments.getInt(REQUEST_CODE) != requestCode) return false
        arguments.putCharSequenceArrayList(LABELS, ArrayList(labels))
        arguments.putStringArray(TAGS, tags)
        arguments.putStringArray(TAGS2, tags2)
        arguments.putInt(CHECKED, checkedIndex)
        arguments.putBoolean(LOADING, false)
        renderOptions()
        return true
    }

    fun showOptionsLoading(requestCode: Int) {
        if (requireArguments().getInt(REQUEST_CODE) != requestCode) return
        requireArguments().putBoolean(LOADING, true)
        renderOptions()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listenerSort = parentFragment as OnSortOptionChanged
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()
        val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val radioGroup = RadioGroup(context).apply {
            layoutParams = params
            context.obtainStyledAttributes(intArrayOf(R.attr.dialogLayoutPadding)).use {
                setPadding(it.getDimensionPixelSize(0, 0))
            }
        }
        optionsGroup = radioGroup
        renderOptions()
        return NestedScrollView(context).apply { addView(radioGroup) }
    }

    private fun renderOptions() {
        val radioGroup = optionsGroup ?: return
        val context = radioGroup.context
        val arguments = requireArguments()
        val generation = ++optionsGeneration
        radioGroup.clearCheck()
        radioGroup.removeAllViews()
        if (arguments.getBoolean(LOADING)) {
            radioGroup.addView(ProgressBar(context), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            return
        }
        val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val checkedId = arguments.getInt(CHECKED)
        val tags2 = arguments.getStringArray(TAGS2)
        val clickListener = View.OnClickListener { v ->
            if (generation != optionsGeneration || arguments.getBoolean(LOADING)) return@OnClickListener
            val clickedId = v.id
            if (clickedId != checkedId) {
                listenerSort.onChange(arguments.getInt(REQUEST_CODE), clickedId, (v as RadioButton).text, v.tag as String?, tags2?.getOrNull(clickedId)?.takeIf { it != "null" })
            }
            // The callback can reject a stale source selection and refresh this
            // dialog into its loading state. Do not let the old click dismiss that
            // newly rendered state.
            if (generation == optionsGeneration && !arguments.getBoolean(LOADING)) {
                dismiss()
            }
        }
        val tags = arguments.getStringArray(TAGS)
        arguments.getCharSequenceArrayList(LABELS)?.forEachIndexed { index, label ->
            val button = AppCompatRadioButton(context).apply {
                id = index
                text = label
                tag = tags?.getOrNull(index)?.takeIf { it != "null" }
                setOnClickListener(clickListener)
            }
            radioGroup.addView(button, params)
        }
        radioGroup.check(checkedId)
    }

    override fun onDestroyView() {
        optionsGroup = null
        optionsGeneration++
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
