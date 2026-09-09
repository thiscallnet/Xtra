package com.github.andreyasadchy.xtra.ui.drops

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.DialogDropFiltersBinding
import com.github.andreyasadchy.xtra.databinding.ItemDropFilterBinding
import com.github.andreyasadchy.xtra.model.ui.DropStreamFilter
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class DropFiltersBottomSheet : BottomSheetDialogFragment() {
    private var _binding: DialogDropFiltersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DropFilterAdapter
    private var campaigns = emptyList<TwitchDropCampaign>()
    private var inventory = emptyList<TwitchDrop>()
    private var selectedKeys = emptySet<String>()
    private var pendingCampaignSelection = emptySet<String>()
    private var query = ""
    private var loading = true

    private val repository
        get() = (requireContext().applicationContext as XtraApp).xtraModule.dropsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = initialFilters()
        selectedKeys = savedInstanceState?.getStringArrayList(SELECTED_KEYS)?.toSet()
            ?: initial.let { filters ->
                filters.flatMap { filter ->
                    if (filter.dropIds.isEmpty()) {
                        listOf(filter.campaignId)
                    } else {
                        filter.dropIds.map { dropId -> optionKey(filter.campaignId, dropId) }
                    }
                }.toSet()
            }
        pendingCampaignSelection = if (savedInstanceState == null) initial.mapTo(mutableSetOf(), DropStreamFilter::campaignId) else emptySet()
        query = savedInstanceState?.getString(QUERY).orEmpty()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { dialog ->
            dialog.setOnShowListener {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                    BottomSheetBehavior.from(sheet).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogDropFiltersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = DropFilterAdapter { key ->
            selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
            render()
        }
        binding.list.adapter = adapter
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.searchView.setQuery(query, false)
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(newQuery: String): Boolean = false

            override fun onQueryTextChange(newQuery: String): Boolean {
                query = newQuery.trim()
                render()
                return true
            }
        })
        binding.cancel.setOnClickListener { dismiss() }
        binding.apply.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                RESULT_KEY,
                Bundle().apply {
                    putParcelableArrayList(FILTERS_KEY, ArrayList(selectedFilters()))
                },
            )
            dismiss()
        }
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.dashboard.collect { value ->
                        campaigns = value
                        loading = false
                        render()
                    }
                }
                launch {
                    repository.inventory.collect { state ->
                        inventory = state.drops
                        render()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            if (repository.dashboard.value.isEmpty()) {
                try {
                    withTimeout(45_000L) { repository.refreshDashboard(force = false) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    loading = false
                    render()
                }
            } else {
                loading = false
                render()
            }
        }
    }

    private fun render() {
        if (_binding == null) return
        val options = options()
        if (!loading && pendingCampaignSelection.isNotEmpty()) {
            selectedKeys = selectedKeys + options
                .filter { it.campaignId in pendingCampaignSelection }
                .mapTo(mutableSetOf(), DropFilterOption::key)
            pendingCampaignSelection = emptySet()
        }
        binding.loading.isVisible = loading
        binding.empty.isVisible = !loading && options.isEmpty()
        binding.list.isVisible = options.isNotEmpty()
        binding.apply.isEnabled = selectedKeys.isNotEmpty()
        adapter.submitList(options, selectedKeys)
    }

    private fun options(filterQuery: String = query): List<DropFilterOption> {
        val normalizedQuery = filterQuery.lowercase()
        return campaigns
            .filter(::campaignCanFindLiveStreams)
            .flatMap { campaign ->
                if (campaign.drops.isEmpty()) {
                    listOf(
                        DropFilterOption(
                            key = campaign.id,
                            campaignId = campaign.id,
                            campaignName = campaign.name ?: campaign.gameName.orEmpty(),
                            gameId = campaign.gameId,
                            gameName = campaign.gameName.orEmpty(),
                            dropId = null,
                            name = campaign.name ?: campaign.gameName,
                            imageUrl = campaign.imageUrl,
                            requiredMinutesWatched = 0,
                            currentMinutesWatched = 0,
                            progressPercent = 0,
                        ),
                    )
                } else {
                    campaign.drops.map { drop ->
                        val accountDrop = inventory.firstOrNull { it.id == drop.id }
                        DropFilterOption(
                            key = optionKey(campaign.id, drop.id),
                            campaignId = campaign.id,
                            campaignName = campaign.name ?: campaign.gameName.orEmpty(),
                            gameId = campaign.gameId,
                            gameName = campaign.gameName.orEmpty(),
                            dropId = drop.id,
                            name = drop.name ?: drop.benefits.firstOrNull()?.name ?: getString(R.string.drops),
                            imageUrl = drop.benefits.firstOrNull()?.imageUrl ?: campaign.imageUrl,
                            requiredMinutesWatched = drop.requiredMinutesWatched,
                            currentMinutesWatched = accountDrop?.currentMinutesWatched ?: 0,
                            progressPercent = accountDrop?.progressPercent ?: 0,
                        )
                    }
                }
            }
            .filter { option ->
                normalizedQuery.isBlank() || listOfNotNull(
                    option.name,
                    option.campaignName,
                    option.gameName,
                ).any { it.contains(normalizedQuery, ignoreCase = true) }
            }
    }

    private fun selectedFilters(): List<DropStreamFilter> =
        options(filterQuery = "").filter { it.key in selectedKeys }
            .groupBy(DropFilterOption::campaignId)
            .values
            .map { selected ->
                val first = selected.first()
                DropStreamFilter(
                    campaignId = first.campaignId,
                    campaignName = first.campaignName,
                    gameId = first.gameId,
                    gameName = first.gameName,
                    dropIds = selected.mapNotNull(DropFilterOption::dropId).toSet(),
                    dropNames = selected.mapNotNull(DropFilterOption::name),
                )
            }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(SELECTED_KEYS, ArrayList(selectedKeys))
        outState.putString(QUERY, query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun initialFilters(): List<DropStreamFilter> =
        arguments?.parcelableArrayList<DropStreamFilter>(INITIAL_FILTERS).orEmpty()

    companion object {
        const val TAG = "drop-filters"
        const val RESULT_KEY = "drop-filters-result"
        const val FILTERS_KEY = "drop-filters"
        private const val INITIAL_FILTERS = "initial-filters"
        private const val SELECTED_KEYS = "selected-keys"
        private const val QUERY = "query"

        fun newInstance(filters: List<DropStreamFilter>) = DropFiltersBottomSheet().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(INITIAL_FILTERS, ArrayList(filters))
            }
        }
    }
}

