package com.github.andreyasadchy.xtra.ui.games

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.BundleCompat
import com.github.andreyasadchy.xtra.databinding.DialogGamesSortBinding
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.ui.common.SearchTagsDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

class GamesSortDialog : BottomSheetDialogFragment(), SearchTagsDialog.OnTagSelectedListener {

    interface OnFilter {
        fun onChange(tags: Array<Tag>)
    }

    companion object {
        private const val TAG_IDS = "tag_ids"
        private const val TAG_NAMES = "tag_names"
        private const val SELECTED_TAGS = "selected_tags"

        fun newInstance(tagIds: Array<String>?, tagNames: Array<String>?): GamesSortDialog {
            return GamesSortDialog().apply {
                arguments = Bundle().apply {
                    putStringArray(TAG_IDS, tagIds)
                    putStringArray(TAG_NAMES, tagNames)
                }
            }
        }
    }

    private var _binding: DialogGamesSortBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: OnFilter

    private var selectedTags = mutableListOf<Tag>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as OnFilter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogGamesSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        with(binding) {
            val args = requireArguments()
            val originalTagIds = args.getStringArray(TAG_IDS) ?: emptyArray()
            val originalTags = requireArguments().getStringArray(TAG_NAMES)?.let { names ->
                originalTagIds.zip(names).map {
                    Tag(
                        id = it.first,
                        name = it.second,
                    )
                }
            } ?: emptyList()
            selectedTags = savedInstanceState?.let {
                BundleCompat.getParcelableArrayList(it, SELECTED_TAGS, Tag::class.java)
            }?.toMutableList() ?: originalTags.toMutableList()
            selectedTags.forEach(::addTagChip)
            selectTags.setOnClickListener {
                SearchTagsDialog.Companion.newInstance(true).show(childFragmentManager, null)
            }
            apply.setOnClickListener {
                val tags = selectedTags.sortedBy { it.id }
                if (!tags.mapNotNull { it.id }.toTypedArray().contentEquals(originalTagIds)) {
                    listener.onChange(
                        tags.toTypedArray(),
                    )
                }
                dismiss()
            }
        }
    }

    override fun onTagSelected(tag: Tag) {
        if (tag.id != null && selectedTags.find { it.id == tag.id } == null) {
            selectedTags.add(tag)
            addTagChip(tag)
        }
    }

    private fun addTagChip(tag: Tag) {
        val tagGroup = _binding?.tagGroup ?: return
        tagGroup.addView(Chip(requireContext()).apply {
            text = tag.name
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                selectedTags.removeAll { it.id == tag.id }
                tagGroup.removeView(this)
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList(SELECTED_TAGS, ArrayList(selectedTags))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
