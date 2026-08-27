package com.github.andreyasadchy.xtra.ui.saved.clips

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentClipsListItemBinding
import com.google.android.material.color.MaterialColors
import java.text.DateFormat
import java.util.Date

class ClipsAdapter(
    context: Context,
    private val onSelect: (LocalClip) -> Unit,
    private val onShare: (LocalClip) -> Unit,
    private val onDelete: (LocalClip) -> Unit,
) : RecyclerView.Adapter<ClipsAdapter.ClipViewHolder>() {

    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private val context = context
    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)
    private var selectedUri = ""

    fun submitList(clips: List<LocalClip>) {
        differ.submitList(clips.toList())
    }

    fun setSelected(clip: LocalClip?) {
        val previous = selectedUri
        selectedUri = clip?.uri?.toString().orEmpty()
        differ.currentList.indexOfFirst { it.uri.toString() == previous }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
        differ.currentList.indexOfFirst { it.uri.toString() == selectedUri }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder = ClipViewHolder(
        FragmentClipsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        val clip = differ.currentList[position]
        holder.bind(clip, clip.uri.toString() == selectedUri)
    }

    override fun getItemCount(): Int = differ.currentList.size

    inner class ClipViewHolder(
        private val binding: FragmentClipsListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(clip: LocalClip, selected: Boolean) {
            binding.card.strokeWidth = if (selected) 2 else 0
            if (selected) {
                binding.card.strokeColor = MaterialColors.getColor(
                    binding.root,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            }
            binding.name.text = clip.displayName
            binding.meta.text = context.getString(
                R.string.clips_date_and_size,
                DateUtils.formatElapsedTime(clip.durationMs / 1_000L),
                Formatter.formatShortFileSize(context, clip.sizeBytes),
                dateFormat.format(Date(clip.modifiedAtMs)),
            )
            binding.card.setOnClickListener { onSelect(clip) }
            binding.share.setOnClickListener { onShare(clip) }
            binding.delete.setOnClickListener { onDelete(clip) }
            binding.share.contentDescription = context.getString(R.string.share)
            binding.delete.contentDescription = context.getString(R.string.delete)
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LocalClip>() {
            override fun areItemsTheSame(oldItem: LocalClip, newItem: LocalClip): Boolean =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: LocalClip, newItem: LocalClip): Boolean =
                oldItem == newItem
        }
    }
}
