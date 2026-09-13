package com.github.andreyasadchy.xtra.ui.appearance

import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import com.google.android.material.color.MaterialColors

/** Owns the one activity-sized app wallpaper; destinations never load it themselves. */
internal class ActivityBackgroundController(
    private val root: View,
    private val image: ImageView,
    private val scrim: View,
    private val repository: AppearanceRepository,
) {
    private var imageRequest: Disposable? = null
    private var requestedUri: android.net.Uri? = null
    private var removeChangeListener: (() -> Unit)? = null

    fun start() {
        if (removeChangeListener != null) return
        removeChangeListener = repository.addChangeListener(::render)
        render()
    }

    fun stop() {
        removeChangeListener?.invoke()
        removeChangeListener = null
        imageRequest?.dispose()
        imageRequest = null
        requestedUri = null
    }

    private fun render() {
        val configuration = repository.appBackground()
        val uri = configuration.uri
        if (!configuration.canRender || uri == null) {
            imageRequest?.dispose()
            imageRequest = null
            requestedUri = null
            image.setImageDrawable(null)
            image.isGone = true
            scrim.isGone = true
            return
        }

        image.isVisible = true
        image.alpha = configuration.visibility / 100f
        scrim.isVisible = true
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        scrim.setBackgroundColor(ColorUtils.setAlphaComponent(surface, 0xB8))

        if (requestedUri == uri && imageRequest != null) return
        imageRequest?.dispose()
        imageRequest = null
        requestedUri = uri
        image.setImageDrawable(null)
        imageRequest = root.context.imageLoader.enqueue(
            ImageRequest.Builder(root.context)
                .data(uri)
                .crossfade(true)
                .target(image)
                .listener(object : ImageRequest.Listener {
                    override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                        if (requestedUri != uri) return
                        imageRequest = null
                        requestedUri = null
                        image.setImageDrawable(null)
                        image.isGone = true
                        scrim.isGone = true
                    }
                })
                .build(),
        )
    }
}
