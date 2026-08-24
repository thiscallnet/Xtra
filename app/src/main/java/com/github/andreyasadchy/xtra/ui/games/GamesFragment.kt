package com.github.andreyasadchy.xtra.ui.games

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentGamesBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.common.GamesAdapter
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.games.GamesViewModel.Companion.GamesViewModelFactory
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.repository.streamfeed.RefreshReason
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GamesFragment : PagedListFragment(), Scrollable, GamesSortDialog.OnFilter {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!
    private val args: GamesFragmentArgs by navArgs()
    private val viewModel: GamesViewModel by viewModels { GamesViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<Game, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGamesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
                                setPositiveButton(getString(R.string.yes)) { _, _ -> activity.logoutResultLauncher?.launch(Intent(activity, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_LOGOUT, true)) }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                        }
                        true
                    }
                    else -> false
                }
            }
            recyclerViewLayout.recyclerView.let {
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
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                if (activity.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                    val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                    recyclerViewLayout.recyclerView.updatePadding(bottom = systemBars.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }
        }
        pagingAdapter = GamesAdapter(this) { addTag(it) }
        setAdapter(binding.recyclerViewLayout.recyclerView, pagingAdapter)
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (viewModel.filter.value == null) {
                viewModel.setFilter(viewModel.tags.ifEmpty { args.tags })
                viewModel.filtersText.value = if (viewModel.tags.isNotEmpty()) {
                    buildString {
                        append(
                            resources.getQuantityString(
                                R.plurals.tags,
                                viewModel.tags.size,
                                viewModel.tags.mapNotNull { it.name }.joinToString()
                            )
                        )
                    }
                } else null
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData ->
                    pagingAdapter.submitData(pagingData)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(5 * 60_000L)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        viewModel.refreshCurrent(RefreshReason.SCREEN_VISIBLE)
                    }
                }
            }
        }
        val enableScrollTopButton = !args.tags.isNullOrEmpty()
        initializeAdapter(binding.recyclerViewLayout, pagingAdapter, enableScrollTopButton = enableScrollTopButton)
        if (enableScrollTopButton && requireContext().prefs().getBoolean(C.UI_SCROLL_TOP, true)) {
            binding.recyclerViewLayout.scrollTop.setOnClickListener {
                scrollToTop()
                it.visibility = View.GONE
            }
        }
        with(binding) {
            sortBar.root.visibility = View.VISIBLE
            sortBar.root.setOnClickListener {
                val tags = viewModel.tags.mapNotNull { tag ->
                    if (tag.id != null && tag.name != null) {
                        tag.id to tag.name
                    } else null
                }.toMap()
                GamesSortDialog.newInstance(
                    tagIds = tags.keys.toTypedArray(),
                    tagNames = tags.values.toTypedArray(),
                ).show(childFragmentManager, null)
            }
            sortBar.sortText.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.filtersText.collectLatest {
                        if (it != null) {
                            sortBar.filtersText.visibility = View.VISIBLE
                            sortBar.filtersText.text = it
                        } else {
                            sortBar.filtersText.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun addTag(tag: Tag) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = viewModel.tags.plus(tag).sortedBy { it.id }.toTypedArray()
            viewModel.setFilter(tags)
            viewModel.filtersText.value = buildString {
                append(
                    resources.getQuantityString(
                        R.plurals.tags,
                        viewModel.tags.size,
                        viewModel.tags.mapNotNull { it.name }.joinToString()
                    )
                )
            }
        }
    }

    override fun onChange(tags: Array<Tag>) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.setFilter(tags)
            viewModel.filtersText.value = if (viewModel.tags.isNotEmpty()) {
                buildString {
                    append(
                        resources.getQuantityString(
                            R.plurals.tags,
                            viewModel.tags.size,
                            viewModel.tags.mapNotNull { it.name }.joinToString()
                        )
                    )
                }
            } else null
        }
    }

    override fun scrollToTop() {
        with(binding) {
            appBar.setExpanded(true, true)
            recyclerViewLayout.recyclerView.scrollToPosition(0)
        }
    }

    override fun onNetworkRestored() {
        viewModel.refreshCurrent(RefreshReason.NETWORK_RESTORED, force = true)
        pagingAdapter.retry()
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filter.filterNotNull().first()
            viewModel.setVisibleFeed()
            viewModel.refreshCurrent(RefreshReason.SCREEN_VISIBLE)
        }
    }

    override fun onPause() {
        viewModel.clearVisibleFeed()
        super.onPause()
    }

    override fun onIntegrityTokenLoaded(callback: String?) {
        when (callback) {
            "refresh" -> {
                pagingAdapter.refresh()
            }
        }
    }

    override fun onDestroyView() {
        viewModel.clearVisibleFeed()
        super.onDestroyView()
        _binding = null
    }
}
