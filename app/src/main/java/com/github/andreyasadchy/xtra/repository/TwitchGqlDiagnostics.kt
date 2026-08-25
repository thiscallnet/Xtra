package com.github.andreyasadchy.xtra.repository

import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object TwitchGqlDiagnostics {

    private const val TAG = "TwitchGql"
    private const val TRACE_FILE_NAME = "twitch-gql-trace.ndjson"
    private const val MAX_TRACE_BYTES = 2L * 1024L * 1024L
    private val operationNameRegex = Regex("\\\"operationName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val errorCodeRegex = Regex("\\\"(?:code|errorCode)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val errorMessageRegex = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]{0,240})\\\"")

    fun operationName(body: String): String? = operationNameRegex.find(body)?.groupValues?.getOrNull(1)

    fun logRequest(operationName: String?, headers: Map<String, String>, body: String) {
        appendTrace(
            JSONObject().apply {
                put("event", "native_request")
                put("operation", operationName.orUnknown())
                put("headers", headerSummary(headers))
                put("body", bodySummary(body))
            },
        )
        if (!isProtectedRequest(headers)) return

        val authorization = headers["Authorization"]
        val integrityHeaders = headers.keys
            .filter { it.contains("integrity", ignoreCase = true) }
            .joinToString(",")
            .ifBlank { "none" }
        Log.d(
            TAG,
            "request operation=${operationName.orUnknown()} " +
                "auth=${authorizationScheme(authorization)} " +
                "clientId=${headers.hasHeader("Client-Id")} " +
                "cookie=${headers.hasHeader("Cookie")} " +
                "clientSessionId=${headers.hasHeader("Client-Session-Id")} " +
                "deviceId=${headers.hasHeader("X-Device-Id")} " +
                "integrityHeaders=$integrityHeaders integrityExpiry=none " +
                "sessionHash=${headers.headerHash("Client-Session-Id")} " +
                "deviceHash=${headers.headerHash("X-Device-Id")}",
        )
    }

    fun logResponse(operationName: String?, httpStatusCode: Int, body: String, headers: Map<String, String>) {
        val errorCodes = errorCodeRegex.findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        val errorMessages = errorMessageRegex.findAll(body)
            .map { it.groupValues[1].replace('\n', ' ') }
            .distinct()
            .toList()
        appendTrace(
            JSONObject().apply {
                put("event", "native_response")
                put("operation", operationName.orUnknown())
                put("httpStatus", httpStatusCode)
                put("headers", headerSummary(headers))
                put("body", bodySummary(body))
                put("gqlErrorCodes", jsonArray(errorCodes))
                put("gqlErrorMessages", jsonArray(errorMessages))
            },
        )
        if (!isProtectedRequest(headers)) return

        val errorCodeSummary = errorCodes.joinToString(",").ifBlank { "none" }
        Log.d(
            TAG,
            "response operation=${operationName.orUnknown()} http=$httpStatusCode gqlErrorCodes=$errorCodeSummary",
        )
    }

    fun logBrowserRequest(requestId: String?, method: String?, headerNames: List<String>) {
        appendTrace(
            JSONObject().apply {
                put("event", "browser_request")
                put("requestIdHash", requestId?.let(::hash).orUnknown())
                put("method", method.orUnknown())
                put("headerNames", headerNames)
            },
        )
        Log.d(
            TAG,
            "browser request idHash=${requestId?.let(::hash).orUnknown()} " +
                "method=${method.orUnknown()} headerNames=${headerNames.joinToString(",").ifBlank { "none" }}",
        )
    }

    fun logBrowserResponse(requestId: String?, statusCode: Int, headerNames: List<String>) {
        appendTrace(
            JSONObject().apply {
                put("event", "browser_response")
                put("requestIdHash", requestId?.let(::hash).orUnknown())
                put("httpStatus", statusCode)
                put("headerNames", headerNames)
            },
        )
        Log.d(
            TAG,
            "browser response idHash=${requestId?.let(::hash).orUnknown()} " +
                "http=$statusCode headerNames=${headerNames.joinToString(",").ifBlank { "none" }}",
        )
    }

    fun logBrowserError(requestId: String?, error: String?) {
        val safeError = error?.replace(Regex("[\\r\\n]"), " ")?.take(160).orUnknown()
        appendTrace(
            JSONObject().apply {
                put("event", "browser_error")
                put("requestIdHash", requestId?.let(::hash).orUnknown())
                put("error", safeError)
            },
        )
        Log.d(
            TAG,
            "browser error idHash=${requestId?.let(::hash).orUnknown()} error=$safeError",
        )
    }

    fun logBrowserHeaders(headers: Map<String, String>, integrityExpiresAtMillis: Long?) {
        val expiresInSeconds = integrityExpiresAtMillis
            ?.let { ((it - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000L).toString() }
            ?: "unknown"
        appendTrace(
            JSONObject().apply {
                put("event", "browser_headers")
                put("headers", headerSummary(headers))
                put("integrityExpiresInSeconds", expiresInSeconds)
            },
        )
        Log.d(
            TAG,
            "browser gql headers " +
                "auth=${authorizationScheme(headers["Authorization"])} " +
                "clientId=${headers.hasHeader("Client-Id")} " +
                "clientIntegrity=${headers.hasHeader("Client-Integrity")} " +
                "clientSessionId=${headers.hasHeader("Client-Session-Id")} " +
                "clientVersion=${headers.hasHeader("Client-Version")} " +
                "deviceId=${headers.hasHeader("X-Device-Id")} " +
                "expiresInSeconds=$expiresInSeconds",
        )
    }

    private fun isProtectedRequest(headers: Map<String, String>): Boolean =
        headers.hasHeader("Client-Session-Id") && headers.hasHeader("X-Device-Id")

    private fun headerSummary(headers: Map<String, String>): JSONObject = JSONObject().apply {
        put("authorization", authorizationScheme(headers["Authorization"]))
        put("clientId", headers.hasHeader("Client-Id"))
        put("cookie", headers.hasHeader("Cookie"))
        put("clientIntegrity", headers.hasHeader("Client-Integrity"))
        put("clientSessionId", headers.hasHeader("Client-Session-Id"))
        put("clientVersion", headers.hasHeader("Client-Version"))
        put("deviceId", headers.hasHeader("X-Device-Id"))
        put("integrityHeaderNames", jsonArray(headers.keys.filter { it.contains("integrity", ignoreCase = true) }))
        put("sessionHash", headers.headerHash("Client-Session-Id"))
        put("deviceHash", headers.headerHash("X-Device-Id"))
    }

    private fun bodySummary(body: String): JSONObject = JSONObject().apply {
        put("bytes", body.toByteArray().size)
        put("sha256", hash(body))
        put("topLevelKeys", jsonArray(runCatching {
            val keys = JSONObject(body).keys()
            buildList { while (keys.hasNext()) add(keys.next()) }
        }.getOrDefault(emptyList<String>())))
        put("variableKeys", jsonArray(runCatching {
            val keys = JSONObject(body).optJSONObject("variables")?.keys()
            buildList { while (keys?.hasNext() == true) add(keys.next()) }
        }.getOrDefault(emptyList<String>())))
    }

    private fun jsonArray(values: List<String>): JSONArray = JSONArray().apply {
        values.forEach(::put)
    }

    private fun Map<String, String>.hasHeader(name: String): Boolean = keys.any { it.equals(name, ignoreCase = true) }

    private fun Map<String, String>.headerHash(name: String): String =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.let(::hash)
            ?: "none"

    private fun authorizationScheme(value: String?): String =
        value?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "none"

    private fun String?.orUnknown(): String = this ?: "unknown"

    private fun hash(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

    private fun appendTrace(event: JSONObject) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(XtraApp.INSTANCE.cacheDir, TRACE_FILE_NAME)
            synchronized(this) {
                file.parentFile?.mkdirs()
                if (file.length() > MAX_TRACE_BYTES) file.writeText("")
                file.appendText(event.toString() + '\n')
            }
        }
    }
}
