package com.github.andreyasadchy.xtra.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.CommonPagingLoadStateFooterBinding

class PagingLoadStateAdapter(
    private val retry: () -> Unit,
) : LoadStateAdapter<PagingLoadStateAdapter.ViewHolder>() {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        loadState: LoadState,
    ): ViewHolder {
        val binding = CommonPagingLoadStateFooterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding, retry)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        loadState: LoadState,
    ) {
        holder.bind(loadState)
    }

    class ViewHolder(
        private val binding: CommonPagingLoadStateFooterBinding,
        retry: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.retryButton.setOnClickListener {
                retry()
            }
        }

        fun bind(loadState: LoadState) {
            binding.progressBar.isVisible = loadState is LoadState.Loading
            binding.errorGroup.isVisible = loadState is LoadState.Error
        }
    }
}
