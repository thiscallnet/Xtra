package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import com.github.andreyasadchy.xtra.ui.account.AccountActivity
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds the signed-in user's profile shortcut to the shared top toolbar. */
object ProfileMenuBinder {

    private const val ACTION_SIZE_DP = 48
    private const val AVATAR_SIZE_DP = 36
    private const val BADGE_SIZE_DP = 17
    private const val AUTH_BADGE_TAG = "xtra.auth-health-badge"

    fun bind(toolbar: androidx.appcompat.widget.Toolbar, activity: MainActivity) {
        val item = toolbar.menu.findItem(R.id.profile) ?: return
        val userId = activity.tokenPrefs().getString(C.USER_ID, null)
        val login = activity.tokenPrefs().getString(C.USERNAME, null)
        val isLoggedIn = !userId.isNullOrBlank() || !login.isNullOrBlank()

        item.isVisible = true
        val contentDescription = if (isLoggedIn) {
            activity.getString(R.string.view_profile)
        } else {
            activity.getString(R.string.sign_in)
        }
        val avatarViews = item.actionView?.tag as? AvatarViews
            ?: createAvatar(activity, item, contentDescription)
        if (!avatarViews.initialized || avatarViews.userId != userId || avatarViews.login != login) {
            avatarViews.profileImageJob?.cancel()
            avatarViews.imageRequest?.dispose()
            avatarViews.imageRequest = null
            avatarViews.userId = userId
            avatarViews.login = login
            avatarViews.imageUrl = null
            avatarViews.lastImageLoadAt = null
            avatarViews.lastImageLookupAt = null
            avatarViews.initialized = true
            showPlaceholder(avatarViews.image)
        }
        if (item.title != contentDescription) item.title = contentDescription
        avatarViews.container.contentDescription = contentDescription
        if (item.actionView !== avatarViews.container) item.actionView = avatarViews.container
        if (!isLoggedIn) {
            avatarViews.container.findViewWithTag<TextView>(AUTH_BADGE_TAG)?.let(avatarViews.container::removeView)
            avatarViews.container.setOnClickListener { launchLogin(activity) }
            return
        }

        val authHealth = (activity.application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value
        bindAuthHealthBadge(activity, avatarViews.container, authHealth)
        avatarViews.container.setOnClickListener { openProfile(activity) }

        val cachedUserId = activity.tokenPrefs().getString(C.PROFILE_IMAGE_USER_ID, null)
        val cachedUrl = activity.tokenPrefs().getString(C.PROFILE_IMAGE_URL, null)
        if (cachedUserId == userId && !cachedUrl.isNullOrBlank()) {
            if (avatarViews.imageUrl != cachedUrl) {
                loadImage(activity, avatarViews, cachedUrl)
            }
        } else {
            loadProfileImage(activity, avatarViews, userId, login)
        }
    }

    fun refreshAuthHealth(toolbar: androidx.appcompat.widget.Toolbar, activity: MainActivity) {
        val item = toolbar.menu.findItem(R.id.profile) ?: return
        val container = item.actionView as? FrameLayout ?: return
        if (!isLoggedIn(activity)) {
            container.findViewWithTag<TextView>(AUTH_BADGE_TAG)?.let(container::removeView)
            item.title = activity.getString(R.string.sign_in)
            container.contentDescription = activity.getString(R.string.sign_in)
            container.setOnClickListener { launchLogin(activity) }
            return
        }
        val health = (activity.application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value
        bindAuthHealthBadge(activity, container, health)
        container.setOnClickListener { openProfile(activity) }
    }

    private data class AvatarViews(
        val container: FrameLayout,
        val image: ShapeableImageView,
    ) {
        var initialized = false
        var userId: String? = null
        var login: String? = null
        var imageUrl: String? = null
        var lastImageLoadUrl: String? = null
        var lastImageLoadAt: Long? = null
        var lastImageLookupAt: Long? = null
        var profileImageJob: Job? = null
        var imageRequest: Disposable? = null
    }

    private fun createAvatar(context: Context, item: MenuItem, contentDescription: String): AvatarViews {
        val density = context.resources.displayMetrics.density
        val actionSize = (ACTION_SIZE_DP * density).toInt()
        val avatarSize = (AVATAR_SIZE_DP * density).toInt()
        val image = ShapeableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER)
            setBackgroundResource(R.drawable.bg_profile_avatar_empty)
            this.contentDescription = contentDescription
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(avatarSize / 2f)
                .build()
        }
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(actionSize, actionSize).apply {
                gravity = Gravity.CENTER
            }
            addView(image)
            isClickable = true
            isFocusable = true
            // Keep the action view's tooltip/title available to accessibility services.
            this.contentDescription = contentDescription
            item.title = contentDescription
        }
        return AvatarViews(container, image).also { container.tag = it }
    }

    private fun isLoggedIn(activity: MainActivity): Boolean =
        !activity.tokenPrefs().getString(C.USER_ID, null).isNullOrBlank() ||
            !activity.tokenPrefs().getString(C.USERNAME, null).isNullOrBlank()

    private fun launchLogin(activity: MainActivity) {
        val intent = Intent(activity, TwitchWebLoginActivity::class.java)
        activity.loginResultLauncher?.launch(intent) ?: activity.startActivity(intent)
    }

    private fun openProfile(activity: MainActivity) {
        val health = (activity.application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value
        if (health.requiresUserAction) {
            showAuthHealthDialog(activity, health)
        } else {
            activity.startActivity(Intent(activity, AccountActivity::class.java))
        }
    }

    private fun bindAuthHealthBadge(context: Context, container: FrameLayout, health: AuthHealth) {
        container.findViewWithTag<TextView>(AUTH_BADGE_TAG)?.let(container::removeView)
        if (!health.requiresUserAction) {
            container.contentDescription = context.getString(R.string.view_profile)
            return
        }
        val density = context.resources.displayMetrics.density
        val size = (BADGE_SIZE_DP * density).toInt()
        val badge = TextView(context).apply {
            text = "!"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(220, 38, 38))
            }
            elevation = 2f * density
            tag = AUTH_BADGE_TAG
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        container.addView(badge, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
        })
        container.contentDescription = context.getString(R.string.auth_health_attention_description)
    }

    private fun showAuthHealthDialog(activity: MainActivity, health: AuthHealth) {
        val spec = when (health) {
            AuthHealth.REAUTH_REQUIRED -> AuthHealthDialogSpec(
                title = R.string.auth_health_reauth_title,
                message = R.string.auth_health_reauth_message,
                actionLabel = R.string.auth_health_reconnect,
                intent = Intent(activity, TwitchWebLoginActivity::class.java)
                    .putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true),
            )
            else -> return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(spec.title)
            .setMessage(spec.message)
            .setNegativeButton(activity.getString(R.string.auth_health_not_now), null)
            .setNeutralButton(activity.getString(R.string.auth_health_account_details)) { _, _ ->
                activity.startActivity(Intent(activity, AccountActivity::class.java))
            }
            .setPositiveButton(activity.getString(spec.actionLabel)) { _, _ -> activity.startActivity(spec.intent) }
            .show()
    }

    private data class AuthHealthDialogSpec(
        val title: Int,
        val message: Int,
        val actionLabel: Int,
        val intent: Intent,
    )

    private fun showPlaceholder(avatar: ShapeableImageView) {
        avatar.scaleType = ImageView.ScaleType.FIT_CENTER
        ContextCompat.getDrawable(avatar.context, R.drawable.baseline_person_black_24)
            ?.mutate()
            ?.let { icon ->
                DrawableCompat.setTint(
                    icon,
                    MaterialColors.getColor(
                        avatar,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                avatar.setImageDrawable(icon)
            }
    }

    private fun loadImage(context: Context, avatarViews: AvatarViews, url: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (avatarViews.lastImageLoadUrl == url &&
            avatarViews.lastImageLoadAt?.let { now - it < 45_000L } == true
        ) return
        avatarViews.lastImageLoadUrl = url
        avatarViews.lastImageLoadAt = now
        avatarViews.imageUrl = url
        val avatar = avatarViews.image
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatarViews.imageRequest = context.imageLoader.enqueue(
            ImageRequest.Builder(context).apply {
                data(TwitchApiHelper.getProfileImage(url) ?: url)
                transformations(CircleCropTransformation())
                target(avatar)
                listener(onError = { _, _ ->
                    if (avatarViews.imageUrl == url) avatarViews.imageUrl = null
                })
            }.build()
        )
    }

    private fun loadProfileImage(
        activity: MainActivity,
        avatarViews: AvatarViews,
        userId: String?,
        login: String?,
    ) {
        if (userId.isNullOrBlank() && login.isNullOrBlank()) {
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (avatarViews.profileImageJob?.isActive == true ||
            avatarViews.lastImageLookupAt?.let { now - it < 45_000L } == true
        ) return
        avatarViews.lastImageLookupAt = now
        avatarViews.profileImageJob = activity.lifecycleScope.launch {
            val imageUrl = withContext(Dispatchers.IO) {
                fetchProfileImage(activity, userId, login)
            }
            // A late response must not replace the avatar/cache after an account change.
            if (avatarViews.userId != userId || avatarViews.login != login ||
                activity.tokenPrefs().getString(C.USER_ID, null) != userId ||
                activity.tokenPrefs().getString(C.USERNAME, null) != login
            ) return@launch
            if (!imageUrl.isNullOrBlank()) {
                activity.tokenPrefs().edit {
                    putString(C.PROFILE_IMAGE_URL, imageUrl)
                    putString(C.PROFILE_IMAGE_USER_ID, userId)
                }
                if (avatarViews.image.isAttachedToWindow) {
                    loadImage(activity, avatarViews, imageUrl)
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
