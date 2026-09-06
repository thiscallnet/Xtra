package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import android.content.Context
import android.graphics.Color
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges the already-used catalog endpoints into the immutable v2 catalog.
 * Each provider is independently best-effort: a failed provider is omitted
 * from the result so ChatCatalogRepository keeps its last-good map.
 */
class TwitchChatCatalogSource(
    context: Context,
    private val playerRepository: PlayerRepository,
    private val channelId: String,
    private val channelLogin: String,
) : ChatCatalogSource {
    private val context = context.applicationContext
    private val globalCatalogCache = GlobalCatalogCacheRegistry.get(context)

    override val hasIndependentBadgeProvider: Boolean = true

    override val catalogConfigFingerprint: String
        get() = listOf(
            context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            context.prefs().getBoolean(C.CHAT_ENABLE_STV, true),
            context.prefs().getBoolean(C.CHAT_ENABLE_BTTV, true),
            context.prefs().getBoolean(C.CHAT_ENABLE_FFZ, true),
            context.prefs().getBoolean(C.ANIMATED_EMOTES, true),
            context.tokenPrefs().getString(C.USER_ID, null),
        ).joinToString("|")

    suspend fun loadPersonalEmoteSet(setId: String): Map<String, ChatCatalogEmote> {
        return PersonalEmoteSetCache.get(setId) {
            val network = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            emoteMap(
                playerRepository.loadSTVPersonalEmotes(
                    playerRepository.loadSTVEmoteSetResponse(network, setId, throwOnHttpError = true),
                    useWebp = true,
                ).second,
                ChatAssetProvider.SEVEN_TV,
                ChatEmoteScope.PERSONAL,
            )
        }
    }

    override suspend fun load(): ChatCatalogLoadResult = load(force = false)

    override suspend fun load(force: Boolean): ChatCatalogLoadResult = withContext(Dispatchers.IO) {
        val network = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helix = TwitchApiHelper.getHelixHeaders(context)
        val gql = TwitchApiHelper.getGQLHeaders(context, true)
        val useWebp = true
        supervisorScope {
            val sevenTv = async { if (context.prefs().getBoolean(C.CHAT_ENABLE_STV, true)) loadSevenTv(network, useWebp, force) else emptyProviderUpdate() }
            val bttv = async { if (context.prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)) loadBttv(network, useWebp, force) else emptyProviderUpdate() }
            val ffz = async { if (context.prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)) loadFfz(network, useWebp, force) else emptyProviderUpdate() }
            val cheermotes = async { provider {
                playerRepository.loadCheerEmotes(
                    network,
                    helix,
                    gql,
                    channelId,
                    channelLogin,
                    animateGifs = context.prefs().getBoolean(C.ANIMATED_EMOTES, true),
                ).mapNotNull { it.toCatalog() }.toMap()
            } }
            ChatCatalogLoadResult(
                twitch = ChatCatalogProviderUpdate(emptyMap()),
                sevenTv = sevenTv.await(),
                bttv = bttv.await(),
                ffz = ffz.await(),
                cheermotes = cheermotes.await(),
            )
        }
    }

    override suspend fun loadBadges(): ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>? = loadBadges(force = false)

    override suspend fun loadBadges(force: Boolean): ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>? = withContext(Dispatchers.IO) {
        val network = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helix = TwitchApiHelper.getHelixHeaders(context)
        val gql = TwitchApiHelper.getGQLHeaders(context, true)
        provider {
            val global = GlobalBadgeCache.get(GlobalCatalogKey.TWITCH_BADGES, force = force) {
                playerRepository.loadGlobalBadges(network, helix, gql, "4")
            }
            val channel = playerRepository.loadChannelBadges(network, helix, gql, channelId, channelLogin, "4")
            (global + channel).associateBy { "${it.setId}:${it.version}" }.mapValues { (_, badge) -> badge.toCatalog() }
        }
    }

    private suspend fun loadSevenTv(
        network: String?,
        useWebp: Boolean,
        force: Boolean,
    ): ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>> {
        var channelSetId: String? = null
        val global = scopeUpdate(channel = false) {
            val response = globalCatalogCache.get(GlobalCatalogKey.SEVEN_TV, force) {
                playerRepository.loadGlobalSTVEmoteSetResponse(network).also {
                    playerRepository.loadSTVEmoteSet(it, useWebp, true)
                }
            }
            playerRepository.loadSTVEmoteSet(response, useWebp, true).second
        }.map { emoteMap(it, ChatAssetProvider.SEVEN_TV, ChatEmoteScope.GLOBAL) }
        val channel = scopeUpdate(channel = true, emptyValue = emptyList()) {
            val user = playerRepository.loadSTVUser(
                playerRepository.loadSTVUserResponse(network, channelId, throwOnHttpError = true),
                useWebp,
            )
            channelSetId = user.first
            user.second ?: if (!user.first.isNullOrBlank()) {
                playerRepository.loadSTVEmoteSet(
                    playerRepository.loadSTVEmoteSetResponse(
                        network,
                        user.first!!,
                        throwOnHttpError = true,
                    ),
                    useWebp,
                    false,
                ).second
            } else emptyList()
        }.map { emoteMap(it, ChatAssetProvider.SEVEN_TV, ChatEmoteScope.CHANNEL) }
        val personal = context.tokenPrefs().getString(C.USER_ID, null)?.let { accountId ->
            // Personal emotes are optional. A missing entitlement query must not make the
            // channel catalog retry forever or hide the global/channel scopes.
            scopeUpdate(channel = true, emptyValue = emptyMap()) {
                playerRepository.loadSTVEntitledEmoteSetIds(network, accountId).associateWith { setId ->
                    try {
                        emoteMap(
                            playerRepository.loadSTVPersonalEmotes(
                                playerRepository.loadSTVEmoteSetResponse(
                                    network,
                                    setId,
                                    throwOnHttpError = true,
                                ),
                                useWebp,
                            ).second,
                            ChatAssetProvider.SEVEN_TV,
                            ChatEmoteScope.PERSONAL,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        emptyMap()
                    }
                }
            }
        } ?: ScopeUpdate.Success(emptyMap())
        return scopedProviderUpdate(global, channel, personal, channelSetId)
    }

    private suspend fun loadBttv(
        network: String?,
        useWebp: Boolean,
        force: Boolean,
    ): ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>> {
        val global = scopeUpdate(channel = false) {
            val response = globalCatalogCache.get(GlobalCatalogKey.BTTV, force) {
                playerRepository.loadGlobalBTTVEmotesResponse(network).also {
                    playerRepository.loadGlobalBTTVEmotes(it, useWebp)
                }
            }
            playerRepository.loadGlobalBTTVEmotes(response, useWebp)
        }.map { emoteMap(it, ChatAssetProvider.BTTV, ChatEmoteScope.GLOBAL) }
        val channel = scopeUpdate(channel = true, emptyValue = emptyList()) {
            playerRepository.loadBTTVEmotes(
                playerRepository.loadBTTVEmotesResponse(
                    network,
                    channelId,
                    throwOnHttpError = true,
                ),
                useWebp,
            )
        }.map { emoteMap(it, ChatAssetProvider.BTTV, ChatEmoteScope.CHANNEL) }
        return scopedProviderUpdate(global, channel)
    }

    private suspend fun loadFfz(
        network: String?,
        useWebp: Boolean,
        force: Boolean,
    ): ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>> {
        val global = scopeUpdate(channel = false) {
            val response = globalCatalogCache.get(GlobalCatalogKey.FFZ, force) {
                playerRepository.loadGlobalFFZEmotesResponse(network).also {
                    playerRepository.loadGlobalFFZEmotes(it, useWebp)
                }
            }
            playerRepository.loadGlobalFFZEmotes(response, useWebp)
        }.map { emoteMap(it, ChatAssetProvider.FFZ, ChatEmoteScope.GLOBAL) }
        val channel = scopeUpdate(channel = true, emptyValue = emptyList()) {
            playerRepository.loadFFZEmotes(
                playerRepository.loadFFZEmotesResponse(
                    network,
                    channelId,
                    throwOnHttpError = true,
                ),
                useWebp,
            )
        }.map { emoteMap(it, ChatAssetProvider.FFZ, ChatEmoteScope.CHANNEL) }
        return scopedProviderUpdate(global, channel)
    }

    private fun emoteMap(emotes: List<Emote>, provider: ChatAssetProvider, scope: ChatEmoteScope): Map<String, ChatCatalogEmote> = buildMap {
        // Provider response order is stable; first alias wins within a provider.
        emotes.forEach { emote ->
            val name = emote.name?.takeIf { it.isNotBlank() } ?: return@forEach
            if (name in this) return@forEach
            val url = emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x ?: return@forEach
            val width = emote.width?.takeIf { it > 0 } ?: 56
            val height = emote.height?.takeIf { it > 0 } ?: 56
            put(name, ChatCatalogEmote(
                id = emote.id?.takeIf { it.isNotBlank() } ?: return@forEach,
                name = name,
                asset = ChatAssetSpec(
                    key = ChatAssetKey(url),
                    sourceWidth = width,
                    sourceHeight = height,
                    targetHeight = 28,
                ),
                provider = provider,
                animated = emote.isAnimated,
                scope = scope,
                zeroWidth = emote.isOverlayEmote,
            ))
        }
    }

    private fun TwitchBadge.toCatalog(): ChatCatalogBadge {
        val url = url4x ?: url3x ?: url2x ?: url1x
        return ChatCatalogBadge(
            name = "$setId:$version",
            asset = ChatAssetSpec(
                key = ChatAssetKey(
                    url?.takeIf { it.isNotBlank() }
                        ?: "twitch-badge:$setId:$version",
                ),
                sourceWidth = 18,
                sourceHeight = 18,
                targetHeight = 18,
            ),
            provider = ChatAssetProvider.TWITCH,
            setId = setId,
            versionId = version,
            info = title,
        )
    }

    private suspend fun <T> scopeUpdate(
        channel: Boolean,
        emptyValue: T? = null,
        block: suspend () -> T,
    ): ScopeUpdate<T> = try {
        ScopeUpdate.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (channel && isExpectedMissingChannel(e) && emptyValue != null) {
            ScopeUpdate.Success(emptyValue)
        } else {
            ScopeUpdate.Failed
        }
    }

    private fun <T, R> ScopeUpdate<T>.map(transform: (T) -> R): ScopeUpdate<R> = when (this) {
        is ScopeUpdate.Success -> ScopeUpdate.Success(transform(value))
        ScopeUpdate.Failed -> ScopeUpdate.Failed
    }

    private fun scopedProviderUpdate(
        global: ScopeUpdate<Map<String, ChatCatalogEmote>>,
        channel: ScopeUpdate<Map<String, ChatCatalogEmote>>,
        personal: ScopeUpdate<Map<String, Map<String, ChatCatalogEmote>>>? = null,
        channelSetId: String? = null,
    ): ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>> {
        val value = buildMap {
            if (global is ScopeUpdate.Success) putAll(global.value)
            if (channel is ScopeUpdate.Success) putAll(channel.value)
            if (personal is ScopeUpdate.Success) personal.value.values.forEach { putAll(it) }
        }
        return ChatCatalogProviderUpdate(
            value = value,
            global = global,
            channel = channel,
            personal = personal,
            channelSetId = channelSetId,
        )
    }

    private fun emptyProviderUpdate(): ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>> =
        ChatCatalogProviderUpdate(
            value = emptyMap(),
            global = ScopeUpdate.Success(emptyMap()),
            channel = ScopeUpdate.Success(emptyMap()),
            personal = ScopeUpdate.Success(emptyMap()),
        )

    private fun isExpectedMissingChannel(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            (it as? NetworkUtils.HttpStatusException)?.statusCode == 404
        }

    private suspend fun <T> provider(block: suspend () -> T): ChatCatalogProviderUpdate<T>? = try {
        ChatCatalogProviderUpdate(block())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

internal fun CheerEmote.toCatalog(): Pair<String, ChatCatalogCheermote>? {
    val url = url4x ?: url3x ?: url2x ?: url1x ?: return null
    val key = "twitch-cheer:$name:$minBits"
    val color = color?.let { value -> runCatching { Color.parseColor(value) }.getOrNull() }
    return key to ChatCatalogCheermote(
        asset = ChatAssetSpec(
            key = ChatAssetKey(url),
            sourceWidth = 28,
            sourceHeight = 28,
            targetHeight = 28,
        ),
        color = color,
        animated = isAnimated,
    )
}

private enum class GlobalCatalogKey {
    SEVEN_TV,
    BTTV,
    FFZ,
    TWITCH_BADGES,
}

/** Process-scoped single-flight cache for provider data shared by every live chat session. */
internal class ExpiringSingleFlightCache<K>(private val ttlMs: Long = 5 * 60 * 1000L) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<K, Entry>()
    private val inFlight = mutableMapOf<K, CompletableDeferred<Any>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(key: K, force: Boolean = false, loader: suspend () -> T): T {
        data class Lookup(val deferred: CompletableDeferred<Any>, val owner: Boolean)
        val lookup = mutex.withLock {
            val now = System.currentTimeMillis()
            entries[key]?.takeIf { !force && now - it.createdAtMs < ttlMs }?.let {
                return@withLock Lookup(CompletableDeferred(it.value), owner = false)
            }
            inFlight[key]?.let { return@withLock Lookup(it, owner = false) }
            val deferred = CompletableDeferred<Any>()
            inFlight[key] = deferred
            Lookup(deferred, owner = true)
        }
        if (!lookup.owner) return lookup.deferred.await() as T
        return try {
            val value = loader()
            mutex.withLock {
                entries[key] = Entry(System.currentTimeMillis(), value)
                inFlight.remove(key)?.complete(value)
            }
            value
        } catch (error: Throwable) {
            mutex.withLock { inFlight.remove(key)?.completeExceptionally(error) }
            throw error
        }
    }

    private data class Entry(val createdAtMs: Long, val value: Any)
}

private val GlobalBadgeCache = ExpiringSingleFlightCache<GlobalCatalogKey>()
private val PersonalEmoteSetCache = ExpiringSingleFlightCache<String>()

private object GlobalCatalogCacheRegistry {
    @Volatile
    private var cache: PersistentStringSingleFlightCache<GlobalCatalogKey>? = null

    @Synchronized
    fun get(context: Context): PersistentStringSingleFlightCache<GlobalCatalogKey> =
        cache ?: PersistentStringSingleFlightCache<GlobalCatalogKey>(
            directory = File(context.applicationContext.filesDir, "chat-v2/global"),
            ttlMs = 12 * 60 * 60 * 1000L,
            fileName = { key: GlobalCatalogKey -> "${key.name.lowercase()}.json" },
        ).also { cache = it }
}

/** Persistent L1/L2 cache for provider responses shared by all channel sessions. */
internal class PersistentStringSingleFlightCache<K>(
    private val directory: File,
    private val ttlMs: Long,
    private val fileName: (K) -> String,
) {
    private data class Entry(val fetchedAtMs: Long, val value: String)
    private data class DiskEntry(val fetchedAtMs: Long, val value: String)

    private val mutex = Mutex()
    private val entries = mutableMapOf<K, Entry>()
    private val inFlight = mutableMapOf<K, CompletableDeferred<String>>()

    suspend fun get(key: K, force: Boolean = false, loader: suspend () -> String): String {
        data class Lookup(val deferred: CompletableDeferred<String>, val owner: Boolean)
        val lookup = mutex.withLock {
            inFlight[key]?.let { return@withLock Lookup(it, owner = false) }
            if (!force) {
                entries[key]?.takeIf { isFresh(it.fetchedAtMs) }?.let {
                    return@withLock Lookup(CompletableDeferred(it.value), owner = false)
                }
            }
            val deferred = CompletableDeferred<String>()
            inFlight[key] = deferred
            Lookup(deferred, owner = true)
        }
        if (!lookup.owner) return lookup.deferred.await()

        return try {
            val disk = if (!force) readDisk(key) else null
            val diskFresh = disk?.takeIf { isFresh(it.fetchedAtMs) }
            val value = diskFresh?.value ?: loader()
            val fetchedAtMs = diskFresh?.fetchedAtMs ?: System.currentTimeMillis()
            mutex.withLock {
                entries[key] = Entry(fetchedAtMs, value)
                inFlight.remove(key)?.complete(value)
            }
            if (diskFresh == null) writeDisk(key, fetchedAtMs, value)
            value
        } catch (error: Throwable) {
            mutex.withLock { inFlight.remove(key)?.completeExceptionally(error) }
            throw error
        }
    }

    private fun isFresh(fetchedAtMs: Long): Boolean =
        fetchedAtMs > 0L && System.currentTimeMillis() - fetchedAtMs in 0 until ttlMs

    private suspend fun readDisk(key: K): DiskEntry? = withContext(Dispatchers.IO) {
        val file = File(directory, fileName(key))
        if (!file.isFile) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            DiskEntry(root.optLong("fetchedAt", 0L), root.getString("payload"))
        }.getOrNull()
    }

    private suspend fun writeDisk(key: K, fetchedAtMs: Long, value: String) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val file = File(directory, fileName(key))
            val temp = File(directory, "${file.name}.tmp")
            temp.writeText(JSONObject().apply {
                put("fetchedAt", fetchedAtMs)
                put("payload", value)
            }.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        }
    }
}

