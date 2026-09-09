package com.github.andreyasadchy.xtra.ui.drops

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemDropBinding
import com.github.andreyasadchy.xtra.databinding.ItemDropRewardBinding
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropBenefit
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
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
    private val onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<DropsRow> = emptyList()
    private var claimingDropId: String? = null
    private val expandedCampaignIds = mutableSetOf<String>()
    private var campaignDetailsLoading: Set<String> = emptySet()

    fun setClaimingDropId(id: String?) {
        if (claimingDropId == id) return
        val changedIds = setOfNotNull(claimingDropId, id)
        claimingDropId = id
        rows.forEachIndexed { index, row ->
            if (row is DropsRow.Drop && row.value.id in changedIds) notifyItemChanged(index)
        }
    }

    private fun toggleCampaign(campaignId: String) {
        if (!expandedCampaignIds.add(campaignId)) {
            expandedCampaignIds.remove(campaignId)
        }
        rows.indexOfFirst { it is DropsRow.Campaign && it.value.id == campaignId }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
        onCampaignClick(campaignId)
    }

    fun expandCampaign(campaignId: String) {
        if (!expandedCampaignIds.add(campaignId)) return
        rows.indexOfFirst { it is DropsRow.Campaign && it.value.id == campaignId }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
        onCampaignClick(campaignId)
    }

    fun setCampaignDetailsLoading(ids: Set<String>) {
        if (campaignDetailsLoading == ids) return
        val changedIds = campaignDetailsLoading xor ids
        campaignDetailsLoading = ids
        rows.forEachIndexed { index, row ->
            if (row is DropsRow.Campaign && row.value.id in changedIds) notifyItemChanged(index)
        }
    }

    fun submitList(value: List<DropsRow>) {
        if (rows == value) return
        rows = value
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is DropsRow.Section) SECTION else CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == SECTION) {
            object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                setPadding(16, 20, 16, 6)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            }) {}
        } else {
            DropViewHolder(ItemDropBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DropsRow.Section -> (holder.itemView as TextView).text = row.title
            is DropsRow.Drop -> (holder as DropViewHolder).bind(
                row.value,
                claimingDropId,
                onClaim,
                onFindStreamsForDrop,
                onImageClick,
            )
            is DropsRow.Campaign -> (holder as DropViewHolder).bind(
                row.value,
                row.value.id in expandedCampaignIds,
                row.value.id in campaignDetailsLoading,
                ::toggleCampaign,
                onFindStreams,
                onImageClick,
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
            onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
        ) {
            binding.title.text = drop.benefits.mapNotNull { it.name }
                .distinct()
                .ifEmpty { listOfNotNull(drop.rewardName ?: drop.name ?: drop.campaignName) }
                .joinToString(", ")
            binding.subtitle.text = listOfNotNull(
                drop.campaignName,
                "${drop.currentMinutesWatched}/${drop.requiredMinutesWatched} min",
            ).joinToString(" · ")
            bindRewards(
                drop.benefits,
                visible = drop.benefits.size > 1,
                onImageClick = onImageClick,
            )
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
            binding.findStreamsButton.isVisible = dropCanFindLiveStreams(drop)
            binding.findStreamsButton.setOnClickListener { onFindStreams(drop) }
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
            binding.image.loadDropImage(drop.imageUrl, drop.imageSource)
        }

        fun bind(
            campaign: TwitchDropCampaign,
            expanded: Boolean,
            detailsLoading: Boolean,
            onClick: (String) -> Unit,
            onFindStreams: (TwitchDropCampaign) -> Unit,
            onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
        ) {
            binding.title.text = campaign.name ?: campaign.gameName ?: binding.root.context.getString(R.string.drops)
            binding.subtitle.text = listOfNotNull(
                campaign.gameName,
                campaign.startTime.formatCampaignTime(binding.root.context)
                    ?.let { binding.root.context.getString(R.string.source_vod_start, it) },
                campaign.endTime.formatCampaignTime(binding.root.context)
                    ?.let { binding.root.context.getString(R.string.source_vod_end, it) },
            ).joinToString(" · ")
            val rewards = campaign.drops.flatMap { drop ->
                drop.benefits.ifEmpty {
                    listOf(TwitchDropBenefit(drop.name ?: "Drop", null))
                }
            }
            bindRewards(
                rewards,
                visible = expanded && !detailsLoading,
                onImageClick = onImageClick,
            )
            binding.detailsLoading.isVisible = expanded && detailsLoading
            binding.progress.isVisible = false
            binding.progressLabel.isVisible = false
            binding.claimButton.isVisible = false
            binding.claimButton.isEnabled = true
            binding.claimButton.setOnClickListener(null)
            binding.card.setOnClickListener { onClick(campaign.id) }
            binding.card.isClickable = true
            binding.card.isFocusable = true
            binding.expandIcon.isVisible = true
            binding.expandIcon.rotation = if (expanded) 0f else 180f
            binding.expandIcon.contentDescription = binding.root.context.getString(
                if (expanded) R.string.chat_identity_campaign_collapse
                else R.string.chat_identity_campaign_expand,
            )
            binding.findStreamsButton.isVisible = expanded && !detailsLoading &&
                campaignCanFindLiveStreams(campaign)
            binding.findStreamsButton.setOnClickListener { onFindStreams(campaign) }
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
            binding.image.loadDropImage(campaign.imageUrl, campaign.imageSource)
        }

        private fun bindRewards(
            rewards: List<TwitchDropBenefit>,
            visible: Boolean,
            onImageClick: (String, String?, TwitchDropImageSource) -> Unit,
        ) {
            binding.rewardStrip.removeAllViews()
            rewards.distinctBy { it.name to it.imageUrl }.forEach { reward ->
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
            binding.rewardScroll.isVisible = visible && rewards.isNotEmpty()
        }
    }

    private infix fun Set<String>.xor(other: Set<String>): Set<String> =
        (this - other) + (other - this)

    private companion object {
        const val SECTION = 0
        const val CARD = 1
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
