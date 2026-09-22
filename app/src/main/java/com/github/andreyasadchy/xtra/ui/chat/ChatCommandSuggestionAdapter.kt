package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.ItemChatCommandSuggestionBinding

internal class ChatCommandSuggestionAdapter(
    private val clickListener: (ChatCommandDescriptor) -> Unit,
) : ListAdapter<ChatCommandDescriptor, ChatCommandSuggestionAdapter.ViewHolder>(DIFF_CALLBACK) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemChatCommandSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        clickListener,
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: ItemChatCommandSuggestionBinding,
        private val clickListener: (ChatCommandDescriptor) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: ChatCommandDescriptor? = null

        init {
            binding.root.setOnClickListener { item?.let(clickListener) }
        }

        fun bind(command: ChatCommandDescriptor) {
            item = command
            binding.commandName.text = command.name
            val description = binding.root.context.getString(command.descriptionResource)
            binding.commandUsage.text = if (command.name == "/help") description else command.usage
            binding.root.contentDescription = "${command.usage}. $description"
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatCommandDescriptor>() {
            override fun areItemsTheSame(oldItem: ChatCommandDescriptor, newItem: ChatCommandDescriptor) =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: ChatCommandDescriptor, newItem: ChatCommandDescriptor) =
                oldItem == newItem
        }
    }
}
