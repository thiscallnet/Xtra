package com.github.andreyasadchy.xtra.ui.common

import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R

fun View.personPlaceholder(): Drawable? =
    ContextCompat.getDrawable(context, R.drawable.ic_person_placeholder)
