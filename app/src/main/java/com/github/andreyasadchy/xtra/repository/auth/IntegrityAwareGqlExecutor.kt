package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsCategory
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsLogger
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsSeverity
import com.github.andreyasadchy.xtra.diagnostics.DiagnosticsTransport
import com.github.andreyasadchy.xtra.util.C

/** Shared identity lifecycle policy for every authenticated Gecko-backed GQL transport. */
internal class IntegrityAwareGqlExecutor<T>(
    private val isWebSessionActive: () -> Boolean,
    private val isCurrentAuthorization: (String?) -> Boolean,
    private val currentRequest: () -> GeckoGqlRequest?,
    private val refresh: suspend () -> Boolean,
    private val invalidateIfCurrent: (GeckoGqlIdentity) -> Boolean,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    suspend fun execute(
        fallbackHeaders: Map<String, String>,
        requireActiveWebSession: Boolean,
        isFailedIntegrityCheck: (T) -> Boolean,
        send: suspend (Map<String, String>) -> T,
        diagnosticsOperation: String? = null,
        diagnosticsCorrelationId: String? = null,
    ): T {
        fun log(event: String, severity: DiagnosticsSeverity = DiagnosticsSeverity.DEBUG, code: String? = null) {
            val logger = diagnosticsLogger ?: return
            if (!logger.isEnabled) return
            logger.event(
                category = DiagnosticsCategory.INTEGRITY,
                severity = severity,
                transport = DiagnosticsTransport.GQL,
                operation = diagnosticsOperation ?: "authenticated_gql",
                event = event,
                code = code,
                correlationId = diagnosticsCorrelationId,
            )
        }

        if (!isWebSessionActive()) {
            log("identity_missing", DiagnosticsSeverity.WARN, "web_session_missing")
            if (requireActiveWebSession) {
                throw MissingAuthenticationException("authenticated Twitch GQL")
            }
            return send(fallbackHeaders)
        }
        if (!isCurrentAuthorization(fallbackHeaders[C.HEADER_TOKEN])) {
            log("identity_mismatch", DiagnosticsSeverity.WARN, "authorization_mismatch")
            throw MissingAuthenticationException("authenticated Twitch GQL")
        }

        var request = currentRequest()
        if (request == null) {
            log("integrity_refresh_start")
            val refreshed = refresh()
            log(
                if (refreshed) "integrity_refresh_success" else "integrity_refresh_failed",
                if (refreshed) DiagnosticsSeverity.INFO else DiagnosticsSeverity.ERROR,
                if (refreshed) null else "refresh_failed",
            )
            if (!refreshed) {
                throw MissingAuthenticationException("authenticated Twitch GQL")
            }
            request = currentRequest()
                ?: throw MissingAuthenticationException("authenticated Twitch GQL")
        }

        val response = send(request.headers)
        if (!isFailedIntegrityCheck(response)) return response
        log("integrity_rejected", DiagnosticsSeverity.WARN, "integrity_rejected")

        if (!invalidateIfCurrent(request.identity)) {
            // Another request already installed a newer snapshot. Preserve it
            // and retry this operation once with the newer identity.
            currentRequest()?.let { newerRequest ->
                log("identity_superseded", DiagnosticsSeverity.INFO, "newer_identity_available")
                log("integrity_retry_start")
                val retried = send(newerRequest.headers)
                val retryFailed = isFailedIntegrityCheck(retried)
                log(
                    "integrity_retry_result",
                    if (retryFailed) DiagnosticsSeverity.ERROR else DiagnosticsSeverity.INFO,
                    if (retryFailed) "integrity_rejected" else "success",
                )
                return retried
            }
        }
        log("integrity_refresh_start")
        val refreshed = refresh()
        log(
            if (refreshed) "integrity_refresh_success" else "integrity_refresh_failed",
            if (refreshed) DiagnosticsSeverity.INFO else DiagnosticsSeverity.ERROR,
            if (refreshed) null else "refresh_failed",
        )
        if (!refreshed) {
            if (!isWebSessionActive()) {
                throw MissingAuthenticationException("authenticated Twitch GQL")
            }
            return response
        }
        val refreshedRequest = currentRequest()
        if (refreshedRequest == null) {
            if (!isWebSessionActive()) {
                throw MissingAuthenticationException("authenticated Twitch GQL")
            }
            return response
        }
        log("integrity_retry_start")
        val retried = send(refreshedRequest.headers)
        val retryFailed = isFailedIntegrityCheck(retried)
        log(
            "integrity_retry_result",
            if (retryFailed) DiagnosticsSeverity.ERROR else DiagnosticsSeverity.INFO,
            if (retryFailed) "integrity_rejected" else "success",
        )
        return retried
    }
}
