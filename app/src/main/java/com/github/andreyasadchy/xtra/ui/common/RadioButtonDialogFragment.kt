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
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.res.use
import androidx.core.view.setPadding
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class RadioButtonDialogFragment : BottomSheetDialogFragment() {

    data class Header(val title: String, val description: String)

    interface HeaderProvider {
        suspend fun optionHeader(requestCode: Int): Header?
    }

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
        private const val REFRESHING = "refreshing"

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
    private var headerView: View? = null
    private var renderedHeader: Header? = null
    private var optionsGeneration = 0

    fun updateOptions(requestCode: Int, labels: Collection<CharSequence>, tags: Array<String>?, tags2: Array<String>?, checkedIndex: Int): Boolean {
        val arguments = requireArguments()
        if (arguments.getInt(REQUEST_CODE) != requestCode) return false
        arguments.putCharSequenceArrayList(LABELS, ArrayList(labels))
        arguments.putStringArray(TAGS, tags)
        arguments.putStringArray(TAGS2, tags2)
        arguments.putInt(CHECKED, checkedIndex)
        arguments.putBoolean(LOADING, false)
        arguments.putBoolean(REFRESHING, false)
        renderOptions()
        return true
    }

    fun showOptionsLoading(requestCode: Int) {
        if (requireArguments().getInt(REQUEST_CODE) != requestCode) return
        requireArguments().putBoolean(LOADING, true)
        requireArguments().putBoolean(REFRESHING, false)
        renderOptions()
    }

    fun showOptionsRefreshing(requestCode: Int) {
        val arguments = requireArguments()
        if (arguments.getInt(REQUEST_CODE) != requestCode) return
        arguments.putBoolean(LOADING, true)
        arguments.putBoolean(REFRESHING, true)
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
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        headerView = inflater.inflate(R.layout.dialog_option_header, content, false).also {
            it.isVisible = false
            content.addView(it)
        }
        content.addView(radioGroup)
        return NestedScrollView(context).apply { addView(content) }
    }

    private fun renderOptions() {
        val radioGroup = optionsGroup ?: return
        val context = radioGroup.context
        val arguments = requireArguments()
        val generation = ++optionsGeneration
        radioGroup.clearCheck()
        radioGroup.removeAllViews()
        val labels = arguments.getCharSequenceArrayList(LABELS)
        val refreshing = arguments.getBoolean(LOADING) && arguments.getBoolean(REFRESHING) && !labels.isNullOrEmpty()
        if (arguments.getBoolean(LOADING) && !refreshing) {
            radioGroup.addView(ProgressBar(context), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            return
        }
        if (refreshing) {
            val size = (24 * context.resources.displayMetrics.density).toInt()
            radioGroup.addView(ProgressBar(context), LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * context.resources.displayMetrics.density).toInt()
            })
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
        labels?.forEachIndexed { index, label ->
            val button = AppCompatRadioButton(context).apply {
                id = index
                text = label
                tag = tags?.getOrNull(index)?.takeIf { it != "null" }
                isEnabled = !arguments.getBoolean(LOADING)
                setOnClickListener(clickListener)
            }
            radioGroup.addView(button, params)
        }
        radioGroup.check(checkedId)
    }

    override fun onDestroyView() {
        optionsGroup = null
        headerView = null
        renderedHeader = null
        optionsGeneration++
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val provider = parentFragment as? HeaderProvider ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        val header = provider.optionHeader(requireArguments().getInt(REQUEST_CODE))
                        if (header != renderedHeader) {
                            renderedHeader = header
                            headerView?.apply {
                                isVisible = header != null
                                findViewById<TextView>(R.id.optionHeaderTitle).text = header?.title
                                findViewById<TextView>(R.id.optionHeaderDescription).text = header?.description
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Retain the last successful status during a transient session failure.
                    }
                    delay(1_000L)
                }
            }
        }
    }
}
