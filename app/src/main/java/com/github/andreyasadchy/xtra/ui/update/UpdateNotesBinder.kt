package com.github.andreyasadchy.xtra.ui.update

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.updater.ChangeKind
import com.github.andreyasadchy.xtra.util.updater.ChangeItem
import com.github.andreyasadchy.xtra.util.updater.UpdateRelease

object UpdateNotesBinder {
    fun bind(container: LinearLayout, release: UpdateRelease?, maxItems: Int? = 6, clear: Boolean = true): Int {
        if (clear) container.removeAllViews()
        if (release == null) {
            container.visibility = View.GONE
            return 0
        }
        val items = release.structuredReleaseNotes.items.let { notes ->
            if (maxItems == null) notes else notes.take(maxItems)
        }
        if (items.isEmpty()) {
            addText(container, container.context.getString(R.string.update_no_release_notes))
        } else {
            listOf(ChangeKind.NEW, ChangeKind.IMPROVED, ChangeKind.FIXED, ChangeKind.SECURITY, ChangeKind.OTHER)
                .forEach { kind ->
                    val grouped = items.filter { it.kind == kind }
                    if (grouped.isNotEmpty()) addGroup(container, kind, grouped)
                }
        }
        container.visibility = View.VISIBLE
        return items.size
    }

    private fun addGroup(container: LinearLayout, kind: ChangeKind, items: List<ChangeItem>) {
        val context = container.context
        val title = TextView(context).apply {
            text = context.getString(
                when (kind) {
                    ChangeKind.NEW -> R.string.update_section_new
                    ChangeKind.IMPROVED -> R.string.update_section_improved
                    ChangeKind.FIXED -> R.string.update_section_fixed
                    ChangeKind.SECURITY -> R.string.update_section_security
                    ChangeKind.OTHER -> R.string.update_section_other
                },
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        }
        container.addView(title)
        items.forEach { item ->
            addText(container, "• ${item.text}", topMargin = 5)
        }
    }

    private fun addText(container: LinearLayout, value: String, topMargin: Int = 0) {
        container.addView(TextView(container.context).apply {
            text = value
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            if (topMargin > 0) {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { this.topMargin = topMargin }
            }
        })
    }
}
