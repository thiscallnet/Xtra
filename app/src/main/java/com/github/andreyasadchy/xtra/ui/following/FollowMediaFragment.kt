package com.github.andreyasadchy.xtra.ui.following

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.following.channels.FollowedChannelsFragment
import com.github.andreyasadchy.xtra.ui.following.games.FollowedGamesFragment
import com.github.andreyasadchy.xtra.ui.following.streams.FollowedStreamsFragment
import com.github.andreyasadchy.xtra.ui.following.videos.FollowedVideosFragment
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.settings.setTabCustomizationLongPress
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class FollowMediaFragment : Fragment(), Scrollable, FragmentHost {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!

    private var previousItem = -1
    private var tabKeys: List<String> = emptyList()

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentById(R.id.fragmentContainer)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previousItem = savedInstanceState?.getInt("previousItem", -1) ?: -1
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val activity = requireActivity() as MainActivity
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.statistics -> {
                        activity.openStatistics()
                        true
                    }
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java))
                        }
                        true
                    }
                    else -> false
                }
            }
            val showVideosTab = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank()
            val tabPreference = requireContext().prefs().getString(C.UI_FOLLOWING_TABS, null)
            val tabList = FollowingTabs.resolve(tabPreference)
            val tabs = FollowingTabs.visibleKeys(tabList, showVideosTab)
            tabKeys = tabs
            if (tabs.size > 1) {
                spinner.visibility = View.VISIBLE
            }
            spinner.setTabCustomizationLongPress(requireContext(), C.UI_FOLLOWING_TABS)
            spinner.editText?.setTabCustomizationLongPress(requireContext(), C.UI_FOLLOWING_TABS)
            (spinner.editText as? MaterialAutoCompleteTextView)?.apply {
                setSimpleItems(tabs.map { getString(FollowingTabs.titleRes(it)) }.toTypedArray().ifEmpty { arrayOf(getString(R.string.live)) })
                setOnItemClickListener { _, _, position, _ ->
                    if (position != previousItem) {
                        childFragmentManager.beginTransaction().replace(R.id.fragmentContainer, onSpinnerItemSelected(tabs, position)).commit()
                        previousItem = position
                    }
                }
                if (previousItem == -1) {
                    val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
                    val position = tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("1").takeIf { it != -1 } ?: 0
                    childFragmentManager.beginTransaction().replace(R.id.fragmentContainer, onSpinnerItemSelected(tabs, position)).commit()
                    previousItem = position
                }
                if (previousItem <= tabs.lastIndex) {
                    setText(adapter.getItem(previousItem).toString(), false)
                }
            }
            childFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    f.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
                        appBar.setLiftOnScrollTargetView(it)
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                appBar.isLifted = recyclerView.canScrollVertically(-1)
                            }
                        })
                        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            appBar.isLifted = it.canScrollVertically(-1)
                        }
                    }
                    if (f is Sortable) {
                        f.setupSortBar(sortBar)
                    } else {
                        sortBar.root.visibility = View.GONE
                    }
                }
            }, false)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }
    }

    private fun onSpinnerItemSelected(tabs: List<String>, position: Int): Fragment {
        return when (tabs.getOrNull(position)) {
            "0" -> FollowedGamesFragment()
            "1" -> FollowedStreamsFragment()
            "2" -> FollowedVideosFragment()
            "3" -> FollowedChannelsFragment()
            else -> FollowedStreamsFragment()
        }
    }

    fun selectFollowingTab(key: String) {
        val position = tabKeys.indexOf(key).takeIf { it >= 0 } ?: return
        if (position == previousItem) return
        previousItem = position
        (binding.spinner.editText as? MaterialAutoCompleteTextView)?.let { spinner ->
            spinner.setText(spinner.adapter?.getItem(position)?.toString().orEmpty(), false)
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, onSpinnerItemSelected(tabKeys, position))
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("previousItem", previousItem)
        super.onSaveInstanceState(outState)
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