private data class DropFilterOption(
    val key: String,
    val campaignId: String,
    val campaignName: String,
    val gameId: String?,
    val gameName: String,
    val dropId: String?,
    val name: String?,
    val imageUrl: String?,
    val requiredMinutesWatched: Int,
    val currentMinutesWatched: Int,
    val progressPercent: Int,
)

private class DropFilterAdapter(
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<DropFilterAdapter.ViewHolder>() {
    private var options = emptyList<DropFilterOption>()
    private var selectedKeys = emptySet<String>()

    fun submitList(value: List<DropFilterOption>, selected: Set<String>) {
        options = value
        selectedKeys = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemDropFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(options[position], options[position].key in selectedKeys, onToggle)
    }

    override fun getItemCount(): Int = options.size

    class ViewHolder(private val binding: ItemDropFilterBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(option: DropFilterOption, selected: Boolean, onToggle: (String) -> Unit) {
            val context = binding.root.context
            binding.title.text = option.name ?: context.getString(R.string.drops)
            binding.subtitle.text = listOfNotNull(
                option.campaignName,
                option.gameName,
                option.requiredMinutesWatched.takeIf { it > 0 }?.let {
                    if (option.currentMinutesWatched > 0) {
                        context.getString(
                            R.string.stream_drops_progress_minutes,
                            option.currentMinutesWatched,
                            it,
                        )
                    } else {
                        context.getString(R.string.stream_drops_watch_requirement, it)
                    }
                },
            ).distinct().joinToString(" · ")
            binding.progress.isVisible = option.requiredMinutesWatched > 0 && option.currentMinutesWatched > 0
            binding.progress.progress = option.progressPercent
            binding.selected.setOnCheckedChangeListener(null)
            binding.selected.isChecked = selected
            binding.selected.setOnCheckedChangeListener { _, _ -> onToggle(option.key) }
            binding.card.setOnClickListener { onToggle(option.key) }
            binding.image.contentDescription = option.name
            binding.image.loadDropImage(option.imageUrl, com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource.ORIGINAL)
        }
    }
}

private fun optionKey(campaignId: String, dropId: String): String = "$campaignId:$dropId"

private inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
