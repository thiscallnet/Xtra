package com.github.andreyasadchy.xtra.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaPagerBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.PlayerOverlayTabAvoidance
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.games.GamesFragment
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.tabs.TabLayoutMediator

class BrowsePagerFragment : Fragment(), Scrollable, FragmentHost {

    private var _binding: FragmentMediaPagerBinding? = null
    private var firstLaunch = true
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabLayoutMediator: TabLayoutMediator? = null
    private val configuredRecyclerViews = mutableSetOf<RecyclerView>()
    private var playerOverlayTabAvoidance: PlayerOverlayTabAvoidance? = null
    private val pageLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (_binding == null) return
            hideNestedToolbar(f)
            if (f === currentFragment) configurePage(f)
        }
    }

    override val currentFragment: Fragment?
        get() {
            val pageBinding = _binding ?: return null
            if (!isAdded) return null
            return childFragmentManager.findFragmentByTag("f${pageBinding.viewPager.currentItem}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaPagerBinding.inflate(inflater, container, false)
        return requireNotNull(_binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pageBinding = requireNotNull(_binding)
        with(pageBinding) {
            val activity = requireActivity() as MainActivity
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.rootGamesFragment,
                    R.id.rootTopFragment,
                    R.id.followPagerFragment,
                    R.id.followMediaFragment,
                    R.id.savedPagerFragment,
                    R.id.savedMediaFragment,
                )
            )
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()

            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        navController.navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) {
                                    _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true))
                                }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java))
                        }
                        true
                    }
                    else -> false
                }
            }

            val adapter = BrowsePagerAdapter(this@BrowsePagerFragment)
            viewPager.adapter = adapter
            viewPager.configureForSmoothPaging()
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (_binding !== pageBinding) return
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
                    if (_binding !== pageBinding) return
                    viewPager.doOnLayout {
                        if (_binding !== pageBinding) return@doOnLayout
                        configureCurrentPage(pageBinding)
                        viewPager.post {
                            configureCurrentPage(pageBinding)
                        }
                    }
                }
            }
            pageChangeCallback = callback
            viewPager.registerOnPageChangeCallback(callback)
            childFragmentManager.registerFragmentLifecycleCallbacks(pageLifecycleCallbacks, false)
            if (firstLaunch) {
                viewPager.setCurrentItem(0, false)
                firstLaunch = false
            }
            tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = getString(if (position == 0) R.string.following_categories else R.string.channels)
            }.also { it.attach() }
            viewPager.doOnLayout { configureCurrentPage(pageBinding) }
        }
        playerOverlayTabAvoidance?.close()
        playerOverlayTabAvoidance = requireActivity().findViewById<View>(R.id.playerContainer)?.let { playerContainer ->
            PlayerOverlayTabAvoidance(pageBinding.appBar, pageBinding.tabLayout, pageBinding.viewPager, playerContainer)
        }
    }

    private fun configureCurrentPage(pageBinding: FragmentMediaPagerBinding) {
        if (_binding !== pageBinding) return
        configurePage(currentFragment)
    }

    private fun configurePage(fragment: Fragment?) {
        fragment ?: return
        val pageBinding = _binding ?: return
        hideNestedToolbar(fragment)
        fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
            pageBinding.appBar.setLiftOnScrollTargetView(recyclerView)
            val lifted = recyclerView.canScrollVertically(-1)
            if (pageBinding.appBar.isLifted != lifted) pageBinding.appBar.isLifted = lifted
            if (configuredRecyclerViews.add(recyclerView)) {
                recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (_binding === pageBinding && fragment === currentFragment) {
                            val lifted = recyclerView.canScrollVertically(-1)
                            if (pageBinding.appBar.isLifted != lifted) pageBinding.appBar.isLifted = lifted
                        }
                    }
                })
                recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    if (_binding === pageBinding && fragment === currentFragment) {
                        val lifted = recyclerView.canScrollVertically(-1)
                        if (pageBinding.appBar.isLifted != lifted) pageBinding.appBar.isLifted = lifted
                    }
                }
            }
        }
        if (fragment is GamesFragment) {
            pageBinding.sortBar.root.visibility = View.GONE
        } else if (fragment is Sortable) {
            fragment.setupSortBar(pageBinding.sortBar)
        } else {
            pageBinding.sortBar.root.visibility = View.GONE
        }
    }

    private fun hideNestedToolbar(fragment: Fragment) {
        if (fragment is GamesFragment || fragment is TopStreamsFragment) {
            fragment.view?.findViewById<View>(R.id.toolbar)?.visibility = View.GONE
        }
    }

    override fun scrollToTop() {
        val pageBinding = _binding ?: return
        pageBinding.appBar.setExpanded(true, true)
        currentFragment?.takeIf { it.view != null }?.let { (it as? Scrollable)?.scrollToTop() }
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { _binding?.viewPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        childFragmentManager.unregisterFragmentLifecycleCallbacks(pageLifecycleCallbacks)
        dispatchPagerScrollState(false)
        configuredRecyclerViews.clear()
        playerOverlayTabAvoidance?.close()
        playerOverlayTabAvoidance = null
        _binding = null
        super.onDestroyView()
    }
}
