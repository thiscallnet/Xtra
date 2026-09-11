package com.github.andreyasadchy.xtra.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsField
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsFieldKey
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsSeverity
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsTransport
import com.github.andreyasadchy.xtra.model.ui.TwitchChannelDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.DropProgressUpdate
import com.github.andreyasadchy.xtra.util.chat.GqlDropsParser
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    private val inventoryRefreshMutex = Mutex()
    private val dashboardRefreshMutex = Mutex()
    private val claimMutex = Mutex()
    private val campaignDetailsMutex = Mutex()
    private val progressMutex = Mutex()
    private val cacheMutex = Mutex()
    private val cacheWriteMutex = Mutex()
    private val completedClaims = mutableSetOf<String>()
    private val channelDropScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )
    private val channelDropSemaphore = Semaphore(MAX_CHANNEL_DROP_REQUESTS)
    private val channelDropIds = ExpiringSingleFlightCache<String, Set<String>>(
        ttlMillis = INVENTORY_CACHE_MILLIS,
        scope = channelDropScope,
        loadSemaphore = channelDropSemaphore,
    )
    private val channelDropCatalog = ExpiringSingleFlightCache<String, List<TwitchChannelDropCampaign>>(
        ttlMillis = INVENTORY_CACHE_MILLIS,
        scope = channelDropScope,
        loadSemaphore = channelDropSemaphore,
    )
    private val campaignDetails = mutableMapOf<String, TwitchDropCampaign>()
    private val _inventory = MutableStateFlow(DropsInventoryState())
    private val _dashboard = MutableStateFlow<List<TwitchDropCampaign>>(emptyList())
    private val _dashboardError = MutableStateFlow<Throwable?>(null)

    private inline fun logDiagnostics(block: DiagnosticsLogger.() -> Unit) {
        val logger = diagnosticsLogger ?: return
        if (logger.isEnabled) logger.block()
    }

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
                logDiagnostics {
                    event(
                    category = DiagnosticsCategory.DROPS,
                    transport = DiagnosticsTransport.LOCAL,
                    operation = "DropsRepository",
                    event = "inventory_updated",
                    fields = listOf(DiagnosticsField(DiagnosticsFieldKey.COUNT, drops.size.toString())),
                    )
                }
                persistCachedState(userId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logDiagnostics {
                    event(
                    category = DiagnosticsCategory.DROPS,
                    severity = DiagnosticsSeverity.ERROR,
                    transport = DiagnosticsTransport.LOCAL,
                    operation = "DropsRepository",
                    event = "inventory_refresh_failed",
                    code = "refresh_failed",
                    )
                }
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
                        logDiagnostics {
                            event(
                            category = DiagnosticsCategory.DROPS,
                            transport = DiagnosticsTransport.LOCAL,
                            operation = "DropsRepository",
                            event = "dashboard_updated",
                            fields = listOf(DiagnosticsField(DiagnosticsFieldKey.COUNT, it.size.toString())),
                            )
                        }
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
                logDiagnostics {
                    event(
                        category = DiagnosticsCategory.DROPS,
                        severity = DiagnosticsSeverity.ERROR,
                        transport = DiagnosticsTransport.LOCAL,
                        operation = "DropsRepository",
                        event = "dashboard_refresh_failed",
                        code = "refresh_failed",
                    )
                }
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

    suspend fun refreshChannelDropCatalog(
        channelId: String?,
        force: Boolean = false,
    ): List<TwitchChannelDropCampaign>? {
        val id = channelId?.takeIf { it.isNotBlank() } ?: return null
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) return null

        val cacheKey = channelCacheKey(id)
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        return channelDropCatalog.get(cacheKey, force) {
            val body = try {
                graphQLRepository.loadAvailableDrops(networkLibrary, headers, id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Unable to load channel Drops (${error::class.simpleName})")
                return@get null
            }
            if (body.contains("PersistedQueryNotFound", ignoreCase = true) ||
                body.contains("Persisted query not found", ignoreCase = true)
            ) {
                Log.w(TAG, "Twitch AvailableDrops persisted query was not found")
            }
            val result = GqlDropsParser.parseAvailableDrops(body)
            if (result != null) {
                logDiagnostics {
                    event(
                        category = DiagnosticsCategory.DROPS,
                        transport = DiagnosticsTransport.LOCAL,
                        operation = "DropsRepository",
                        event = "channel_catalog_updated",
                        fields = listOf(
                            DiagnosticsField(DiagnosticsFieldKey.CHANNEL_ID, id),
                            DiagnosticsField(DiagnosticsFieldKey.COUNT, result.sumOf { it.drops.size }.toString()),
                        ),
                    )
                }
            } else {
                logDiagnostics {
                    event(
                        category = DiagnosticsCategory.DROPS,
                        severity = DiagnosticsSeverity.ERROR,
                        transport = DiagnosticsTransport.LOCAL,
                        operation = "DropsRepository",
                        event = "channel_catalog_parse_failed",
                        code = "schema_changed",
                    )
                }
                Log.w(TAG, "Twitch AvailableDrops response schema changed")
            }
            result
        }
    }

    suspend fun refreshChannelDrops(
        channelId: String?,
    ): List<TwitchDrop> {
        val id = channelId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) return emptyList()

        val available = refreshChannelDropCatalog(id)
        val availableIds = available?.flatMap { campaign ->
            listOf(campaign.id) + campaign.drops.map { it.id }
        }?.toSet()
        // CurrentDrop identifies the Drop Twitch says is active in this session. Use it
        // alongside AvailableDrops so a changed/partial private response cannot make the
        // channel projection unnecessarily stale. The cache owns only bookkeeping; network
        // work runs in its keyed request scope.
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val currentIds = channelDropIds.get(channelCacheKey(id)) {
            try {
                GqlDropsParser.parseCurrentDropIds(
                    graphQLRepository.loadCurrentDrop(networkLibrary, headers, id),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        val channelIds = when {
            availableIds != null && currentIds != null -> availableIds + currentIds
            availableIds != null -> availableIds
            currentIds != null -> currentIds
            else -> emptySet()
        }
        if (channelIds.isEmpty()) return emptyList()
        return projectDropsForChannel(inventory.value.drops, channelIds)
    }

    suspend fun refreshCurrentDropProgress(
        channelId: String?,
    ): DropProgressUpdate? {
        val id = channelId?.takeIf { it.isNotBlank() } ?: return null
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (headers[C.HEADER_TOKEN].isNullOrBlank()) return null

        return try {
            val body = channelDropSemaphore.withPermit {
                graphQLRepository.loadCurrentDrop(
                    networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    headers = headers,
                    channelId = id,
                )
            }
            GqlDropsParser.parseCurrentDropProgress(body).also { progress ->
                if (progress != null) {
                    logDiagnostics {
                        event(
                        category = DiagnosticsCategory.PROGRESSION,
                        transport = DiagnosticsTransport.LOCAL,
                        operation = "DropsRepository",
                        event = "progress_updated",
                        fields = listOf(
                            DiagnosticsField(DiagnosticsFieldKey.DROP_ID, progress.dropId),
                            DiagnosticsField(
                                DiagnosticsFieldKey.PROGRESS,
                                "${progress.currentMinutesWatched}/${progress.requiredMinutesWatched ?: "?"}",
                            ),
                        ),
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    suspend fun applyDropProgress(update: DropProgressUpdate): Boolean = progressMutex.withLock {
        val current = _inventory.value
        var changed = false
        val drops = current.drops.map { drop ->
            if (drop.id != update.dropId) {
                drop
            } else {
                val currentMinutes = maxOf(drop.currentMinutesWatched, update.currentMinutesWatched)
                val requiredMinutes = update.requiredMinutesWatched ?: drop.requiredMinutesWatched
                if (currentMinutes != drop.currentMinutesWatched ||
                    requiredMinutes != drop.requiredMinutesWatched
                ) {
                    changed = true
                    drop.copy(
                        currentMinutesWatched = currentMinutes,
                        requiredMinutesWatched = requiredMinutes,
                    )
                } else {
                    drop
                }
            }
        }
        if (changed) {
            logDiagnostics {
                event(
                category = DiagnosticsCategory.PROGRESSION,
                transport = DiagnosticsTransport.LOCAL,
                operation = "DropsRepository",
                event = "progress_applied",
                fields = listOf(
                    DiagnosticsField(DiagnosticsFieldKey.DROP_ID, update.dropId),
                    DiagnosticsField(
                        DiagnosticsFieldKey.PROGRESS,
                        "${update.currentMinutesWatched}/${update.requiredMinutesWatched ?: "?"}",
                    ),
                ),
                )
            }
            _inventory.value = current.copy(drops = drops, error = null)
            persistCachedState(currentUserId())
        }
        changed
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
        logDiagnostics {
            event(
            category = DiagnosticsCategory.DROPS,
            transport = DiagnosticsTransport.LOCAL,
            operation = "DropsRepository",
            event = "claim_request",
            fields = listOf(DiagnosticsField(DiagnosticsFieldKey.DROP_ID, drop.id)),
            )
        }
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
        logDiagnostics {
            event(
            category = DiagnosticsCategory.DROPS,
            severity = if (success) DiagnosticsSeverity.INFO else DiagnosticsSeverity.WARN,
            transport = DiagnosticsTransport.LOCAL,
            operation = "DropsRepository",
            event = "claim_result",
            code = if (success) "success" else "claim_failed",
            fields = listOf(
                DiagnosticsField(DiagnosticsFieldKey.DROP_ID, drop.id),
                DiagnosticsField(DiagnosticsFieldKey.CLAIMED, success.toString()),
            ),
            )
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
        logDiagnostics {
            event(
            category = DiagnosticsCategory.DROPS,
            transport = DiagnosticsTransport.LOCAL,
            operation = "DropsRepository",
            event = "auto_claim_completed",
            fields = listOf(DiagnosticsField(DiagnosticsFieldKey.COUNT, claimed.toString())),
            )
        }
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
            channelDropCatalog.clear()
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

    private fun channelCacheKey(channelId: String): String =
        "${currentUserId().orEmpty()}:$channelId"

    companion object {
        private const val INVENTORY_CACHE_MILLIS = 45_000L
        private const val MAX_CHANNEL_DROP_REQUESTS = 3
        private const val MAX_AUTO_CLAIMS = 10
        private const val TAG = "DropsRepository"
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
    val catalogImage = catalog?.benefits?.firstOrNull()?.imageUrl
    val useCatalogImage = drop.imageSource == TwitchDropImageSource.GAME_BOX_ART && catalogImage != null
    val dashboardImage = if (useCatalogImage) catalogImage else drop.imageUrl ?: catalogImage ?: campaign?.imageUrl
    drop.copy(
        campaignName = drop.campaignName ?: campaign?.name,
        gameName = drop.gameName ?: campaign?.gameName,
        imageUrl = dashboardImage,
        imageSource = when {
            drop.imageUrl != null && !useCatalogImage -> drop.imageSource
            catalogImage != null -> TwitchDropImageSource.ORIGINAL
            campaign?.imageUrl != null -> campaign.imageSource
            else -> drop.imageSource
        },
        benefits = drop.benefits.ifEmpty { catalog?.benefits.orEmpty() },
        campaignStartTime = drop.campaignStartTime ?: campaign?.startTime,
        campaignEndTime = drop.campaignEndTime ?: campaign?.endTime,
    )
}

internal fun dropsCacheMustReload(
    cachedAccountId: String?,
    userId: String?,
): Boolean = !userId.isNullOrBlank() && cachedAccountId != userId
