package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarMenu

/** Bottom navigation whose item count follows the user's navigation configuration. */
class UnlimitedBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle,
) : BottomNavigationView(context, attrs, defStyleAttr) {

    override fun getMaxItemCount(): Int = NavigationBarMenu.NO_MAX_ITEM_LIMIT
}
