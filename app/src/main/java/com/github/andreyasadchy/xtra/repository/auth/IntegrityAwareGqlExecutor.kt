package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import com.github.andreyasadchy.xtra.util.C

/** Shared identity lifecycle policy for every authenticated Gecko-backed GQL transport. */
internal class IntegrityAwareGqlExecutor<T>(
    private val isWebSessionActive: () -> Boolean,
    private val isCurrentAuthorization: (String?) -> Boolean,
    private val currentRequest: () -> GeckoGqlRequest?,
    private val refresh: suspend () -> Boolean,
    private val invalidateIfCurrent: (GeckoGqlIdentity) -> Boolean,
) {
    suspend fun execute(
        fallbackHeaders: Map<String, String>,
        requireActiveWebSession: Boolean,
        isFailedIntegrityCheck: (T) -> Boolean,
        send: suspend (Map<String, String>) -> T,
    ): T {
        if (!isWebSessionActive()) {
            if (requireActiveWebSession) {
                throw MissingAuthenticationException("authenticated Twitch GQL")
            }
            return send(fallbackHeaders)
        }
        if (!isCurrentAuthorization(fallbackHeaders[C.HEADER_TOKEN])) {
            throw MissingAuthenticationException("authenticated Twitch GQL")
        }

        var request = currentRequest()
        if (request == null) {
            if (!refresh()) {
                throw MissingAuthenticationException("authenticated Twitch GQL")
            }
            request = currentRequest()
                ?: throw MissingAuthenticationException("authenticated Twitch GQL")
        }

        val response = send(request.headers)
        if (!isFailedIntegrityCheck(response)) return response

        if (!invalidateIfCurrent(request.identity)) {
            // Another request already installed a newer snapshot. Preserve it
            // and retry this operation once with the newer identity.
            currentRequest()?.let { newerRequest ->
                return send(newerRequest.headers)
            }
        }
        if (!refresh()) {
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
        return send(refreshedRequest.headers)
    }
}
