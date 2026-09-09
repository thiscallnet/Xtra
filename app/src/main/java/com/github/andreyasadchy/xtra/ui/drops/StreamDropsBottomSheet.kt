package com.github.andreyasadchy.xtra.ui.drops

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.DialogStreamDropsBinding
import com.github.andreyasadchy.xtra.databinding.ItemStreamDropBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.TwitchChannelDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchChannelDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant

class StreamDropsBottomSheet : BottomSheetDialogFragment() {
    private var _binding: DialogStreamDropsBinding? = null
    private val binding get() = _binding!!
    private lateinit var stream: Stream
    private lateinit var adapter: StreamDropsAdapter
    private var catalog: List<TwitchChannelDropCampaign>? = null
    private var inventory: List<TwitchDrop> = emptyList()
    private var loading = true

    private val repository
        get() = (requireContext().applicationContext as XtraApp).xtraModule.dropsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stream = requireArguments().parcelable<Stream>(ARG_STREAM) ?: Stream()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also { dialog ->
            dialog.setOnShowListener {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                    BottomSheetBehavior.from(sheet).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogStreamDropsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = StreamDropsAdapter()
        binding.dropsList.adapter = adapter
        binding.title.text = getString(
            R.string.stream_drops_title,
            stream.channelName ?: stream.channelLogin ?: getString(R.string.live),
        )
        binding.subtitle.text = stream.gameName.orEmpty()
        binding.subtitle.isVisible = binding.subtitle.text.isNotBlank()
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.inventory.collectLatest { state ->
                        inventory = state.drops
                        render()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            load()
        }
    }

    private suspend fun load() {
        val channelId = stream.channelId?.takeIf { it.isNotBlank() }
        if (channelId == null) {
            loading = false
            catalog = null
            render()
            return
        }

        try {
            supervisorScope {
                val inventoryRequest = async {
                    runCatching {
                        withTimeout(30_000L) { repository.refreshInventory() }
                    }.getOrNull()
                }
                val catalogRequest = async {
                    runCatching {
                        withTimeout(30_000L) {
                            repository.refreshChannelDropCatalog(channelId)
                        }
                    }.getOrNull()
                }
                inventoryRequest.await()?.let { inventory = it.drops }
                catalog = catalogRequest.await()
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            loading = false
            render()
        }
    }

    private fun render() {
        if (_binding == null) return
        binding.loading.isVisible = loading
        if (loading) {
            binding.empty.isVisible = false
            binding.dropsList.isVisible = false
            return
        }

        val campaigns = catalog
        if (campaigns == null) {
            binding.dropsList.isVisible = false
            binding.empty.isVisible = true
            val authenticated = repository.inventory.value.authenticated
            binding.empty.text = getString(
                if (authenticated) R.string.drops_load_failed else R.string.drops_sign_in_required,
            )
            return
        }
        if (campaigns.isEmpty()) {
            binding.dropsList.isVisible = false
            binding.empty.isVisible = true
            binding.empty.text = getString(R.string.stream_drops_empty)
            return
        }

        binding.empty.isVisible = false
        binding.dropsList.isVisible = true
        adapter.submitList(campaigns, inventory)
    }

    override fun onDestroyView() {
        binding.dropsList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_STREAM = "stream"
        private const val TAG = "streamDrops"

        fun show(fragment: Fragment, stream: Stream) {
            if (!fragment.isAdded || fragment.childFragmentManager.isStateSaved) return
            if (fragment.childFragmentManager.findFragmentByTag(TAG) != null) return
            newInstance(stream).show(fragment.childFragmentManager, TAG)
        }

        private fun newInstance(stream: Stream) = StreamDropsBottomSheet().apply {
            arguments = Bundle().apply { putParcelable(ARG_STREAM, stream) }
        }
    }
}

private sealed interface StreamDropsRow {
    data class Campaign(val value: TwitchChannelDropCampaign) : StreamDropsRow
    data class Drop(
        val campaign: TwitchChannelDropCampaign,
        val value: TwitchChannelDrop,
        val accountDrop: TwitchDrop?,
    ) : StreamDropsRow
}

private class StreamDropsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<StreamDropsRow> = emptyList()

    fun submitList(
        campaigns: List<TwitchChannelDropCampaign>,
        inventory: List<TwitchDrop>,
    ) {
        rows = campaigns.flatMap { campaign ->
            listOf(StreamDropsRow.Campaign(campaign)) + campaign.drops.map { drop ->
                StreamDropsRow.Drop(
                    campaign = campaign,
                    value = drop,
                    accountDrop = inventory.firstOrNull { it.id == drop.id },
                )
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is StreamDropsRow.Campaign) CAMPAIGN else DROP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == CAMPAIGN) {
            CampaignViewHolder(TextView(parent.context).apply {
                setPadding(4, 18, 4, 6)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            })
        } else {
            DropViewHolder(ItemStreamDropBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is StreamDropsRow.Campaign -> (holder as CampaignViewHolder).bind(row.value)
            is StreamDropsRow.Drop -> (holder as DropViewHolder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    private class CampaignViewHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(campaign: TwitchChannelDropCampaign) {
            view.text = listOfNotNull(
                campaign.name ?: campaign.localizedTitle,
                campaign.gameName,
                campaign.endTime?.formatCampaignEnd(view.context),
            ).joinToString(" · ")
        }
    }

    private class DropViewHolder(private val binding: ItemStreamDropBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: StreamDropsRow.Drop) {
            val context = binding.root.context
            val drop = row.value
            val accountDrop = row.accountDrop
            val rewardNames = drop.benefits.mapNotNull { it.name }.distinct()
            binding.title.text = drop.name ?: rewardNames.firstOrNull() ?: context.getString(R.string.drops)
            binding.reward.text = rewardNames
                .filterNot { it == binding.title.text }
                .joinToString(", ")
            binding.reward.isVisible = binding.reward.text.isNotBlank()
            binding.requirement.text = requirementText(context, drop)

            val hasWatchRequirement = drop.requiredMinutesWatched > 0
            val isClaimed = accountDrop?.isClaimed == true
            val isClaimable = accountDrop?.isClaimable == true
            binding.progress.isVisible = hasWatchRequirement && accountDrop != null && !isClaimed
            binding.progressText.isVisible = hasWatchRequirement
            binding.progressText.text = if (accountDrop == null) {
                context.getString(R.string.stream_drops_watch_requirement, drop.requiredMinutesWatched)
            } else {
                context.getString(
                    R.string.stream_drops_progress_minutes,
                    accountDrop.currentMinutesWatched,
                    drop.requiredMinutesWatched,
                )
            }
            if (binding.progress.isVisible) binding.progress.progress = accountDrop?.progressPercent ?: 0
            binding.status.text = when {
                isClaimed -> context.getString(R.string.drops_claimed)
                isClaimable -> context.getString(R.string.stream_drops_ready)
                accountDrop != null && hasWatchRequirement -> context.getString(R.string.stream_drops_watching)
                else -> ""
            }
            binding.status.isVisible = binding.status.text.isNotBlank()

            val image = drop.benefits.firstOrNull()?.imageUrl ?: row.campaign.imageUrl
            binding.image.contentDescription = rewardNames.firstOrNull() ?: drop.name
            if (image.isNullOrBlank()) binding.image.setImageResource(R.drawable.ic_drops) else binding.image.load(image)
        }

        private fun requirementText(context: Context, drop: TwitchChannelDrop): String = buildList {
            if (drop.requiredMinutesWatched > 0) {
                add(context.getString(R.string.stream_drops_watch_requirement, drop.requiredMinutesWatched))
            }
            if (drop.requiredSubs > 0) {
                add(context.resources.getQuantityString(R.plurals.stream_drops_sub_requirement, drop.requiredSubs, drop.requiredSubs))
            }
            if (isEmpty() && drop.isEventBased) add(context.getString(R.string.stream_drops_event_requirement))
        }.joinToString(" · ")
    }

    private companion object {
        const val CAMPAIGN = 0
        const val DROP = 1
    }
}

private fun String.formatCampaignEnd(context: Context): String? {
    val timestamp = Instant.parseOrNull(this)?.toEpochMilliseconds()
        ?: return null
    return context.getString(
        R.string.stream_drops_ends,
        DateUtils.formatDateTime(
            context,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        ),
    )
}

private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