/** Structured, versioned last-good catalog storage for one playback channel. */
class TwitchChatCatalogCache(
    context: Context,
    channelId: String,
) : ChatCatalogCache {
    private val file = File(
        File(context.applicationContext.filesDir, "chat-v2/catalog"),
        channelId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json",
    )

    override suspend fun read(): ChatCatalogSnapshot? = readEntry()?.snapshot

    override suspend fun readEntry(): ChatCatalogCacheEntry? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            ChatCatalogCacheEntry(
                snapshot = decode(root),
                fetchedAtMs = root.optLong("fetchedAt", 0L),
                badgesFetchedAtMs = root.optLong("badgesFetchedAt", 0L),
                catalogConfigFingerprint = root.optString("catalogConfigFingerprint").takeIf { it.isNotBlank() },
                badgeConfigFingerprint = root.optString("badgeConfigFingerprint").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    override suspend fun write(snapshot: ChatCatalogSnapshot) = write(snapshot, System.currentTimeMillis())

    override suspend fun write(snapshot: ChatCatalogSnapshot, fetchedAtMs: Long) = withContext(Dispatchers.IO) {
        write(snapshot, fetchedAtMs, 0L, null, null)
    }

    override suspend fun write(
        snapshot: ChatCatalogSnapshot,
        fetchedAtMs: Long,
        badgesFetchedAtMs: Long,
        catalogConfigFingerprint: String?,
        badgeConfigFingerprint: String?,
    ) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(
            encode(
                snapshot,
                fetchedAtMs,
                badgesFetchedAtMs,
                catalogConfigFingerprint,
                badgeConfigFingerprint,
            ).toString(),
        )
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Unable to publish chat catalog cache" }
        }
    }

    private fun encode(
        snapshot: ChatCatalogSnapshot,
        fetchedAtMs: Long,
        badgesFetchedAtMs: Long,
        catalogConfigFingerprint: String?,
        badgeConfigFingerprint: String?,
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", 6)
        put("revision", snapshot.revision)
        put("provider", "combined")
        put("fetchedAt", fetchedAtMs)
        put("badgesFetchedAt", badgesFetchedAtMs)
        putOpt("catalogConfigFingerprint", catalogConfigFingerprint)
        putOpt("badgeConfigFingerprint", badgeConfigFingerprint)
        put("twitch", encodeEmotes(snapshot.twitch))
        put("sevenTv", encodeScopedEmotes(snapshot.sevenTv))
        putOpt("sevenTvChannelSetId", snapshot.sevenTvChannelSetId)
        put("bttv", encodeScopedEmotes(snapshot.bttv))
        put("ffz", encodeScopedEmotes(snapshot.ffz))
        put("badges", encodeBadges(snapshot.badges))
    }

    private fun encodeEmotes(map: Map<String, ChatCatalogEmote>) = JSONArray().apply {
        map.values.forEach { emote ->
            put(JSONObject().apply {
                put("name", emote.name)
                put("id", emote.id)
                put("scope", emote.scope.name)
                put("provider", emote.provider.name)
                put("animated", emote.animated)
                put("zeroWidth", emote.zeroWidth)
                put("asset", encodeSpec(emote.asset))
            })
        }
    }

    private fun encodeScopedEmotes(scoped: ScopedEmoteCatalog) = JSONObject().apply {
        put("global", encodeEmotes(scoped.global))
        put("channel", encodeEmotes(scoped.channel))
        put("personal", JSONObject().apply {
            scoped.personal.forEach { (setId, emotes) -> put(setId, encodeEmotes(emotes)) }
        })
        put("legacyCombined", encodeEmotes(scoped.legacyCombined))
        put("pending", JSONObject().apply {
            scoped.pending.forEach { (setId, emotes) -> put(setId, encodeEmotes(emotes)) }
        })
    }

    private fun encodeBadges(map: Map<String, ChatCatalogBadge>) = JSONArray().apply {
        map.values.forEach { badge ->
            put(JSONObject().apply {
                put("name", badge.name)
                put("provider", badge.provider.name)
                put("setId", badge.setId)
                put("versionId", badge.versionId)
                putOpt("info", badge.info)
                put("asset", encodeSpec(badge.asset))
            })
        }
    }

    private fun encodeSpec(spec: ChatAssetSpec): JSONObject = JSONObject().apply {
        put("key", spec.key.value)
        put("sourceWidth", spec.sourceWidth)
        put("sourceHeight", spec.sourceHeight)
        put("targetHeight", spec.targetHeight)
        val encodedOverlays = JSONArray()
        spec.overlays.forEach { overlay -> encodedOverlays.put(encodeSpec(overlay)) }
        put("overlays", encodedOverlays)
    }

    private fun decode(root: JSONObject): ChatCatalogSnapshot {
        val schemaVersion = root.optInt("schemaVersion")
        check(schemaVersion in 1..6)
        fun emoteArray(
            array: JSONArray?,
            legacyCombined: Boolean = false,
        ): Map<String, ChatCatalogEmote> = buildMap {
            array ?: return@buildMap
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val emoteName = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() }
                    ?: emoteName.takeIf { schemaVersion == 1 }
                    ?: continue
                val provider = runCatching { ChatAssetProvider.valueOf(item.optString("provider")) }.getOrNull() ?: continue
                val storedScope = item.optString("scope").takeIf { it.isNotBlank() }
                put(emoteName, ChatCatalogEmote(
                    name = emoteName,
                    asset = decodeSpec(item.optJSONObject("asset")),
                    provider = provider,
                    animated = item.optBoolean("animated"),
                    zeroWidth = item.optBoolean("zeroWidth"),
                    id = id,
                    scope = if (legacyCombined || schemaVersion == 1 && storedScope == null) {
                        ChatEmoteScope.LEGACY_COMBINED
                    } else {
                        runCatching { ChatEmoteScope.valueOf(storedScope.orEmpty()) }
                            .getOrDefault(ChatEmoteScope.GLOBAL)
                    },
                ))
            }
        }
        fun emotes(name: String): ScopedEmoteCatalog {
            val value = root.opt(name)
            if (value is JSONObject) {
                val personal = value.opt("personal")
                return ScopedEmoteCatalog(
                    global = emoteArray(value.optJSONArray("global")),
                    channel = emoteArray(value.optJSONArray("channel")),
                    personal = if (personal is JSONObject) buildMap {
                        personal.keys().forEach { setId ->
                            put(setId, emoteArray(personal.optJSONArray(setId)))
                        }
                    } else emptyMap(),
                    legacyCombined = emoteArray(value.optJSONArray("legacyCombined")),
                    pending = (value.opt("pending") as? JSONObject)?.let { pending ->
                        buildMap {
                            pending.keys().forEach { setId ->
                                put(setId, emoteArray(pending.optJSONArray(setId)))
                            }
                        }
                    }.orEmpty(),
                )
            }
            // Schema 1/2 used one array per provider. Schema 1 had no stable provider
            // identity, so retain those records as legacy combined entries and synthesize
            // the emote name as their only available identity.
            return if (schemaVersion == 1) {
                ScopedEmoteCatalog(
                    legacyCombined = emoteArray(root.optJSONArray(name), legacyCombined = true),
                )
            } else {
                ScopedEmoteCatalog.fromEffective(emoteArray(root.optJSONArray(name)))
            }
        }
        val badges = buildMap {
            val array = root.optJSONArray("badges") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val key = "${item.optString("setId")}:${item.optString("versionId")}"
                put(key, ChatCatalogBadge(
                    item.optString("name"),
                    decodeSpec(item.optJSONObject("asset")),
                    ChatAssetProvider.TWITCH,
                    item.optString("setId"),
                    item.optString("versionId"),
                    item.optString("info").takeIf { it.isNotBlank() },
                ))
            }
        }
        return ChatCatalogSnapshot(
            revision = root.optLong("revision", 0L),
            twitch = emoteArray(root.optJSONArray("twitch"), legacyCombined = schemaVersion == 1),
            sevenTv = emotes("sevenTv"),
            sevenTvChannelSetId = root.optString("sevenTvChannelSetId").takeIf { it.isNotBlank() },
            bttv = emotes("bttv"),
            ffz = emotes("ffz"),
            badges = badges,
        )
    }

    private fun decodeSpec(value: JSONObject?): ChatAssetSpec {
        requireNotNull(value)
        val overlays = value.optJSONArray("overlays")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::decodeSpec) }
        }.orEmpty()
        return ChatAssetSpec(
            key = ChatAssetKey(value.optString("key")),
            sourceWidth = value.optInt("sourceWidth", 56),
            sourceHeight = value.optInt("sourceHeight", 56),
            targetHeight = value.optInt("targetHeight", 28),
            overlays = overlays,
        )
    }
}
