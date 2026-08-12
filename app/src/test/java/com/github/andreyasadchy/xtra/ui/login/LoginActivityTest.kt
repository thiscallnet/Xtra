package com.github.andreyasadchy.xtra.ui.login

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginActivityTest {

    @Test
    fun `login API values are bounded and invalid values use Both`() {
        assertEquals(0, parseLoginApi(null))
        assertEquals(0, parseLoginApi("0"))
        assertEquals(1, parseLoginApi("1"))
        assertEquals(2, parseLoginApi("2"))
        assertEquals(0, parseLoginApi("not-a-number"))
        assertEquals(0, parseLoginApi("-1"))
        assertEquals(2, parseLoginApi("3"))
    }
}
