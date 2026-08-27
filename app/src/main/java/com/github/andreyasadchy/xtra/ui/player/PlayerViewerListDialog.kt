package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentViewerListBinding
import com.github.andreyasadchy.xtra.databinding.FragmentViewerListItemBinding
import com.github.andreyasadchy.xtra.model.ui.ChannelViewer
import com.github.andreyasadchy.xtra.model.ui.ChannelViewerList
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.player.PlayerViewerListViewModel.Companion.PlayerViewerListViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlayerViewerListDialog : BottomSheetDialogFragment() {

    companion object {

        private const val LOGIN = "login"

        fun newInstance(login: String): PlayerViewerListDialog {
            return PlayerViewerListDialog().apply {
                arguments = Bundle().apply {
                    putString(LOGIN, login)
                }
            }
        }
    }

    private var _binding: FragmentViewerListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewerListViewModel by viewModels { PlayerViewerListViewModelFactory }

    private val broadcastersListItems = mutableListOf<ChannelViewer>()
    private var broadcastersListOffset = 0
    private val moderatorsListItems = mutableListOf<ChannelViewer>()
    private var moderatorsListOffset = 0
    private val vipsListItems = mutableListOf<ChannelViewer>()
    private var vipsListOffset = 0
    private val viewerListItems = mutableListOf<ChannelViewer>()
    private var viewerListOffset = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentViewerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val login = requireArguments().getString(LOGIN)
        val networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), includeToken = true)
        fun loadViewerList() {
            viewModel.loadViewerList(
                login,
                networkLibrary,
                gqlHeaders,
            )
        }
        with(binding) {
            retryButton.setOnClickListener { loadViewerList() }
            loadViewerList()
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.isLoading.collect { loading ->
                        loadingState.isVisible = loading
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.hasError.collect { hasError ->
                        errorState.isVisible = hasError
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.viewerList.collectLatest { fullList ->
                        if (fullList != null) {
                            emptyState.isVisible = fullList.broadcasters.isEmpty() &&
                                    fullList.moderators.isEmpty() &&
                                    fullList.vips.isEmpty() &&
                                    fullList.viewers.isEmpty()
                            if (fullList.broadcasters.isNotEmpty()) {
                                broadcasterText.visibility = View.VISIBLE
                                broadcasterText.text = getString(R.string.viewer_list_section, getString(R.string.broadcaster), fullList.broadcasters.size)
                                broadcasterList.apply {
                                    visibility = View.VISIBLE
                                    adapter = Adapter(requireContext(), broadcastersListItems, ::openViewer)
                                }
                                loadItems(fullList, broadcasterList, networkLibrary, gqlHeaders)
                            } else {
                                broadcasterText.visibility = View.GONE
                                broadcasterList.visibility = View.GONE
                            }
                            if (fullList.moderators.isNotEmpty()) {
                                moderatorsText.visibility = View.VISIBLE
                                moderatorsText.text = getString(R.string.viewer_list_section, getString(R.string.moderators), fullList.moderators.size)
                                moderatorsList.apply {
                                    visibility = View.VISIBLE
                                    adapter = Adapter(requireContext(), moderatorsListItems, ::openViewer)
                                }
                                loadItems(fullList, moderatorsList, networkLibrary, gqlHeaders)
                            } else {
                                moderatorsText.visibility = View.GONE
                                moderatorsList.visibility = View.GONE
                            }
                            if (fullList.vips.isNotEmpty()) {
                                vipsText.visibility = View.VISIBLE
                                vipsText.text = getString(R.string.viewer_list_section, getString(R.string.vips), fullList.vips.size)
                                vipsList.apply {
                                    visibility = View.VISIBLE
                                    adapter = Adapter(requireContext(), vipsListItems, ::openViewer)
                                }
                                if (fullList.moderators.size <= 100) {
                                    loadItems(fullList, vipsList, networkLibrary, gqlHeaders)
                                }
                            } else {
                                vipsText.visibility = View.GONE
                                vipsList.visibility = View.GONE
                            }
                            if (fullList.viewers.isNotEmpty()) {
                                viewersText.visibility = View.VISIBLE
                                viewersText.text = getString(R.string.viewer_list_section, getString(R.string.viewers), fullList.viewers.size)
                                viewersList.apply {
                                    visibility = View.VISIBLE
                                    adapter = Adapter(requireContext(), viewerListItems, ::openViewer)
                                }
                                if ((fullList.moderators.size + fullList.vips.size) <= 100) {
                                    loadItems(fullList, viewersList, networkLibrary, gqlHeaders)
                                }
                            } else {
                                viewersText.visibility = View.GONE
                                viewersList.visibility = View.GONE
                            }
                            if (fullList.count != null) {
                                userCount.visibility = View.VISIBLE
                                userCount.text = getString(R.string.user_count, TwitchApiHelper.formatCount(fullList.count, requireContext().prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)))
                            } else {
                                userCount.visibility = View.GONE
                            }
                            scrollView.viewTreeObserver.addOnScrollChangedListener {
                                if (!scrollView.canScrollVertically(1)) {
                                    when {
                                        broadcastersListOffset != fullList.broadcasters.size -> loadItems(fullList, broadcasterList, networkLibrary, gqlHeaders)
                                        moderatorsListOffset != fullList.moderators.size -> loadItems(fullList, moderatorsList, networkLibrary, gqlHeaders)
                                        vipsListOffset != fullList.vips.size -> loadItems(fullList, vipsList, networkLibrary, gqlHeaders)
                                        viewerListOffset != fullList.viewers.size -> loadItems(fullList, viewersList, networkLibrary, gqlHeaders)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openViewer(viewer: ChannelViewer) {
        val parent = parentFragment ?: return
        parent.findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = viewer.id,
                channelLogin = viewer.login,
                channelName = viewer.displayName ?: viewer.login,
                channelImage = viewer.profileImageURL,
            )
        )
        (parent as? Media3PlayerFragment)?.minimize() ?:
        (parent as? PlayerFragment)?.minimize()
        dismiss()
    }

    private fun loadItems(
        fullList: ChannelViewerList,
        recyclerView: RecyclerView,
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
    ) {
        val (sourceItems, targetItems) = with(binding) {
            when (recyclerView) {
                broadcasterList -> fullList.broadcasters to broadcastersListItems
                moderatorsList -> fullList.moderators to moderatorsListItems
                vipsList -> fullList.vips to vipsListItems
                viewersList -> fullList.viewers to viewerListItems
                else -> return
            }
        }
        val offset = when (recyclerView) {
            binding.broadcasterList -> broadcastersListOffset
            binding.moderatorsList -> moderatorsListOffset
            binding.vipsList -> vipsListOffset
            binding.viewersList -> viewerListOffset
            else -> return
        }
        val add = minOf(100, sourceItems.size - offset)
        if (add <= 0) return
        val insertAt = targetItems.size
        val items = sourceItems.subList(offset, offset + add).toList()
        targetItems.addAll(items)
        when (recyclerView) {
            binding.broadcasterList -> broadcastersListOffset += add
            binding.moderatorsList -> moderatorsListOffset += add
            binding.vipsList -> vipsListOffset += add
            binding.viewersList -> viewerListOffset += add
        }
        recyclerView.adapter?.notifyItemRangeInserted(insertAt, add)
        viewLifecycleOwner.lifecycleScope.launch {
            val enrichedItems = viewModel.enrichProfiles(networkLibrary, gqlHeaders, items)
            enrichedItems.forEachIndexed { index, item -> targetItems[insertAt + index] = item }
            recyclerView.adapter?.notifyItemRangeChanged(insertAt, enrichedItems.size)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class Adapter internal constructor(
        private val context: Context,
        data: List<ChannelViewer>,
        private val onClick: (ChannelViewer) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {
        private val mData: List<ChannelViewer> = data
        private val mInflater: LayoutInflater = LayoutInflater.from(context)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = mInflater.inflate(R.layout.fragment_viewer_list_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(mData[position])
        }

        override fun getItemCount(): Int {
            return mData.size
        }

        inner class ViewHolder internal constructor(itemView: View) : RecyclerView.ViewHolder(itemView) {

            private val binding = FragmentViewerListItemBinding.bind(itemView)
            private val placeholderTint = ImageViewCompat.getImageTintList(binding.userImage)

            fun bind(viewer: ChannelViewer) = with(binding) {
                val displayName = viewer.displayName?.takeIf { it.isNotBlank() }
                    ?.takeUnless { it.equals(viewer.login, ignoreCase = true) }
                    ?: viewer.login
                val nameDisplayMode = context.prefs().getString(C.UI_NAME_DISPLAY, "0")
                val showLogin = nameDisplayMode == "0" && !displayName.equals(viewer.login, ignoreCase = true)
                val shownName = if (nameDisplayMode == "2") viewer.login else displayName
                root.setOnClickListener { onClick(viewer) }
                root.contentDescription = context.getString(R.string.player_open_channel, shownName)
                userName.text = shownName
                userLogin.isVisible = showLogin
                userLogin.text = "@${viewer.login}"
                userImage.setImageResource(R.drawable.baseline_person_black_24)
                ImageViewCompat.setImageTintList(userImage, placeholderTint)
                viewer.profileImageURL?.takeIf { it.isNotBlank() }?.let { url ->
                    ImageViewCompat.setImageTintList(userImage, null)
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .crossfade(true)
                            .transformations(CircleCropTransformation())
                            .target(userImage)
                            .build()
                    )
                }
            }
        }
    }
}




