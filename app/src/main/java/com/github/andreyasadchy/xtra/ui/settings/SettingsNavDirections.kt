package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import androidx.navigation.NavDirections

/** A small adapter for destinations that do not need generated arguments. */
data class SettingsNavDirections(private val destinationId: Int) : NavDirections {
    override val actionId: Int = destinationId
    override val arguments: Bundle = Bundle.EMPTY
}
