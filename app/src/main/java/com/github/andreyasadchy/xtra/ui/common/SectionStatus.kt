package com.github.andreyasadchy.xtra.ui.common

import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemSectionStatusBinding

enum class SectionLoadState { LOADING, READY, FAILED }

data class SectionStatus(val loading: Boolean, val hasCachedData: Boolean = false)

fun ItemSectionStatusBinding.bindStatus(status: SectionStatus?, title: CharSequence, onRetry: () -> Unit) {
    root.isVisible = status != null
    if (status == null) return
    sectionProgress.isVisible = status.loading
    sectionMessage.setText(when {
        status.loading && status.hasCachedData -> R.string.section_refreshing
        status.loading -> R.string.section_loading
        status.hasCachedData -> R.string.section_stale
        else -> R.string.list_load_error
    })
    sectionRetry.isVisible = !status.loading
    sectionRetry.contentDescription = root.context.getString(R.string.section_retry, title)
    sectionRetry.setOnClickListener { onRetry() }
}
