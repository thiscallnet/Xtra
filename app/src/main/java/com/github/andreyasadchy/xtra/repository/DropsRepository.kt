package com.github.andreyasadchy.xtra.repository

import android.content.Context
import android.os.SystemClock
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.GqlDropsParser
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DropsInventoryState(
    val drops: List<TwitchDrop> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: Throwable? = null,
    val authenticated: Boolean = false,
)

class DropsRepository(
    private val context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val metadataCache: MetadataCache,
) {
    private val inventoryRefreshMutex = Mutex()
    private val dashboardRefreshMutex = Mutex()
    private val claimMutex = Mutex()
    private val channelMutex = Mutex()
    private val campaignDetailsMutex = Mutex()
    private val cacheMutex = Mutex()
    private val cacheWriteMutex = Mutex()
    private val completedClaims = mutableSetOf<String>()
    private val channelDropIds = mutableMapOf<String, Set<String>>()
    private val channelDropRefreshElapsed = mutableMapOf<String, Long>()
    private val campaignDetails = mutableMapOf<String, TwitchDropCampaign>()
    private val _inventory = MutableStateFlow(DropsInventoryState())
    private val _dashboard = MutableStateFlow<List<TwitchDropCampaign>>(emptyList())
    private val _dashboardError = MutableStateFlow<Throwable?>(null)

    @Volatile
    private var lastInventoryRefreshElapsed = 0L

    @Volatile
    private var lastDashboardRefreshElapsed = 0L

    private var dashboardLoaded = false
    private var cacheAccountId: String? = null

    val inventory: StateFlow<DropsInventoryState> = _inventory.asStateFlow()
    val dashboard: StateFlow<List<TwitchDropCampaign>> = _dashboard.asStateFlow()
    val dashboardError: StateFlow<Throwable?> = _dashboardError.asStateFlow()

    suspend fun refreshInventory(force: Boolean = false): DropsInventoryState =
        inventoryRefreshMutex.withLock {
            val headers = TwitchApiHelper.getGQLHeaders(context, true)
            if (headers[C.HEADER_TOKEN].isNullOrBlank()) {
                clearUnauthenticatedState()
                return@withLock _inventory.value
            }

            val userId = currentUserId()
            loadCachedState(userId)

            val now = SystemClock.elapsedRealtime()
            if (!force &&
                _inventory.value.loaded &&
                now - lastInventoryRefreshElapsed < INVENTORY_CACHE_MILLIS
            ) {
                return@withLock _inventory.value
            }

            _inventory.value = _inventory.value.copy(
                refreshing = true,
                error = null,
                authenticated = true,
            )
            try {
                val body = graphQLRepository.loadDropsInventory(
                    context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers,
                )
                val drops = GqlDropsParser.parseInventory(body)
                    ?: error("Twitch Drops inventory response changed or failed")
                lastInventoryRefreshElapsed = SystemClock.elapsedRealtime()
                _inventory.value = DropsInventoryState(
                    drops = drops,
                    loaded = true,
                    refreshing = false,
                    authenticated = true,
                )
                persistCachedState(userId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _inventory.value = _inventory.value.copy(
                    refreshing = false,
                    error = error,
                    authenticated = true,
                )
            } finally {
                // A timeout or lifecycle cancellation can interrupt the request before the
                // normal success/error state is written. Never leave the UI stuck loading.
                if (_inventory.value.refreshing) {
                    _inventory.value = _inventory.value.copy(refreshing = false)
                }
            }
            _inventory.value
        }

    suspend fun refreshDashboard(force: Boolean = false): List<TwitchDropCampaign> =
        dashboardRefreshMutex.withLock {
            val headers = TwitchApiHelper.getGQLHeaders(context, true)
            if (headers[C.HEADER_TOKEN].isNullOrBlank()) {
                clearUnauthenticatedState()
                return@withLock emptyList()
            }
            val userId = currentUserId()
            loadCachedState(userId)
            val now = SystemClock.elapsedRealtime()
            if (!force &&
                dashboardLoaded &&
                now - lastDashboardRefreshElapsed < INVENTORY_CACHE_MILLIS
            ) {
                return@withLock _dashboard.value
            }
            try {
                val body = graphQLRepository.loadDropsDashboard(
                    context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers,
                )
                GqlDropsParser.parseDashboard(body)
                    ?.also {
                        _dashboard.value = it
                        _dashboardError.value = null
                        dashboardLoaded = true
                        lastDashboardRefreshElapsed = SystemClock.elapsedRealtime()
                        persistCachedState(userId)
                    }
                    ?: error("Twitch Drops dashboard response changed or failed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _dashboardError.value = error
                _dashboard.value
            }
        }

    suspend fun loadCampaignDetails(campaignId: String): TwitchDropCampaign? =
        campaignDetailsMutex.withLock {
            if (campaignId.isBlank()) return@withLock null
            campaignDetails[campaignId]?.let { return@withLock it }

            val headers = TwitchApiHelper.getGQLHeaders(context, true)
            if (headers[C.HEADER_TOKEN].isNullOrBlank()) return@withLock null
            val login = context.tokenPrefs().getString(C.USERNAME, null).orEmpty()
            if (login.isBlank()) return@withLock null

            val result = try {
                val body = graphQLRepository.loadDropCampaignDetails(
                    context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers,
                    login,
                    campaignId,
                )
                GqlDropsParser.parseCampaignDetails(body, campaignId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            result?.also { campaignDetails[campaignId] = it }
        }

    suspend fun refreshChannelDrops(
        channelId: String?,
        channelLogin: String,
    ): List<TwitchDrop> {
        val id = channelId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) return emptyList()
        val availableIds = channelMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            channelDropIds[id]?.takeIf {
                now - (channelDropRefreshElapsed[id] ?: 0L) < INVENTORY_CACHE_MILLIS
            }?.let { return@withLock it }
            val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            val available = try {
                GqlDropsParser.parseAvailableDropIds(
                    graphQLRepository.loadAvailableDrops(networkLibrary, headers, id),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            // CurrentDrop identifies the Drop Twitch says is active in this session. Use it
            // alongside AvailableDrops so a changed/partial private response cannot make the
            // channel projection unnecessarily stale.
            val current = try {
                GqlDropsParser.parseCurrentDropIds(
                    graphQLRepository.loadCurrentDrop(networkLibrary, headers, id, channelLogin),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val channelIds = when {
                available != null && current != null -> available + current
                available != null -> available
                current != null -> current
                else -> null
            }
            channelIds?.also {
                channelDropIds[id] = it
                channelDropRefreshElapsed[id] = SystemClock.elapsedRealtime()
            } ?: emptySet()
        }
        if (availableIds.isEmpty()) return emptyList()
        return projectDropsForChannel(inventory.value.drops, availableIds)
    }

    suspend fun claim(drop: TwitchDrop): Boolean {
        val success = claimMutex.withLock { claimWithoutReconciliation(drop) }
        if (success) refreshInventory(force = true)
        return success
    }

    private suspend fun claimWithoutReconciliation(drop: TwitchDrop): Boolean {
        if (!drop.isClaimable) return false
        val claimId = drop.dropInstanceId?.takeIf { it.isNotBlank() }
            ?: return false
        if (claimId in completedClaims) {
            removeClaimedDrop(claimId)
            return true
        }

        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) return false
        val body = graphQLRepository.claimDrop(
            context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            headers,
            claimId,
        )
        val success = GqlDropsParser.claimSucceeded(body)
        if (success) {
            completedClaims += claimId
            removeClaimedDrop(claimId)
        }
        return success
    }

    suspend fun autoClaimCompletedDrops(maxClaims: Int = MAX_AUTO_CLAIMS): Int {
        val snapshot = inventory.value.drops.filter(TwitchDrop::isClaimable).take(maxClaims)
        val claimed = claimMutex.withLock {
            var count = 0
            for (drop in snapshot) {
                if (claimWithoutReconciliation(drop)) count++
            }
            count
        }
        if (claimed > 0) refreshInventory(force = true)
        return claimed
    }

    private suspend fun removeClaimedDrop(claimId: String) {
        val current = _inventory.value
        val updated = current.drops.filterNot { it.dropInstanceId == claimId }
        if (updated != current.drops) {
            _inventory.value = current.copy(drops = updated, error = null)
            persistCachedState(currentUserId())
        }
    }

    private suspend fun loadCachedState(userId: String?) {
        if (userId.isNullOrBlank()) return
        cacheMutex.withLock {
            if (!dropsCacheMustReload(cacheAccountId, userId)) return@withLock
            cacheAccountId = userId
            val cached = runCatching { metadataCache.readDrops(userId) }.getOrNull()
            _inventory.value = if (cached == null) {
                DropsInventoryState(authenticated = true)
            } else {
                DropsInventoryState(
                    drops = cached.drops.map { it.copy(dropInstanceId = null) },
                    loaded = true,
                    authenticated = true,
                )
            }
            _dashboard.value = cached?.campaigns.orEmpty()
            dashboardLoaded = false
            lastInventoryRefreshElapsed = 0L
            lastDashboardRefreshElapsed = 0L
        }
    }

    private suspend fun clearUnauthenticatedState() {
        cacheMutex.withLock {
            cacheAccountId = null
            _inventory.value = DropsInventoryState()
            _dashboard.value = emptyList()
            _dashboardError.value = null
            dashboardLoaded = false
            channelDropIds.clear()
            channelDropRefreshElapsed.clear()
            campaignDetails.clear()
            lastInventoryRefreshElapsed = 0L
            lastDashboardRefreshElapsed = 0L
        }
    }

    private suspend fun persistCachedState(userId: String?) {
        if (userId.isNullOrBlank()) return
        val snapshot = DropsCacheSnapshot(
            drops = _inventory.value.drops.map { it.copy(dropInstanceId = null) },
            campaigns = _dashboard.value,
        )
        cacheWriteMutex.withLock {
            runCatching { metadataCache.writeDrops(userId, snapshot) }
        }
    }

    private fun currentUserId(): String? =
        context.tokenPrefs().getString(C.USER_ID, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val INVENTORY_CACHE_MILLIS = 45_000L
        private const val MAX_AUTO_CLAIMS = 10
    }
}

internal fun projectDropsForChannel(
    drops: List<TwitchDrop>,
    availableIds: Set<String>,
): List<TwitchDrop> = drops.filter {
    it.id in availableIds || it.campaignId in availableIds
}

internal fun mergeDropsWithDashboard(
    drops: List<TwitchDrop>,
    campaigns: List<TwitchDropCampaign>,
): List<TwitchDrop> = drops.map { drop ->
    val campaign = campaigns.firstOrNull { it.id == drop.campaignId }
    val catalog = campaign?.drops?.firstOrNull { it.id == drop.id }
    drop.copy(
        campaignName = drop.campaignName ?: campaign?.name,
        gameName = drop.gameName ?: campaign?.gameName,
        imageUrl = drop.imageUrl ?: catalog?.benefits?.firstOrNull()?.imageUrl ?: campaign?.imageUrl,
        benefits = drop.benefits.ifEmpty { catalog?.benefits.orEmpty() },
        campaignStartTime = drop.campaignStartTime ?: campaign?.startTime,
        campaignEndTime = drop.campaignEndTime ?: campaign?.endTime,
    )
}

internal fun dropsCacheMustReload(
    cachedAccountId: String?,
    userId: String?,
): Boolean = !userId.isNullOrBlank() && cachedAccountId != userId
