package com.github.andreyasadchy.xtra.ui.common

import android.widget.TextView
import com.github.andreyasadchy.xtra.R

internal fun TextView.showLanguageFilter(languages: Array<String>) {
    setSingleLine(false)
    text = if (languages.isEmpty()) {
        resources.getString(R.string.languages)
    } else {
        val codes = resources.getStringArray(R.array.gqlUserLanguageValues)
        val names = resources.getStringArray(R.array.gqlUserLanguageEntries)
        val labels = languages.map { code ->
            codes.indexOf(code).takeIf { it >= 0 }?.let { names[it] } ?: code
        }
        resources.getQuantityString(R.plurals.languages, languages.size, labels.joinToString())
    }
}
