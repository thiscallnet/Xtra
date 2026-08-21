package com.github.andreyasadchy.xtra.repository.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionMaintainerTest {
    @Test
    fun `maintenance validation strips authorization scheme before raw-token calls`() {
        assertEquals(
            "raw-helix-token",
            rawAccessTokenFromAuthorizationHeader("Bearer raw-helix-token"),
        )
        assertEquals(
            "raw-gql-token",
            rawAccessTokenFromAuthorizationHeader("OAuth raw-gql-token"),
        )
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
    fun `successful authentication replacement resets both capability states`() {
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
