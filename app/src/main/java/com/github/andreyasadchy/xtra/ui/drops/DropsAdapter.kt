package com.github.andreyasadchy.xtra.ui.drops

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemDropBinding
import com.github.andreyasadchy.xtra.databinding.ItemDropRewardBinding
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropBenefit
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import kotlin.time.Instant

sealed interface DropsRow {
    data class Section(val title: String) : DropsRow
    data class Drop(val value: TwitchDrop) : DropsRow
    data class Campaign(val value: TwitchDropCampaign) : DropsRow
}

class DropsAdapter(
    private val onClaim: (TwitchDrop) -> Unit,
    private val onCampaignClick: (String) -> Unit,
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
            )
            is DropsRow.Campaign -> (holder as DropViewHolder).bind(
                row.value,
                row.value.id in expandedCampaignIds,
                row.value.id in campaignDetailsLoading,
                ::toggleCampaign,
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    private class DropViewHolder(private val binding: ItemDropBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            drop: TwitchDrop,
            claimingDropId: String?,
            onClaim: (TwitchDrop) -> Unit,
        ) {
            binding.title.text = drop.benefits.mapNotNull { it.name }
                .distinct()
                .ifEmpty { listOfNotNull(drop.rewardName ?: drop.name ?: drop.campaignName) }
                .joinToString(", ")
            binding.subtitle.text = listOfNotNull(
                drop.campaignName,
                "${drop.currentMinutesWatched}/${drop.requiredMinutesWatched} min",
            ).joinToString(" · ")
            bindRewards(drop.benefits, visible = true)
            binding.detailsLoading.isVisible = false
            binding.progress.isVisible = true
            binding.progress.progress = drop.progressPercent
            binding.claimButton.isVisible = drop.isClaimable
            binding.claimButton.isEnabled = claimingDropId == null
            binding.claimButton.text = if (claimingDropId == drop.id) {
                binding.root.context.getString(com.github.andreyasadchy.xtra.R.string.drops_claiming)
            } else {
                binding.root.context.getString(com.github.andreyasadchy.xtra.R.string.drops_tap_to_claim)
            }
            binding.claimButton.setOnClickListener { onClaim(drop) }
            binding.card.setOnClickListener(null)
            binding.expandIcon.isVisible = false
            binding.image.contentDescription = drop.rewardName ?: drop.name
            if (drop.imageUrl.isNullOrBlank()) {
                binding.image.setImageResource(R.drawable.ic_drops)
            } else {
                binding.image.load(drop.imageUrl)
            }
        }

        fun bind(
            campaign: TwitchDropCampaign,
            expanded: Boolean,
            detailsLoading: Boolean,
            onClick: (String) -> Unit,
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
            bindRewards(rewards, visible = expanded && !detailsLoading)
            binding.detailsLoading.isVisible = expanded && detailsLoading
            binding.progress.isVisible = false
            binding.claimButton.isVisible = false
            binding.claimButton.isEnabled = true
            binding.claimButton.setOnClickListener(null)
            binding.card.setOnClickListener { onClick(campaign.id) }
            binding.expandIcon.isVisible = true
            binding.expandIcon.rotation = if (expanded) 0f else 180f
            binding.expandIcon.contentDescription = binding.root.context.getString(
                if (expanded) R.string.chat_identity_campaign_collapse
                else R.string.chat_identity_campaign_expand,
            )
            binding.image.contentDescription = campaign.gameName ?: campaign.name
            if (campaign.imageUrl.isNullOrBlank()) {
                binding.image.setImageResource(R.drawable.ic_drops)
            } else {
                binding.image.load(campaign.imageUrl)
            }
        }

        private fun bindRewards(rewards: List<TwitchDropBenefit>, visible: Boolean) {
            binding.rewardStrip.removeAllViews()
            rewards.distinctBy { it.name to it.imageUrl }.forEach { reward ->
                val rewardBinding = ItemDropRewardBinding.inflate(
                    LayoutInflater.from(binding.root.context),
                    binding.rewardStrip,
                    false,
                )
                rewardBinding.title.text = reward.name
                rewardBinding.image.contentDescription = reward.name
                if (reward.imageUrl.isNullOrBlank()) {
                    rewardBinding.image.setImageResource(R.drawable.ic_drops)
                } else {
                    rewardBinding.image.load(reward.imageUrl)
                }
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
