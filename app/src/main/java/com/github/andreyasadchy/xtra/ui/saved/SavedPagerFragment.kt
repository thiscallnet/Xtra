package com.github.andreyasadchy.xtra.ui.saved

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaPagerBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.RecyclerViewLiftTargetConnector
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.saved.SavedPagerViewModel.Companion.SavedPagerViewModelFactory
import com.github.andreyasadchy.xtra.ui.saved.downloads.DownloadsFragment
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.settings.setTabCustomizationLongPress
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class SavedPagerFragment : Fragment(), Scrollable, FragmentHost {

    private var _binding: FragmentMediaPagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SavedPagerViewModel by viewModels { SavedPagerViewModelFactory }
    private var firstLaunch = true
    private var liftTargetConnector: RecyclerViewLiftTargetConnector? = null
    private var selectedPageLifecycleCallbacks: FragmentManager.FragmentLifecycleCallbacks? = null
    private var folderResultLauncher: ActivityResultLauncher<Intent>? = null
    private var fileResultLauncher: ActivityResultLauncher<Intent>? = null

    override val currentFragment: Fragment?
        get() = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
        folderResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let {
                    requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    viewModel.saveFolders(it.toString())
                }
            }
        }
        fileResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val list = mutableListOf<String>()
                result.data?.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        val item = clipData.getItemAt(i)
                        item.uri?.let {
                            requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            list.add(it.toString())
                        }
                    }
                } ?: result.data?.data?.let {
                    requireContext().contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    list.add(it.toString())
                }
                viewModel.saveVideos(list)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaPagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        liftTargetConnector = RecyclerViewLiftTargetConnector(binding.appBar)
        with(binding) {
            val activity = requireActivity() as MainActivity
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(setOf(R.id.rootGamesFragment, R.id.rootTopFragment, R.id.followPagerFragment, R.id.followMediaFragment, R.id.savedPagerFragment, R.id.savedMediaFragment))
            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search, R.id.searchOverflow -> {
                        findNavController().navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
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
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java))
                        }
                        true
                    }
                    R.id.importFolders -> {
                        folderResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        true
                    }
                    R.id.importFiles -> {
                        fileResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        })
                        true
                    }
                    else -> false
                }
            }
            val tabList = requireContext().prefs().getString(C.UI_SAVED_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_SAVED_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.mapNotNull {
                val split = it.split(':')
                val key = split[0]
                val enabled = split[2] != "0"
                if (enabled) {
                    key
                } else {
                    null
                }
            }
            if (tabs.size <= 1) {
                tabLayout.visibility = View.GONE
            } else {
                if (tabs.size >= 5) {
                    tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
                    tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                }
            }
            val adapter = SavedPagerAdapter(this@SavedPagerFragment, tabs)
            val fragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fragmentManager: FragmentManager,
                    fragment: Fragment,
                    view: View,
                    savedInstanceState: Bundle?,
                ) {
                    val position = viewPager.currentItem
                    if (fragment.tag == "f$position") {
                        updateSelectedPageChrome(position, tabs, fragment)
                    }
                }
            }
            childFragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, false)
            selectedPageLifecycleCallbacks = fragmentLifecycleCallbacks
            viewPager.adapter = adapter
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
                    sortBar.root.visibility = View.GONE
                    liftTargetConnector?.disconnect()
                    setDownloadsActionsVisible(tabs.getOrNull(position) == "1")
                    viewPager.doOnLayout {
                        updateSelectedPageChrome(position, tabs)
                    }
                }
            })
            if (firstLaunch) {
                val defaultItem = tabList.find { it.split(':')[1] != "0" }?.split(':')[0] ?: "1"
                viewPager.setCurrentItem(
                    tabs.indexOf(defaultItem).takeIf { it != -1 } ?: tabs.indexOf("1").takeIf { it != -1 } ?: 0,
                    false
                )
                firstLaunch = false
            }
            viewPager.configureForSmoothPaging()
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = when (tabs.getOrNull(position)) {
                    "0" -> getString(R.string.bookmarks)
                    "1" -> getString(R.string.downloads)
                    "2" -> getString(R.string.filters)
                    "3" -> getString(R.string.clips)
                    else -> getString(R.string.bookmarks)
                }
            }.attach()
            tabLayout.setTabCustomizationLongPress(requireContext(), C.UI_SAVED_TABS)
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                windowInsets
            }
        }
    }

    private fun updateSelectedPageChrome(position: Int, tabs: List<String>, selectedFragment: Fragment? = null) {
        val currentBinding = _binding ?: return
        if (currentBinding.viewPager.currentItem != position) return

        val fragment = selectedFragment ?: childFragmentManager.findFragmentByTag("f$position") ?: return
        if (fragment.tag != "f$position" || fragment.view == null) return

        fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let {
            liftTargetConnector?.connect(it)
        }
        if (fragment is Sortable) {
            fragment.setupSortBar(currentBinding.sortBar)
        } else {
            currentBinding.sortBar.root.visibility = View.GONE
        }
        setDownloadsActionsVisible(tabs.getOrNull(position) == "1" && fragment is DownloadsFragment)
    }

    private fun setDownloadsActionsVisible(isDownloads: Boolean) {
        val toolbar = _binding?.toolbar ?: return
        toolbar.menu.findItem(R.id.search).isVisible = !isDownloads
        toolbar.menu.findItem(R.id.searchOverflow).isVisible = isDownloads
        toolbar.menu.findItem(R.id.importFolders).isVisible = isDownloads
        toolbar.menu.findItem(R.id.importFiles).isVisible = isDownloads
    }

    override fun scrollToTop() {
        binding.appBar.setExpanded(true, true)
        (currentFragment as? Scrollable)?.scrollToTop()
    }

    override fun onDestroyView() {
        dispatchPagerScrollState(false)
        selectedPageLifecycleCallbacks?.let(childFragmentManager::unregisterFragmentLifecycleCallbacks)
        selectedPageLifecycleCallbacks = null
        liftTargetConnector?.disconnect()
        liftTargetConnector = null
        super.onDestroyView()
        _binding = null
    }
}
