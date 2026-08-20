package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.model.id.DeviceCodeResponse
import com.github.andreyasadchy.xtra.model.id.TokenResponse
import com.github.andreyasadchy.xtra.model.id.ValidationResponse
import com.github.andreyasadchy.xtra.repository.AuthRepository
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

interface TwitchAuthOperations {
    suspend fun startDeviceAuthorization(clientId: String, scopes: Collection<String>): DeviceCodeResponse
    suspend fun pollDeviceAuthorization(clientId: String, deviceCode: String, scopes: Collection<String>): TokenResponse
    suspend fun refreshUserToken(clientId: String, refreshToken: String): TokenResponse
    /** Access-token arguments are raw values without an Authorization scheme. */
    suspend fun validate(accessToken: String): ValidationResponse
    suspend fun validateCompatibility(accessToken: String): ValidationResponse
    suspend fun revoke(clientId: String, accessToken: String)
}

class TwitchAuthRepository(
    private val repository: AuthRepository,
    private val networkLibrary: String?,
) : TwitchAuthOperations {
    override suspend fun startDeviceAuthorization(clientId: String, scopes: Collection<String>): DeviceCodeResponse =
        repository.startDeviceAuthorization(networkLibrary, clientId, scopes)

    override suspend fun pollDeviceAuthorization(clientId: String, deviceCode: String, scopes: Collection<String>): TokenResponse =
        repository.pollDeviceAuthorization(networkLibrary, clientId, deviceCode, scopes)

    override suspend fun refreshUserToken(clientId: String, refreshToken: String): TokenResponse =
        repository.refreshUserToken(networkLibrary, clientId, refreshToken)

    override suspend fun validate(accessToken: String): ValidationResponse =
        repository.validateAccessToken(networkLibrary, accessToken)

    override suspend fun validateCompatibility(accessToken: String): ValidationResponse =
        repository.validate(networkLibrary, TwitchApiHelper.addTokenPrefixGQL(accessToken))

    override suspend fun revoke(clientId: String, accessToken: String) =
        repository.revoke(networkLibrary, clientId, accessToken)
}
