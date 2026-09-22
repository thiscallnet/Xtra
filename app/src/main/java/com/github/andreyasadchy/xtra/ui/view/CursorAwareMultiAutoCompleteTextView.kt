package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.util.AttributeSet
import android.widget.MultiAutoCompleteTextView

class CursorAwareMultiAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.autoCompleteTextViewStyle,
) : MultiAutoCompleteTextView(context, attrs, defStyleAttr) {
    var onSelectionChangedListener: ((selectionStart: Int, selectionEnd: Int) -> Unit)? = null
    var suppressAutocomplete = false

    override fun enoughToFilter(): Boolean = !suppressAutocomplete && super.enoughToFilter()

    override fun showDropDown() {
        if (!suppressAutocomplete) super.showDropDown()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke(selStart, selEnd)
    }
}
