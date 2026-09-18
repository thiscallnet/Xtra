package com.github.andreyasadchy.xtra.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.ItemChatUserRecommendationBinding
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.UsernameRecommendation

class ChatUserRecommendationAdapter(
    private val clickListener: (UsernameRecommendation) -> Unit,
) : ListAdapter<UsernameRecommendation, ChatUserRecommendationAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemChatUserRecommendationBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        private val binding: ItemChatUserRecommendationBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var avatarRequest: Disposable? = null

        fun bind(item: UsernameRecommendation) {
            unbind()
            val user = item.user
            binding.avatar.setImageResource(R.drawable.baseline_person_black_24)
            binding.displayName.text = user.displayName
            binding.login.text = binding.root.context.getString(R.string.chat_username_recommendation_login, user.login)
            binding.root.contentDescription = binding.root.context.getString(R.string.use_username, user.login)
            binding.root.setOnClickListener { clickListener(item) }
            user.profileImageUrl?.takeIf(String::isNotBlank)?.let { url ->
                avatarRequest = binding.root.context.imageLoader.enqueue(
                    ImageRequest.Builder(binding.root.context)
                        .data(url)
                        .placeholder(R.drawable.baseline_person_black_24)
                        .error(R.drawable.baseline_person_black_24)
                        .crossfade(true)
                        .transformations(CircleCropTransformation())
                        .target(binding.avatar)
                        .build(),
                )
            }
        }

        fun unbind() {
            avatarRequest?.dispose()
            avatarRequest = null
            binding.avatar.setImageDrawable(null)
            binding.root.setOnClickListener(null)
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UsernameRecommendation>() {
            override fun areItemsTheSame(
                oldItem: UsernameRecommendation,
                newItem: UsernameRecommendation,
            ): Boolean = oldItem.user.login.equals(newItem.user.login, ignoreCase = true)

            override fun areContentsTheSame(
                oldItem: UsernameRecommendation,
                newItem: UsernameRecommendation,
            ): Boolean = oldItem == newItem
        }
    }
}
