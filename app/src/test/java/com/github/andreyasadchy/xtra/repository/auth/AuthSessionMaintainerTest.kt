package com.github.andreyasadchy.xtra.repository.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionMaintainerTest {
    @Test
    fun `only incomplete authentication requests user attention`() {
        assertTrue(AuthHealth.REAUTH_REQUIRED.requiresUserAction)
        assertFalse(AuthHealth.SIGNED_OUT.requiresUserAction)
        assertFalse(AuthHealth.HEALTHY.requiresUserAction)
        assertFalse(AuthHealth.UNKNOWN.requiresUserAction)
    }

    @Test
    fun `only a complete same-user pair is healthy`() {
        assertEquals(
            AuthHealth.HEALTHY,
            classifyAuthHealth(
                officialState = OfficialAuthState.VALID,
                compatibilityState = CompatibilityAuthState.AVAILABLE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = true,
                compatibilityUserMatches = true,
                legacyCredentialPresent = true,
            ),
        )
    }

    @Test
    fun `missing compatibility is reconnect-required rather than limited`() {
        assertEquals(
            AuthHealth.REAUTH_REQUIRED,
            classifyAuthHealth(
                officialState = OfficialAuthState.VALID,
                compatibilityState = CompatibilityAuthState.UNAVAILABLE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = false,
                compatibilityUserMatches = true,
                legacyCredentialPresent = false,
            ),
        )
    }

    @Test
    fun `legacy and incomplete credential topologies require full reconnect`() {
        val cases = listOf(
            Triple(OfficialAuthState.VALID, false, true),
            Triple(OfficialAuthState.VALID, false, false),
            Triple(OfficialAuthState.IDLE, false, true),
            Triple(OfficialAuthState.VALID, true, false),
        )
        cases.forEach { (officialState, identityPresent, legacyPresent) ->
            assertEquals(
                AuthHealth.REAUTH_REQUIRED,
                classifyAuthHealth(
                    officialState = officialState,
                    compatibilityState = CompatibilityAuthState.UNAVAILABLE,
                    officialSessionComplete = officialState == OfficialAuthState.VALID && legacyPresent,
                    structuredCompatibilityPresent = false,
                    compatibilityUserMatches = identityPresent,
                    legacyCredentialPresent = legacyPresent,
                    storedAccountIdentityPresent = identityPresent,
                ),
            )
        }
    }

    @Test
    fun `wrong compatibility account is never healthy`() {
        assertEquals(
            AuthHealth.REAUTH_REQUIRED,
            classifyAuthHealth(
                officialState = OfficialAuthState.VALID,
                compatibilityState = CompatibilityAuthState.AVAILABLE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = true,
                compatibilityUserMatches = false,
                legacyCredentialPresent = true,
            ),
        )
    }

    @Test
    fun `transient validation failure does not look like invalid authentication`() {
        assertEquals(
            AuthHealth.UNKNOWN,
            classifyAuthHealth(
                officialState = OfficialAuthState.TRANSIENT_FAILURE,
                compatibilityState = CompatibilityAuthState.AVAILABLE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = true,
                compatibilityUserMatches = true,
                legacyCredentialPresent = true,
            ),
        )
        assertEquals(
            AuthHealth.UNKNOWN,
            classifyAuthHealth(
                officialState = OfficialAuthState.VALID,
                compatibilityState = CompatibilityAuthState.TRANSIENT_FAILURE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = true,
                compatibilityUserMatches = true,
                legacyCredentialPresent = true,
            ),
        )
    }

    @Test
    fun `known official invalidity wins over compatibility transient failure`() {
        assertEquals(
            AuthHealth.REAUTH_REQUIRED,
            classifyAuthHealth(
                officialState = OfficialAuthState.REAUTHORIZATION_REQUIRED,
                compatibilityState = CompatibilityAuthState.TRANSIENT_FAILURE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = true,
                compatibilityUserMatches = true,
                legacyCredentialPresent = true,
            ),
        )
    }

    @Test
    fun `successful complete replacement clears reconnect-required state`() {
        assertEquals(
            AuthHealth.REAUTH_REQUIRED,
            classifyAuthHealth(
                officialState = OfficialAuthState.VALID,
                compatibilityState = CompatibilityAuthState.REAUTHORIZATION_REQUIRED,
                officialSessionComplete = true,
                structuredCompatibilityPresent = false,
                compatibilityUserMatches = true,
                legacyCredentialPresent = false,
            ),
        )
        assertEquals(
            AuthHealth.HEALTHY,
            classifyAuthHealth(
                officialState = OfficialAuthState.VALID,
                compatibilityState = CompatibilityAuthState.AVAILABLE,
                officialSessionComplete = true,
                structuredCompatibilityPresent = true,
                compatibilityUserMatches = true,
                legacyCredentialPresent = true,
            ),
        )
    }

    @Test
    fun `maintenance validation strips authorization scheme before raw-token calls`() {
        assertEquals("raw-helix-token", rawAccessTokenFromAuthorizationHeader("Bearer raw-helix-token"))
        assertEquals("raw-gql-token", rawAccessTokenFromAuthorizationHeader("OAuth raw-gql-token"))
    }

    @Test
    fun `compatibility reauthorization does not suppress official validation`() {
        val state = AuthSessionMaintenanceStateMachine()
        state.setOfficialState(OfficialAuthState.VALID)
        state.setCompatibilityState(CompatibilityAuthState.REAUTHORIZATION_REQUIRED)

        assertFalse(state.shouldSkipOfficialValidation())
        assertEquals(
            AuthSessionMaintenanceState.COMPATIBILITY_REAUTHORIZATION_REQUIRED,
            state.maintenanceState,
        )
    }

    @Test
    fun `official reauthorization suppresses repeated validation`() {
        val state = AuthSessionMaintenanceStateMachine()
        state.setOfficialState(OfficialAuthState.REAUTHORIZATION_REQUIRED)

        assertTrue(state.shouldSkipOfficialValidation())
        assertEquals(AuthSessionMaintenanceState.REAUTHORIZATION_REQUIRED, state.maintenanceState)
    }

    @Test
    fun `complete authentication replacement resets both capability states`() {
        val state = AuthSessionMaintenanceStateMachine()
        state.setOfficialState(OfficialAuthState.REAUTHORIZATION_REQUIRED)
        state.setCompatibilityState(CompatibilityAuthState.REAUTHORIZATION_REQUIRED)

        state.onAuthenticationStateChanged(hasOfficialSession = true, hasCompatibilitySession = true)

        assertEquals(OfficialAuthState.VALID, state.officialState)
        assertEquals(CompatibilityAuthState.AVAILABLE, state.compatibilityState)
        assertFalse(state.shouldSkipOfficialValidation())
        assertEquals(AuthSessionMaintenanceState.VALID, state.maintenanceState)
    }

    @Test
    fun `logout resets maintenance state to idle`() {
        val state = AuthSessionMaintenanceStateMachine()
        state.setOfficialState(OfficialAuthState.REAUTHORIZATION_REQUIRED)
        state.setCompatibilityState(CompatibilityAuthState.REAUTHORIZATION_REQUIRED)

        state.onAuthenticationStateChanged(hasOfficialSession = false, hasCompatibilitySession = false)

        assertEquals(OfficialAuthState.IDLE, state.officialState)
        assertEquals(CompatibilityAuthState.UNAVAILABLE, state.compatibilityState)
        assertEquals(AuthSessionMaintenanceState.IDLE, state.maintenanceState)
    }
}
