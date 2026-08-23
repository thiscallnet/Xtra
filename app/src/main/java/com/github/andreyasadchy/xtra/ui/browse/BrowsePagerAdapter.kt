package com.github.andreyasadchy.xtra.ui.browse

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.andreyasadchy.xtra.ui.games.GamesFragment
import com.github.andreyasadchy.xtra.ui.games.GamesFragmentArgs
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragment
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragmentArgs

class BrowsePagerAdapter(
    fragment: Fragment,
) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> GamesFragment().apply {
                arguments = GamesFragmentArgs().toBundle()
            }
            else -> TopStreamsFragment().apply {
                arguments = TopStreamsFragmentArgs().toBundle()
            }
        }
    }

    override fun getItemCount(): Int = 2
}
