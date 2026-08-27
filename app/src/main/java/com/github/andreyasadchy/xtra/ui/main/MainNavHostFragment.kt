package com.github.andreyasadchy.xtra.ui.main

import androidx.navigation.Navigator
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment

/** Nav host that keeps the main tab fragment views attached between tab switches. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainNavHostFragment : NavHostFragment() {

    @Suppress("DEPRECATION")
    override fun createFragmentNavigator(): Navigator<out FragmentNavigator.Destination> {
        return KeepStateFragmentNavigator(requireContext(), childFragmentManager, id)
    }
}
