package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class DeviceAuthorizationPoller(
    private val requestToken: suspend (deviceCode: String, scopes: Collection<String>) -> TokenResponse,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val maxNetworkRetries: Int = DEFAULT_MAX_NETWORK_RETRIES,
) {
    suspend fun poll(
        deviceAuthorization: DeviceCodeResponse,
        scopes: Collection<String> = emptyList(),
    ): TokenResponse {
        val deviceCode = deviceAuthorization.deviceCode
            ?.takeIf { it.isNotBlank() }
            ?: throw TwitchAuthProtocolException("Twitch did not return a device code")
        val expiresIn = deviceAuthorization.expiresIn
            ?.takeIf { it > 0 }
            ?: throw TwitchAuthProtocolException("Twitch did not return a device-code lifetime")
        var intervalSeconds = (deviceAuthorization.interval ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1)
        val expiresAt = nowMillis() + expiresIn * 1_000L
        var consecutiveNetworkFailures = 0
        var nextRequestAt = nowMillis() + intervalSeconds * 1_000L

        while (nowMillis() < expiresAt) {
            val waitMillis = (nextRequestAt - nowMillis()).coerceAtLeast(0L)
            if (waitMillis > 0) delayMillis(waitMillis)
            if (nowMillis() >= expiresAt) break

            val response = try {
                requestToken(deviceCode, scopes).also { consecutiveNetworkFailures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwitchAuthHttpException) {
                if (e.statusCode !in 500..599) throw e
                consecutiveNetworkFailures += 1
                if (consecutiveNetworkFailures > maxNetworkRetries) throw e
                nextRequestAt = nowMillis() + intervalSeconds * 1_000L
                continue
            } catch (e: TwitchAuthException) {
                consecutiveNetworkFailures += 1
                if (consecutiveNetworkFailures > maxNetworkRetries) throw e
                nextRequestAt = nowMillis() + intervalSeconds * 1_000L
                continue
            } catch (e: Exception) {
                consecutiveNetworkFailures += 1
                if (consecutiveNetworkFailures > maxNetworkRetries) {
                    throw TwitchAuthException("Network error while waiting for Twitch authorization", e)
                }
                nextRequestAt = nowMillis() + intervalSeconds * 1_000L
                continue
            }

            val accessToken = response.accessToken?.takeIf { it.isNotBlank() }
            if (accessToken != null) return response

            when (response.oauthError?.lowercase()) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += SLOW_DOWN_INCREMENT_SECONDS
                "expired_token" -> throw TwitchAuthException("The Twitch device code expired")
                "access_denied" -> throw TwitchAuthException("Twitch authorization was denied")
                else -> throw TwitchAuthProtocolException(
                    response.errorDescription ?: response.message ?: "Twitch returned an unexpected token response",
                )
            }
            nextRequestAt = nowMillis() + intervalSeconds * 1_000L
        }

        throw TwitchAuthException("The Twitch device code expired")
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5
        const val SLOW_DOWN_INCREMENT_SECONDS = 5
        const val DEFAULT_MAX_NETWORK_RETRIES = 3
    }
}
