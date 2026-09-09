package com.github.andreyasadchy.xtra.ui.drops

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogDropImageBinding
import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
import com.google.android.material.color.MaterialColors

class DropImageDialog : DialogFragment() {
    private var _binding: DialogDropImageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogDropImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val url = args.getString(IMAGE_URL).orEmpty()
        val imageSource = args.getString(IMAGE_SOURCE)
            ?.let { runCatching { TwitchDropImageSource.valueOf(it) }.getOrNull() }
            ?: TwitchDropImageSource.ORIGINAL
        binding.toolbar.title = args.getString(IMAGE_NAME).orEmpty()
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.image.contentDescription = getString(
            R.string.drops_view_image,
            args.getString(IMAGE_NAME).orEmpty().ifBlank { getString(R.string.drops) },
        )
        binding.image.load(dropsImageUrl(url, imageSource)) {
            placeholder(R.drawable.ic_drops)
            error(R.drawable.ic_thumbnail_error)
            fallback(R.drawable.ic_thumbnail_error)
            crossfade(true)
        }
        binding.image.setOnClickListener {
            val visible = !binding.toolbar.isVisible
            binding.toolbar.isVisible = visible
            binding.zoomHint.isVisible = visible
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(
                ColorDrawable(MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)),
            )
            setDimAmount(0.9f)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "drop-image"
        private const val IMAGE_URL = "image_url"
        private const val IMAGE_NAME = "image_name"
        private const val IMAGE_SOURCE = "image_source"

        fun newInstance(
            url: String,
            name: String?,
            imageSource: TwitchDropImageSource = TwitchDropImageSource.ORIGINAL,
        ): DropImageDialog =
            DropImageDialog().apply {
                arguments = Bundle().apply {
                    putString(IMAGE_URL, url)
                    putString(IMAGE_NAME, name)
                    putString(IMAGE_SOURCE, imageSource.name)
                }
            }
    }
}
