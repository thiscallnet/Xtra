package com.github.andreyasadchy.xtra.ui.player.hud

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compact, versioned clipboard format for sharing both HUD orientations.
 * Normal app persistence remains readable JSON; compression is only for the
 * user-facing share string.
 */
internal object HudConfigShareCodec {
    private const val PREFIX = "xtra-hud:v1:"
    private const val MAX_ENCODED_LENGTH = 16 * 1024
    private const val MAX_COMPRESSED_BYTES = 12 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024
    private val BASE64_URL = Regex("[A-Za-z0-9_-]+")

    fun encode(config: PlayerHudConfig): String {
        val json = HudConfigJson.encode(config).toByteArray(UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(json)
            deflater.finish()
            val compressed = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0) { "HUD setup compression made no progress" }
                compressed.write(buffer, 0, count)
            }
            PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(compressed.toByteArray())
        } finally {
            deflater.end()
        }
    }

    fun decode(raw: String): PlayerHudConfig? {
        val value = raw.trim()
        if (value.length > MAX_ENCODED_LENGTH || !value.startsWith(PREFIX)) return null
        val encoded = value.removePrefix(PREFIX)
        if (encoded.isEmpty() || !BASE64_URL.matches(encoded)) return null

        val compressed = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        if (compressed.isEmpty() || compressed.size > MAX_COMPRESSED_BYTES) return null

        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val json = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = runCatching { inflater.inflate(buffer) }.getOrNull() ?: return null
                if (count <= 0 || json.size() + count > MAX_DECOMPRESSED_BYTES) return null
                json.write(buffer, 0, count)
            }
            if (inflater.remaining != 0) return null
            val root = runCatching {
                org.json.JSONObject(json.toByteArray().toString(UTF_8))
            }.getOrNull() ?: return null
            if (root.optInt("version", -1) != 2 ||
                root.optJSONObject("portrait") == null ||
                root.optJSONObject("landscape") == null
            ) return null
            HudConfigJson.decode(root.toString())
        } finally {
            inflater.end()
        }
    }
}
