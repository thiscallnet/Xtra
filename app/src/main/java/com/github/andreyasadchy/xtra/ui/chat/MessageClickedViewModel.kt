package com.github.andreyasadchy.xtra.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.UserCardBadge
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MessageClickedViewModel(
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) : ViewModel() {

    val user = MutableStateFlow<Pair<User?, Boolean?>?>(null)
    data class FollowResult(
        val userId: String,
        val isFollowing: Boolean,
        val errorMessage: String? = null,
        val failed: Boolean = false,
    )

    val followResult = MutableStateFlow<FollowResult?>(null)
    private var isLoading = false

    fun loadUser(channelId: String?, channelLogin: String?, targetId: String?, targetLogin: String?, networkLibrary: String?, gqlHeaders: Map<String, String>, helixHeaders: Map<String, String>) {
        if (user.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                val enrichedUser = if (!channelLogin.isNullOrBlank() && !targetLogin.isNullOrBlank()) {
                    try {
                        val data = graphQLRepository.loadQueryUserMessageClicked(
                            networkLibrary,
                            gqlHeaders,
                            channelId,
                            channelLogin,
                            targetId,
                            targetLogin,
                        ).data
                        data?.user?.userMessageClickedUser?.let { clickedUser ->
                            val earnedBadges = data.channelViewer?.earnedBadges.orEmpty().mapNotNull { badge ->
                                mapUserCardBadge(
                                    id = badge.id,
                                    setId = badge.setID,
                                    version = badge.version,
                                    title = badge.title,
                                    description = badge.description,
                                    imageUrl = badge.imageURL,
                                )
                            }
                            mapUser(user = clickedUser, earnedBadges = earnedBadges)
                        }
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                val gqlUser = enrichedUser ?: try {
                        val data = graphQLRepository.loadBasicQueryUserMessageClicked(
                            networkLibrary,
                            gqlHeaders,
                            channelId,
                            channelLogin.takeIf { channelId.isNullOrBlank() },
                            targetId,
                        ).data
                        data?.user?.userMessageClickedUser?.let { clickedUser ->
                            mapUser(clickedUser)
                        }
                    } catch (e: Exception) {
                        null
                    }
                val response = gqlUser ?: if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    try {
                        helixRepository.getUsers(
                            networkLibrary = networkLibrary,
                            headers = helixHeaders,
                            ids = channelId?.let { listOf(it) },
                            logins = if (channelId.isNullOrBlank()) channelLogin?.let { listOf(it) } else null
                        ).data.firstOrNull()?.let {
                            User(
                                id = it.id,
                                login = it.login,
                                name = it.displayName,
                                profileImageURL = it.profileImageURL,
                                type = it.type,
                                broadcasterType = it.broadcasterType,
                                createdAt = it.createdAt,
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }
                } else null
                user.value = Pair(response, response == null)
                isLoading = false
            }
        }
    }

    private fun mapUser(
        user: com.github.andreyasadchy.xtra.graphql.fragment.UserMessageClickedUser,
        earnedBadges: List<UserCardBadge> = emptyList(),
    ): User {
        return mapUser(
            id = user.id,
            login = user.login,
            name = user.displayName,
            profileImageURL = user.profileImageURL,
            bannerImageURL = user.bannerImageURL,
            createdAt = user.createdAt,
            followedAt = user.follow?.followedAt,
            displayBadges = earnedBadges.ifEmpty {
                mapBadges(user.displayBadges) { badge ->
                    BadgeFields(badge?.id, badge?.setID, badge?.version, badge?.title, badge?.description, badge?.imageURL)
                }
            },
            subscriptionMonths = user.relationship?.subscriptionTenure?.months,
            isSubscribed = user.relationship?.subscriptionBenefit != null,
            viewerFollowsUser = user.self?.follower != null,
            viewerCanFollowUser = user.self?.canFollow == true,
        )
    }

    private fun mapUser(
        id: String?,
        login: String?,
        name: String?,
        profileImageURL: String?,
        bannerImageURL: String?,
        createdAt: Any?,
        followedAt: Any?,
        displayBadges: List<UserCardBadge>,
        subscriptionMonths: Int?,
        isSubscribed: Boolean,
        viewerFollowsUser: Boolean,
        viewerCanFollowUser: Boolean,
    ): User {
        return User(
            id = id,
            login = login,
            name = name,
            profileImageURL = profileImageURL,
            bannerImageURL = bannerImageURL,
            createdAt = createdAt?.toString(),
            followedAt = followedAt?.toString(),
            displayBadges = displayBadges,
            subscriptionMonths = subscriptionMonths,
            isSubscribed = isSubscribed,
            viewerFollowsUser = viewerFollowsUser,
            viewerCanFollowUser = viewerCanFollowUser,
        )
    }

    private fun <T> mapBadges(
        badges: List<T>?,
        fields: (T) -> BadgeFields,
    ): List<UserCardBadge> {
        return badges.orEmpty().mapNotNull { badge ->
            fields(badge).let { field ->
                mapUserCardBadge(field.id, field.setId, field.version, field.title, field.description, field.imageUrl)
            }
        }
    }

    private data class BadgeFields(
        val id: String?,
        val setId: String?,
        val version: String?,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
    )

    private fun mapUserCardBadge(
        id: String?,
        setId: String?,
        version: String?,
        title: String?,
        description: String?,
        imageUrl: String?,
    ): UserCardBadge? {
        if (id == null || setId == null || version == null || title == null || imageUrl == null) return null
        return UserCardBadge(id, setId, version, title, description.orEmpty(), imageUrl)
    }

    fun toggleFollowUser(user: User, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        val userId = user.id ?: run {
            followResult.value = FollowResult("", user.viewerFollowsUser, "Missing user ID")
            return
        }
        viewModelScope.launch {
            try {
                val response = if (user.viewerFollowsUser) {
                    graphQLRepository.loadUnfollowUser(networkLibrary, gqlHeaders, userId)
                } else {
                    graphQLRepository.loadFollowUser(networkLibrary, gqlHeaders, userId, disableNotifications = false)
                }
                followResult.value = FollowResult(
                    userId = userId,
                    isFollowing = !user.viewerFollowsUser,
                    errorMessage = response.errors?.firstOrNull()?.let {
                        it.message
                    },
                    failed = !response.errors.isNullOrEmpty(),
                )
            } catch (e: Exception) {
                followResult.value = FollowResult(
                    userId = userId,
                    isFollowing = user.viewerFollowsUser,
                    errorMessage = e.message,
                    failed = true,
                )
            }
        }
    }

    companion object {
        val MessageClickedViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                MessageClickedViewModel(xtraModule.graphQLRepository, xtraModule.helixRepository)
            }
        }
    }
}
