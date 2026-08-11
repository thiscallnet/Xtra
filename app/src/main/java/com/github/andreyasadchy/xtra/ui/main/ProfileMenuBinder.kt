package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds the signed-in user's profile shortcut to the shared top toolbar. */
object ProfileMenuBinder {

    private const val AVATAR_SIZE_DP = 40
    private const val AVATAR_PADDING_DP = 4

    private var loadingUserId: String? = null
    private var loadingJob: Job? = null

    fun bind(toolbar: androidx.appcompat.widget.Toolbar, activity: MainActivity) {
        val item = toolbar.menu.findItem(R.id.profile) ?: return
        val userId = activity.tokenPrefs().getString(C.USER_ID, null)
        val login = activity.tokenPrefs().getString(C.USERNAME, null)
        val isLoggedIn = !userId.isNullOrBlank() || !login.isNullOrBlank()

        item.isVisible = isLoggedIn
        if (!isLoggedIn) {
            item.actionView = null
            return
        }

        val avatar = createAvatar(activity, item)
        avatar.setOnClickListener { activity.openOwnProfile() }
        item.actionView = avatar

        val cachedUserId = activity.tokenPrefs().getString(C.PROFILE_IMAGE_USER_ID, null)
        val cachedUrl = activity.tokenPrefs().getString(C.PROFILE_IMAGE_URL, null)
        if (cachedUserId == userId && !cachedUrl.isNullOrBlank()) {
            loadImage(activity, avatar, cachedUrl)
        } else {
            showPlaceholder(activity, avatar)
            loadProfileImage(activity, avatar, userId, login)
        }
    }

    private fun createAvatar(context: Context, item: MenuItem): ShapeableImageView {
        val density = context.resources.displayMetrics.density
        val size = (AVATAR_SIZE_DP * density).toInt()
        val padding = (AVATAR_PADDING_DP * density).toInt()
        return ShapeableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            setPadding(padding, padding, padding, padding)
            contentDescription = context.getString(R.string.view_profile)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(size / 2f)
                .build()
            isClickable = true
            isFocusable = true
            // Keep the action view's tooltip/title available to accessibility services.
            item.title = context.getString(R.string.view_profile)
        }
    }

    private fun showPlaceholder(context: Context, avatar: ShapeableImageView) {
        val drawable = ContextCompat.getDrawable(context, R.drawable.baseline_person_black_24)?.mutate()
        if (drawable != null) {
            DrawableCompat.setTint(
                drawable,
                com.google.android.material.color.MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.GRAY,
                ),
            )
        }
        avatar.setImageDrawable(drawable)
    }

    private fun loadImage(context: Context, avatar: ShapeableImageView, url: String) {
        context.imageLoader.enqueue(
            ImageRequest.Builder(context).apply {
                data(TwitchApiHelper.getProfileImage(url) ?: url)
                transformations(CircleCropTransformation())
                target(avatar)
            }.build()
        )
    }

    private fun loadProfileImage(
        activity: MainActivity,
        avatar: ShapeableImageView,
        userId: String?,
        login: String?,
    ) {
        if (userId.isNullOrBlank() && login.isNullOrBlank()) {
            return
        }
        synchronized(this) {
            if (loadingUserId == userId && loadingJob?.isActive == true) {
                return
            }
            loadingUserId = userId
            loadingJob = activity.lifecycleScope.launch {
                val imageUrl = withContext(Dispatchers.IO) {
                    fetchProfileImage(activity, userId, login)
                }
                if (!imageUrl.isNullOrBlank()) {
                    activity.tokenPrefs().edit {
                        putString(C.PROFILE_IMAGE_URL, imageUrl)
                        putString(C.PROFILE_IMAGE_USER_ID, userId)
                    }
                    if (avatar.isAttachedToWindow) {
                        loadImage(activity, avatar, imageUrl)
                    }
                }
            }
        }
    }

    private suspend fun fetchProfileImage(
        context: Context,
        userId: String?,
        login: String?,
    ): String? {
        val application = context.applicationContext as? XtraApp ?: return null
        val module = application.xtraModule
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helixHeaders = TwitchApiHelper.getHelixHeaders(context)
        if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            runCatching {
                module.helixRepository.getUsers(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    ids = userId?.let { listOf(it) },
                    logins = if (userId.isNullOrBlank()) login?.let { listOf(it) } else null,
                ).data.firstOrNull()?.profileImageURL
            }.getOrNull()?.let { return it }
        }

        val gqlHeaders = TwitchApiHelper.getGQLHeaders(context, includeToken = true)
        return runCatching {
            module.graphQLRepository.loadQueryUser(
                networkLibrary = networkLibrary,
                headers = gqlHeaders,
                id = userId,
                login = if (userId.isNullOrBlank()) login else null,
            ).data?.user?.profileImageURL
        }.getOrNull()
    }
}
