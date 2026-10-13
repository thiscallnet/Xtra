package com.github.andreyasadchy.xtra.ui.drops

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.databinding.ItemDropBinding
import com.github.andreyasadchy.xtra.databinding.ItemDropRewardBinding
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropBenefit
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
import com.github.andreyasadchy.xtra.util.isTelevision
import kotlin.time.Instant

sealed interface DropsRow {
    data class Section(val title: String) : DropsRow
    data class Drop(val value: TwitchDrop) : DropsRow
    data class Campaign(val value: TwitchDropCampaign) : DropsRow
}

class DropsAdapter(
    private val onClaim: (TwitchDrop) -> Unit,
    private val onCampaignClick: (String) -> Unit,
    private val onFindStreams: (TwitchDropCampaign) -> Unit,
    private val onFindStreamsForDrop: (TwitchDrop) -> Unit,
    private val onTrack: (TwitchDrop) -> Unit,
    private val onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<DropsRow>() {
        override fun areItemsTheSame(oldItem: DropsRow, newItem: DropsRow): Boolean = when {
            oldItem is DropsRow.Section && newItem is DropsRow.Section -> oldItem.title == newItem.title
            oldItem is DropsRow.Drop && newItem is DropsRow.Drop -> oldItem.value.id == newItem.value.id
            oldItem is DropsRow.Campaign && newItem is DropsRow.Campaign -> oldItem.value.id == newItem.value.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: DropsRow, newItem: DropsRow) = oldItem == newItem

        override fun getChangePayload(oldItem: DropsRow, newItem: DropsRow): Any? = when {
            oldItem is DropsRow.Drop && newItem is DropsRow.Drop && oldItem.value.copy(
                currentMinutesWatched = newItem.value.currentMinutesWatched,
                requiredMinutesWatched = newItem.value.requiredMinutesWatched,
                isClaimed = newItem.value.isClaimed,
                dropInstanceId = newItem.value.dropInstanceId,
                sessionOnly = newItem.value.sessionOnly,
            ) == newItem.value -> STATE
            oldItem is DropsRow.Campaign && newItem is DropsRow.Campaign &&
                oldItem.value.copy(drops = newItem.value.drops) == newItem.value -> STATE
            else -> null
        }
    })
    private val rows: List<DropsRow> get() = differ.currentList
    private var submittedRows: List<DropsRow> = emptyList()
    private var claimingDropId: String? = null
    private var trackedDropId: String? = null
    private val expandedCampaignIds = mutableSetOf<String>()
    private var campaignDetailsLoading: Set<String> = emptySet()

    fun setClaimingDropId(id: String?) {
        if (claimingDropId == id) return
        val changedIds = setOfNotNull(claimingDropId, id)
        val availabilityChanged = (claimingDropId == null) != (id == null)
        claimingDropId = id
        rows.forEachIndexed { index, row ->
            if (row is DropsRow.Drop && (row.value.id in changedIds || availabilityChanged && row.value.isClaimable)) {
                notifyItemChanged(index, STATE)
            }
        }
    }

    fun setTrackedDropId(id: String?) {
        if (trackedDropId == id) return
        val changedIds = setOfNotNull(trackedDropId, id)
        trackedDropId = id
        rows.forEachIndexed { index, row ->
            if (row is DropsRow.Drop && row.value.id in changedIds) notifyItemChanged(index, STATE)
        }
    }

    private fun toggleCampaign(campaignId: String) {
        if (currentCampaign(campaignId) == null) return
        if (!expandedCampaignIds.add(campaignId)) {
            expandedCampaignIds.remove(campaignId)
        }
        rows.indexOfFirst { it is DropsRow.Campaign && it.value.id == campaignId }
            .takeIf { it >= 0 }
            ?.let { notifyItemChanged(it, STATE) }
        onCampaignClick(campaignId)
    }

    fun expandCampaign(campaignId: String) {
        if (!expandedCampaignIds.add(campaignId)) return
        rows.indexOfFirst { it is DropsRow.Campaign && it.value.id == campaignId }
            .takeIf { it >= 0 }
            ?.let { notifyItemChanged(it, STATE) }
        onCampaignClick(campaignId)
    }

    fun setCampaignDetailsLoading(ids: Set<String>) {
        if (campaignDetailsLoading == ids) return
        val changedIds = campaignDetailsLoading xor ids
        campaignDetailsLoading = ids.toSet()
        rows.forEachIndexed { index, row ->
            if (row is DropsRow.Campaign && row.value.id in changedIds) notifyItemChanged(index, STATE)
        }
    }

    fun submitList(value: List<DropsRow>, onCommitted: (() -> Unit)? = null) {
        if (submittedRows != value) submittedRows = value.toList()
        differ.submitList(submittedRows) { onCommitted?.invoke() }
    }

    private fun claimDrop(drop: TwitchDrop) {
        if (claimingDropId != null) return
        // A row can still show the previous model while its diff is in flight.
        // Never claim a removed row or use credentials from a stale submission.
        currentDrop(drop.id)?.takeIf { it.isClaimable }?.let(onClaim)
    }

    private fun currentDrop(id: String): TwitchDrop? =
        submittedRows.asSequence().filterIsInstance<DropsRow.Drop>().firstOrNull { it.value.id == id }?.value

    private fun currentCampaign(id: String): TwitchDropCampaign? =
        submittedRows.asSequence().filterIsInstance<DropsRow.Campaign>().firstOrNull { it.value.id == id }?.value

    fun refreshSectionSpacing() {
        rows.forEachIndexed { index, row ->
            if (row is DropsRow.Section) notifyItemChanged(index, STATE)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is DropsRow.Section) SECTION else CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == SECTION) {
            object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        androidx.appcompat.R.attr.colorPrimary,
                    ),
                )
            }) {}
        } else {
            DropViewHolder(ItemDropBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bind(holder, position, stateOnly = false)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        bind(holder, position, stateOnly = payloads.isNotEmpty() && payloads.all { it == STATE })
    }

    private fun bind(holder: RecyclerView.ViewHolder, position: Int, stateOnly: Boolean) {
        when (val row = rows[position]) {
            is DropsRow.Section -> (holder.itemView as TextView).apply {
                val density = resources.displayMetrics.density
                val compact = resources.configuration.let {
                    !context.isTelevision() &&
                        it.orientation == Configuration.ORIENTATION_LANDSCAPE && it.screenHeightDp < 480
                }
                // Align with the cards' 12dp margin plus a 4dp optical inset.
                setPadding(
                    (16 * density).toInt(),
                    ((if (compact) 6 else 20) * density).toInt(),
                    (16 * density).toInt(),
                    ((if (compact) 4 else 6) * density).toInt(),
                )
                text = row.title
            }
            is DropsRow.Drop -> (holder as DropViewHolder).bind(
                row.value,
                claimingDropId,
                ::claimDrop,
                { currentDrop(it.id)?.let(onFindStreamsForDrop) },
                { currentDrop(it.id)?.let(onTrack) },
                trackedDropId,
                onImageClick,
                bindImages = !stateOnly,
            )
            is DropsRow.Campaign -> (holder as DropViewHolder).bind(
                row.value,
                row.value.id in expandedCampaignIds,
                row.value.id in campaignDetailsLoading,
                ::toggleCampaign,
                { currentCampaign(it.id)?.let(onFindStreams) },
                onImageClick,
                bindImage = !stateOnly,
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    private class DropViewHolder(private val binding: ItemDropBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            drop: TwitchDrop,
            claimingDropId: String?,
            onClaim: (TwitchDrop) -> Unit,
            onFindStreams: (TwitchDrop) -> Unit,
            onTrack: (TwitchDrop) -> Unit,
            trackedDropId: String?,
            onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
            bindImages: Boolean,
        ) {
            binding.title.text = drop.benefits.mapNotNull { it.name }
                .distinct()
                .ifEmpty { listOfNotNull(drop.rewardName ?: drop.name ?: drop.campaignName) }
                .joinToString(", ")
            binding.subtitle.text = listOfNotNull(
                drop.campaignName,
                "${drop.currentMinutesWatched}/${drop.requiredMinutesWatched} min",
            ).joinToString(" · ")
            if (bindImages) {
                bindRewards(
                    drop.benefits,
                    visible = drop.benefits.size > 1,
                    onImageClick = onImageClick,
                )
            }
            binding.detailsLoading.isVisible = false
            binding.progress.isVisible = true
            binding.progress.progress = drop.progressPercent
            binding.progressLabel.isVisible = true
            binding.progressLabel.text = binding.root.context.getString(
                R.string.drops_progress_accessibility,
                drop.currentMinutesWatched,
                drop.requiredMinutesWatched,
                drop.progressPercent,
            )
            binding.progress.contentDescription = binding.progressLabel.text
            binding.claimButton.isVisible = drop.isClaimable
            binding.claimButton.isEnabled = claimingDropId == null
            binding.claimButton.text = if (claimingDropId == drop.id) {
                binding.root.context.getString(com.github.andreyasadchy.xtra.R.string.drops_claiming)
            } else {
                binding.root.context.getString(com.github.andreyasadchy.xtra.R.string.drops_tap_to_claim)
            }
            binding.claimButton.setOnClickListener { onClaim(drop) }
            binding.card.setOnClickListener(null)
            binding.card.isClickable = false
            binding.card.isFocusable = false
            binding.expandIcon.isVisible = false
            val canFindStreams = dropCanFindLiveStreams(drop)
            val canTrack = !drop.isClaimed && drop.requiredMinutesWatched > 0
            val isTracked = trackedDropId == drop.id
            binding.trackedLabel.isVisible = isTracked
            binding.moreButton.isVisible = canFindStreams || canTrack
            binding.moreButton.contentDescription = binding.root.context.getString(
                R.string.drops_more_actions_for,
                binding.title.text,
            )
            binding.moreButton.setOnClickListener { anchor ->
                val isCurrentlyTracked = trackedDropId == drop.id
                PopupMenu(binding.root.context, anchor).apply {
                    if (canFindStreams) {
                        menu.add(R.string.drops_find_streams).setOnMenuItemClickListener {
                            onFindStreams(drop)
                            true
                        }
                    }
                    if (canTrack) {
                        menu.add(if (isCurrentlyTracked) R.string.drops_untrack else R.string.drops_track)
                            .setOnMenuItemClickListener {
                                onTrack(drop)
                                true
                            }
                    }
                    show()
                }
            }
            binding.image.contentDescription = binding.root.context.getString(
                R.string.drops_view_image,
                drop.rewardName ?: drop.name ?: binding.root.context.getString(R.string.drops),
            )
            binding.image.isClickable = !drop.imageUrl.isNullOrBlank()
            binding.image.isFocusable = binding.image.isClickable
            binding.image.setOnClickListener {
                drop.imageUrl?.takeIf { it.isNotBlank() }?.let {
                    onImageClick(it, drop.rewardName ?: drop.name, drop.imageSource)
                }
            }
            if (bindImages) binding.image.loadDropImage(drop.imageUrl, drop.imageSource)
        }

        fun bind(
            campaign: TwitchDropCampaign,
            expanded: Boolean,
            detailsLoading: Boolean,
            onClick: (String) -> Unit,
            onFindStreams: (TwitchDropCampaign) -> Unit,
            onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
            bindImage: Boolean,
        ) {
            binding.title.text = campaign.name ?: campaign.gameName ?: binding.root.context.getString(R.string.drops)
            binding.subtitle.text = listOfNotNull(
                campaign.gameName,
                campaign.startTime.formatCampaignTime(binding.root.context)
                    ?.let { binding.root.context.getString(R.string.source_vod_start, it) },
                campaign.endTime.formatCampaignTime(binding.root.context)
                    ?.let { binding.root.context.getString(R.string.source_vod_end, it) },
            ).joinToString(" · ")
            val rewardsVisible = expanded && !detailsLoading
            val rewards = if (rewardsVisible) {
                campaign.drops.flatMap { drop ->
                    drop.benefits.ifEmpty {
                        listOf(TwitchDropBenefit(drop.name ?: "Drop", null))
                    }
                }
            } else emptyList()
            bindRewards(
                rewards,
                visible = rewardsVisible,
                onImageClick = onImageClick,
            )
            binding.detailsLoading.isVisible = expanded && detailsLoading
            binding.progress.isVisible = false
            binding.progressLabel.isVisible = false
            binding.claimButton.isVisible = false
            binding.trackedLabel.isVisible = false
            binding.moreButton.isVisible = false
            binding.claimButton.isEnabled = true
            binding.claimButton.setOnClickListener(null)
            binding.moreButton.setOnClickListener(null)
            binding.card.setOnClickListener { onClick(campaign.id) }
            binding.card.isClickable = true
            binding.card.isFocusable = true
            binding.expandIcon.isVisible = true
            binding.expandIcon.rotation = if (expanded) 0f else 180f
            binding.expandIcon.contentDescription = binding.root.context.getString(
                if (expanded) R.string.chat_identity_campaign_collapse
                else R.string.chat_identity_campaign_expand,
            )
            val canFindStreams = expanded && !detailsLoading && campaignCanFindLiveStreams(campaign)
            binding.moreButton.isVisible = canFindStreams
            binding.moreButton.contentDescription = binding.root.context.getString(
                R.string.drops_more_actions_for,
                binding.title.text,
            )
            binding.moreButton.setOnClickListener { anchor ->
                if (canFindStreams) {
                    PopupMenu(binding.root.context, anchor).apply {
                        menu.add(R.string.drops_find_streams).setOnMenuItemClickListener {
                            onFindStreams(campaign)
                            true
                        }
                        show()
                    }
                }
            }
            binding.image.contentDescription = binding.root.context.getString(
                R.string.drops_view_image,
                campaign.gameName ?: campaign.name ?: binding.root.context.getString(R.string.drops),
            )
            binding.image.isClickable = !campaign.imageUrl.isNullOrBlank()
            binding.image.isFocusable = binding.image.isClickable
            binding.image.setOnClickListener {
                campaign.imageUrl?.takeIf { it.isNotBlank() }?.let {
                    onImageClick(it, campaign.gameName ?: campaign.name, campaign.imageSource)
                }
            }
            if (bindImage) binding.image.loadDropImage(campaign.imageUrl, campaign.imageSource)
        }

        private fun bindRewards(
            rewards: List<TwitchDropBenefit>,
            visible: Boolean,
            onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
        ) {
            binding.rewardStrip.removeAllViews()
            binding.rewardScroll.isVisible = visible && rewards.isNotEmpty()
            if (!binding.rewardScroll.isVisible) {
                if (BuildConfig.DEBUG) Log.d("XtraDropRewards", "event=skip visible=$visible count=0")
                return
            }
            val distinctRewards = rewards.distinctBy { it.name to it.imageUrl }
            if (BuildConfig.DEBUG) Log.d("XtraDropRewards", "event=build visible=$visible count=${distinctRewards.size}")
            distinctRewards.forEach { reward ->
                val rewardBinding = ItemDropRewardBinding.inflate(
                    LayoutInflater.from(binding.root.context),
                    binding.rewardStrip,
                    false,
                )
                rewardBinding.title.text = reward.name
                rewardBinding.root.contentDescription = binding.root.context.getString(
                    R.string.drops_view_image,
                    reward.name ?: binding.root.context.getString(R.string.drops),
                )
                rewardBinding.root.isClickable = !reward.imageUrl.isNullOrBlank()
                rewardBinding.root.isFocusable = rewardBinding.root.isClickable
                rewardBinding.root.setOnClickListener {
                    reward.imageUrl?.takeIf { it.isNotBlank() }?.let {
                        onImageClick(it, reward.name, TwitchDropImageSource.ORIGINAL)
                    }
                }
                rewardBinding.image.contentDescription = reward.name
                rewardBinding.image.loadDropImage(reward.imageUrl, TwitchDropImageSource.ORIGINAL)
                binding.rewardStrip.addView(rewardBinding.root)
            }
        }
    }

    private infix fun Set<String>.xor(other: Set<String>): Set<String> =
        (this - other) + (other - this)

    private companion object {
        const val SECTION = 0
        const val CARD = 1
        const val STATE = "state"
    }
}

internal fun ImageView.loadDropImage(
    url: String?,
    source: TwitchDropImageSource,
) {
    setImageResource(R.drawable.ic_drops)
    if (url.isNullOrBlank()) return
    load(dropsImageUrl(url, source)) {
        placeholder(R.drawable.ic_drops)
        error(R.drawable.ic_thumbnail_error)
        fallback(R.drawable.ic_thumbnail_error)
        crossfade(true)
    }
}

private fun String?.formatCampaignTime(context: Context): String? {
    val timestamp = this
        ?.let(Instant::parseOrNull)
        ?.toEpochMilliseconds()
        ?: return null
    return DateUtils.formatDateTime(
        context,
        timestamp,
        DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_ALL,
    )
}
