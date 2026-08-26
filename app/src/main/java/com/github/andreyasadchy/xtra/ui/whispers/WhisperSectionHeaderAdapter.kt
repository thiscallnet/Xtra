package com.github.andreyasadchy.xtra.ui.whispers

import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R

class WhisperSectionHeaderAdapter(private val titleRes: Int) : RecyclerView.Adapter<WhisperSectionHeaderAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(16), dp(12), dp(16), dp(6))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setText(titleRes)
            contentDescription = text
        },
    )

    override fun getItemCount() = 1
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = Unit

    class ViewHolder(view: TextView) : RecyclerView.ViewHolder(view)

    private fun TextView.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
