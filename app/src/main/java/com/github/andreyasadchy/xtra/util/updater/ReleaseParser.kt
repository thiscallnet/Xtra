package com.github.andreyasadchy.xtra.util.updater

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull

object ReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val tag = Regex("^v?([0-9]+(?:\\.[0-9]+)+)(?:-build\\.(\\d+))?$", RegexOption.IGNORE_CASE)

    fun parse(raw: String, fallbackUrl: String): ReleaseParseResult = try {
        parse(json.parseToJsonElement(raw).jsonObject, fallbackUrl)
    } catch (_: Exception) {
        ReleaseParseResult.Failure(UpdateError.InvalidResponse)
    }

    fun parse(response: JsonObject, fallbackUrl: String): ReleaseParseResult {
        val tagName = response.string("tag_name")?.takeIf { it.isNotBlank() }
            ?: return ReleaseParseResult.Failure(UpdateError.UnexpectedResponse)
        val match = tag.matchEntire(tagName)
            ?: return ReleaseParseResult.Failure(UpdateError.InvalidResponse)
        val versionName = match.groupValues[1]
        val buildNumber = match.groupValues.getOrNull(2)?.toLongOrNull()
        val assets = response["assets"]?.asArrayOrNull()?.mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            val name = asset.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = asset.string("browser_download_url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            UpdateAsset(
                name = name,
                contentType = asset.string("content_type"),
                downloadUrl = url,
                size = asset["size"]?.jsonPrimitive?.longOrNull,
            )
        } ?: return ReleaseParseResult.Failure(UpdateError.UnexpectedResponse)
        val metadata = response[RELEASE_METADATA_RESPONSE_KEY]?.let { metadataElement ->
            runCatching { metadataElement.jsonObject }.getOrNull()
                ?: return ReleaseParseResult.Failure(UpdateError.InvalidResponse)
        }
        val expectedVersionCode = metadata?.let {
            val metadataVersionName = metadata.string("versionName")
            if (metadataVersionName != null && metadataVersionName != versionName) {
                return ReleaseParseResult.Failure(UpdateError.InvalidResponse)
            }
            metadata["versionCode"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                ?: return ReleaseParseResult.Failure(UpdateError.InvalidResponse)
        }
        val expectedSha256 = metadata?.string("sha256")?.let { digest ->
            digest.lowercase().takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                ?: return ReleaseParseResult.Failure(UpdateError.InvalidResponse)
        }
        val artifactSha256 = when {
            metadata == null || !metadata.containsKey("artifacts") -> emptyMap()
            else -> parseArtifactSha256(metadata)
                ?: return ReleaseParseResult.Failure(UpdateError.InvalidResponse)
        }
        val body = response.string("body").orEmpty().trim()
        val commits = response["commits"]?.asArrayOrNull()?.mapNotNull { it.asObjectString("message") } ?: emptyList()
        val release = UpdateRelease(
            tagName = tagName,
            versionName = versionName,
            buildNumber = buildNumber,
            title = response.string("name")?.takeIf { it.isNotBlank() } ?: "Xtra $versionName",
            releaseNotes = ReleaseNotes.normalize(body, commits),
            rawBody = body,
            releaseUrl = response.string("html_url")?.takeIf { it.isNotBlank() } ?: fallbackUrl,
            publishedAt = response.string("published_at"),
            assets = assets,
            prerelease = response.boolean("prerelease") ?: false,
            draft = response.boolean("draft") ?: false,
            expectedVersionCode = expectedVersionCode,
            expectedSha256 = expectedSha256,
            artifactSha256 = artifactSha256,
        )
        return ReleaseParseResult.Success(release)
    }

    fun parseHistory(response: JsonArray, fallbackUrl: String): List<UpdateRelease> = response.mapNotNull { element ->
        val release = (element as? JsonObject) ?: return@mapNotNull null
        (parse(release, fallbackUrl) as? ReleaseParseResult.Success)?.release
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun parseArtifactSha256(metadata: JsonObject): Map<String, String>? {
        val artifacts = runCatching { metadata["artifacts"]!!.jsonArray }.getOrNull() ?: return null
        val entries = artifacts.map { element ->
            val artifact = runCatching { element.jsonObject }.getOrNull() ?: return null
            val name = artifact.string("name")?.takeIf { it.isNotBlank() } ?: return null
            val digest = artifact.string("sha256")?.lowercase()
                ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                ?: return null
            name to digest
        }
        if (entries.map { it.first }.distinct().size != entries.size) return null
        return entries.toMap()
    }
    private fun JsonElement.asArrayOrNull() = runCatching { jsonArray }.getOrNull()
    private fun JsonElement.asObjectString(key: String): String? = runCatching { jsonObject[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
